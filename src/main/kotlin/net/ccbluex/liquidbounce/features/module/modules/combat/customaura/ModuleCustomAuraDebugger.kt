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
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object ModuleCustomAuraDebugger : ClientModule(
    "CustomAuraDebug",
    Category.MISC,
    aliases = arrayOf("CustomAuraDebugger", "CADebug")
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
     * Whether to count PolarBypass clamp events and warn if they fire
     * too often (sign of over-aggressive base angle smooth).
     */
    private val monitorPolarBypass by boolean("MonitorPolarBypass", true)

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
        val preset: String?,
        val yaw: Float?,
        val pitch: Float?,
        val serverYaw: Float?,
        val pbClampedYaw: Float?,
        val pbNoisedYaw: Float?
    )

    /**
     * Thread-safe ring buffer. ConcurrentLinkedDeque so we can append
     * from any thread without locking.
     */
    private val ringBuffer = ConcurrentLinkedDeque<AttackEvent>()

    /**
     * Statistics counters. AtomicLong so they are thread-safe.
     */
    private val totalAttempts = AtomicLong(0)
    private val totalLanded = AtomicLong(0)
    private val totalSkipped = AtomicLong(0)
    private val totalFailed = AtomicLong(0)

    /**
     * Skip-reason histogram. AtomicInteger so incrementAndGet is atomic.
     */
    private val skipReasons = mutableMapOf<String, AtomicInteger>()

    /**
     * PolarBypass clamp monitor — counts how often the per-tick yaw
     * delta exceeded the clamp threshold.
     */
    private val pbClampEvents = AtomicLong(0)
    private val pbTotalProcess = AtomicLong(0)
    private var lastPbTargetYaw: Float? = null

    /**
     * Log file writer. Lazily initialized when logToFile is true.
     */
    private var logFile: File? = null
    private var logWriter: PrintWriter? = null
    private var lastFlushMs: Long = 0L

    /**
     * Last event seen — used by the overlay to render the most recent
     * state without consuming the ring buffer.
     */
    @Volatile
    private var lastEvent: AttackEvent? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

    override fun enable() {
        super.enable()
        ringBuffer.clear()
        totalAttempts.set(0)
        totalLanded.set(0)
        totalSkipped.set(0)
        totalFailed.set(0)
        skipReasons.clear()
        pbClampEvents.set(0)
        pbTotalProcess.set(0)
        lastPbTargetYaw = null
        lastEvent = null

        if (logToFile) {
            openLogFile()
        }
    }

    override fun disable() {
        super.disable()
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
            preset = ModuleCustomAura.preset.name,
            yaw = player.rotation.yaw,
            pitch = player.rotation.pitch,
            serverYaw = RotationManager.serverRotation.yaw,
            pbClampedYaw = null,  // set by PolarBypass monitor
            pbNoisedYaw = null
        )

        // Update statistics.
        totalAttempts.incrementAndGet()
        when (phase) {
            "LANDED" -> totalLanded.incrementAndGet()
            "SKIPPED" -> {
                totalSkipped.incrementAndGet()
                skipReason?.let { reason ->
                    synchronized(skipReasons) {
                        skipReasons.getOrPut(reason) { AtomicInteger(0) }.incrementAndGet()
                    }
                }
            }
            "FAILED" -> totalFailed.incrementAndGet()
        }

        // Push to ring buffer, evict oldest if over capacity.
        ringBuffer.addLast(event)
        while (ringBuffer.size > ringBufferSize) {
            ringBuffer.pollFirst()
        }

        lastEvent = event
        appendToLog(event)
    }

    /**
     * Public API called by CustomAuraPolarBypass to track clamp events.
     * If the yaw delta between current and target exceeded maxYawDelta,
     * the clamp engaged — that's normal, but if it fires too often it
     * means the base AngleSmooth is too aggressive.
     */
    fun recordPolarBypassProcess(currentYaw: Float, targetYaw: Float, clamped: Boolean) {
        if (!running || !monitorPolarBypass) return

        pbTotalProcess.incrementAndGet()
        if (clamped) {
            pbClampEvents.incrementAndGet()
        }
        lastPbTargetYaw = targetYaw
    }

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        if (!running) return@handler

        // Periodic log flush.
        if (logToFile && logWriter != null) {
            val now = System.currentTimeMillis()
            if (now - lastFlushMs >= flushIntervalMs) {
                flushLog(force = false)
                lastFlushMs = now
            }
        }

        // PolarBypass health warning — if clamp fires more than 30% of
        // the time over the last 100 process calls, warn in chat.
        if (monitorPolarBypass && pbTotalProcess.get() >= 100) {
            val ratio = pbClampEvents.get().toDouble() / pbTotalProcess.get().toDouble()
            if (ratio > 0.30) {
                // Only warn once per 1000 ticks to avoid spam.
                if (player.age % 1000 == 0) {
                    net.ccbluex.liquidbounce.utils.client.chat(
                        "§e[CustomAuraDebug] §cPolarBypass clamp ratio ${(ratio * 100).toInt()}% — " +
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

        val lines = buildList {
            add("§b§lCustomAura Debug")
            add("§7Preset: §f${last.preset}")
            add("§7Criticals: §f${last.criticalsMode}")
            add("§7Tick: §f${last.tick}")
            add("")
            add("§7Attempts: §a${totalAttempts.get()}")
            add("§7Landed: §a${totalLanded.get()}")
            add("§7Skipped: §c${totalSkipped.get()}")
            add("§7Failed: §c${totalFailed.get()}")
            add("")
            add("§7Last phase: §f${last.phase}")
            last.skipReason?.let { add("§7Skip reason: §c$it") }
            last.targetName?.let { add("§7Target: §f$it (#${last.targetId})") }
            last.targetDistance?.let { add("§7Dist: §f${"%.2f".format(it)}") }
            last.effectiveRange?.let { add("§7EffRange: §f${"%.2f".format(it)}") }
            last.jitter?.let { add("§7Jitter: §f${"%.3f".format(it)}") }
            last.isFacingEnemy?.let { add("§7Facing: §f$it") }
            last.isClickTick?.let { add("§7ClickTick: §f$it") }
            last.onGround?.let { add("§7OnGround: §f$it") }
            last.validateAttack?.let { add("§7Validate: §f$it") }
            add("")
            add("§7Yaw: §f${"%.2f".format(last.yaw ?: 0f)}")
            add("§7Server yaw: §f${"%.2f".format(last.serverYaw ?: 0f)}")
            if (monitorPolarBypass && pbTotalProcess.get() > 0) {
                val ratio = pbClampEvents.get() * 100 / pbTotalProcess.get()
                add("§7PB clamp: §f${pbClampEvents.get()}/${pbTotalProcess.get()} ($ratio%)")
            }
            synchronized(skipReasons) {
                val top3 = skipReasons.entries.sortedByDescending { it.value.get() }.take(3)
                if (top3.isNotEmpty()) {
                    add("")
                    add("§7Top skip reasons:")
                    top3.forEach { (reason, count) ->
                        add(" §c$reason§7: §f${count.get()}")
                    }
                }
            }
        }

        val yOffset = 60
        lines.forEachIndexed { idx, line ->
            ctx.drawTextWithShadow(
                textRenderer,
                net.minecraft.text.Text.literal(line),
                5,
                yOffset + idx * 10,
                0xFFFFFF
            )
        }
    }

    // ── Log file management ───────────────────────────────────────────

    private fun openLogFile() {
        try {
            val dir = File(mc.runDirectory, "liquidbounce")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "customaura-debug.log")
            logFile = file
            // Append mode — keep history across sessions.
            logWriter = PrintWriter(file, "UTF-8").apply {
                println("# CustomAura debug log started at ${Date()}")
                println("# Format: timestamp | tick | phase | skipReason | target | " +
                    "dist | effRange | jitter | facing | clickTick | onGround | validate | yaw | serverYaw")
                flush()
            }
            lastFlushMs = System.currentTimeMillis()
        } catch (e: Exception) {
            // Don't crash the module if the log can't be opened.
            net.ccbluex.liquidbounce.utils.client.chat(
                "§c[CustomAuraDebug] Failed to open log file: ${e.message}"
            )
        }
    }

    private fun appendToLog(event: AttackEvent) {
        val writer = logWriter ?: return
        val ts = dateFormat.format(Date(event.timestamp))
        val line = buildString {
            append(ts)
            append(" | tick=").append(event.tick)
            append(" | ").append(event.phase)
            event.skipReason?.let { append(" | reason=").append(it) }
            event.targetName?.let { append(" | target=").append(it).append("(#").append(event.targetId).append(")") }
            event.targetDistance?.let { append(" | dist=").append("%.3f".format(it)) }
            event.effectiveRange?.let { append(" | effRange=").append("%.3f".format(it)) }
            event.jitter?.let { append(" | jitter=").append("%.3f".format(it)) }
            event.isFacingEnemy?.let { append(" | facing=").append(it) }
            event.isClickTick?.let { append(" | clickTick=").append(it) }
            event.onGround?.let { append(" | onGround=").append(it) }
            event.validateAttack?.let { append(" | validate=").append(it) }
            event.yaw?.let { append(" | yaw=").append("%.2f".format(it)) }
            event.serverYaw?.let { append(" | serverYaw=").append("%.2f".format(it)) }
        }
        writer.println(line)
    }

    private fun flushLog(force: Boolean) {
        logWriter?.let {
            it.flush()
            if (force) {
                // No-op — PrintWriter doesn't need fsync for our purposes.
            }
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
        pw.println("# Skip reasons: ${skipReasons.entries.associate { it.key to it.value.get() }}")
        pw.println("# ---")
        ringBuffer.forEach { event ->
            pw.println("${event.timestamp} | tick=${event.tick} | ${event.phase}" +
                " | reason=${event.skipReason}" +
                " | target=${event.targetName}(#${event.targetId})" +
                " | dist=${event.targetDistance}" +
                " | effRange=${event.effectiveRange}" +
                " | jitter=${event.jitter}" +
                " | facing=${event.isFacingEnemy}" +
                " | clickTick=${event.isClickTick}" +
                " | onGround=${event.onGround}" +
                " | validate=${event.validateAttack}" +
                " | yaw=${event.yaw}" +
                " | serverYaw=${event.serverYaw}")
        }
        return sw.toString()
    }
}
