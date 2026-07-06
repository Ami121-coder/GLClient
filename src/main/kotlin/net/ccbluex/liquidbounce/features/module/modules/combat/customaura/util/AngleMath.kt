/*
 * AngleMath — shared angle math utilities for CustomAura.
 *
 * Previously, [wrapDegrees] was duplicated in both [CustomAuraPolarBypass]
 * and [CustomAuraAntiCheater] (and as a member of [PolarBypassPureMath]).
 * This file centralizes the float-based angle wrap so all call sites share
 * a single, O(1) implementation.
 *
 * The implementation uses modulo arithmetic instead of the while-loop
 * approach so it stays O(1) even for very large inputs (e.g. accumulated
 * yaw deltas that exceed 10000°).
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura.util

/**
 * Wrap an angle in degrees to the canonical range [-180, 180].
 *
 * Idempotent: `wrapDegrees(wrapDegrees(x)) == wrapDegrees(x)`.
 *
 * Algorithm: O(1) modulo. The naive `while (d > 180) d -= 360` approach
 * becomes pathological for inputs in the thousands of degrees (e.g.
 * yaw deltas accumulated over a long session), so we use the algebraic
 * form `((x + 180) % 360 + 360) % 360 - 180` which always terminates
 * in constant time.
 *
 * @param degrees The raw angle in degrees. Any float value is allowed,
 *   including negatives and values far outside [-180, 180].
 * @return The equivalent angle in the closed range [-180, 180].
 */
fun wrapDegrees(degrees: Float): Float {
    @Suppress("MagicNumber") // 360.0 and 180.0 are intrinsic to angle wrap math
    val d = ((degrees + 180f) % 360f + 360f) % 360f - 180f
    return d
}

/**
 * Wrap an angle in degrees to the canonical range [-180, 180] (Double overload).
 *
 * Provided as a convenience for call sites that already work in Double
 * (e.g. sqrt of a squared distance).
 */
fun wrapDegrees(degrees: Double): Double {
    @Suppress("MagicNumber")
    val d = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    return d
}

/**
 * Absolute smallest angular distance between two yaw values, accounting
 * for the wrap-around at ±180°. The result is always in [0, 180].
 *
 * Example: `angularDistance(179f, -179f) == 2f` (not 358f).
 */
fun angularDistance(a: Float, b: Float): Float {
    @Suppress("MagicNumber")
    return kotlin.math.abs(wrapDegrees(a - b))
}
