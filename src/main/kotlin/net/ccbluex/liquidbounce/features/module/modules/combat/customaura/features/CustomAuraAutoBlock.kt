/*
 * CustomAura AutoBlock — Polar-safe blocking.
 *
 * The stock KillAuraAutoBlock exposes four modes (BASIC, INTERACT,
 * HYPIXEL, FAKE) and three unblock modes (STOP_USING_ITEM,
 * CHANGE_SLOT, NONE). Only a tiny subset is safe under Polar:
 *
 *  - BlockMode.BASIC       : vanilla interactItem on the block hand, nothing else.
 *  - BlockMode.FAKE        : visual-only, no packets — safest but no actual block.
 *  - UnblockMode.STOP_USING_ITEM : vanilla stopUsingItem.
 *
 * INTERACT mode sends interact-with-entity + interact-with-block packets
 * on every block tick → flagged by Polar BadPackets as block-while-attacking.
 *
 * HYPIXEL mode spams interactEntity every 5 ticks → BadPackets flag.
 *
 * CHANGE_SLOT unblock sends two UpdateSelectedSlotC2SPacket in one tick
 * → Polar AutoBlock C flag (slot-toggle pattern).
 *
 * So this submodule intentionally offers ONLY BASIC + FAKE for blocking,
 * and ONLY STOP_USING_ITEM for unblocking. No blink, no Hypixel, no
 * interact. This is the key Polar-bypass trade-off: less defensive
 * blocking, but no AutoBlock flags.
 */
@file:Suppress("MagicNumber", "WildcardImport")
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura.features

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.ModuleCustomAura
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.entity.isBlockAction
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.input.shouldSwingHand
import net.minecraft.item.ItemStack
import net.minecraft.item.consume.UseAction
import net.minecraft.util.Hand

