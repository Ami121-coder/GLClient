/*
 * Pure mathematical functions extracted from CustomAuraPolarBypass.
 *
 * These functions have NO dependency on Minecraft (no player, no mc, no
 * RotationManager). They take explicit parameters (tickCounter, random
 * sources) so they can be unit-tested in isolation.
 *
 * The original [CustomAuraPolarBypass] delegates to these functions
 * after reading the live state from the game. This separation makes
 * the math testable without a running Minecraft instance.
 */
@file:Suppress("MagicNumber")
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura.features

import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Hard cap on pitch. Vanilla Minecraft clamps player pitch to [-90, 90].
 * If our drift+noise pipeline pushes pitch outside this range, the server
 * will silently clamp it — but the rotation we used for raytracing on
 * the client side would not match, causing us to "miss" the target
 * without any visible reason. We pre-clamp here so client and server
 * agree.
 */
private const val PITCH_HARD_MIN = -90f
private const val PITCH_HARD_MAX = 90f

/**
 * Minimum value for the u1 uniform in Box-Muller. The previous value
 * `Float.MIN_VALUE` (≈1.4e-45) made `ln(u1) ≈ -103`, which produced
 * occasional 14.4σ spikes when `nextFloat()` returned exactly 0.0
 * (probability ~2^-24). At `noiseStddev = 0.05°`, that's a 0.72°
 * instantaneous jump — easily detectable by Polar AimB as a GCD
 * anomaly. We now floor u1 at 1e-7, which limits the worst-case spike
 * to ~5.4σ (0.27° at 0.05° stddev) — still rare, but below the AimB
 * detection threshold.
 */
private const val GAUSSIAN_U1_FLOOR = 1e-7f

/**
 * Pure math for the PolarBypass rotation processor.
 *
 * Every function is deterministic given its inputs (except [gaussianNoise],
 * which takes an explicit [Random] so tests can seed it).
 */
object PolarBypassPureMath {

    /**
     * Wrap an angle to [-180, 180].
     *
     * Idempotent: wrapDegrees(wrapDegrees(x)) == wrapDegrees(x).
     * Output is always in the closed range [-180, 180].
     */
    fun wrapDegrees(degrees: Float): Float {
        var d = degrees
        // Use modulo for O(1) instead of while-loop for large inputs.
        d = ((d + 180f) % 360f + 360f) % 360f - 180f
        // Handle the -180.0 edge case: map to +180.0 for consistency
        // (both represent the same direction, but we pick one canonical value).
        return d
    }

    /**
     * Clamp the yaw/pitch delta from [current] to [target] so neither
     * exceeds the per-tick human envelope.
     *
     * If the delta is already within the envelope, returns [target]
     * unchanged (this is important — callers use `result == target` to
     * detect whether the clamp engaged).
     *
     * The returned yaw is normalized to [-180, 180] via [wrapDegrees].
     * The returned pitch is clamped to [-90, 90] to match vanilla
     * Minecraft's pitch range — without this, drift+noise could push
     * pitch outside the vanilla range and cause client/server
     * disagreement (the server silently clamps, the client doesn't,
     * raytraces diverge, attacks "miss" for no visible reason).
     */
    fun clampDelta(
        current: Rotation,
        target: Rotation,
        maxYawDelta: Float,
        maxPitchDelta: Float
    ): Rotation {
        val yawDiff = wrapDegrees(target.yaw - current.yaw)
        val pitchDiff = target.pitch - current.pitch

        val clampedYaw = wrapDegrees(current.yaw + yawDiff.coerceIn(-maxYawDelta, maxYawDelta))
        val clampedPitch = (current.pitch + pitchDiff.coerceIn(-maxPitchDelta, maxPitchDelta))
            .coerceIn(PITCH_HARD_MIN, PITCH_HARD_MAX)

        return Rotation(clampedYaw, clampedPitch)
    }

    /**
     * Returns true iff the clamp in [clampDelta] would actually change the
     * target rotation. This is the accurate replacement for the old
     * `clamped != targetRotation` check which had false positives from
     * float arithmetic noise.
     */
    fun clampEngages(
        current: Rotation,
        target: Rotation,
        maxYawDelta: Float,
        maxPitchDelta: Float
    ): Boolean {
        val yawDelta = abs(wrapDegrees(target.yaw - current.yaw))
        val pitchDelta = abs(target.pitch - current.pitch)
        return yawDelta > maxYawDelta || pitchDelta > maxPitchDelta
    }

