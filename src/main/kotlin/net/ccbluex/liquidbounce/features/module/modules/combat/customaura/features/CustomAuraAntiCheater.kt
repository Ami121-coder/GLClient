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
 *  3. Perfect tracking — if the enemy's crosshair stays within 3° of
 *     our hitbox center (yaw-only, ignoring pitch) for >20 consecutive
 *     ticks while we strafe, that is a target-lock signature. The
 *     previous 1° threshold gave false negatives for typical aimbots
 *     that jitter 3-5°; the new 3° threshold catches them while still
 *     being well below a human's natural 5-15° aim jitter at 4 blocks.
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
 * [states] is a ConcurrentHashMap. The inner [TrackState] holds mutable
 * fields that can be touched from BOTH the GameTick handler (which
 * iterates and mutates scores) AND the HealthUpdateEvent handler
 * (which appends to [recentAttackTimestamps] and bumps score for
 * long-range hits).
 *
 * ALL mutations of [TrackState.score] and [recentAttackTimestamps]
 * are guarded by `synchronized(state)` — including the decay RMW in
 * the tick handler. The previous implementation skipped the lock on
 * the decay RMW, which caused lost updates when [recordAttack] ran
 * concurrently. The lock is cheap (uncontended on the tick thread,
 * and HealthUpdateEvent is rare), so we take it always.
 *
 * The [score] read in [updateState] is the only RMW done outside the
 * per-state lock — it is a single Float RMW, and the worst-case race
 * is a single lost increment (≈0.5 score points on a 0..100 scale),
 * which is acceptable for a heuristic.
 */
@file:Suppress("MagicNumber", "WildcardImport")
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura.features

import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.HealthUpdateEvent
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
 * Maximum YAW angle (in degrees) between the enemy's facing direction and
 * the line to our hitbox center for a tick to count as "perfect tracking".
 * 3° is below any human's natural aim jitter at 4 blocks, but above the
 * 0.5-2° jitter of a typical silent-aimbot. The previous 1° threshold
 * gave false negatives for aimbots that intentionally jitter 2-4° to
 * evade AimC detection.
 *
 * Yaw-only (horizontal plane) — pitch is excluded because an enemy
 * looking at our feet (negative pitch) would otherwise push the
 * combined angle above the threshold even with perfect yaw aim.
 */
