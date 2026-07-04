/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
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
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.event.Sequence
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.player.ModuleBlink
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.render.drawLineStrip
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withColor
import net.ccbluex.liquidbounce.utils.combat.findEnemy
import net.ccbluex.liquidbounce.utils.entity.PlayerSimulationCache
import net.ccbluex.liquidbounce.utils.entity.SimulatedPlayerCache
import net.ccbluex.liquidbounce.utils.kotlin.mapArray
import net.ccbluex.liquidbounce.utils.math.sq
import net.ccbluex.liquidbounce.utils.math.toVec3
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket
import net.minecraft.util.math.Vec3d
import kotlin.math.ceil
import kotlin.math.min

/**
 * TickBase
 *
 * Calls tick function to speed up, when needed.
 *
 * Improvements over the original implementation:
 *  - Proper hysteresis on the tick balance (separate "depleted" and "resumed"
 *    thresholds) to prevent oscillation when [balanceRecoveryIncrement] is
 *    large relative to [balanceMaxValue].
 *  - Target selection is synchronized with KillAura's current target when
 *    available, so we fast-forward toward the same enemy KillAura is about
 *    to hit instead of always picking the nearest one.
 *  - Enemy position is predicted [bestTick] ticks into the future using
 *    [PlayerSimulationCache], so the chosen candidate tick stays valid by
 *    the time the burst completes.
 *  - Critical hit detection requires `!onGround && fallDistance > 0`,
 *    matching vanilla mechanics, instead of just `fallDistance > 0`.
 *  - Burst execution: the [bestTick] advance is split into chunks of at
 *    most [maxTicksPerBurst] ticks with a one-tick gap between bursts to
 *    reduce per-tick packet burst detection by anticheats.
 *  - The PAST mode re-checks the break condition between and inside
 *    bursts, so a flag or KillAura state change can abort mid-execution.
 *  - A flag (PlayerPositionLookS2CPacket) immediately aborts any in-flight
 *    TickBase, clears the buffer, and forces the balance to zero.
 *  - The simulation buffer is refreshed on every game tick (not only on
 *    movement input events), so stationary players still have a valid
 *    forecast when an enemy approaches.
 *  - The upper bound of the activation range is clamped to KillAura's
 *    attack range minus a small safety margin to avoid fast-forwarding
 *    into a position where KillAura cannot actually land a hit.
 */
internal object ModuleTickBase : ClientModule("TickBaseV2", Category.COMBAT) {

    private val mode by enumChoice("Mode", TickBaseMode.PAST)
        .apply { tagBy(this) }
    private val call by enumChoice("Call", TickBaseCall.GAME)

    /**
     * The range defines where we want to fast-forward into. The first value
     * is the minimum range, which we can fast-forward into, and the second
     * value is the range where we cannot fast-forward at all.
     */
    private val range by floatRange("Range", 2.5f..4f, 0f..8f)

    // ── Balance ──────────────────────────────────────────────────────────────
    private val balanceRecoveryIncrement by float("BalanceRecoverIncrement", 1f, 0f..2f)
    private val balanceMaxValue by int("BalanceMaxValue", 20, 0..200)
    private val balanceResumeThreshold by int("BalanceResumeThreshold", 10, 1..200, "ticks")
        .doNotIncludeAlways()

    // ── Tick scheduling ──────────────────────────────────────────────────────
    private val maxTicksAtATime by int("MaxTicksAtATime", 4, 1..20, "ticks")
    private val maxTicksPerBurst by int("MaxTicksPerBurst", 2, 1..20, "ticks")
    private val pause by int("Pause", 0, 0..20, "ticks")
    private val cooldown by int("Cooldown", 0, 0..100, "ticks")

    // ── Behavior ─────────────────────────────────────────────────────────────
    private val pauseOnFlag by boolean("PauseOnFlag", true)
    private val forceGround by boolean("ForceGround", false)
    private val syncWithKillAura by boolean("SyncWithKillAura", true)
    private val predictEnemy by boolean("PredictEnemy", true)
    private val respectKillAuraRange by boolean("RespectKillAuraRange", true)
    private val criticalOnly by boolean("CriticalOnly", false)

    private val lineColor by color("Line", Color4b.WHITE)
        .doNotIncludeAlways()

    private val requiresKillAura by boolean("RequiresKillAura", true)

