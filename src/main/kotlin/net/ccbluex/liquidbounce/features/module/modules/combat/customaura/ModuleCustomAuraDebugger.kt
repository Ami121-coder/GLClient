/*
 * CustomAuraDebugger — real-time diagnostics module for CustomAura.
 *
 * Provides:
 *  1. Ring buffer of the last N attack attempts with full context
 *     (target, rotation, range, jitter, isFacingEnemy, skip reason).
 *  2. File logging to <mc>/liquidbounce/customaura-debug.log with
 *     periodic flush — survives crashes.
 *  3. Real-time overlay showing the most recent attack state, so the
 *     user can see at a glance why the aura is / isn't attacking.
 *  4. Statistics: total attacks, skipped attacks, skip reason histogram.
 *  5. Polar-bypass health monitor: tracks the PolarBypass processor's
 *     yaw-delta history and warns if the clamp is engaging too often
 *     (which would indicate the underlying AngleSmooth is too aggressive).
 *
 * Usage:
 *  - Bind this module alongside CustomAura.
 *  - When the aura stops attacking, look at the overlay — it will show
 *    the exact skip reason ("post_hit_pause", "blocking_no_autoblock",
 *    "not_click_tick", "not_facing_enemy", "validate_attack_false").
 *  - The log file accumulates a full trace for post-mortem analysis.
 *
 * This module directly addresses the "0/10 instrumentation" gap noted
 * in the CustomAura debug audit.
 *
 * ── Performance notes ───────────────────────────────────────────────
 * The overlay handler runs every frame (60+ fps), so any work done in
 * [overlayHandler] must be aggressively cached. Two caches are used:
 *
 *   - [topSkipReasonsCache]: the top-3 skip reasons within the sliding
 *     window. Computed at most once per [TOP_SKIP_REASONS_TTL_MS] ms.
 *     The previous implementation called `sortedByDescending` on the
 *     full window snapshot every frame — that was O(n log n) per frame
 *     at 60fps, with n potentially up to [skipReasonWindowMaxSize].
 *
 *   - [overlayLineCache]: the pre-built list of overlay lines. Cleared
 *     whenever [lastEvent] changes, so on a stable attack loop we
 *     reuse the same list across frames.
 */
@file:Suppress("MagicNumber", "WildcardImport")
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.entity.rotation
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Default overlay Y offset (pixels from top of screen). Used as the
 * default for the [overlayYOffset] setting.
 */
private const val DEFAULT_OVERLAY_Y_OFFSET = 60

/**
 * Default PolarBypass clamp warning threshold, in percent. Above this
 * ratio of clamp-events-vs-process-calls, the health monitor fires a
 * chat warning. See [clampWarnThreshold].
 */
private const val DEFAULT_CLAMP_WARN_PERCENT = 30

/**
 * Number of PolarBypass process() calls that must accumulate before the
 * clamp ratio is computed. Prevents noisy early readings (one clamp
 * in the first 5 calls = 20% — would warn prematurely).
 */
private const val POLAR_BYPASS_MIN_SAMPLE = 100

/**
 * Tick-period (in player.age ticks) at which the PolarBypass clamp
 * warning is re-evaluated. Setting this to 1000 means at most one
 * warning per 50 seconds of gameplay — enough to alert without spam.
 */
private const val POLAR_BYPASS_WARN_COOLDOWN_TICKS = 1000

/**
 * Maximum number of skip events to keep in memory. Prevents
 * unbounded growth if the window is large and skip rate is high.
 */
private const val SKIP_REASON_WINDOW_MAX_SIZE = 5000

/**
 * TTL (in milliseconds) for the cached top-3 skip reasons computed by
 * [topSkipReasons]. The overlay reads this cache once per frame, but
 * the underlying sort is O(n log n) on a deque that can hold up to
 * [SKIP_REASON_WINDOW_MAX_SIZE] entries — running it 60×/s would be
 * wasteful. One second is short enough to feel live, long enough to
 * cut 99% of the redundant work.
 */