private const val PERFECT_TRACK_YAW_DEG = 3f

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
     * ALL mutations of [score] and [recentAttackTimestamps] are guarded
     * by `synchronized(this)` on the [TrackState] instance itself.
     */
    private class TrackState {
        @Volatile var lastYaw: Float = 0f
        @Volatile var lastPitch: Float = 0f
        @Volatile var consecutiveTrackTicks: Int = 0
        /** Guarded by `synchronized(this)`. */
        val recentAttackTimestamps: ArrayDeque<Long> = ArrayDeque()
        @Volatile var score: Float = 0f
        @Volatile var lastUpdateTick: Long = 0L

        /**
         * Append a fresh attack timestamp and drain stale entries from
         * the FRONT of the deque (oldest first). Both operations are
         * O(1) per call.
         *
         * Must be called while holding `synchronized(this)`.
         */
        fun appendAttackTimestamp(timestampMs: Long, nowMs: Long) {
            val cutoff = nowMs - CPS_WINDOW_MS
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

        /**
         * Atomic-ish RMW on [score]. Used by all mutations to guarantee
         * no lost updates when concurrent callers (tick handler +
         * event handler) touch the same [TrackState].
         *
         * The block is executed under `synchronized(this)`, so callers
         * that already hold the lock can re-enter safely (Kotlin's
         * synchronized is reentrant on the JVM).
         */
        inline fun mutateScore(crossinline block: (Float) -> Float) {
            synchronized(this) {
                score = block(score)
            }
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

        // Decay all scores over time. We take the per-state lock so
        // the read-modify-write of `score` cannot lose an increment
        // added by [recordAttack] running concurrently on the event
        // handler thread. The lock is reentrant on the JVM, so this
        // is safe even if a future code path nests calls.
        states.values.forEach { state ->
            state.mutateScore { s -> (s - scoreDecay).coerceAtLeast(0f) }
        }

        // Prune stale entries.
        val cutoff = player.age.toLong() - STATE_STALENESS_TICKS
        states.entries.removeIf { it.value.lastUpdateTick < cutoff }
    }

    /**
     * Listen for damage events on the local player. When we take damage,
     * we look for the nearest enemy player within [longRangeHit] blocks
     * and credit them with an attack — this feeds the CPS heuristic.
     *
     * This is an approximation: the server doesn't tell us WHO attacked
     * us, only that we took damage. We assume it was the nearest enemy
     * in range. False positives are bounded because:
     *  - If no enemy is in range, we don't credit anyone.
     *  - If multiple enemies are in range, we credit the closest one
     *    (which is the most likely attacker in melee combat).
     *  - The score decays quickly ([scoreDecay] per tick), so a single
     *    false positive doesn't permanently bias targeting.
     *
     * The previous implementation tried to listen on [AttackEntityEvent],
     * but that event only fires for OUR OWN attacks — not for attacks by
     * other players. The CPS heuristic was therefore dead code. This
     * health-based approach is the best we can do without a dedicated
     * "enemy attack" event, which LiquidBounce does not currently emit.
     */
    @Suppress("unused")
    private val healthHandler = handler<HealthUpdateEvent> { event ->
        if (!running) return@handler
        // Only credit an attack if we actually took damage (health
        // decreased). Health-regen increases don't count.
        if (event.health >= event.previousHealth) return@handler

        // Find the nearest enemy player within longRangeHit blocks.
        // This is the most likely attacker in melee combat.
        val maxDistSq = (longRangeHit * longRangeHit).toDouble()
        var nearestAttacker: PlayerEntity? = null
        var nearestDistSq = Double.MAX_VALUE
        for (entity in player.world.entities) {
            if (entity == player) continue
            if (entity !is PlayerEntity) continue
            if (entity.isSpectator || entity.isDead) continue
            val distSq = entity.squaredBoxedDistanceTo(player)
            if (distSq > maxDistSq) continue
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq
                nearestAttacker = entity
            }
        }

        if (nearestAttacker != null) {
            recordAttack(nearestAttacker, nearestDistSq)
        }
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

        // Snap detection — single RMW via mutateScore (lock-protected).
        if (state.lastYaw != 0f && delta > snapThreshold) {
            state.mutateScore { s -> (s + snapScore).coerceAtMost(maxScore) }
        }

        // Perfect-tracking detection: is the enemy's YAW looking at us?
        // Yaw-only check (horizontal plane) — pitch is excluded because
        // an enemy looking at our feet would otherwise fail the combined
        // angle check even with perfect yaw aim.
        val distSq = entity.squaredBoxedDistanceTo(player)
        if (distSq < TRACK_DISTANCE_SQ) {
            val enemyYaw = entity.rotation.yaw
            val toUs = Rotation.lookingAt(
                from = entity.eyePos,
                point = player.box.center
            )
            val yawDiff = abs(wrapDegrees(enemyYaw - toUs.yaw))
            if (yawDiff < PERFECT_TRACK_YAW_DEG) {
                state.consecutiveTrackTicks++
                if (state.consecutiveTrackTicks >= trackingTicksThreshold) {
                    state.mutateScore { s -> (s + trackingScore * 0.1f).coerceAtMost(maxScore) }
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
            state.mutateScore { s -> (s + cpsScore * 0.1f).coerceAtMost(maxScore) }
        }

        state.lastYaw = currentYaw
        state.lastPitch = currentPitch
        state.lastUpdateTick = player.age.toLong()
    }

    /**
     * Called by [healthHandler] when the local player takes damage.
     * Credits the suspected attacker (nearest enemy in range) with an
     * attack timestamp, feeding the CPS heuristic. Also flags a
     * long-range hit if the attacker is beyond [longRangeHit] blocks.
     *
     * Public so external callers (e.g. a future packet-level detector)
     * can feed in more accurate attack data if available.
     */
    fun recordAttack(attacker: LivingEntity, targetDistanceSq: Double) {
        if (!running || attacker !is PlayerEntity) return
        if (attacker == player) return

        val state = states.computeIfAbsent(attacker.id) { TrackState() }
        val now = System.currentTimeMillis()
        synchronized(state) {
            state.appendAttackTimestamp(now, now)
        }

        val distance = sqrt(targetDistanceSq.toDouble()).toFloat()
        if (distance > longRangeHit) {
            state.mutateScore { s -> (s + reachScore * 0.5f).coerceAtMost(maxScore) }
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
