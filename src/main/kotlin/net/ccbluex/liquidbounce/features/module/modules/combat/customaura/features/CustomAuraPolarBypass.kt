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
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.processors.RotationProcessor
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.kotlin.random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

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
     */
    internal var driftAmplitude by float("DriftAmplitude", 0.4f, 0f..2f, "°")

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

    /**
     * Tracks the last rotation we emitted so we can clamp the next
     * delta to the per-tick maximum.
     */
    private var lastEmittedRotation: Rotation? = null

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        // Reset last emitted rotation when there is no active target
        // so the next aim sequence starts from a fresh baseline.
        if (ModuleCustomAura.targetTracker.target == null) {
            lastEmittedRotation = null
        }
    }

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation {
        if (!running) return targetRotation

        // Step 1: clamp the delta from current → target to the per-tick max.
        val clamped = clampDelta(currentRotation, targetRotation)
        // Accurately detect if the clamp actually engaged by checking the
        // raw delta vs the threshold. Comparing clamped != targetRotation
        // is unreliable due to float arithmetic noise — a delta just under
        // the threshold can still produce a slightly different clamped value.
        val yawDelta = abs(wrapDegrees(targetRotation.yaw - currentRotation.yaw))
        val pitchDelta = abs(targetRotation.pitch - currentRotation.pitch)
        val clampedEngaged = yawDelta > maxYawDelta || pitchDelta > maxPitchDelta

        // Step 2: add sinusoidal drift to break perfect-tracking detection.
        val drifted = applyDrift(clamped)

        // Step 3: add gaussian noise to destroy GCD detection.
        val noised = applyNoise(drifted)

        // Step 4: remember the emitted value for the next tick's clamp.
        lastEmittedRotation = noised

        // Report clamp event to the debugger for health monitoring.
        // Also snapshot the clamped/noised yaw so the next AttackEvent
        // captures the PolarBypass state at the moment of the attack.
        net.ccbluex.liquidbounce.features.module.modules.combat.customaura.ModuleCustomAuraDebugger
            .recordPolarBypassProcess(
                currentYaw = currentRotation.yaw,
                targetYaw = targetRotation.yaw,
                clamped = clampedEngaged,
                clampedYaw = clamped.yaw,
                noisedYaw = noised.yaw
            )

        // ── DEBUG: PolarBypass process diagnostics ──────────────────
        net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter(
            this, "PB_CurrentYaw", currentRotation.yaw
        )
        net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter(
            this, "PB_TargetYaw", targetRotation.yaw
        )
        net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter(
            this, "PB_ClampedYaw", clamped.yaw
        )
        net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter(
            this, "PB_DriftedYaw", drifted.yaw
        )
        net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter(
            this, "PB_NoisedYaw", noised.yaw
        )
        net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter(
            this, "PB_NoisedPitch", noised.pitch
        )
        net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter(
            this, "PB_MaxYawDelta", maxYawDelta
        )
        net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter(
            this, "PB_NoiseStddev", noiseStddev
        )
        net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter(
            this, "PB_DriftAmp", driftAmplitude
        )

        return noised
    }

    /**
     * Clamp the yaw/pitch delta from current to target so we never
     * exceed the per-tick human envelope. If the target is already
     * within the envelope, returns target unchanged.
     */
    private fun clampDelta(current: Rotation, target: Rotation): Rotation {
        val yawDiff = wrapDegrees(target.yaw - current.yaw)
        val pitchDiff = target.pitch - current.pitch

        val clampedYaw = current.yaw + yawDiff.coerceIn(-maxYawDelta, maxYawDelta)
        val clampedPitch = current.pitch + pitchDiff.coerceIn(-maxPitchDelta, maxPitchDelta)

        return Rotation(clampedYaw, clampedPitch)
    }

    /**
     * Apply a slow sinusoidal drift so the crosshair never sits
     * perfectly still on the target hitbox center.
     *
     * The drift uses two different frequencies for yaw and pitch
     * (yaw uses the base frequency, pitch uses 1.3×) so the resulting
     * Lissajous figure is non-repeating over short windows.
     */
    private fun applyDrift(rotation: Rotation): Rotation {
        if (driftAmplitude <= 0f) return rotation

        val t = tickCounter * 0.05f  // 20 tps → seconds
        val omega = driftFrequency * 2f * Math.PI.toFloat()

        val yawDrift = driftAmplitude * cos(omega * t)
        val pitchDrift = driftAmplitude * 0.6f * sin(omega * 1.3f * t)

        return Rotation(rotation.yaw + yawDrift, rotation.pitch + pitchDrift)
    }

    /**
     * Add gaussian noise to yaw and pitch. Polar's GCD check operates
     * on the integer-quantized yaw sent in PlayerMoveC2SPacket (which
     * is `byte`-quantized to /256 in 1.8 and `float` in 1.9+).
     *
     * In 1.9+ the float yaw is full-precision, so even 0.04° of noise
     * is enough to make the GCD of any window converge to ~0.
     *
     * In 1.8 the byte quantization (256 levels per 360°) means
     * 0.04° of noise is BELOW the quantization step (1.4°), so the
     * noise has no effect — but the byte quantization itself is the
     * dominant GCD source on 1.8, and there is nothing we can do
     * about it client-side. Users on 1.8 should use a different bypass.
     */
    private fun applyNoise(rotation: Rotation): Rotation {
        if (noiseStddev <= 0f) return rotation

        val yawNoise = gaussianNoise() * noiseStddev
        val pitchNoise = gaussianNoise() * noiseStddev

        return Rotation(rotation.yaw + yawNoise, rotation.pitch + pitchNoise)
    }

    /**
     * Box-Muller gaussian noise generator. Mean=0, stddev=1.
     * Seeded by Random.Default so it is non-deterministic per-tick.
     */
    private fun gaussianNoise(): Float {
        val u1 = Random.nextFloat().coerceAtLeast(Float.MIN_VALUE)
        val u2 = Random.nextFloat()
        val z0 = kotlin.math.sqrt(-2f * kotlin.math.ln(u1)) *
            cos(2f * Math.PI.toFloat() * u2)
        return z0
    }

    /**
     * Wrap an angle to [-180, 180].
     */
    private fun wrapDegrees(degrees: Float): Float {
        var d = degrees
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    /**
     * Apply preset parameters. Called by [ModuleCustomAura.applyPreset].
     */
    internal fun applyPreset(params: net.ccbluex.liquidbounce.features.module.modules.combat.customaura.CustomAuraPresets.Params) {
        // Toggle enabled state — VANILLA preset disables the bypass entirely.
        this.enabled = params.polarBypassEnabled

        maxYawDelta = params.maxYawDelta
        maxPitchDelta = params.maxPitchDelta
        noiseStddev = params.noiseStddev
        driftAmplitude = params.driftAmplitude
        driftFrequency = params.driftFrequency
    }
}
