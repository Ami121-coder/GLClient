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

import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import java.util.concurrent.ConcurrentHashMap

/**
 * # BacktrackStore — position history for combat backtrack.
 *
 * Stores per-entity position snapshots (pos + bounding box + velocity +
 * tick-of-observation) for the last [maxHistoryTicks] ticks. The
 * KillAura1_8 module can then pick the snapshot whose bounding box
 * yields the smallest reach distance from the player's eye — the same
 * technique used by Augustus, Rise, and Polar-bypass clients.
 *
 * ## Thread safety
 *
 * Snapshots are written from the client thread (in the tick handler)
 * and read from the same thread. The map itself is concurrent to be
 * defensive against the rotation-update handler running on a different
 * stage of the same tick.
 *
 * ## Why not reuse ModuleBacktrack?
 *
 * ModuleBacktrack delays inbound packets to extend the server-visible
 * window of entity positions. That is a packet-level technique with
 * different semantics and side effects (packet queue, pause-on-hurt,
 * visualization). This store is a pure read-side history used only to
 * decide *which tick's* hitbox we attack — no packets are delayed, no
 * risk of "lag detected" flags from the anticheat. The two modules can
 * coexist; combining them gives the longest effective backtrack window.
 *
 * Author: Super Z (1.8 PvP rewrite, v4)
 */
object BacktrackStore {

    /**
     * Maximum number of ticks of history kept per entity.
     * 3 ticks = 150 ms, which is enough to compensate 150 ms of ping
     * without exceeding Polar's "PositionHistory" buffer window.
     */
    const val maxHistoryTicks = 3

    private data class Snapshot(
        val pos: Vec3d,
        val box: Box,
        val velocity: Vec3d,
        val yaw: Float,
        val pitch: Float,
        val tick: Long,
        val onGround: Boolean
    )

    /**
     * Per-entity snapshot ring buffer. Each list is kept sorted by tick
     * ascending; new snapshots are appended and the oldest is dropped
     * when the buffer exceeds [maxHistoryTicks].
     */
    private val history: MutableMap<Int, MutableList<Snapshot>> = ConcurrentHashMap()

    /**
     * Global tick counter, incremented on every [tick] call.
     */
    private var tickCounter: Long = 0L

    /**
     * Called once per game tick by [ModuleKillAura1_8]. Captures a
     * snapshot of every LivingEntity in the world.
     */
    fun tick(worldEntities: Iterable<Entity>) {
        tickCounter++

        // Capture snapshots for all living entities.
        val seen = HashSet<Int>()
        for (entity in worldEntities) {
            if (entity !is LivingEntity) continue
            if (entity.isRemoved) continue

            val list = history.getOrPut(entity.id) { ArrayList(maxHistoryTicks + 1) }
            list.add(
                Snapshot(
                    pos = entity.pos,
                    box = entity.boundingBox,
                    velocity = entity.velocity,
                    yaw = entity.yaw,
                    pitch = entity.pitch,
                    tick = tickCounter,
                    onGround = entity.isOnGround
                )
            )
            // Trim old snapshots beyond the window.
            while (list.size > maxHistoryTicks) {
                list.removeAt(0)
            }
            seen.add(entity.id)
        }

        // Drop history for entities no longer in the world to bound memory.
        val iterator = history.entries.iterator()
        while (iterator.hasNext()) {
            val (id, _) = iterator.next()
            if (id !in seen) iterator.remove()
        }
    }

    /**
     * Returns the best (smallest eye-distance) historical snapshot for
     * the given entity, restricted to snapshots not older than
     * [maxAgeTicks] ticks.
     *
     * The "best" snapshot is the one whose nearest-point-on-box distance
     * from [eyePos] is the smallest — i.e. the tick at which the target
     * was closest to the crosshair, which is exactly what a backtrack-
     * assisted KillAura wants to attack.
     *
     * Returns null if the entity has no recorded history.
     */
    fun bestSnapshot(entity: Entity, eyePos: Vec3d, maxAgeTicks: Int = maxHistoryTicks): HistoricalHit? {
        val list = history[entity.id] ?: return null
        if (list.isEmpty()) return null

        val minTick = tickCounter - maxAgeTicks + 1
        var best: HistoricalHit? = null
        var bestDistSq = Double.MAX_VALUE

        for (snap in list) {
            if (snap.tick < minTick) continue
            val nearest = nearestPointInBox(eyePos, snap.box)
            val distSq = eyePos.squaredDistanceTo(nearest)
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                best = HistoricalHit(
                    pos = snap.pos,
                    box = snap.box,
                    nearestPoint = nearest,
                    eyeDistance = Math.sqrt(distSq),
                    tickAge = (tickCounter - snap.tick).toInt(),
                    onGround = snap.onGround,
                    velocity = snap.velocity
                )
            }
        }
        return best
    }

    /**
     * Returns the most recent (tick = now) snapshot, useful as a
     * fallback when no historical snapshot is meaningfully better.
     */
    fun latestSnapshot(entity: Entity): HistoricalHit? {
        val list = history[entity.id] ?: return null
        if (list.isEmpty()) return null
        val snap = list.last()
        val eyePos = net.ccbluex.liquidbounce.utils.client.player.eyePos
        val nearest = nearestPointInBox(eyePos, snap.box)
        return HistoricalHit(
            pos = snap.pos,
            box = snap.box,
            nearestPoint = nearest,
            eyeDistance = eyePos.distanceTo(nearest),
            tickAge = (tickCounter - snap.tick).toInt(),
            onGround = snap.onGround,
            velocity = snap.velocity
        )
    }

    fun reset() {
        history.clear()
        tickCounter = 0L
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Pure-Kotlin clamp of the eye position to the box bounds. Avoids
     * a circular dependency on EntityExtensions.getNearestPoint (which
     * is fine, but inlined here so the store has no combat-package
     * imports).
     */
    private fun nearestPointInBox(eye: Vec3d, box: Box): Vec3d {
        val x = eye.x.coerceIn(box.minX, box.maxX)
        val y = eye.y.coerceIn(box.minY, box.maxY)
        val z = eye.z.coerceIn(box.minZ, box.maxZ)
        return Vec3d(x, y, z)
    }

    /**
     * Public projection of a stored snapshot — exposes everything the
     * KillAura needs to choose an aim point and validate reach.
     */
    data class HistoricalHit(
        val pos: Vec3d,
        val box: Box,
        val nearestPoint: Vec3d,
        val eyeDistance: Double,
        val tickAge: Int,
        val onGround: Boolean,
        val velocity: Vec3d
    )
}
