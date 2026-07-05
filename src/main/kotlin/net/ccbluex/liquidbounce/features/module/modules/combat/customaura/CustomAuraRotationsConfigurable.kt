/*
 * CustomAura rotation configurable.
 *
 * Hard-restricted to NORMAL rotation timing. SNAP and ON_TICK modes from
 * the stock KillAura are intentionally NOT exposed here because they are
 * the single biggest source of Polar AimA / BadPackets flags.
 *
 * Adds [toBypassedRotationTarget] which injects [CustomAuraPolarBypass]
 * at the end of the processor chain so every rotation we send is
 * noise+drift+clamped to defeat Polar AimA/B/C.
 */
@file:Suppress("WildcardImport")
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.features.CustomAuraPolarBypass
import net.ccbluex.liquidbounce.utils.aiming.RotationsConfigurable
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.client.RestrictedSingleUseAction
import net.minecraft.entity.Entity

object CustomAuraRotationsConfigurable : RotationsConfigurable(
    ModuleCustomAura,
    movementCorrection = MovementCorrection.SILENT,
    combatSpecific = true
) {
    /**
     * Only NORMAL is offered. SNAP and ON_TICK are NOT registered as choices
     * at all, so the user cannot accidentally enable them.
     *
     * Why:
     *  - SNAP   → yaw/pitch delta in one tick exceeds any human turn rate
     *             → instant Polar AimA flag.
     *  - ON_TICK → sends a duplicate PlayerMoveC2SPacket.Full around the
     *              attack → BadPackets flag + rotation snap-back detection.
     */
    val rotationTiming by enumChoice("RotationTiming", RotationTiming.NORMAL)

    /**
     * Through-walls aiming is OFF by default — Polar AimC / Reach through-wall
     * flags any attack that lands while a solid block is between the player
     * eye and the target hitbox.
     */
    val aimThroughWalls by boolean("ThroughWalls", false)

    /**
     * Builds a [RotationTarget] that includes [CustomAuraPolarBypass] at the
     * end of the processor chain. This is the entry point the module uses
     * instead of the base [toRotationTarget], so every rotation we send is
     * post-processed by the Polar bypass (noise + drift + delta clamp).
     *
     * The base [RotationsConfigurable.toRotationTarget] is not open, so we
     * cannot override it; we shadow it via a differently-named function and
     * call the parent implementation via the inherited method.
     */
    fun toBypassedRotationTarget(
        rotation: Rotation,
        entity: Entity? = null,
        considerInventory: Boolean = false,
        whenReached: RestrictedSingleUseAction? = null
    ): RotationTarget {
        val base = super.toRotationTarget(rotation, entity, considerInventory, whenReached)

        // Add PolarBypass at the end so it runs AFTER the angle smooth,
        // ShortStop and Fail processors. This lets PolarBypass see the
        // already-smoothed target and apply its noise/drift/clamp on top.
        val processorsWithBypass = base.processors + CustomAuraPolarBypass

        return RotationTarget(
            rotation = base.rotation,
            entity = base.entity,
            processors = processorsWithBypass,
            ticksUntilReset = base.ticksUntilReset,
            resetThreshold = base.resetThreshold,
            considerInventory = base.considerInventory,
            movementCorrection = base.movementCorrection,
            whenReached = base.whenReached
        )
    }

    enum class RotationTiming(override val choiceName: String) : NamedChoice {
        NORMAL("Normal")
    }
}
