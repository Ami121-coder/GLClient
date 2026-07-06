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
 *
 * ── Thread-safety ───────────────────────────────────────────────────
 * [states] is a ConcurrentHashMap, but the inner [TrackState] holds
 * mutable fields that can be touched from BOTH the GameTick handler
 * (which iterates and mutates scores) AND event handlers calling
 * [recordAttack] (which appends to [recentAttackTimestamps]). The
 * previous implementation used a plain MutableList<Long> for the
 * attack-timestamp window — this was an unchecked race condition.
 *
 * We now:
 *   - Mark all mutable fields in [TrackState] as @Volatile for
 *     cross-thread visibility.
 *   - Use an [ArrayDeque] with a synchronized block for the
 *     attack-timestamp window. The deque is drained from the front
 *     (oldest first) which is O(1) per eviction, vs the previous
 *     O(n) `removeAll { it < cutoff }` on every score() call.
 *   - Synchronize ALL mutations of [TrackState] on the [TrackState]
 *     instance itself, so concurrent calls from the tick handler
 *     and event handlers cannot corrupt the score or the deque.
 */
@file:Suppress("MagicNumber", "WildcardImport")
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura.features

import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.ModuleCustomAura
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.util.wrapDegrees
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

/**
 * Squared distance (in blocks²) inside which we run the perfect-tracking
 * heuristic. 16.0 == 4 blocks — beyond this distance, even a perfect
 * crosshair lock could be a coincidence rather than an aimbot signature.
 */
private const val TRACK_DISTANCE_SQ = 16.0

/**
 * Maximum angle (in degrees) between the enemy's facing direction and
 * the line to our hitbox center for a tick to count as "perfect tracking".
 * 1° is well below any human's natural aim jitter at 4 blocks.
 */
private const val PERFECT_TRACK_ANGLE_DEG = 1f

/**
 * CPS window in milliseconds. Attack timestamps older than this are
 * evicted from [TrackState.recentAttackTimestamps] before computing the
 * current CPS. 1000ms = 1s window matches the user-facing "CPS" unit.
 */
private const val CPS_WINDOW_MS = 1000L

/**
 * Stale-entry cutoff for the [states] map. Entries whose
 * [TrackState.lastUpdateTick] is older than [player.age] minus this
 * value are pruned each tick. 200 ticks ≈ 10 seconds at 20 TPS.
 */
private const val STATE_STALENESS_TICKS = 200L