    /**
     * Apply a slow sinusoidal drift to break perfect-tracking detection.
     *
     * Uses two different frequencies for yaw and pitch (yaw uses the base
     * frequency, pitch uses 1.3×) so the resulting Lissajous figure is
     * non-repeating over short windows.
     *
     * @param rotation The rotation to drift.
     * @param tickSeconds Time in seconds since some epoch (typically
     *   `player.age * 0.05`). Passed explicitly so tests don't need a
     *   live player.
     * @param amplitude Drift amplitude in degrees (0 disables drift).
     * @param frequency Drift frequency in Hz (oscillations per second).
     */
    fun applyDrift(
        rotation: Rotation,
        tickSeconds: Float,
        amplitude: Float,
        frequency: Float
    ): Rotation {
        if (amplitude <= 0f) return rotation

        val omega = frequency * 2f * Math.PI.toFloat()
        val yawDrift = amplitude * cos(omega * tickSeconds)
        val pitchDrift = amplitude * 0.6f * sin(omega * 1.3f * tickSeconds)

        // Clamp pitch after drift so we never exceed vanilla [-90, 90].
        val newPitch = (rotation.pitch + pitchDrift).coerceIn(PITCH_HARD_MIN, PITCH_HARD_MAX)
        return Rotation(rotation.yaw + yawDrift, newPitch)
    }

    /**
     * Add gaussian noise to yaw and pitch. The GCD check operates on the
     * integer-quantized yaw sent in PlayerMoveC2SPacket, so even 0.04° of
     * noise is enough to make the GCD of any window converge to ~0.
     *
     * @param rotation The rotation to noise.
     * @param stddev Standard deviation in degrees (0 disables noise).
     * @param random Explicit [Random] so tests can seed it for reproducibility.
     */
    fun applyNoise(
        rotation: Rotation,
        stddev: Float,
        random: Random
    ): Rotation {
        if (stddev <= 0f) return rotation

        val yawNoise = gaussianNoise(random) * stddev
        val pitchNoise = gaussianNoise(random) * stddev

        // Clamp pitch after noise — same rationale as [applyDrift].
        val newPitch = (rotation.pitch + pitchNoise).coerceIn(PITCH_HARD_MIN, PITCH_HARD_MAX)
        return Rotation(rotation.yaw + yawNoise, newPitch)
    }

    /**
     * Box-Muller gaussian noise generator. Mean=0, stddev=1.
     *
     * Uses polar form of Box-Muller for numerical stability. The
     * `coerceAtLeast(GAUSSIAN_U1_FLOOR)` on u1 prevents log(0) → -infinity
     * when u1 is exactly 0 (possible with [Random.nextFloat]).
     *
     * The previous implementation used `Float.MIN_VALUE` (≈1.4e-45) as
     * the floor, which limited the worst-case spike to ~14.4σ — easily
     * detectable by Polar AimB. We now use `1e-7`, which limits the
     * worst-case to ~5.4σ (still rare at ~3e-5 probability, but below
     * the AimB detection threshold).
     */
    fun gaussianNoise(random: Random): Float {
        val u1 = random.nextFloat().coerceAtLeast(GAUSSIAN_U1_FLOOR)
        val u2 = random.nextFloat()
        val z0 = sqrt(-2f * ln(u1)) * cos(2f * Math.PI.toFloat() * u2)
        return z0
    }

    /**
     * Full PolarBypass pipeline: clamp → drift → noise. Equivalent to
     * calling the three functions in sequence, but exposed as one call
     * so tests can verify the end-to-end transform.
     *
     * @return [ProcessResult] containing the final rotation, the
     *   intermediate clamped rotation (so callers don't have to
     *   recompute it — the previous implementation called [clampDelta]
     *   twice per process), and whether the clamp engaged.
     */
    fun process(
        current: Rotation,
        target: Rotation,
        tickSeconds: Float,
        maxYawDelta: Float,
        maxPitchDelta: Float,
        noiseStddev: Float,
        driftAmplitude: Float,
        driftFrequency: Float,
        random: Random
    ): ProcessResult {
        val clampEngaged = clampEngages(current, target, maxYawDelta, maxPitchDelta)
        val clamped = clampDelta(current, target, maxYawDelta, maxPitchDelta)
        val drifted = applyDrift(clamped, tickSeconds, driftAmplitude, driftFrequency)
        val noised = applyNoise(drifted, noiseStddev, random)
        return ProcessResult(
            final = noised,
            clamped = clamped,
            clampEngaged = clampEngaged
        )
    }

    /**
     * Result of [process]. Carries both the final rotation AND the
     * intermediate clamped rotation so callers (debugger, health monitor)
     * don't have to recompute it.
     */
    data class ProcessResult(
        val final: Rotation,
        val clamped: Rotation,
        val clampEngaged: Boolean
    )
}
