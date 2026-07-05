/*
 * CustomAuraAntiCheater — detects enemy cheaters and prioritizes them
 * so our aura out-clicks competing cheaters.
 *
 * Detection heuristics (each adds to a per-entity "cheater score"):
 *
 *  1. Rotation snap — if the enemy's server-side yaw delta between two
 *     ticks exceeds ~60°, that is a near-certain aimbot signature.
 *     Humans max out around 30-40°/tick even on high-DPI.
 *
 *  2. Suspicious CPS — if the enemy's attack rate against us or other
 *     players exceeds ~14 CPS, they are likely using an autoclicker.
 *
 *  3. Perfect tracking — if the enemy's crosshair stays within 1° of
 *     our hitbox center for >20 consecutive ticks while we strafe,
 *     that is a target-lock signature.
 *
 *  4. Reach outlier — if the enemy lands hits from >3.8 blocks
 *     consistently, they are using a reach extender.
 *
 * The score is decayed each tick so historical flags age out, and the
 * tracker only returns a high score for entities that are CURRENTLY
 * acting suspiciously.
 *
 * The module's target selector uses [score] to break ties so we prefer
 * killing cheaters when multiple enemies are in range.
 */
@file:Suppress("MagicNumber", "WildcardImport")
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura.features

import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.ModuleCustomAura
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.entity.box
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

object CustomAuraAntiCheater : ToggleableConfigurable(
    owner = ModuleCustomAura,
    name = "AntiCheater",
    default = true
) {
    /**
     * Per-entity tracking state. Keyed by entity ID so we don't hold
     * references to dead entities.
     */
    private data class TrackState(
        var lastYaw: Float = 0f,
        var lastPitch: Float = 0f,
        var maxYawDelta: Float = 0f,
        var consecutiveTrackTicks: Int = 0,
        var recentAttackTimestamps: MutableList<Long> = mutableListOf(),
        var hitsAtLongRange: Int = 0,
        var score: Float = 0f,
        var lastUpdateTick: Long = 0L
    )

    private val states = ConcurrentHashMap<Int, TrackState>()

    /**
     * Yaw delta threshold (degrees per tick) above which we consider the
     * enemy a snap-aimbot. 60° is well above any human capability.
     */
    private val snapThreshold by float("SnapThreshold", 60f, 30f..180f, "°")

    /**
     * CPS threshold above which we flag the enemy as autoclicker.
     */
    private val cpsThreshold by float("CpsThreshold", 14f, 8f..20f, "cps")

    /**
     * Consecutive ticks of perfect tracking required to flag target-lock.
     */
    private val trackingTicksThreshold by int("TrackingTicks", 20, 5..60, "ticks")

    /**
     * Reach (in blocks) above which we count a hit as "long range".
     */
    private val longRangeHit by float("LongRangeHit", 3.8f, 3f..6f, "blocks")

    /**
     * Score decay per tick — historical flags age out at this rate.
     */
    private val scoreDecay by float("ScoreDecay", 0.5f, 0.1f..2f)

    /**
     * Score cap so a single cheater doesn't permanently dominate
     * targeting if a non-cheater gets much closer.
     */
    private val maxScore by float("MaxScore", 100f, 10f..1000f)

    /**
     * Per-flag score contributions.
     */
    private val snapScore by float("SnapScore", 30f, 1f..100f)
    private val cpsScore by float("CpsScore", 20f, 1f..100f)
    private val trackingScore by float("TrackingScore", 25f, 1f..100f)
    private val reachScore by float("ReachScore", 15f, 1f..100f)

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (!running) return@handler

        // Decay all scores over time.
        states.values.forEach { state ->
            state.score = (state.score - scoreDecay).coerceAtLeast(0f)
        }

        // Prune stale entries.
        val cutoff = player.age - 200  // 10 seconds
        states.entries.removeIf { it.value.lastUpdateTick < cutoff }
    }

    /**
     * Public API: score an entity for prioritization. Higher score =
     * more likely a cheater = should be attacked first.
     *
     * Called every tick by the target selector. Also internally updates
     * tracking state for the entity.
     */
    fun score(entity: LivingEntity): Float {
        if (!running) return 0f
        if (entity !is PlayerEntity) return 0f

        val state = states.computeIfAbsent(entity.id) { TrackState() }
        updateState(state, entity)

        return state.score
    }

    private fun updateState(state: TrackState, entity: PlayerEntity) {
        val currentYaw = entity.rotation.yaw
        val currentPitch = entity.rotation.pitch
        val delta = abs(wrapDegrees(currentYaw - state.lastYaw))

        // Snap detection.
        if (state.lastYaw != 0f && delta > snapThreshold) {
            state.score = (state.score + snapScore).coerceAtMost(maxScore)
        }
        state.maxYawDelta = maxOf(state.maxYawDelta, delta)

        // Perfect-tracking detection: is the enemy looking directly at us?
        val distSq = entity.squaredBoxedDistanceTo(player)
        if (distSq < 16.0) {  // within 4 blocks
            val lookDir = entity.rotation
            val toUs = Rotation.lookingAt(
                from = entity.eyePos,
                point = player.box.center
            )
            val angleDiff = lookDir.angleTo(toUs)
            if (angleDiff < 1f) {
                state.consecutiveTrackTicks++
                if (state.consecutiveTrackTicks >= trackingTicksThreshold) {
                    state.score = (state.score + trackingScore * 0.1f).coerceAtMost(maxScore)
                }
            } else {
                state.consecutiveTrackTicks = 0
            }
        } else {
            state.consecutiveTrackTicks = 0
        }

        // CPS detection: trim attack timestamps older than 1 second.
        val now = System.currentTimeMillis()
        state.recentAttackTimestamps.removeAll { it < now - 1000 }
        val cps = state.recentAttackTimestamps.size
        if (cps >= cpsThreshold) {
            state.score = (state.score + cpsScore * 0.1f).coerceAtMost(maxScore)
        }

        // Long-range hits — checked in [recordAttack].
        state.lastYaw = currentYaw
        state.lastPitch = currentPitch
        state.lastUpdateTick = player.age
    }

    /**
     * Called by the module when the enemy lands an attack on us or any
     * other player. Records the timestamp and (if applicable) flags a
     * long-range hit.
     */
    fun recordAttack(attacker: LivingEntity, targetDistanceSq: Double) {
        if (!running || attacker !is PlayerEntity) return

        val state = states.computeIfAbsent(attacker.id) { TrackState() }
        state.recentAttackTimestamps.add(System.currentTimeMillis())

        val distance = sqrt(targetDistanceSq.toDouble()).toFloat()
        if (distance > longRangeHit) {
            state.hitsAtLongRange++
            state.score = (state.score + reachScore * 0.5f).coerceAtMost(maxScore)
        }
    }

    /**
     * Reset all tracking state (called on module disable or world change).
     */
    fun reset() {
        states.clear()
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
}