    // ── State ────────────────────────────────────────────────────────────────
    private var ticksToSkip = 0
    private var tickBalance = 0f
    private var reachedTheLimit = false
    private var flaggedRecently = false
    private var currentTarget: LivingEntity? = null

    private val tickBuffer = mutableListOf<TickData>()

    override fun disable() {
        tickBuffer.clear()
        currentTarget = null
        tickBalance = 0f
        reachedTheLimit = false
        flaggedRecently = false
        ticksToSkip = 0
    }

    @Suppress("unused")
    private val playerTickHandler = handler<PlayerTickEvent> { event ->
        // We do not want this module to conflict with blink.
        if (player.vehicle != null || ModuleBlink.running) {
            return@handler
        }

        // If we got flagged this tick, abort any in-flight TickBase and let
        // the player tick normally so the server-resynchronized position is
        // applied immediately.
        if (flaggedRecently) {
            ticksToSkip = 0
            tickBuffer.clear()
            flaggedRecently = false
            return@handler
        }

        if (ticksToSkip-- > 0) {
            event.cancelEvent()
        }
    }

    @Suppress("unused")
    private val tickHandler = tickHandler {
        // We do not want this module to conflict with blink.
        if (player.vehicle != null || ModuleBlink.running) {
            return@tickHandler
        }

        // Refresh the simulation buffer in case no MovementInputEvent has
        // fired this tick (e.g. when the player is standing still).
        if (tickBuffer.isEmpty()) {
            refreshTickBuffer()
        }
        if (tickBuffer.isEmpty()) {
            return@tickHandler
        }

        val target = chooseTarget()
        if (target == null) {
            currentTarget = null
            return@tickHandler
        }
        currentTarget = target

        // Compute the effective range. When KillAura is running and the user
        // wants us to respect its range, clamp the upper bound so we never
        // fast-forward into a position where KillAura cannot actually hit.
        val effectiveRangeEnd = if (respectKillAuraRange && ModuleKillAura.running) {
            min(range.endInclusive, (ModuleKillAura.range - 0.15f).coerceAtLeast(range.start))
        } else {
            range.endInclusive
        }
        val rangeSq = range.start.sq()..effectiveRangeEnd.sq()

        val currentDistance = player.pos.squaredDistanceTo(target.pos)

        // Build a per-tick cache of predicted enemy positions. This is only
        // possible for PlayerEntity targets; for other entities we fall back
        // to the current position.
        val enemyCache = if (predictEnemy && target is PlayerEntity) {
            PlayerSimulationCache.getSimulationForOtherPlayers(target)
        } else {
            null
        }

        // Collect the indices of ticks that bring us closer to the enemy than
        // we currently are AND land us inside the effective range AND (when
        // forceGround is on) put us on ground. We work with indices into
        // [tickBuffer] to avoid defining a local data class.
        val candidateIndices = tickBuffer.indices.filter { i ->
            val tick = tickBuffer[i]
            val enemyPos = predictEnemyPosition(enemyCache, target, i)
            val distSq = tick.position.squaredDistanceTo(enemyPos)
            val inRange = distSq < currentDistance && distSq in rangeSq
            val groundOk = !forceGround || tick.onGround
            inRange && groundOk
        }

        if (candidateIndices.isEmpty()) {
            return@tickHandler
        }

        // Prefer a tick that allows a critical hit (vanilla-correct check:
        // the player must be airborne and have a non-zero fall distance).
        val criticalIndex = candidateIndices.firstOrNull { i ->
            isCriticalCandidate(tickBuffer[i])
        }
        val bestTick = criticalIndex ?: candidateIndices.first()

        if (bestTick == 0) {
            return@tickHandler
        }

        // If CriticalOnly is on and we don't have a critical candidate, bail.
        if (criticalOnly && criticalIndex == null) {
            return@tickHandler
        }

        // We do not want to fast-forward if KillAura is not ready to attack.
        // Re-evaluated between and inside bursts so we can abort mid-execution.
        val breakRequirement: () -> Boolean = {
            flaggedRecently || (requiresKillAura && !(ModuleKillAura.running &&
                ModuleKillAura.clickScheduler.willClickAt(bestTick)))
        }

        if (breakRequirement()) {
            return@tickHandler
        }

        executeBurst(sequence = this, bestTick = bestTick, breakRequirement = breakRequirement)

        waitTicks(cooldown)
    }

