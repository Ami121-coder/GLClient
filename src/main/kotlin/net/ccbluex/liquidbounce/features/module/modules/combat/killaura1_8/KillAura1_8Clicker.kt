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

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp
import kotlin.math.max

/**
 * # KillAura1_8Clicker (v4) — millisecond-precise CPS scheduler.
 *
 * Addresses two v3 issues:
 *
 *  1. **Tick-quantized intervals.** v3 used `System.currentTimeMillis()`
 *     but called `shouldClickThisTick()` once per tick, so intervals
 *     were still effectively multiples of 50 ms. v4 schedules the
 *     *next* click time on a background single-thread executor that
 *     wakes up precisely at the desired millisecond, sets a
 *     `pendingClick` flag, and the tick handler picks up that flag on
 *     the next tick. This means the scheduler resolution is bounded
 *     only by the OS scheduler (~1-5 ms on modern systems), not by
 *     Minecraft's tick loop.
 *
 *  2. **Butterfly pattern as random 50/50.** v3 produced unphysical
 *     runs of double-clicks or runs of pauses. v4 uses an explicit
 *     state machine:
 *
 *     ```
 *     IDLE → PAIR_FIRST → PAIR_SECOND → PAUSE → PAIR_FIRST → …
 *     ```
 *
 * ## Lifecycle
 *
 *  - [reset]: clears state but keeps the scheduler thread alive. Safe
 *    to call repeatedly (e.g. when module is re-enabled).
 *  - [shutdown]: permanently terminates the scheduler thread. After
 *    this call the clicker is unusable; a new instance must be
 *    created. Called only by the module's `disable()` handler — but
 *    since the clicker is a `val` field of the singleton module
 *    (`object ModuleKillAura1_8`), the clicker lives for the entire
 *    client lifetime and `shutdown()` is effectively never called
 *    in normal operation.
 *
 * ## Thread safety
 *
 * The scheduler thread only writes to `pendingClickCount`. The tick
 * handler reads and clears it. The butterfly state machine is advanced
 * on the client thread only (in `onClicked()`), so it does not need
 * synchronization beyond `AtomicReference`'s atomic set/get.
 *
 * Author: Super Z (1.8 PvP rewrite, v4)
 */
