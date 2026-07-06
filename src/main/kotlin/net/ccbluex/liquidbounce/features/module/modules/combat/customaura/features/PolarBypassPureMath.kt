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
     */
    fun clampDelta(
        current: Rotation,
        target: Rotation,
        maxYawDelta: Float,
        maxPitchDelta: Float
    ): Rotation {
        val yawDiff = wrapDegrees(target.yaw - current.yaw)
        val pitchDiff = target.pitch - current.pitch

        val clampedYaw = current.yaw + yawDiff.coerceIn(-maxYawDelta, maxYawDelta)
        val clampedPitch = current.pitch + pitchDiff.coerceIn(-maxPitchDelta, maxPitchDelta)

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

        return Rotation(rotation.yaw + yawDrift, rotation.pitch + pitchDrift)
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

        return Rotation(rotation.yaw + yawNoise, rotation.pitch + pitchNoise)
    }

    /**
     * Box-Muller gaussian noise generator. Mean=0, stddev=1.
     *
     * Uses polar form of Box-Muller for numerical stability. The
     * `coerceAtLeast(Float.MIN_VALUE)` on u1 prevents log(0) → -infinity
     * when u1 is exactly 0 (possible with [Random.nextFloat]).
     */
    fun gaussianNoise(random: Random): Float {
        val u1 = random.nextFloat().coerceAtLeast(Float.MIN_VALUE)
        val u2 = random.nextFloat()
        val z0 = sqrt(-2f * ln(u1)) * cos(2f * Math.PI.toFloat() * u2)
        return z0
    }

    /**
     * Full PolarBypass pipeline: clamp → drift → noise. Equivalent to
     * calling the three functions in sequence, but exposed as one call
     * so tests can verify the end-to-end transform.
     *
     * @return Pair of (finalRotation, clampEngaged)
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
    ): Pair<Rotation, Boolean> {
        val clampEngaged = clampEngages(current, target, maxYawDelta, maxPitchDelta)
        val clamped = clampDelta(current, target, maxYawDelta, maxPitchDelta)
        val drifted = applyDrift(clamped, tickSeconds, driftAmplitude, driftFrequency)
        val noised = applyNoise(drifted, noiseStddev, random)
        return noised to clampEngaged
    }
}
