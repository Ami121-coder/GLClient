/*
 * CustomAura clicker — humanized CPS with micro-jitter.
 *
 * Differences vs. the stock KillAuraClicker:
 *  - Default CPS range is 9..12 (higher than stock 5..8) so we out-click
 *    the typical cheater who runs at 7-10 CPS.
 *  - Default click pattern is BUTTERFLY (not STABILIZED) because butterfly
 *    produces a much noisier CPS distribution, which Polar ClickA
 *    (cps consistency) struggles to fingerprint.
 *  - Forces AttackCooldown=true (vanilla 1.9+ cooldown respect) so we
 *    never trip ClickB (cooldown bypass). The base Clicker exposes this
 *    as a user-toggleable value; we re-assert it on every tick so the
 *    user cannot accidentally disable it.
 *  - On every successful hit, schedules a small random "post-hit pause"
 *    of 0-2 ticks so the click train is not perfectly periodic even
 *    under the butterfly pattern.
 *
 * 1.9+ ONLY: the cooldown bypass assumes the vanilla 1.9+ attack cooldown
 * mechanic. On 1.8 servers the cooldown flag is a no-op and the clicker
 * falls back to pure CPS scheduling.
 */
@file:Suppress("WildcardImport")
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura

import net.ccbluex.liquidbounce.event.Sequence
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.clicking.Clicker
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.entity.isBlockAction
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.ccbluex.liquidbounce.features.module.modules.exploit.ModuleMultiActions
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.features.CustomAuraAutoBlock
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter

object CustomAuraClicker : Clicker<ModuleCustomAura>(
    parent = ModuleCustomAura,
    keyBinding = mc.options.attackKey,
    showCooldown = true,
    maxCps = 20,
    name = "Clicker",
    // 1.9+-tuned defaults — out-click cheaters without tripping ClickA/B.
    defaultCps = 9..12,
    defaultPattern = ClickPatterns.BUTTERFLY,
    defaultAttackCooldown = true
) {
    /**
     * Post-hit pause range. After a successful attack we wait this many
     * ticks before allowing the next click to land. This breaks the
     * constant-period pattern that Polar ClickA detects.
     *
     * Default 0..1 — barely affects DPS but adds enough noise.
     */
    private val postHitPause by intRange("PostHitPause", 0..1, 0..3, "ticks")

    private var pausedUntilTick: Long = 0L

    /**
     * Re-assert the AttackCooldown flag every tick so the user cannot
     * accidentally disable it from the GUI. This is the defensive layer
     * on top of [defaultAttackCooldown]; without it a single GUI click
     * would silently drop us into cooldown-bypass mode and trip Polar
     * ClickB on the next attack.
     */
    @Suppress("unused")
    private val forceCooldownHandler = handler<GameTickEvent> {
        forceAttackCooldownEnabled()
    }

    /**
     * Polar-safe attack routine — NEVER sends a duplicate PlayerMoveC2SPacket.
     * The rotation passed in is the one already accepted by the server via
     * the normal movement packet flow.
     */
    suspend fun attack(sequence: Sequence, rotation: Rotation? = null, attack: () -> Boolean) {
        if (!isClickTick) {
            this.debugParameter("Clicker_SkipReason") { "not_click_tick" }
            return
        }

        // Honor post-hit pause.
        if (player.age.toLong() < pausedUntilTick) {
            this.debugParameter("Clicker_SkipReason") { "post_hit_pause" }
            this.debugParameter("Clicker_PausedUntil") { pausedUntilTick }
            this.debugParameter("Clicker_PlayerAge") { player.age }
            return
        }

        // Make sure we are not stuck blocking — but use vanilla stop only.
        if (player.isBlockAction) {
            if (!CustomAuraAutoBlock.enabled && !ModuleMultiActions.mayAttackWhileUsing()) {
                this.debugParameter("Clicker_SkipReason") { "blocking_no_autoblock" }
                return
            }
            if (CustomAuraAutoBlock.enabled && CustomAuraAutoBlock.shouldUnblockToHit) {
                if (CustomAuraAutoBlock.stopBlocking(pauses = true) &&
                    CustomAuraAutoBlock.currentTickOff > 0) {
                    sequence.waitTicks(CustomAuraAutoBlock.currentTickOff)
                }
            }
        }

        click(attack)

        // Schedule the next pause if the click landed. Compute the pause
        // ONCE and reuse it for both the actual scheduling and the debug
        // output — the previous implementation called postHitPause.random()
        // twice, which displayed a different value than the one applied.
        if (clickAmount != null && clickAmount!! > 0) {
            val pause = postHitPause.random().toLong()
            pausedUntilTick = player.age.toLong() + pause
            this.debugParameter("Clicker_Landed") { clickAmount }
            this.debugParameter("Clicker_PostHitPause") { pause }
        } else {
            this.debugParameter("Clicker_Landed") { 0 }
        }
    }
}
