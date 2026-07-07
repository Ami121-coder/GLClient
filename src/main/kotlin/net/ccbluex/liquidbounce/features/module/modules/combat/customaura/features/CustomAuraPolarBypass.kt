/*
 * CustomAuraPolarBypass — rotation processor stack specifically designed
 * to defeat Polar's aim checks.
 *
 * What Polar checks and how we defeat each:
 *
 *  1. AimA (snap detection):
 *     Polar flags any single-tick yaw/pitch delta that exceeds the
 *     human turn-rate envelope. We add a randomized per-tick ceiling
 *     on the rotation delta so we never exceed ~25°/tick on yaw and
 *     ~15°/tick on pitch (well inside the human envelope even for
 *     high-DPI flicks).
 *
 *  2. AimB (GCD / linear correlation):
 *     Polar computes the GCD of all yaw deltas over a window. If the
 *     GCD is suspiciously high (e.g. exactly 0.5°), it indicates a
 *     quantized aim source. We add floating-point gaussian noise with
 *     stddev ~0.05° to every rotation packet, which destroys any GCD
 *     signal — the GCD of noisy floats converges to ~0.0.
 *
 *  3. AimC (target lock / perfect-tracking):
 *     Polar flags when the crosshair stays perfectly centered on a
 *     moving target's hitbox for many ticks in a row. We inject a
 *     sinusoidal "drift" of amplitude ~0.5° that mimics a human
 *     overshooting and correcting.
 *
 *  4. Aim snap-back (post-attack yaw reversal):
 *     Some cheats snap to the target, attack, then snap back. We never
 *     snap, so this never triggers.
 *
 *  5. Silent rotation detection:
 *     Polar correlates the visible client rotation (sent in
 *     PlayerMoveC2SPacket) with the actual attack direction. We always
 *     use the SILENT movement correction, so the server-side rotation
 *     matches the rotation we attack with — no mismatch possible.
 *
 * This processor is registered as a tree() child of ModuleCustomAura,
 * so it runs on every rotation update via the standard
 * RotationProcessor pipeline.
 */
@file:Suppress("MagicNumber", "WildcardImport")
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura.features

import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.ModuleCustomAura
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.ModuleCustomAuraDebugger
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.processors.RotationProcessor
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.kotlin.random
import kotlin.random.Random

/**
 * Tick-to-seconds conversion factor. The game runs at 20 TPS, so one
 * tick = 0.05 seconds. Used to drive the drift oscillator's time axis.
 */
private const val TICK_SECONDS = 0.05f

/**
 * Polar-bypass rotation processor. Always enabled — turning this off
 * reverts the module to "stock-like" aim behavior and is strongly
 * discouraged on Polar-protected servers.
 */
object CustomAuraPolarBypass : ToggleableConfigurable(
    parent = ModuleCustomAura,
    name = "PolarBypass",
    enabled = true
), RotationProcessor {

    /**
     * Maximum per-tick yaw delta. Vanilla clients can hit ~30°/tick on
     * high-DPI mice; we cap at 22°/tick to stay clearly inside the
     * envelope. Polar AimA typically trips at ~40°+/tick.
     */
    internal var maxYawDelta by float("MaxYawDelta", 22f, 5f..45f, "°")

    /**
     * Maximum per-tick pitch delta. Humans rarely exceed ~15°/tick.
     */
    internal var maxPitchDelta by float("MaxPitchDelta", 15f, 3f..30f, "°")

    /**
     * GCD-noise standard deviation in degrees. Adds gaussian noise of
     * this magnitude to every emitted rotation, destroying the GCD
     * signal that Polar AimB looks for.
     *
     * 0.04° is small enough to never miss the hitbox, large enough to
     * kill GCD detection.
     */
    internal var noiseStddev by float("NoiseStddev", 0.04f, 0f..0.3f, "°")

    /**
     * Sinusoidal drift amplitude. Adds a slow sinusoidal offset to yaw
     * and pitch so the crosshair never sits perfectly on the target
     * center — defeats AimC "perfect tracking" check.
     *
     * Lowered from 0.4° to 0.2° — at 0.4° the drift combined with
     * PointTracker's predictive aim could push the crosshair off the
     * hitbox edge on moving targets, causing facingEnemy to fail on
     * ~10% of click ticks. 0.2° is still enough to defeat AimC while
     * staying well inside the hitbox at 3.85 blocks.
     */
    internal var driftAmplitude by float("DriftAmplitude", 0.2f, 0f..2f, "°")

    /**
     * Drift frequency in Hz (oscillations per second). 0.3 Hz means a
     * ~3.3s period — slow enough to look like natural aim wobble.
     */
    internal var driftFrequency by float("DriftFrequency", 0.3f, 0.1f..1.5f, "Hz")

    /**
     * Tick counter for the drift oscillator. Uses player.age so it
     * advances in sync with the game loop.
     */
    private val tickCounter: Long
        get() = player.age.toLong()

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        // No per-tick state to reset — the previous [lastEmittedRotation]
        // field was dead code (written but never read; [clampDelta] uses
        // the [current] parameter passed by the processor pipeline, not
        // any cached value). It has been removed.
    }

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation {
        if (!running) return targetRotation

        // Delegate the math to [PolarBypassPureMath] so it can be unit-tested
        // without a live Minecraft instance. The game-specific glue here is
        // limited to: reading player.age for the time axis, using the live
        // Random for noise, and reporting results to the debugger/overlay.
        val tickSeconds = tickCounter * TICK_SECONDS
        val result = PolarBypassPureMath.process(
            current = currentRotation,
            target = targetRotation,
            tickSeconds = tickSeconds,
            maxYawDelta = maxYawDelta,
            maxPitchDelta = maxPitchDelta,
            noiseStddev = noiseStddev,
            driftAmplitude = driftAmplitude,
            driftFrequency = driftFrequency,
            random = Random
        )

        // Report clamp event to the debugger for health monitoring.
        // Also snapshot the clamped/noised yaw so the next AttackEvent
        // captures the PolarBypass state at the moment of the attack.
        //
        // The previous implementation called [PolarBypassPureMath.clampDelta]
        // a SECOND time here just to recover the clamped value for the
        // debugger — that was wasted work on every rotation update. The
        // new [ProcessResult] carries [PolarBypassPureMath.ProcessResult.clamped]
        // so we get it for free.
        ModuleCustomAuraDebugger.recordPolarBypassProcess(
            currentYaw = currentRotation.yaw,
            targetYaw = targetRotation.yaw,
            clamped = result.clampEngaged,
            clampedYaw = result.clamped.yaw,
            noisedYaw = result.final.yaw
        )

        // ── DEBUG: PolarBypass process diagnostics ──────────────────
        this.debugParameter("PB_CurrentYaw") { currentRotation.yaw }
        this.debugParameter("PB_TargetYaw") { targetRotation.yaw }
        this.debugParameter("PB_ClampedYaw") { result.clamped.yaw }
        this.debugParameter("PB_NoisedYaw") { result.final.yaw }
        this.debugParameter("PB_NoisedPitch") { result.final.pitch }
        this.debugParameter("PB_MaxYawDelta") { maxYawDelta }
        this.debugParameter("PB_NoiseStddev") { noiseStddev }
        this.debugParameter("PB_DriftAmp") { driftAmplitude }

        return result.final
    }
}
