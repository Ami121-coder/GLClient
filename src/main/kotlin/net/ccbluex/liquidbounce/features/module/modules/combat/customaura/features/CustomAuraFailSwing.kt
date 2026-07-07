/*
 * CustomAura FailSwing — Polar-safe swing-on-miss.
 *
 * Same idea as the stock KillAuraFailSwing (swing when we would have
 * hit a target but missed due to rotation/range), but:
 *
 *  - No AdditionalRange beyond a very small window (1.0..1.5 blocks).
 *    The stock module allows up to 10 blocks of additional range, which
 *    causes swings that are clearly not at any enemy the server can
 *    see — Polar flags this as BadPackets (swing at nothing).
 *
 *  - Honors the vanilla attack cooldown (mc.attackCooldown). The stock
 *    module also does this but we re-affirm it because it is critical:
 *    swinging during the post-miss cooldown window is a Polar ClickB flag.
 *
 *  - Limits the fail-swing rate to 1 swing per 200ms. The stock module
 *    can swing every tick when there is a near-miss target, which is
 *    a strong BadPackets signal.
 */
@file:Suppress("MagicNumber", "WildcardImport")
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura.features

import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.Sequence
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.CustomAuraClicker
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.ModuleCustomAura
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.combat.findEnemy
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.minecraft.entity.Entity
import net.minecraft.util.Hand
import net.minecraft.util.hit.HitResult
import kotlin.math.pow

internal object CustomAuraFailSwing : ToggleableConfigurable(
    parent = ModuleCustomAura,
    name = "FailSwing",
    enabled = false
) {
    /**
     * Capped additional range — kept small to avoid swinging at "nothing"
     * from the server's perspective.
     */
    internal var additionalRange by floatRange("AdditionalRange", 1.0f..1.5f, 0f..3f).onChanged { r ->
        currentAdditionalRange = r.random()
    }

    private var currentAdditionalRange: Float = additionalRange.random()

    /**
     * Minimum interval between fail-swings, in milliseconds. Prevents
     * per-tick swing spam.
     */
    internal var minIntervalMs by int("MinInterval", 200, 50..1000, "ms")

    private var lastSwingTimestamp: Long = 0L

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> {
        currentAdditionalRange = additionalRange.random()
    }

    /**
     * Public entry — called by the main module when the aura has a target
     * but cannot land a hit on it this tick.
     */
    suspend fun performFailSwing(sequence: Sequence, target: Entity?) {
        if (!enabled || !ModuleCustomAura.validateAttack()) return

        // Rate-limit.
        val now = System.currentTimeMillis()
        if (now - lastSwingTimestamp < minIntervalMs) return

        val range = ModuleCustomAura.range + currentAdditionalRange
        val entity = target ?: world.findEnemy(0f..range.toFloat()) ?: return
        val raycastType = mc.crosshairTarget?.type

        if (entity.isRemoved ||
            entity.squaredBoxedDistanceTo(player) > range.pow(2) ||
            raycastType != HitResult.Type.MISS
        ) {
            return
        }

        // Set the visual block state (render thread reads blockVisual).
        CustomAuraAutoBlock.setVisualBlockState()

        CustomAuraClicker.attack(sequence) {
            // Honor vanilla attack cooldown.
            if (mc.attackCooldown > 0) return@attack false

            player.swingHand(Hand.MAIN_HAND)
            lastSwingTimestamp = System.currentTimeMillis()
            true
        }
    }
}