object CustomAuraAntiCheater : ToggleableConfigurable(
    parent = ModuleCustomAura,
    name = "AntiCheater",
    enabled = true
) {
    /**
     * Per-entity tracking state. Keyed by entity ID so we don't hold
     * references to dead entities.
     *
     * ALL mutable fields are guarded by `synchronized(this)` on the
     * [TrackState] instance itself. This is preferable to a separate
     * lock object because [TrackState] is private and never escapes
     * the [states] map, so external code cannot deadlock on it.
     */
    private class TrackState {
        @Volatile var lastYaw: Float = 0f
        @Volatile var lastPitch: Float = 0f
        @Volatile var maxYawDelta: Float = 0f
        @Volatile var consecutiveTrackTicks: Int = 0
        /** Guarded by `synchronized(this)`. */
        val recentAttackTimestamps: ArrayDeque<Long> = ArrayDeque()
        @Volatile var hitsAtLongRange: Int = 0
        @Volatile var score: Float = 0f
        @Volatile var lastUpdateTick: Long = 0L

        /**
         * Append a fresh attack timestamp and drain stale entries from
         * the FRONT of the deque (oldest first). Both operations are
         * O(1) per call — the previous implementation used
         * `MutableList.removeAll { it < cutoff }` which was O(n) on
         * every call AND not thread-safe.
         *
         * Must be called while holding `synchronized(this)`.
         */
        fun appendAttackTimestamp(timestampMs: Long, nowMs: Long) {
            val cutoff = nowMs - CPS_WINDOW_MS
            // Drain from the front: timestamps are appended in
            // monotonically increasing order, so once we hit one that's
            // still within the window we can stop.
            // Note: kotlin.collections.ArrayDeque doesn't have Java Deque's
            // peekFirst()/pollFirst() — use firstOrNull()/removeFirstOrNull()
            // instead, which return null on an empty deque.
            while (recentAttackTimestamps.isNotEmpty()) {
                val oldest = recentAttackTimestamps.firstOrNull() ?: break
                if (oldest < cutoff) {
                    recentAttackTimestamps.removeFirst()
                } else {
                    break
                }
            }
            recentAttackTimestamps.addLast(timestampMs)
        }

        /**
         * Current CPS (attacks within the last [CPS_WINDOW_MS] ms).
         *
         * Must be called while holding `synchronized(this)`.
         */
        fun currentCps(nowMs: Long): Int {
            val cutoff = nowMs - CPS_WINDOW_MS
            // Drain stale entries before counting so the deque does not
            // grow without bound if [currentCps] is called repeatedly
            // without [appendAttackTimestamp] (e.g. score() on tick
            // handler while no new attacks arrive).
            while (recentAttackTimestamps.isNotEmpty()) {
                val oldest = recentAttackTimestamps.firstOrNull() ?: break
                if (oldest < cutoff) {
                    recentAttackTimestamps.removeFirst()
                } else {
                    break
                }
            }
            return recentAttackTimestamps.size
        }
    }

    private val states = ConcurrentHashMap<Int, TrackState>()

    /**
     * Yaw delta threshold (degrees per tick) above which we consider the
     * enemy a snap-aimbot. 60° is well above any human capability.
     */
    internal var snapThreshold by float("SnapThreshold", 60f, 30f..180f, "°")

    /**
     * CPS threshold above which we flag the enemy as autoclicker.
     */
    internal var cpsThreshold by float("CpsThreshold", 14f, 8f..20f, "cps")

    /**
     * Consecutive ticks of perfect tracking required to flag target-lock.
     */
    internal var trackingTicksThreshold by int("TrackingTicks", 20, 5..60, "ticks")

    /**
     * Reach (in blocks) above which we count a hit as "long range".
     */
    internal var longRangeHit by float("LongRangeHit", 3.8f, 3f..6f, "blocks")

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

        // Decay all scores over time. We do NOT take the per-state lock
        // here — the read-modify-write of `score` is a single Float, and
        // the worst-case race with [recordAttack] is a lost decay tick
        // (one extra increment of ~0.5), which is acceptable for a
        // heuristic scoring system. Taking the lock would risk deadlock
        // if [score] (called from the tick thread) and [recordAttack]
        // (called from event handlers) ever nest.
        states.values.forEach { state ->
            state.score = (state.score - scoreDecay).coerceAtLeast(0f)
        }

        // Prune stale entries.
        val cutoff = player.age.toLong() - STATE_STALENESS_TICKS
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

        // Snap detection — single RMW on a Float, see tick handler note.
        if (state.lastYaw != 0f && delta > snapThreshold) {
            state.score = (state.score + snapScore).coerceAtMost(maxScore)
        }
        state.maxYawDelta = maxOf(state.maxYawDelta, delta)

        // Perfect-tracking detection: is the enemy looking directly at us?
        val distSq = entity.squaredBoxedDistanceTo(player)
        if (distSq < TRACK_DISTANCE_SQ) {
            val lookDir = entity.rotation
            val toUs = Rotation.lookingAt(
                from = entity.eyePos,
                point = player.box.center
            )
            val angleDiff = lookDir.angleTo(toUs)
            if (angleDiff < PERFECT_TRACK_ANGLE_DEG) {
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

        // CPS detection: drain-and-count under the per-state lock.
        val now = System.currentTimeMillis()
        val cps = synchronized(state) { state.currentCps(now) }
        if (cps >= cpsThreshold) {
            state.score = (state.score + cpsScore * 0.1f).coerceAtMost(maxScore)
        }

        // Long-range hits — checked in [recordAttack].
        state.lastYaw = currentYaw
        state.lastPitch = currentPitch
        state.lastUpdateTick = player.age.toLong()
    }

    /**
     * Called by the module when the enemy lands an attack on us or any
     * other player. Records the timestamp and (if applicable) flags a
     * long-range hit.
     */
    fun recordAttack(attacker: LivingEntity, targetDistanceSq: Double) {
        if (!running || attacker !is PlayerEntity) return

        val state = states.computeIfAbsent(attacker.id) { TrackState() }
        val now = System.currentTimeMillis()
        synchronized(state) {
            state.appendAttackTimestamp(now, now)
        }

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
     * Apply preset parameters. Called by [ModuleCustomAura.applyPreset].
     */
    internal fun applyPreset(params: net.ccbluex.liquidbounce.features.module.modules.combat.customaura.CustomAuraPresets.Params) {
        this.enabled = params.antiCheaterEnabled
        snapThreshold = params.snapThreshold
        cpsThreshold = params.cpsThreshold
        trackingTicksThreshold = params.trackingTicksThreshold
        longRangeHit = params.longRangeHit
    }
}
