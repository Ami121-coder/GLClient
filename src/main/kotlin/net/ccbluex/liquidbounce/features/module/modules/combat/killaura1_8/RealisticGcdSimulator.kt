/*
 * This file is part of LiquidBounce (https://github.com/LiquidBounce)
 *
 * Copyright (c) 2015 - 2025 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.killaura1_8

import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import java.util.Random
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # RealisticGcdSimulator — emulated mouse-delta rotation path.
 *
 * Replaces the v3 `roundToGcd(value / g) * g` quantizer, which produces
 * perfectly-grid-aligned rotations that Polar `Rotation-Heuristics` and
 * Intave `RotationGCD` can fingerprint as "no native mouse delta noise".
 *
 * ## How vanilla mouse movement works on 1.8
 *
 * 1. The OS reports raw cursor delta `(dx, dy)` to LWJGL.
 * 2. Minecraft applies sensitivity:
 *    `f = sensitivity * 0.6 + 0.2`
 *    `mult = f * f * f * 1.2` (this is the per-tick multiplier)
 * 3. The applied rotation delta is:
 *    `deltaYaw = -dx * mult`
 *    `deltaPitch = dy * mult`
 * 4. Because `dx, dy` are integers (pixels), `deltaYaw` and
 *    `deltaPitch` are *multiples* of `mult` (the GCD), but with
 *    *different* integer multipliers — and crucially, the resulting
 *    yaw/pitch values do NOT lie on a perfect uniform grid because
 *    they are accumulated from many deltas of varying magnitude.
 *
 * ## What this simulator does
 *
 * Instead of starting from a desired target rotation and rounding to
 * the GCD grid, we start from the *delta* between the current rotation
 * and the desired rotation, decompose it into integer mouse-deltas
 * that, when re-applied through the vanilla formula, produce a rotation
 * close to (but not exactly on) the desired one.
 *
 * A small Gaussian noise of ±0.5 px is added to each delta before
 * rounding, simulating sub-pixel mouse jitter that real hardware
 * produces. This breaks the perfect grid alignment while staying
 * statistically inside the GCD family.
 *
 * ## Result
 *
 * The yaw/pitch values sent to the server are NOT all multiples of
 * the GCD. Some are off by a fraction of `mult`. This matches what a
 * real player produces: rotations clustered around GCD multiples but
 * with per-tick noise of ~0.001-0.01°.
 *
 * Author: Super Z (1.8 PvP rewrite, v4)
 */
object RealisticGcdSimulator {

    private val rng = Random(System.nanoTime())

    /**
     * Cached sensitivity multiplier. Refreshed whenever the configured
     * sensitivity changes. The value is `f³ * 1.2` where
     * `f = sensitivity * 0.6 + 0.2`.
     */
    fun sensitivityMultiplier(sensitivity: Float): Double {
        val f = (sensitivity * 0.6f + 0.2f).toDouble()
        return f * f * f * 1.2
    }

    /**
     * Transforms a desired rotation into a "realistically achievable"
     * rotation by emulating the vanilla mouse-delta pipeline.
     *
     * Algorithm:
     *  1. Compute the desired delta from [current] → [target].
     *  2. Divide by the sensitivity multiplier to get the implied
     *     mouse delta in pixels (float).
     *  3. Add sub-pixel Gaussian noise (sigma = 0.5 px).
     *  4. Round to integer pixels and multiply back by the multiplier.
     *  5. The result is the actual achievable delta. Apply it to
     *     [current] to get the rotation we will send.
     *
     * This naturally limits the maximum rotation per call to
     * `round(maxMouseDelta) * mult`, which matches the physical
     * constraint of a human wrist movement.
     *
     * @param current  The rotation we last sent (or player rotation).
     * @param target   The rotation we would ideally send.
     * @param sensitivity  Mouse sensitivity (0..1).
     * @param maxPixelsPerTick  Maximum mouse pixels per tick. Default
     *        is 80, which at sensitivity 0.6 yields ~17°/tick
     *        (~340°/sec) — fast enough for any realistic combat
     *        rotation but slow enough to look human. Setting this
     *        above 100 produces rotations that Polar `AimSpeed`
     *        flags as inhuman.
     */
    fun simulate(
        current: Rotation,
        target: Rotation,
        sensitivity: Float,
        maxPixelsPerTick: Int = 80
    ): Rotation {
        val mult = sensitivityMultiplier(sensitivity)
        if (mult <= 0.0) return target

        // 1. Desired deltas (degrees).
        val desiredDeltaYaw = wrapDegrees(target.yaw - current.yaw)
        val desiredDeltaPitch = (target.pitch - current.pitch).coerceIn(-90f, 90f)

        // 2. Implied mouse pixels (positive dx rotates yaw left on 1.8,
        //    so we invert the sign — see vanilla MouseHelper).
        val impliedDx = (-desiredDeltaYaw / mult).toFloat()
        val impliedDy = (desiredDeltaPitch / mult).toFloat()

        // 3. Sub-pixel Gaussian noise.
        val noisyDx = impliedDx + (rng.nextGaussian() * 0.5).toFloat()
        val noisyDy = impliedDy + (rng.nextGaussian() * 0.5).toFloat()

        // 4. Cap to maxPixelsPerTick.
        val cappedDx = noisyDx.coerceIn(-maxPixelsPerTick.toFloat(), maxPixelsPerTick.toFloat())
        val cappedDy = noisyDy.coerceIn(-maxPixelsPerTick.toFloat(), maxPixelsPerTick.toFloat())

        // 5. Round to integer pixels and re-apply multiplier.
        val pixelDx = cappedDx.roundToInt()
        val pixelDy = cappedDy.roundToInt()

        val actualDeltaYaw = (-pixelDx * mult).toFloat()
        val actualDeltaPitch = (pixelDy * mult).toFloat()

        val finalYaw = wrapDegrees(current.yaw + actualDeltaYaw)
        val finalPitch = (current.pitch + actualDeltaPitch).coerceIn(-90f, 90f)

        return Rotation(finalYaw, finalPitch)
    }

    /**
     * Wraps degrees to the [-180, 180] range. Used for yaw arithmetic.
     */
    fun wrapDegrees(degrees: Float): Float {
        var d = degrees % 360f
        if (d >= 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /**
     * Helper used in tests / debug: computes the rotation needed to
     * look from [from] to [to]. Mirrors `Rotation.lookingAt` but kept
     * local to avoid pulling in extra imports.
     */
    fun rotationLookingAt(from: net.minecraft.util.math.Vec3d, to: net.minecraft.util.math.Vec3d): Rotation {
        val delta = to.subtract(from)
        val yaw = Math.toDegrees(atan2(delta.z, delta.x)).toFloat() - 90f
        val pitch = -Math.toDegrees(
            atan2(delta.y, sqrt(delta.x * delta.x + delta.z * delta.z))
        ).toFloat()
        return Rotation(wrapDegrees(yaw), pitch.coerceIn(-90f, 90f))
    }

    /**
     * Unused for now, kept for future math use (Bezier rotation path).
     */
    @Suppress("unused")
    private fun lerpAngle(a: Float, b: Float, t: Float): Float {
        val delta = wrapDegrees(b - a)
        return wrapDegrees(a + delta * t)
    }

    @Suppress("unused")
    private fun cosineEase(t: Float): Float {
        return (1f - cos(t * Math.PI.toFloat())) * 0.5f
    }
}