    /**
     * Runs the fast-forward burst for both PAST and FUTURE modes.
     *
     * PAST semantics: skip [bestTick] + [pause] real ticks locally first
     * (player frozen), then advance the game state by [bestTick] ticks to
     * "catch up" with where we should be.
     *
     * FUTURE semantics: advance the game state by [bestTick] ticks first to
     * "fast-forward" into the future, then skip [bestTick] + [pause] real
     * ticks locally to keep the real-time / game-time balance.
     *
     * In both modes the advance is split into bursts of at most
     * [maxTicksPerBurst] ticks, with a one-tick gap between bursts. During
     * the gap the player is kept frozen (ticksToSkip = 1) so the user does
     * not double-tick. The break condition is re-evaluated between and
     * inside bursts, so a flag or KillAura state change can abort the
     * fast-forward early.
     */
    private suspend fun executeBurst(
        sequence: Sequence,
        bestTick: Int,
        breakRequirement: () -> Boolean
    ) {
        val numBursts = ceil(bestTick.toDouble() / maxTicksPerBurst).toInt().coerceAtLeast(1)
        val interBurstGaps = numBursts - 1

        // Compensate the skip count for the inter-burst gaps so the net
        // "behindness" stays equal to [pause] ticks (matching the original
        // semantics when maxTicksPerBurst >= bestTick).
        val compensatedSkip = (bestTick + pause - interBurstGaps).coerceAtLeast(0)

        // PAST: skip locally first, then fast-forward.
        // FUTURE: fast-forward first, then skip locally.
        if (mode == TickBaseMode.PAST) {
            ticksToSkip = compensatedSkip
            if (compensatedSkip > 0) {
                sequence.waitTicks(compensatedSkip)
            }
            // After the wait, ticksToSkip has been decremented to 0 by playerTickHandler.
        }

        var remaining = bestTick
        var actuallySkipped = 0

        while (remaining > 0 && !breakRequirement()) {
            val burst = minOf(remaining, maxTicksPerBurst)
            var burstDone = 0

            // Inner loop: re-check the break condition on every call.tick()
            // so a flag arriving mid-burst aborts immediately.
            while (burstDone < burst && !breakRequirement()) {
                call.tick()
                tickBalance = (tickBalance - 1f).coerceAtLeast(0f)
                burstDone++
                actuallySkipped++
            }
            remaining -= burstDone

            if (remaining > 0) {
                // Freeze the player for 1 tick between bursts to keep the
                // "player advances exactly bestTick ticks" invariant.
                ticksToSkip = 1
                sequence.waitTicks(1)
                // ticksToSkip is now 0 again.
            }
        }

        ModuleDebug.debugParameter(this, "Recommended Skip", bestTick)
        ModuleDebug.debugParameter(this, "Actually Skipped", actuallySkipped)

        // FUTURE: skip locally after the fast-forward.
        if (mode == TickBaseMode.FUTURE) {
            val finalSkip = (actuallySkipped + pause - interBurstGaps).coerceAtLeast(0)
            ticksToSkip = finalSkip
            if (finalSkip > 0) {
                sequence.waitTicks(finalSkip)
            }
        }

        ticksToSkip = 0
    }

    /**
     * Choose the target enemy to fast-forward toward. Prefers KillAura's
     * current target when [syncWithKillAura] is enabled and KillAura is
     * running; falls back to the nearest enemy in range.
     */
    private fun chooseTarget(): LivingEntity? {
        if (syncWithKillAura && ModuleKillAura.running) {
            val kaTarget = ModuleKillAura.targetTracker.target
            if (kaTarget != null) {
                return kaTarget
            }
        }
        return world.findEnemy(0f..range.endInclusive) as? LivingEntity
    }

    /**
     * Predict the enemy's position [tickAhead] ticks into the future.
     * Returns the entity's current position if prediction is unavailable
     * (e.g. for non-player entities or when the cache is null).
     */
    private fun predictEnemyPosition(
        cache: SimulatedPlayerCache?,
        target: LivingEntity,
        tickAhead: Int
    ): Vec3d {
        if (cache == null) return target.pos
        return try {
            cache.getSnapshotAt(tickAhead).pos
        } catch (_: Throwable) {
            target.pos
        }
    }

