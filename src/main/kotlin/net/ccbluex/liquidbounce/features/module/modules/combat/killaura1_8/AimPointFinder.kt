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

import net.minecraft.entity.LivingEntity
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # AimPointFinder — picks the point on the target's hitbox to aim at.
 *
 * Replaces the v3 random-sphere-around-torso heuristic with three
 * selectable modes:
 *
 *  - **NEAREST_POINT** — closest point on the hitbox to the player's
 *    current crosshair ray. Minimizes rotation delta (faster aiming)
 *    and naturally distributes hits across the entire hitbox surface
 *    depending on player position — exactly what real players do.
 *
 *  - **TORSO_SPHERE** — v3 behaviour. Random point in a small sphere
 *    around the torso center. Kept for comparison / fallback.
 *
 *  - **CENTER** — exact bounding-box center. Deterministic; useful
 *    only for vanilla servers where the crosshair is always centered.
 *
 * ## NEAREST_POINT algorithm
 *
 * 1. Take the player's eye position and current look direction (as a
 *    normalized vector from eye to a far point).
 * 2. For each of the 6 faces of the target's bounding box, find the
 *    intersection of the look ray with the plane of that face.
 * 3. Keep the intersection points that lie within the face.
 * 4. Among all valid intersection points, choose the one with the
 *    smallest angular distance from the current crosshair.
 * 5. Add a small random offset (≤ 0.05 blocks) to break determinism.
 *
 * If the look ray does not intersect any face (e.g. the player is
 * looking away from the hitbox), fall back to the geometric nearest
 * point (clamp the eye position to the box bounds).
 *
 * Author: Super Z (1.8 PvP rewrite, v4)
 */
object AimPointFinder {

    enum class Mode {
        NEAREST_POINT,
        TORSO_SPHERE,
        CENTER
    }

    private val rng = java.util.Random(System.nanoTime())

    /**
     * Computes the aim point on the target's hitbox.
     *
     * @param target     The target entity.
     * @param eyePos     The player's eye position.
     * @param lookDir    The player's current look direction (normalized).
     * @param mode       The aim-point mode.
     * @param jitterBlocks  Maximum random offset added to the chosen
     *                      point, in blocks. Recommended: 0.05.
     */
    fun findAimPoint(
        target: LivingEntity,
        eyePos: Vec3d,
        lookDir: Vec3d,
        mode: Mode,
        jitterBlocks: Float = 0.05f
    ): Vec3d {
        val box = target.boundingBox
        val point = when (mode) {
            Mode.CENTER -> box.center
            Mode.TORSO_SPHERE -> {
                // v3 behaviour: random point in a small sphere around
                // the torso center, biased upward toward the chest.
                val center = box.center
                val offX = (rng.nextDouble() - 0.5) * 0.6
                val offY = (rng.nextDouble() - 0.5) * 0.4
                val offZ = (rng.nextDouble() - 0.5) * 0.6
                Vec3d(center.x + offX, center.y + offY, center.z + offZ)
            }
            Mode.NEAREST_POINT -> {
                val faceHit = rayTraceBoxFaces(eyePos, lookDir, box)
                if (faceHit != null) {
                    faceHit
                } else {
                    // Fall back: clamp eye position to box bounds.
                    clampToBox(eyePos, box)
                }
            }
        }

        // Add tiny jitter to break determinism (NEAREST_POINT alone can
        // produce identical aim points tick after tick if the player is
        // stationary, which Polar Rotation-Heuristics flags as "no
        // noise in aim").
        if (jitterBlocks > 0f) {
            val jx = (rng.nextDouble() - 0.5) * jitterBlocks * 2
            val jy = (rng.nextDouble() - 0.5) * jitterBlocks * 2
            val jz = (rng.nextDouble() - 0.5) * jitterBlocks * 2
            return Vec3d(point.x + jx, point.y + jy, point.z + jz)
        }
        return point
    }