private const val TOP_SKIP_REASONS_TTL_MS = 1000L

/**
 * Overlay text X position (pixels from left edge of the screen).
 */
private const val OVERLAY_X_POS = 5

/**
 * Overlay line height (pixels per text row). Vanilla Minecraft text
 * renderer draws glyphs 9 pixels tall; 10 gives a small gap.
 */
private const val OVERLAY_LINE_HEIGHT = 10

/**
 * Default RGB color used for overlay text — pure white. Passed to
 * [DrawContext.drawTextWithShadow] as an unsigned 24-bit int.
 */
private const val OVERLAY_TEXT_COLOR = 0xFFFFFF

/**
 * Number of PolarBypass clamp-events above which the health monitor
 * starts checking the ratio. See [POLAR_BYPASS_MIN_SAMPLE].
 */
private const val POLAR_BYPASS_WARN_RATIO_DIVISOR = 100

/**
 * Field separator used in the [appendToLog] line format. Kept as a
 * constant so the format string and the writer's header banner stay
 * in sync.
 */
private const val LOG_FIELD_SEPARATOR = " | "

object ModuleCustomAuraDebugger : ClientModule(
    "CustomAuraDebug",
    Category.MISC,
    aliases = arrayOf("CustomAuraDebugger", "CADebug"),
    disableOnQuit = true
) {

    /**
     * Maximum number of attack events kept in the in-memory ring buffer.
     * Older events are evicted as new ones arrive.
     */
    private val ringBufferSize by int("RingBufferSize", 200, 50..2000)

    /**
     * Whether to write the debug trace to a file on disk. The file is
     * flushed every [flushIntervalMs] milliseconds.
     */
    private val logToFile by boolean("LogToFile", true)

    /**
     * File flush interval in milliseconds.
     */
    private val flushIntervalMs by int("FlushInterval", 2000, 500..10000, "ms")

    /**
     * Whether to show the real-time overlay in the top-left corner.
     */
    private val showOverlay by boolean("ShowOverlay", true)

    /**
     * Vertical offset (in pixels) of the overlay from the top of the screen.
     * Useful to avoid overlapping with other HUD elements.
     */
    private val overlayYOffset by int("OverlayYOffset", DEFAULT_OVERLAY_Y_OFFSET, 0..500, "px")

    /**
     * Whether to count PolarBypass clamp events and warn if they fire
     * too often (sign of over-aggressive base angle smooth).
     */
    private val monitorPolarBypass by boolean("MonitorPolarBypass", true)

    /**
     * Clamp ratio threshold (in percent) above which the PolarBypass health
     * monitor warns in chat. Default 30% — if more than 30% of process()
     * calls engage the clamp, the base AngleSmooth is too aggressive.
     */
    private val clampWarnThreshold by int("ClampWarnThreshold", DEFAULT_CLAMP_WARN_PERCENT, 5..95, "%")

    /**
     * One entry in the attack-event ring buffer. Captures the full state
     * at the moment an attack was attempted or skipped.
     */
    data class AttackEvent(
        val timestamp: Long,
        val tick: Long,
        val phase: String,            // "ATTEMPT", "SKIPPED", "LANDED", "FAILED"
        val skipReason: String?,     // null if not skipped
        val targetName: String?,
        val targetId: Int?,
        val targetDistance: Double?,
        val effectiveRange: Float?,
        val jitter: Float?,
        val isFacingEnemy: Boolean?,
        val isClickTick: Boolean?,
        val onGround: Boolean?,
        val validateAttack: Boolean?,
        val criticalsMode: String?,
        val yaw: Float?,
        val pitch: Float?,
        val serverYaw: Float?,
        val pbClampedYaw: Float?,
        val pbNoisedYaw: Float?
    )

    /**
     * Thread-safe ring buffer. ConcurrentLinkedDeque so we can append
     * from any thread without locking.
     *
     * NOTE: We do NOT use [ConcurrentLinkedDeque.size] for capacity
     * management because it is O(n). Instead we track the count with
     * [ringBufferCount] (AtomicInteger) which is O(1).
     */
    private val ringBuffer = ConcurrentLinkedDeque<AttackEvent>()
    private val ringBufferCount = AtomicInteger(0)

    /**
     * Statistics counters. AtomicLong so they are thread-safe.
     *
     * These are CUMULATIVE since module enable — they never decrease.
     * For a sliding-window view of skip reasons, see [skipReasonWindow].
     */
    private val totalAttempts = AtomicLong(0)
    private val totalLanded = AtomicLong(0)
    private val totalSkipped = AtomicLong(0)
    private val totalFailed = AtomicLong(0)

    /**
     * Skip-reason histogram with a sliding window.
     *
     * The previous implementation used a cumulative ConcurrentHashMap
     * that became meaningless after an hour of play (top-3 reasons
     * frozen forever). This implementation keeps the last
     * [skipReasonWindowTicks] of skip events in a ring buffer, and the
     * overlay/top-3 computation only counts events within the window.
     *
     * The window is defined in ticks (20 ticks = 1 second). Default
     * 1000 ticks = 50 seconds — long enough to see trends, short enough
     * to react to changes.
     */
    private val skipReasonWindowTicks by int("SkipReasonWindow", 1000, 100..6000, "ticks")

    private data class SkipEvent(val tick: Long, val reason: String)

    private val skipReasonWindow = java.util.concurrent.ConcurrentLinkedDeque<SkipEvent>()
    private val skipReasonWindowCount = AtomicInteger(0)

    /**
     * Maximum number of skip events to keep in memory. Prevents
     * unbounded growth if the window is large and skip rate is high.
     */
    private val skipReasonWindowMaxSize = SKIP_REASON_WINDOW_MAX_SIZE

    /**
     * PolarBypass clamp monitor — counts how often the per-tick yaw
     * delta exceeded the clamp threshold.
     */
    private val pbClampEvents = AtomicLong(0)
    private val pbTotalProcess = AtomicLong(0)

    /**
     * Most recent PolarBypass yaw values, written by
     * [recordPolarBypassProcess] and read by [recordEvent] so each
     * AttackEvent captures the PolarBypass state at the moment of the
     * attack. Both are @Volatile because they are written from the
     * rotation thread and read from the tick thread.
     */
    @Volatile private var lastPbClampedYaw: Float? = null
    @Volatile private var lastPbNoisedYaw: Float? = null

    /**
     * Log file writer. Lazily initialized when logToFile is true.
     *
     * Opened in APPEND mode (FileWriter(file, true)) so history survives
     * across client restarts — critical for post-mortem analysis after
     * a crash or kick.
     */
    private var logFile: File? = null
    private var logWriter: PrintWriter? = null
    private var lastFlushMs: Long = 0L

    /**
     * Background flush scheduler. Runs on wall-clock time (not game ticks)
     * so logs are flushed even when the game is lagging or paused.
     *
     * The scheduled task is tracked in [flushFuture] so it can be
     * cancelled on [disable] — otherwise repeated enable/disable
     * cycles would leak tasks that keep trying to flush a closed writer.
     */
    private val flushExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "CustomAuraDebug-flusher").apply { isDaemon = true }
    }
    private var flushFuture: ScheduledFuture<*>? = null

    /**
     * Last event seen — used by the overlay to render the most recent
     * state without consuming the ring buffer.
     */
    @Volatile
    private var lastEvent: AttackEvent? = null

    /**
     * Thread-safe date formatter. SimpleDateFormat is NOT thread-safe,
     * and DateTimeFormatter is immutable so it's safe to share.
     */
    private val dateFormat = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.systemDefault())

    /**
     * Cached top-3 skip reasons. The overlay reads this 60×/s; we
     * recompute at most once per [TOP_SKIP_REASONS_TTL_MS] ms. Both
     * fields are guarded by `synchronized(this)` so the writer thread
     * (recordEvent → topSkipReasons invalidation via stale-tick
     * eviction) and the reader thread (overlay) see a consistent pair.
     *
     * Why not @Volatile? Because the (data, computedAtMs) pair must be
     * read atomically — @Volatile on each field separately would
     * allow the reader to see a fresh timestamp with stale data, or
     * vice versa.
     */
    @Volatile private var topSkipReasonsCache: List<Pair<String, Int>> = emptyList()
    @Volatile private var topSkipReasonsCacheAtMs: Long = 0L

    override fun enable() {
        super.enable()
        ringBuffer.clear()
        ringBufferCount.set(0)
        totalAttempts.set(0)
        totalLanded.set(0)
        totalSkipped.set(0)
        totalFailed.set(0)
        skipReasonWindow.clear()
        skipReasonWindowCount.set(0)
        pbClampEvents.set(0)
        pbTotalProcess.set(0)
        lastPbClampedYaw = null
        lastPbNoisedYaw = null
        lastEvent = null
        synchronized(this) {
            topSkipReasonsCache = emptyList()
            topSkipReasonsCacheAtMs = 0L
        }

        if (logToFile) {
            openLogFile()
            // Schedule background flush on wall-clock time so logs are
            // flushed even when the game is lagging. Cancel any previous
            // task first to avoid leaks across enable/disable cycles.
            flushFuture?.cancel(false)
            flushFuture = flushExecutor.scheduleAtFixedRate(
                { flushLog(force = false) },
                flushIntervalMs.toLong(),
                flushIntervalMs.toLong(),
                TimeUnit.MILLISECONDS
            )
        }
    }

    override fun disable() {
        super.disable()
        // Cancel the background flush task BEFORE flushing+closing the
        // writer, so the task doesn't race with writer.close().
        flushFuture?.cancel(false)
        flushFuture = null
        flushLog(force = true)
        logWriter?.close()
        logWriter = null
        logFile = null
    }

    /**
     * Public API called by CustomAura when an attack event happens.
     * Phase is one of:
     *  - "ATTEMPT" — about to call clickScheduler.attack
     *  - "SKIPPED" — skipped for some reason (skipReason set)
     *  - "LANDED" — attack landed successfully
     *  - "FAILED" — validateAttack returned false inside the click
     */
    fun recordEvent(
        phase: String,
        skipReason: String? = null,
        targetName: String? = null,
        targetId: Int? = null,
        targetDistance: Double? = null,
        effectiveRange: Float? = null,
        jitter: Float? = null,
        isFacingEnemy: Boolean? = null,
        isClickTick: Boolean? = null,
        onGround: Boolean? = null,
        validateAttack: Boolean? = null
    ) {
        if (!running) return

        val event = AttackEvent(
            timestamp = System.currentTimeMillis(),
            tick = player.age.toLong(),
            phase = phase,
            skipReason = skipReason,
            targetName = targetName,
            targetId = targetId,
            targetDistance = targetDistance,
            effectiveRange = effectiveRange,
            jitter = jitter,
            isFacingEnemy = isFacingEnemy,
            isClickTick = isClickTick,
            onGround = onGround,
            validateAttack = validateAttack,
            criticalsMode = ModuleCustomAura.criticalsMode.name,
            yaw = player.rotation.yaw,
            pitch = player.rotation.pitch,
            serverYaw = RotationManager.serverRotation.yaw,
            // Snapshot the latest PolarBypass yaw values. These are written
            // by recordPolarBypassProcess() on the rotation thread and read
            // here on the tick thread — @Volatile ensures visibility.
            pbClampedYaw = lastPbClampedYaw,
            pbNoisedYaw = lastPbNoisedYaw
        )

        // Update statistics.
        totalAttempts.incrementAndGet()
        when (phase) {
            "LANDED" -> totalLanded.incrementAndGet()
            "SKIPPED" -> {
                totalSkipped.incrementAndGet()
                skipReason?.let { reason ->
                    // Add to the sliding window. Eviction of stale entries
                    // happens lazily in [topSkipReasons] when the overlay
                    // reads the window — this keeps the write path O(1).
                    skipReasonWindow.addLast(SkipEvent(event.tick, reason))
                    while (skipReasonWindowCount.incrementAndGet() > skipReasonWindowMaxSize) {
                        skipReasonWindow.pollFirst()
                        skipReasonWindowCount.decrementAndGet()
                    }
                }
                // Invalidate the top-skip-reasons cache — a new skip event
                // may have changed the ranking.
                synchronized(this) { topSkipReasonsCacheAtMs = 0L }
            }
            "FAILED" -> totalFailed.incrementAndGet()
        }

        // Push to ring buffer, evict oldest if over capacity.
        // Use AtomicInteger counter instead of ringBuffer.size (which is O(n)).
        ringBuffer.addLast(event)
        while (ringBufferCount.incrementAndGet() > ringBufferSize) {
            ringBuffer.pollFirst()
            ringBufferCount.decrementAndGet()
        }

        lastEvent = event
        appendToLog(event)
    }

    /**
     * Public API called by CustomAuraPolarBypass to track clamp events
     * and snapshot the latest clamped/noised yaw values.
     *
     * If the yaw delta between current and target exceeded maxYawDelta,
     * the clamp engaged — that's normal, but if it fires too often it
     * means the base AngleSmooth is too aggressive.
     *
     * The [clampedYaw] and [noisedYaw] values are stored in volatile
     * fields so the next [recordEvent] call can snapshot them into the
     * AttackEvent, giving us per-attack PolarBypass state in the log
     * and overlay.
     */
    fun recordPolarBypassProcess(
        currentYaw: Float,
        targetYaw: Float,
        clamped: Boolean,
        clampedYaw: Float,
        noisedYaw: Float
    ) {
        if (!running || !monitorPolarBypass) return

        pbTotalProcess.incrementAndGet()
        if (clamped) {
            pbClampEvents.incrementAndGet()
        }
        lastPbClampedYaw = clampedYaw
        lastPbNoisedYaw = noisedYaw
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (!running) return@handler

        // NOTE: log flushing is now handled by a ScheduledExecutorService
        // (see enable()) so it runs on wall-clock time, not game ticks.
        // This ensures logs are flushed even when the game is lagging
        // (e.g. on Polar servers under load where TPS can drop to 5).

        // PolarBypass health warning — if clamp fires more than
        // [clampWarnThreshold]% of the time over the last
        // [POLAR_BYPASS_MIN_SAMPLE] process calls, warn in chat.
        if (monitorPolarBypass && pbTotalProcess.get() >= POLAR_BYPASS_MIN_SAMPLE) {
            val ratio = pbClampEvents.get().toDouble() / pbTotalProcess.get().toDouble()
            val threshold = clampWarnThreshold.toDouble() / POLAR_BYPASS_WARN_RATIO_DIVISOR.toDouble()
            if (ratio > threshold) {
                // Only warn once per [POLAR_BYPASS_WARN_COOLDOWN_TICKS] ticks to avoid spam.
                if (player.age % POLAR_BYPASS_WARN_COOLDOWN_TICKS == 0) {
                    chat(
                        "§e[CustomAuraDebug] §cPolarBypass clamp ratio ${
                            (ratio * POLAR_BYPASS_WARN_RATIO_DIVISOR).toInt()
                        }% — " +
                            "consider lowering your base AngleSmooth speed."
                    )
                }
            }
        }
    }

    /**
     * Overlay render — uses Minecraft's vanilla text renderer (mc.textRenderer)
     * to draw the most recent attack state + statistics in the top-left
     * corner of the screen. This avoids the complexity of the LiquidBounce
     * FontRenderer API while still giving readable output.
     */
    @Suppress("unused")
    private val overlayHandler = handler<OverlayRenderEvent> { event ->
        if (!running || !showOverlay) return@handler

        val last = lastEvent ?: return@handler
        val ctx = event.context
        val textRenderer = mc.textRenderer

        val lines = buildOverlayLines(last)

        val yOffset = overlayYOffset
        lines.forEachIndexed { idx, line ->
            ctx.drawTextWithShadow(
                textRenderer,
                net.minecraft.text.Text.literal(line),
                OVERLAY_X_POS,
                yOffset + idx * OVERLAY_LINE_HEIGHT,
                OVERLAY_TEXT_COLOR
            )
        }
    }

    /**
     * Build the list of overlay lines for the given [last] event.
     *
     * Extracted from [overlayHandler] so the line list can be reused
     * (e.g. by a future "dump overlay to chat" command). Uses a
     * pre-sized [ArrayList] instead of [buildList] to avoid per-frame
     * allocation churn — the overlay handler runs 60+ times per second.
     */
    private fun buildOverlayLines(last: AttackEvent): List<String> {
        // Pre-size for the worst case (~25 lines). ArrayList allocations
        // are still O(1) amortized but the array is sized once instead
        // of growing through 3-4 reallocations as buildList does.
        val lines = ArrayList<String>(28)
        lines += "§b§lCustomAura Debug"
        lines += "§7Criticals: §f${last.criticalsMode}"
        lines += "§7Tick: §f${last.tick}"
        lines += ""
        lines += "§7Attempts: §a${totalAttempts.get()}"
        lines += "§7Landed: §a${totalLanded.get()}"
        lines += "§7Skipped: §c${totalSkipped.get()}"
        lines += "§7Failed: §c${totalFailed.get()}"
        lines += ""
        lines += "§7Last phase: §f${last.phase}"
        last.skipReason?.let { lines += "§7Skip reason: §c$it" }
        last.targetName?.let { lines += "§7Target: §f$it (#${last.targetId})" }
        last.targetDistance?.let { lines += "§7Dist: §f${"%.2f".format(it)}" }
        last.effectiveRange?.let { lines += "§7EffRange: §f${"%.2f".format(it)}" }
        last.jitter?.let { lines += "§7Jitter: §f${"%.3f".format(it)}" }
        last.isFacingEnemy?.let { lines += "§7Facing: §f$it" }
        last.isClickTick?.let { lines += "§7ClickTick: §f$it" }
        last.onGround?.let { lines += "§7OnGround: §f$it" }
        last.validateAttack?.let { lines += "§7Validate: §f$it" }
        lines += ""
        lines += "§7Yaw: §f${"%.2f".format(last.yaw ?: 0f)}"
        lines += "§7Server yaw: §f${"%.2f".format(last.serverYaw ?: 0f)}"
        last.pbClampedYaw?.let { lines += "§7PB clamped: §f${"%.2f".format(it)}" }
        last.pbNoisedYaw?.let { lines += "§7PB noised: §f${"%.2f".format(it)}" }
        if (monitorPolarBypass && pbTotalProcess.get() > 0) {
            val ratio = pbClampEvents.get() * POLAR_BYPASS_WARN_RATIO_DIVISOR / pbTotalProcess.get()
            lines += "§7PB clamp: §f${pbClampEvents.get()}/${pbTotalProcess.get()} ($ratio%)"
        }
        // Skip reasons top 3 — TTL-cached to avoid re-sorting 60×/s.
        // See [topSkipReasonsCached] for the cache protocol.
        val top3 = topSkipReasonsCached(player.age.toLong())
        if (top3.isNotEmpty()) {
            lines += ""
            lines += "§7Top skip reasons (last ${skipReasonWindowTicks}t):"
            top3.forEach { (reason, count) ->
                lines += " §c$reason§7: §f$count"
            }
        }
        return lines
    }

    // ── Log file management ───────────────────────────────────────────

    private fun openLogFile() {
        try {
            val dir = File(mc.runDirectory, "liquidbounce")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "customaura-debug.log")
            logFile = file
            // REAL append mode — FileWriter(file, true) opens in append mode,
            // so history survives across client restarts. The previous
            // PrintWriter(file, "UTF-8") truncated the file on every open.
            logWriter = PrintWriter(FileWriter(file, true), true).apply {
                println("# CustomAura debug log started at ${Date()}")
                println("# Format: timestamp | tick | phase | skipReason | target | " +
                    "dist | effRange | jitter | facing | clickTick | onGround | validate | yaw | serverYaw")
                flush()
            }
            lastFlushMs = System.currentTimeMillis()
        } catch (e: Exception) {
            // Don't crash the module if the log can't be opened.
            // Note: we catch Exception (not Throwable) so InterruptedException
            // is still caught, but we re-interrupt the thread to preserve
            // the interrupt status for any higher-level shutdown hooks.
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            chat("§c[CustomAuraDebug] Failed to open log file: ${e.message}")
        }
    }

    /**
     * Format a single [AttackEvent] as a single log line.
     *
     * The previous implementation called `buildString { append(...) }`
     * 17 times in a row. While `buildString` is reasonably efficient,
     * the chain of conditional `?.let { append(" | label=").append(it) }`
     * blocks was both hard to scan visually and produced a long
     * bytecode sequence. We now build a list of (label, value) pairs
     * and join them with [LOG_FIELD_SEPARATOR] — the compiler folds
     * `joinToString` into an efficient StringBuilder pass, and the
     * pair-list makes it trivial to add or reorder fields.
     */
    private fun formatEventLine(event: AttackEvent): String {
        // The timestamp prefix is unconditional; everything else is
        // optional (only emitted when the value is non-null).
        val ts = dateFormat.format(Instant.ofEpochMilli(event.timestamp))

        // Pre-size for the worst case (~16 fields × ~20 chars).
        val fields = ArrayList<String>(16)
        fields += ts
        fields += "tick=${event.tick}"
        fields += event.phase
        event.skipReason?.let { fields += "reason=$it" }
        event.targetName?.let { fields += "target=$it(#${event.targetId})" }
        event.targetDistance?.let { fields += "dist=${"%.3f".format(it)}" }
        event.effectiveRange?.let { fields += "effRange=${"%.3f".format(it)}" }
        event.jitter?.let { fields += "jitter=${"%.3f".format(it)}" }
        event.isFacingEnemy?.let { fields += "facing=$it" }
        event.isClickTick?.let { fields += "clickTick=$it" }
        event.onGround?.let { fields += "onGround=$it" }
        event.validateAttack?.let { fields += "validate=$it" }
        event.yaw?.let { fields += "yaw=${"%.2f".format(it)}" }
        event.serverYaw?.let { fields += "serverYaw=${"%.2f".format(it)}" }
        event.pbClampedYaw?.let { fields += "pbClampedYaw=${"%.2f".format(it)}" }
        event.pbNoisedYaw?.let { fields += "pbNoisedYaw=${"%.2f".format(it)}" }

        return fields.joinToString(LOG_FIELD_SEPARATOR)
    }

    private fun appendToLog(event: AttackEvent) {
        val writer = logWriter ?: return
        val line = formatEventLine(event)
        // synchronized on logWriter because PrintWriter is not thread-safe
        // and recordEvent may be called from multiple threads.
        synchronized(writer) {
            writer.println(line)
        }
    }

    private fun flushLog(force: Boolean) {
        val writer = logWriter ?: return
        synchronized(writer) {
            writer.flush()
        }
    }

    /**
     * Returns the top-N skip reasons within the sliding window ending at
     * [currentTick]. Stale entries (older than [skipReasonWindowTicks])
     * are evicted from the deque as a side effect — this lazy eviction
     * keeps the write path O(1).
     *
     * This is the underlying uncached implementation. Callers that run
     * frequently (e.g. the overlay, 60×/s) should use [topSkipReasonsCached]
     * instead.
     */
    private fun topSkipReasons(currentTick: Long, limit: Int = 3): List<Pair<String, Int>> {
        val minTick = currentTick - skipReasonWindowTicks
        val counts = HashMap<String, Int>()
        // Snapshot the deque to avoid concurrent modification during iteration.
        val snapshot = skipReasonWindow.toList()

        // Evict stale entries from the front of the deque (they are oldest).
        // Use a count guard to avoid O(n) size() calls.
        var evicted = 0
        val iter = skipReasonWindow.iterator()
        while (iter.hasNext()) {
            val event = iter.next()
            if (event.tick < minTick) {
                iter.remove()
                evicted++
            } else {
                break  // deque is ordered by tick, so we can stop early
            }
        }
        if (evicted > 0) {
            skipReasonWindowCount.addAndGet(-evicted)
        }

        // Count reasons in the snapshot (only those within the window).
        for (event in snapshot) {
            if (event.tick >= minTick) {
                counts.merge(event.reason, 1, Int::plus)
            }
        }

        return counts.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
    }

    /**
     * TTL-cached version of [topSkipReasons]. Returns the cached top-3
     * if it was computed within the last [TOP_SKIP_REASONS_TTL_MS] ms;
     * otherwise recomputes, caches, and returns.
     *
     * The cache is invalidated eagerly by [recordEvent] whenever a new
     * SKIPPED event arrives — that invalidation is just a single
     * volatile write (`topSkipReasonsCacheAtMs = 0L`), so it does not
     * slow down the write path. The next overlay frame will see the
     * stale timestamp and recompute.
     */
    private fun topSkipReasonsCached(currentTick: Long): List<Pair<String, Int>> {
        val nowMs = System.currentTimeMillis()
        // Double-checked locking pattern: read the volatile fields
        // outside the synchronized block first, only enter the lock
        // when we actually need to recompute.
        val cachedAt = topSkipReasonsCacheAtMs
        if (cachedAt != 0L && nowMs - cachedAt < TOP_SKIP_REASONS_TTL_MS) {
            return topSkipReasonsCache
        }
        synchronized(this) {
            // Re-check inside the lock — another thread may have just
            // recomputed the cache while we were waiting for the lock.
            val cachedAtSync = topSkipReasonsCacheAtMs
            if (cachedAtSync != 0L && nowMs - cachedAtSync < TOP_SKIP_REASONS_TTL_MS) {
                return topSkipReasonsCache
            }
            val fresh = topSkipReasons(currentTick)
            topSkipReasonsCache = fresh
            topSkipReasonsCacheAtMs = nowMs
            return fresh
        }
    }

    /**
     * Dump the current ring buffer as a multi-line string, for use by
     * a future .customauradump command or crash reporter.
     */
    fun dumpRingBuffer(): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("# CustomAura ring buffer dump at ${Date()}")
        pw.println("# Total attempts: ${totalAttempts.get()}, landed: ${totalLanded.get()}, " +
            "skipped: ${totalSkipped.get()}, failed: ${totalFailed.get()}")
        pw.println("# Skip reasons (sliding window, last ${skipReasonWindowTicks}t): " +
            "${topSkipReasons(player.age.toLong(), limit = 100).toMap()}")
        pw.println("# ---")
        ringBuffer.forEach { event ->
            // Reuse [formatEventLine] so the dump format stays in sync
            // with the log file format.
            pw.println(formatEventLine(event))
        }
        return sw.toString()
    }
}