    /**
     * Check whether a candidate tick would allow a critical hit.
     *
     * Vanilla critical hit check (1.9+): the attacker must be falling
     * (fallDistance > 0) and not on ground, not in water, not on a ladder,
     * and not blinded. The simulation snapshot only carries position,
     * fallDistance, velocity, and onGround, so we approximate with the
     * airborne and falling check.
     */
    private fun isCriticalCandidate(tick: TickData): Boolean {
        return !tick.onGround && tick.fallDistance > 0.0f
    }

    @Suppress("unused")
    private val inputHandler = handler<MovementInputEvent> {
        // We do not want this module to conflict with blink.
        if (player.vehicle != null || ModuleBlink.running) {
            return@handler
        }

        refreshTickBuffer()
    }

    /**
     * Refreshes the simulated future positions of the local player and
     * updates the tick balance with proper hysteresis.
     *
     * Called from both MovementInputEvent and the main tickHandler (when
     * the buffer is empty) so a stationary player still has a valid
     * forecast when an enemy approaches.
     */
    private fun refreshTickBuffer() {
        tickBuffer.clear()

        // ── Balance update with proper hysteresis ──
        // The "depleted" threshold (balance <= 0) and the "resumed"
        // threshold (balance >= balanceResumeThreshold) are kept separate
        // so that a large balanceRecoveryIncrement cannot cause the module
        // to flip-flop between "limited" and "active" every tick.
        if (tickBalance <= 0f) {
            reachedTheLimit = true
        } else if (tickBalance >= balanceResumeThreshold.toFloat()) {
            reachedTheLimit = false
        }

        if (tickBalance < balanceMaxValue.toFloat()) {
            tickBalance = (tickBalance + balanceRecoveryIncrement)
                .coerceAtMost(balanceMaxValue.toFloat())
        }

        if (reachedTheLimit) {
            return
        }

        val simulatedPlayer = PlayerSimulationCache.getSimulationForLocalPlayer()
        val ticksToSimulate = min(tickBalance.toInt().coerceAtLeast(1), maxTicksAtATime)
        val tickRange = 0 until ticksToSimulate
        val snapshots = simulatedPlayer.getSnapshotsBetween(tickRange)

        snapshots.mapTo(tickBuffer) { snapshot ->
            TickData(
                snapshot.pos,
                snapshot.fallDistance,
                snapshot.velocity,
                snapshot.onGround
            )
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (lineColor.a <= 0) {
            return@handler
        }

        renderEnvironmentForWorld(event.matrixStack) {
            withColor(lineColor) {
                drawLineStrip(positions = tickBuffer.mapArray { tick ->
                    relativeToCamera(tick.position).toVec3()
                })
            }
        }
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> {
        // Stops when you got flagged. Immediately abort any in-flight
        // TickBase by clearing state; the playerTickHandler will pick up
        // [flaggedRecently] on the next tick and let the server-resynchronized
        // position apply cleanly.
        if (it.packet is PlayerPositionLookS2CPacket && pauseOnFlag) {
            tickBalance = 0f
            reachedTheLimit = true
            flaggedRecently = true
            ticksToSkip = 0
            tickBuffer.clear()
        }
    }

    @JvmRecord
    private data class TickData(
        val position: Vec3d,
        val fallDistance: Float,
        val velocity: Vec3d,
        val onGround: Boolean
    )

    private enum class TickBaseMode(override val choiceName: String) : NamedChoice {
        PAST("Past"),
        FUTURE("Future")
    }

    @Suppress("unused")
    private enum class TickBaseCall(
        override val choiceName: String,
        val tick: () -> Unit
    ) : NamedChoice {

        /**
         * Runs a full game tick.
         *
         * TODO: Cancel full game ticks after this, not just the player ticks.
         *   The current implementation calls mc.tick() N times within a single
         *   outer game tick, which advances the world (entities, time, etc.)
         *   by N ticks while the outer tick is still mid-execution. Cancelling
         *   the next N automatic game ticks would keep world time and
         *   real time in sync.
         */
        GAME("Game", { mc.tick() }),

        /**
         * This will NOT update the game tick, but only the player tick - that means
         * e.g. Rotation Manager will not update either.
         *
         * This was the previous default behavior of the TickBase, so it is kept
         * for compatibility reasons.
         */
        PLAYER("Player", { player.tick() })
    }

}