object CustomAuraAutoBlock : ToggleableConfigurable(
    parent = ModuleCustomAura,
    name = "AutoBlocking",
    enabled = false
) {
    /**
     * Only BASIC and FAKE modes are offered.
     *  - BASIC: vanilla interactItem(player, hand) on the blocking hand.
     *  - FAKE:  visual-only, no packets sent — totally safe but provides
     *           no actual damage reduction.
     */
    private var blockMode by enumChoice("BlockMode", BlockMode.BASIC)

    /**
     * Only STOP_USING_ITEM is offered.
     * CHANGE_SLOT is NOT registered because it is a direct Polar AutoBlock C flag.
     */
    private var unblockMode by enumChoice("UnblockMode", UnblockMode.STOP_USING_ITEM)

    /**
     * Tick-off delay between unblock and the next attack. Default 0 means
     * we attack on the same tick we unblock, which is the vanilla-accurate
     * behavior. Any non-zero value introduces a noticeable packet pattern.
     */
    internal var tickOffRange by intRange("TickOff", 0..0, 0..2, "ticks").onChanged { range ->
        currentTickOff = range.random()
    }
    var currentTickOff: Int = tickOffRange.random()
        private set

    /**
     * Tick-on delay between block and the next unblock. Default 0.
     */
    internal var tickOnRange by intRange("TickOn", 0..0, 0..2, "ticks").onChanged { range ->
        currentTickOn = range.random()
    }
    var currentTickOn: Int = tickOnRange.random()
        private set

    /**
     * Only block when there is an enemy within our strike range. This
     * minimizes the number of block packets we send, which is the single
     * biggest factor in avoiding Polar AutoBlock pattern detection.
     */
    val onScanRange by boolean("OnScanRange", true)

    /**
     * Visual blocking state — when true, the client renders the
     * blocking animation without us actually blocking.
     *
     * @Volatile because it is read from the render thread (which draws
     * the block animation) and written from the tick thread (which
     * starts/stops blocking). The previous implementation was a plain
     * `var` — a torn read on the render thread could flicker the block
     * animation for one frame.
     */
    @Volatile
    var blockVisual: Boolean = false
        private set

    /**
     * True when we have actually sent a use-item packet and the server
     * considers us blocking.
     *
     * @Volatile — same rationale as [blockVisual].
     */
    @Volatile
    var blockingStateEnforced: Boolean = false
        private set

    val shouldUnblockToHit: Boolean
        get() = unblockMode != UnblockMode.NONE

    val blockImmediate: Boolean
        get() = currentTickOn == 0

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (blockingStateEnforced && blockMode == BlockMode.BASIC) {
            // Already blocking — vanilla keeps the block alive automatically
            // as long as useKey is held, so we don't need to send any
            // additional packets. This is the key Polar-safety property:
            // we send ONE interactItem to start blocking, and ONE
            // stopUsingItem to stop — no periodic interact spam.
        }
    }

    /**
     * Set the visual block animation state without sending any packet.
     * Safe to call every tick.
     */
    fun setVisualBlockState() {
        if (!enabled) return
        blockVisual = true
    }

    /**
     * Start blocking. Sends AT MOST ONE interactItem packet per block
     * session — no Hypixel-style spam.
     *
     * Debug diagnostics for the skip-reason tree are coalesced into a
     * single [emitStartBlockingDebug] helper so the main control flow
     * reads cleanly without a forest of `debugParameter` calls.
     */
    fun startBlocking() {
        // Always emit the entry-state diagnostics — these are cheap and
        // help trace the decision tree when debugging.
        emitStartBlockingDebug()

        if (!enabled || (player.isBlockAction && blockMode == BlockMode.BASIC)) {
            this.debugParameter("AB_SkipReason") { "already_blocking_or_disabled" }
            return
        }

        if (blockMode == BlockMode.FAKE) {
            blockVisual = true
            // NOTE: do NOT set [blockingStateEnforced] in FAKE mode —
            // FAKE mode never sends a packet, so the server never
            // considers us blocking. The previous implementation
            // correctly skipped the packet but also skipped clearing
            // [blockingStateEnforced] when the user later switched
            // from BASIC to FAKE, leaving a stale `true` that caused
            // [stopBlocking] to no-op on the next attempt.
            blockingStateEnforced = false
            this.debugParameter("AB_SkipReason") { "fake_mode_visual_only" }
            return
        }

        val blockHand = when {
            canBlock(player.mainHandStack) -> Hand.MAIN_HAND
            canBlock(player.offHandStack) -> Hand.OFF_HAND
            else -> {
                this.debugParameter("AB_SkipReason") { "no_blockable_hand" }
                return
            }
        }

        val itemStack = player.getStackInHand(blockHand)
        if (itemStack.isEmpty || !itemStack.isItemEnabled(world.enabledFeatures)) {
            this.debugParameter("AB_SkipReason") { "item_empty_or_disabled" }
            return
        }

        this.debugParameter("AB_BlockHand") { blockHand.name }

        val actionResult = mc.interactionManager?.interactItem(player, blockHand)
        if (actionResult == null) {
            this.debugParameter("AB_SkipReason") { "interaction_manager_null" }
            return
        }

        if (actionResult.isAccepted && actionResult.shouldSwingHand()) {
            currentTickOn = tickOnRange.random()
            player.swingHand(blockHand)
        }

        // Only set [blockingStateEnforced] if the server actually
        // accepted the interactItem. The previous implementation set
        // it unconditionally, which meant a rejected interactItem (e.g.
        // item disabled by server, region protection) would leave the
        // flag `true` and cause future [startBlocking] calls to no-op
        // (because the `player.isBlockAction && BASIC` early-return
        // would not fire — the server never put us in block state).
        blockingStateEnforced = actionResult.isAccepted
        blockVisual = actionResult.isAccepted
        this.debugParameter("AB_StartedBlocking") { actionResult.isAccepted }
    }

    /**
     * Emit the entry-state diagnostics for [startBlocking]. Kept as a
     * separate helper so the main control flow in [startBlocking] is
     * readable — without it, the function had six `debugParameter`
     * calls in a row before any actual logic.
     */
    private fun emitStartBlockingDebug() {
        this.debugParameter("AB_Enabled") { enabled }
        this.debugParameter("AB_BlockMode") { blockMode.name }
        this.debugParameter("AB_UnblockMode") { unblockMode.name }
        this.debugParameter("AB_BlockingEnforced") { blockingStateEnforced }
        this.debugParameter("AB_IsBlockAction") { player.isBlockAction }
    }

    /**
     * Stop blocking. Vanilla stopUsingItem only — no slot toggling.
     * Returns true if we actually unblocked (i.e. we were blocking before).
     *
     * The [blockingStateEnforced] flag is ALWAYS cleared when we attempt
     * to stop, even if [player.isBlockAction] is already false (which
     * happens when the server itself dropped the block — e.g. shield
     * broke, item swapped). The previous implementation returned early
     * in that case WITHOUT clearing the flag, leaving a stale `true`
     * that caused future [startBlocking] calls to no-op.
     */
    fun stopBlocking(pauses: Boolean = false): Boolean {
        if (!pauses) {
            blockVisual = false
            if (mc.options.useKey.isPressedOnAny) {
                blockingStateEnforced = false
                return false
            }
        }

        if (!player.isBlockAction) {
            // Server already dropped the block (or never accepted our
            // interactItem). Clear the enforced flag so the next
            // [startBlocking] can actually send a fresh interactItem.
            blockingStateEnforced = false
            return false
        }

        currentTickOff = tickOffRange.random()

        if (unblockMode == UnblockMode.STOP_USING_ITEM) {
            mc.interactionManager?.stopUsingItem(player)
            blockingStateEnforced = false
            return true
        }

        // UnblockMode.NONE — don't send a packet, but still clear the
        // enforced flag so we don't get stuck thinking we're blocking.
        blockingStateEnforced = false
        return false
    }

    private fun canBlock(itemStack: ItemStack) =
        itemStack.item?.getUseAction(itemStack) == UseAction.BLOCK

    enum class BlockMode(override val choiceName: String) : NamedChoice {
        BASIC("Basic"),
        FAKE("Fake")
    }

    enum class UnblockMode(override val choiceName: String) : NamedChoice {
        STOP_USING_ITEM("StopUsingItem"),
        NONE("None")
    }
}