    /**
     * Ray-traces the player's look ray against the 6 faces of [box]
     * and returns the nearest valid intersection point (smallest
     * parameter t > 0).
     *
     * Returns null if the ray does not intersect any face within the
     * forward half-space.
     */
    private fun rayTraceBoxFaces(origin: Vec3d, dir: Vec3d, box: Box): Vec3d? {
        val ndx = dir.x
        val ndy = dir.y
        val ndz = dir.z

        // Avoid division-by-zero by using a tiny epsilon.
        val eps = 1.0e-9

        // Compute t for each of the 6 face planes.
        // For a slab [min, max] on axis a, t = (boundary - origin) / dir.
        // We pick the t that lies inside the other two slabs.
        val ts = doubleArrayOf(
            (box.minX - origin.x) / if (ndx != 0.0) ndx else eps, // -X face
            (box.maxX - origin.x) / if (ndx != 0.0) ndx else eps, // +X face
            (box.minY - origin.y) / if (ndy != 0.0) ndy else eps, // -Y face
            (box.maxY - origin.y) / if (ndy != 0.0) ndy else eps, // +Y face
            (box.minZ - origin.z) / if (ndz != 0.0) ndz else eps, // -Z face
            (box.maxZ - origin.z) / if (ndz != 0.0) ndz else eps  // +Z face
        )

        var bestT = Double.MAX_VALUE
        var bestPoint: Vec3d? = null

        for (t in ts) {
            if (t.isNaN() || t.isInfinite()) continue
            if (t < 0.0) continue
            if (t >= bestT) continue

            val px = origin.x + ndx * t
            val py = origin.y + ndy * t
            val pz = origin.z + ndz * t

            // Check that the point is within the box (with small epsilon
            // to handle floating-point edge cases).
            val e = 1.0e-6
            if (px < box.minX - e || px > box.maxX + e) continue
            if (py < box.minY - e || py > box.maxY + e) continue
            if (pz < box.minZ - e || pz > box.maxZ + e) continue

            bestT = t
            bestPoint = Vec3d(px, py, pz)
        }

        return bestPoint
    }

    /**
     * Clamps [eye] to the bounds of [box] — the geometric nearest
     * point on the box. Used as a fallback when the look ray does not
     * intersect the box.
     */
    private fun clampToBox(eye: Vec3d, box: Box): Vec3d {
        val x = eye.x.coerceIn(box.minX, box.maxX)
        val y = eye.y.coerceIn(box.minY, box.maxY)
        val z = eye.z.coerceIn(box.minZ, box.maxZ)
        return Vec3d(x, y, z)
    }

    /**
     * Computes the angle (in degrees) between two 3D directions.
     * Used by the KillAura for FOV checks.
     */
    fun angleBetween(a: Vec3d, b: Vec3d): Float {
        val dot = a.x * b.x + a.y * b.y + a.z * b.z
        val lenA = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
        val lenB = sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
        if (lenA == 0.0 || lenB == 0.0) return 0f
        val cosTheta = (dot / (lenA * lenB)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(cosTheta)).toFloat()
    }

    /**
     * Converts a (yaw, pitch) pair to a normalized direction vector.
     */
    fun directionFromRotation(yaw: Float, pitch: Float): Vec3d {
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        val cosPitch = cos(pitchRad)
        return Vec3d(
            -sin(yawRad) * cosPitch,
            -sin(pitchRad),
            cos(yawRad) * cosPitch
        )
    }

    /**
     * Unused-for-now helper kept for future Bezier aim smoothing.
     */
    @Suppress("unused")
    private fun rotationFromDelta(eye: Vec3d, point: Vec3d): Pair<Float, Float> {
        val d = point.subtract(eye)
        val yaw = Math.toDegrees(atan2(d.z, d.x)).toFloat() - 90f
        val pitch = -Math.toDegrees(atan2(d.y, sqrt(d.x * d.x + d.z * d.z))).toFloat()
        return RealisticGcdSimulator.wrapDegrees(yaw) to pitch.coerceIn(-90f, 90f)
    }
}