class KillAura1_8Clicker(
    private val cpsProvider: () -> IntRange
) {

    private val rng = java.util.Random(System.currentTimeMillis())

    @Volatile
    var pattern: ModuleKillAura1_8.ClickPattern = ModuleKillAura1_8.ClickPattern.BINOMIAL

    /**
     * Background scheduler. Single-threaded; daemon. Lazily initialized
     * so that a [reset] + re-enable cycle can recreate it if needed.
     */
    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    /**
     * Guards scheduler creation/shutdown. Held while creating or
     * terminating the scheduler thread.
     */
    private val schedulerLock = Any()

    /**
     * Number of pending clicks that have been scheduled but not yet
     * consumed by the tick handler. Each scheduled click increments
     * this atomically; the tick handler drains it with `getAndSet(0)`.
     */
    private val pendingClickCount = AtomicLong(0L)

    /**
     * Whether the scheduler currently has a scheduled task. Avoids
     * double-scheduling when `onClicked()` races with the scheduler's
     * own re-arm callback.
     */
    private val armed = AtomicBoolean(false)

    /**
     * Whether the clicker has been permanently shut down. After this is
     * set to true, all further operations are no-ops.
     */
    private val terminated = AtomicBoolean(false)

    // ── CPS drop state ──────────────────────────────────────────────────────
    private val nextDropStartMs = AtomicLong(0L)
    private val dropActiveUntilMs = AtomicLong(0L)
    private val dropCpsFraction = 0.5

    // ── Butterfly state machine ─────────────────────────────────────────────
    private enum class ButterflyState {
        IDLE, PAIR_FIRST, PAIR_SECOND, PAUSE, DRAG_ARTIFACT
    }

    /**
     * Butterfly state. Advanced only on the client thread (in
     * `onClicked()`), so plain `AtomicReference` is enough.
     */
    private val butterflyState = AtomicReference(ButterflyState.IDLE)

    /**
     * Called once per game tick by [ModuleKillAura1_8]. Advances the
     * drop-state machine. Does NOT arm the scheduler — clicks are
     * scheduled exclusively by the previous click's `onClicked()`
     * callback, which is the only place that knows the correct
     * butterfly state to advance to. This eliminates the race condition
     * between `tick()` re-arming and `onClicked()` re-arming.
     *
     * If no click has ever been scheduled (module just enabled), the
     * first click is armed here with `initial=true`.
     */
    fun tick() {
        if (terminated.get()) return
        val now = System.currentTimeMillis()
        updateDropState(now)

        // Arm the very first click if no click is armed and we have a
        // valid CPS. Subsequent clicks are armed by onClicked().
        val cps = cpsProvider()
        if (cps.start > 0 && !armed.get() && pendingClickCount.get() == 0L) {
            // Only arm if there is no pending click either — otherwise
            // we'd schedule before the previous click has been consumed.
            armScheduler(initial = true)
        }
    }

    /**
     * Returns true if a click should fire this tick. Drains the
     * pending-click counter atomically — so if the scheduler fired
     * multiple times between two ticks, only one click is performed.
     */
    fun shouldClickThisTick(): Boolean {
        val pending = pendingClickCount.getAndSet(0L)
        return pending > 0
    }

    /**
     * Notifies the clicker that a click was performed. Advances the
     * butterfly state machine and schedules the next click.
     */
    fun onClicked() {
        if (terminated.get()) return
        advanceButterflyState()
        scheduleNextClick()
    }

    /**
     * Clears all transient state but keeps the scheduler thread alive.
     * Safe to call when re-enabling the module.
     */
    fun reset() {
        if (terminated.get()) return
        pendingClickCount.set(0L)
        nextDropStartMs.set(0L)
        dropActiveUntilMs.set(0L)
        butterflyState.set(ButterflyState.IDLE)
        armed.set(false)
        // Do NOT shut down the scheduler — the clicker instance lives
        // for the entire client lifetime. If the scheduler thread is
        // still alive, we leave it; armScheduler() will pick up on the
        // next tick() call.
    }

    /**
     * Permanently shuts down the scheduler. After this, the clicker is
     * unusable. Called only on JVM shutdown or module unload.
     */
    fun shutdown() {
        if (!terminated.compareAndSet(false, true)) return
        synchronized(schedulerLock) {
            scheduler?.shutdownNow()
            scheduler = null
        }
    }

    // ── Scheduler internals ────────────────────────────────────────────────

    /**
     * Lazily creates the scheduler if needed. Returns null if the
     * clicker has been terminated.
     */
    private fun getOrCreateScheduler(): ScheduledExecutorService? {
        if (terminated.get()) return null
        synchronized(schedulerLock) {
            scheduler?.let { if (!it.isShutdown) return it }
            val newScheduler = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "KillAura1_8-Clicker").apply { isDaemon = true }
            }
            scheduler = newScheduler
            return newScheduler
        }
    }

    /**
     * Arms the scheduler with the next click. Uses CAS on [armed] to
     * avoid double-scheduling.
     */
    private fun armScheduler(initial: Boolean) {
        if (terminated.get()) return
        if (!armed.compareAndSet(false, true)) return

        val intervalMs = if (initial) {
            randomLong(50, 150)
        } else {
            computeNextIntervalMs()
        }

        if (intervalMs <= 0) {
            armed.set(false)
            return
        }

        val exec = getOrCreateScheduler() ?: run {
            armed.set(false)
            return
        }

        try {
            exec.schedule({
                if (terminated.get()) return@schedule
                pendingClickCount.incrementAndGet()
                // Allow re-arm on the next tick().
                armed.set(false)
            }, intervalMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Scheduler was shut down between our check and the schedule
            // call. Mark not-armed so tick() can recreate it.
            armed.set(false)
        }
    }

    /**
     * Schedules the next click after a performed click.
     */
    private fun scheduleNextClick() {
        if (terminated.get()) return
        val intervalMs = computeNextIntervalMs()
        if (intervalMs <= 0) return

        if (!armed.compareAndSet(false, true)) return

        val exec = getOrCreateScheduler() ?: run {
            armed.set(false)
            return
        }

        try {
            exec.schedule({
                if (terminated.get()) return@schedule
                pendingClickCount.incrementAndGet()
                armed.set(false)
            }, intervalMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            armed.set(false)
        }
    }

    private fun updateDropState(now: Long) {
        if (nextDropStartMs.get() == 0L) {
            nextDropStartMs.set(now + randomLong(2000, 4000))
            return
        }
        if (now >= nextDropStartMs.get() && now >= dropActiveUntilMs.get()) {
            dropActiveUntilMs.set(now + randomLong(500, 1500))
            nextDropStartMs.set(now + randomLong(2000, 4000))
        }
    }

    private fun computeNextIntervalMs(): Long {
        val now = System.currentTimeMillis()
        val inDrop = now < dropActiveUntilMs.get()
        val cpsFactor = if (inDrop) dropCpsFraction else 1.0

        val range = cpsProvider()
        val targetCps = if (range.start == range.endInclusive) {
            range.start.toDouble()
        } else {
            val span = range.endInclusive - range.start + 1
            (range.start + rng.nextInt(span)).toDouble()
        }
        val effectiveCps = targetCps * cpsFactor
        if (effectiveCps <= 0.0) return 1000L

        val baseIntervalMs = 1000.0 / effectiveCps

        val intervalMs = when (pattern) {
            ModuleKillAura1_8.ClickPattern.NORMAL -> {
                val z = rng.nextGaussian()
                baseIntervalMs * exp(0.3 * z)
            }
            ModuleKillAura1_8.ClickPattern.BINOMIAL -> {
                val z = rng.nextGaussian()
                baseIntervalMs * exp(0.2 * z)
            }
            ModuleKillAura1_8.ClickPattern.BUTTERFLY -> butterflyInterval(baseIntervalMs)
        }

        // Drag-click artifact: 3% chance of a 40-60 ms double-click.
        val withDouble = if (rng.nextDouble() < 0.03) {
            randomLong(40, 60).toDouble()
        } else {
            intervalMs
        }

        return max(30L, withDouble.toLong().coerceAtMost(2000L))
    }

    /**
     * Butterfly state-machine interval.
     *
     * The state machine guarantees the cycle:
     *   IDLE → PAIR_FIRST → PAIR_SECOND → PAUSE → (PAIR_FIRST | DRAG_ARTIFACT) → …
     */
    private fun butterflyInterval(baseIntervalMs: Double): Double {
        val state = butterflyState.get()
        return when (state) {
            ButterflyState.IDLE,
            ButterflyState.PAUSE -> {
                val z = rng.nextGaussian()
                baseIntervalMs * exp(0.25 * z)
            }
            ButterflyState.PAIR_FIRST -> {
                randomLong(40, 80).toDouble()
            }
            ButterflyState.PAIR_SECOND -> {
                val z = rng.nextGaussian()
                baseIntervalMs * 1.8 * exp(0.25 * z)
            }
            ButterflyState.DRAG_ARTIFACT -> {
                randomLong(30, 50).toDouble()
            }
        }
    }

    /**
     * Advances the butterfly state machine. Called only on the client
     * thread (in `onClicked()`).
     */
    private fun advanceButterflyState() {
        if (pattern != ModuleKillAura1_8.ClickPattern.BUTTERFLY) return

        val current = butterflyState.get()
        val next = when (current) {
            ButterflyState.IDLE,
            ButterflyState.PAUSE -> {
                if (rng.nextDouble() < 0.04) ButterflyState.DRAG_ARTIFACT
                else ButterflyState.PAIR_FIRST
            }
            ButterflyState.PAIR_FIRST -> ButterflyState.PAIR_SECOND
            ButterflyState.PAIR_SECOND -> ButterflyState.PAUSE
            ButterflyState.DRAG_ARTIFACT -> ButterflyState.PAIR_FIRST
        }
        butterflyState.set(next)
    }

    private fun randomLong(min: Long, max: Long): Long {
        if (min >= max) return min
        return min + (rng.nextDouble() * (max - min)).toLong()
    }
}
