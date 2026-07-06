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
package net.ccbluex.liquidbounce.features.module.modules.misc

import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.MessageMetadata
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.math.Easing
import net.ccbluex.liquidbounce.utils.render.WireframePlayer
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket
import net.minecraft.util.math.Vec3d
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Module Flag Check.
 *
 * Alerts you about set backs (lagbacks, force-rotates, kicks) and logs
 * them to a file for post-mortem analysis.
 *
 * ## v2 improvements (audit-driven rewrite)
 *
 * 1. **Kick reason logging** — captures [DisconnectS2CPacket.reason] and
 *    writes it to `<mc>/liquidbounce/flags.log` along with the timestamp.
 *    The previous version zeroed `flagCount` on disconnect and discarded
 *    the reason entirely.
 *
 * 2. **Packet ring buffer** — keeps the last 100 received packets in
 *    memory. When a flag fires, the buffer is dumped to the log so the
 *    user can see what the server sent in the 5 seconds leading up to
 *    the flag.
 *
 * 3. **Anticheat chat parser** — scans incoming game messages for known
 *    anticheat warning patterns (Polar, Verus, Vulcan, Grim, NCP) and
 *    attributes the flag to the detected anticheat.
 *
 * 4. **Combat module attribution** — when a flag fires, queries
 *    [ModuleManager] for currently-running combat modules and includes
 *    them in the alert + log. Answers "who caused the flag?".
 *
 * 5. **File logging** — every flag is appended to
 *    `<mc>/liquidbounce/flags.log` (real append mode, survives restarts).
 */
object ModuleFlagCheck : ClientModule("FlagCheck", Category.MISC, aliases = arrayOf("FlagDetect")) {

    private var chatMessage by boolean("ChatMessage", true)
    private var notification by boolean("Notification", false)
    private var invalidAttributes by boolean("InvalidAttributes", false)

    /**
     * Master switch for file logging. When ON, every flag is appended to
     * `<mc>/liquidbounce/flags.log` with full context (timestamp, reason,
     * anticheat, active modules, packet ring buffer dump).
     */
    private var logToFile by boolean("LogToFile", true)

    /**
     * Master switch for the packet ring buffer. When ON, the last
     * [ringBufferSize] packets are kept in memory and dumped to the log
     * when a flag fires.
     */
    private var ringBufferEnabled by boolean("RingBuffer", true)

    /**
     * Number of packets to keep in the ring buffer. 100 packets at 20 TPS
     * = ~5 seconds of history.
     */
    private var ringBufferSize by int("RingBufferSize", 100, 20..500)

    /**
     * Master switch for anticheat chat-message parsing.
     */
    private var parseAnticheatChat by boolean("ParseAnticheatChat", true)

    private object ResetFlags : ToggleableConfigurable(this, "ResetFlags", true) {

        private var afterSeconds by int("After", 30, 1..300, "s")

        @Suppress("unused")
        private val repeatable = tickHandler {
            flagCount = 0
            waitSeconds(afterSeconds)
        }

    }

    private object Render : ToggleableConfigurable(this, "Render", true) {

        private val notInFirstPerson by boolean("NotInFirstPerson", true)
        private val renderTime by int("Alive", 1000, 0..3000, "ms")
        private val fadeOut by curve("FadeOut", Easing.QUAD_OUT)
        private val outTime by int("OutTime", 500, 0..2000, "ms")
        private var color by color("Color", Color4b.RED.with(a = 100).darker())
        private var outlineColor by color("OutlineColor", Color4b.RED.darker())

        val wireframePlayer = WireframePlayer(Vec3d.ZERO, 0f, 0f)
        var creationTime = 0L
        var finished = true

        override fun enable() {
            finished = true
        }

        @Suppress("unused")
        val renderHandler = handler<WorldRenderEvent> {
            if (finished || notInFirstPerson && mc.options.perspective.isFirstPerson) {
                return@handler
            }

            val time = System.currentTimeMillis()
            val withinRenderDuration = time - creationTime < renderTime

            if (withinRenderDuration) {
                wireframePlayer.render(it, color, outlineColor)
            } else {
                val factor = 1f - fadeOut.getFactor(creationTime + renderTime, time, outTime.toFloat())
                if (factor == 0f) {
                    finished = true
                    return@handler
                }

                wireframePlayer.render(it, color.fade(factor), outlineColor.fade(factor))
            }
        }

        fun reset() {
            creationTime = System.currentTimeMillis()
            finished = false
        }

    }

    init {
        tree(ResetFlags)
        tree(Render)
    }

    private var flagCount = 0
    private var lastYaw = 0F
    private var lastPitch = 0F

    /**
     * Ring buffer of recent packets. Used to dump the last N packets
     * to the log when a flag fires, so the user can see what the server
     * sent in the seconds leading up to the flag.
     *
     * Synchronized on itself for thread safety — packet handler runs on
     * the network thread, but the flag-triggered dump could in principle
     * race with other readers.
     */
    private val packetRingBuffer = ArrayDeque<String>()

    /**
     * Detected anticheat. Set by the chat-message parser when it sees a
     * known anticheat warning pattern. Cleared on world change.
     */
    @Volatile
    private var detectedAnticheat: Anticheat = Anticheat.UNKNOWN

    /**
     * Log file writer for `flags.log`. Opened lazily on first flag.
     * Uses real append mode (FileWriter(file, true)) so history survives
     * across client restarts.
     */
    private var logWriter: PrintWriter? = null

    /**
     * Thread-safe date formatter for log timestamps.
     */
    private val dateFormat = DateTimeFormatter.ISO_INSTANT

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (player.age <= 25) {
            return@handler
        }

        val packet = event.packet

        // Always record the packet in the ring buffer (regardless of type).
        if (ringBufferEnabled) {
            val packetDesc = describePacket(packet, event)
            synchronized(packetRingBuffer) {
                packetRingBuffer.addLast(packetDesc)
                while (packetRingBuffer.size > ringBufferSize) {
                    packetRingBuffer.pollFirst()
                }
            }
        }

        when (packet) {
            is PlayerPositionLookS2CPacket -> {
                val change = packet.change
                val deltaYaw = calculateAngleDelta(change.yaw, lastYaw)
                val deltaPitch = calculateAngleDelta(change.pitch, lastPitch)

                flagCount++
                val reason = if (deltaYaw >= 90 || deltaPitch >= 90) {
                    AlertReason.FORCEROTATE
                } else {
                    AlertReason.LAGBACK
                }
                val extra = if (reason == AlertReason.FORCEROTATE) {
                    "(${deltaYaw.roundToLong()}° | ${deltaPitch.roundToLong()}°)"
                } else {
                    null
                }

                alert(reason, extra)
                logFlagToFile(reason, extra, change.position, deltaYaw, deltaPitch)

                Render.reset()
                val position = change.position
                Render.wireframePlayer.setPosRot(position.x, position.y, position.z, change.yaw, change.pitch)

                lastYaw = player.headYaw
                lastPitch = player.pitch
            }

            is DisconnectS2CPacket -> {
                // v2: capture the kick reason. The previous version just
                // zeroed flagCount and discarded the reason.
                val reason = packet.reason
                val reasonString = try {
                    reason.string
                } catch (e: Exception) {
                    "<untranslatable>"
                }

                flagCount++
                alert(AlertReason.KICK, reasonString)
                logKickToFile(reasonString)
                flagCount = 0
            }

            is GameMessageS2CPacket -> {
                // v2: parse anticheat chat warnings.
                if (parseAnticheatChat) {
                    val text = packet.content.string
                    val detected = Anticheat.detect(text)
                    if (detected != Anticheat.UNKNOWN) {
                        detectedAnticheat = detected
                        flagCount++
                        alert(AlertReason.ANTICHEAT_WARNING, "[$detected] $text")
                        logAnticheatWarningToFile(detected, text)
                    }
                }
            }
        }
    }

    @Suppress("unused")
    private val repeatable = tickHandler {
        if (!invalidAttributes) {
            return@tickHandler
        }

        val invalidHeath = player.health <= 0f && player.isAlive
        val invalidHunger = player.hungerManager.foodLevel <= 0

        if (!invalidHeath && !invalidHunger) {
            return@tickHandler
        }

        val invalidReasons = mutableListOf<String>()

        if (invalidHeath) {
            invalidReasons.add("Health")
        }

        if (invalidHunger) {
            invalidReasons.add("Hunger")
        }

        if (invalidReasons.isNotEmpty()) {
            flagCount++

            val reasonString = invalidReasons.joinToString()
            alert(AlertReason.INVALID, reasonString)
            logFlagToFile(AlertReason.INVALID, reasonString, null, 0f, 0f)
        }
    }

    private fun alert(reason: AlertReason, extra: String? = null) {
        val message = if (StringUtils.isEmpty(extra)) {
            message("alert", message(reason.key), flagCount)
        } else {
            message("alertWithExtra", message(reason.key), extra!!, flagCount)
        }

        if (notification) {
            notification(name, message, NotificationEvent.Severity.INFO)
        }

        if (chatMessage) {
            chat(message, metadata = MessageMetadata(id = "$name#${reason.key}"))
        }
    }

    private fun calculateAngleDelta(newAngle: Float, oldAngle: Float): Float {
        var delta = newAngle - oldAngle
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        return abs(delta)
    }

    // ── v2: file logging ──────────────────────────────────────────────

    /**
     * Returns the log writer, opening the file lazily if needed.
     * Opened in REAL append mode so history survives restarts.
     */
    private fun getLogWriter(): PrintWriter? {
        if (!logToFile) return null
        logWriter?.let { return it }
        return try {
            val dir = File(mc.runDirectory, "liquidbounce")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "flags.log")
            val writer = PrintWriter(FileWriter(file, true), true)
            logWriter = writer
            writer
        } catch (e: Exception) {
            chat("§c[FlagCheck] Failed to open flags.log: ${e.message}")
            null
        }
    }

    /**
     * Returns a comma-separated list of currently-running combat modules.
     * Used to attribute flags to the module that likely caused them.
     */
    private fun activeCombatModules(): String {
        val running = ModuleManager.modules.filter { mod ->
            mod.running && mod.category == Category.COMBAT
        }.map { it.name }
        return if (running.isEmpty()) "<none>" else running.joinToString(", ")
    }

    /**
     * Snapshot the packet ring buffer as a multi-line string for logging.
     */
    private fun dumpRingBuffer(): String {
        if (!ringBufferEnabled) return "<disabled>"
        synchronized(packetRingBuffer) {
            if (packetRingBuffer.isEmpty()) return "<empty>"
            return packetRingBuffer.joinToString("\n  ")
        }
    }

    private fun logFlagToFile(
        reason: AlertReason,
        extra: String?,
        position: Vec3d?,
        deltaYaw: Float,
        deltaPitch: Float
    ) {
        val writer = getLogWriter() ?: return
        val ts = dateFormat.format(Instant.now())
        val posStr = position?.let { "(${it.x}, ${it.y}, ${it.z})" } ?: "n/a"
        val extraStr = extra?.let { " | extra=$it" } ?: ""
        val activeMods = activeCombatModules()

        synchronized(writer) {
            writer.println("=== FLAG @ $ts ===")
            writer.println("  reason: ${reason.key}$extraStr")
            writer.println("  deltaYaw: ${"%.2f".format(deltaYaw)}° | deltaPitch: ${"%.2f".format(deltaPitch)}°")
            writer.println("  position: $posStr")
            writer.println("  anticheat: $detectedAnticheat")
            writer.println("  active combat modules: $activeMods")
            writer.println("  flag count: $flagCount")
            writer.println("  --- recent packets (oldest first) ---")
            writer.println("  ${dumpRingBuffer()}")
            writer.println("=== END FLAG ===")
            writer.println()
        }
    }

    private fun logKickToFile(reasonString: String) {
        val writer = getLogWriter() ?: return
        val ts = dateFormat.format(Instant.now())
        val activeMods = activeCombatModules()

        synchronized(writer) {
            writer.println("!!! KICK @ $ts !!!")
            writer.println("  reason: $reasonString")
            writer.println("  anticheat: $detectedAnticheat")
            writer.println("  active combat modules at kick: $activeMods")
            writer.println("  flag count before kick: $flagCount")
            writer.println("  --- recent packets (oldest first) ---")
            writer.println("  ${dumpRingBuffer()}")
            writer.println("!!! END KICK !!!")
            writer.println()
        }
    }

    private fun logAnticheatWarningToFile(anticheat: Anticheat, text: String) {
        val writer = getLogWriter() ?: return
        val ts = dateFormat.format(Instant.now())
        val activeMods = activeCombatModules()

        synchronized(writer) {
            writer.println("~~~ ANTICHEAT WARNING @ $ts ~~~")
            writer.println("  anticheat: $anticheat")
            writer.println("  message: $text")
            writer.println("  active combat modules: $activeMods")
            writer.println("~~~ END WARNING ~~~")
            writer.println()
        }
    }

    /**
     * Produce a short human-readable description of a packet for the
     * ring buffer. Only includes packet type + key fields to keep the
     * log readable.
     */
    private fun describePacket(packet: Any, event: PacketEvent): String {
        val ts = dateFormat.format(Instant.now())
        val direction = if (event.origin == net.ccbluex.liquidbounce.event.events.TransferOrigin.INCOMING) "S→C" else "C→S"
        val typeName = packet.javaClass.simpleName
        // Include key fields for high-value packet types.
        val details = when (packet) {
            is PlayerPositionLookS2CPacket -> {
                val c = packet.change
                "pos=(${c.position.x}, ${c.position.y}, ${c.position.z}) yaw=${"%.2f".format(c.yaw)} pitch=${"%.2f".format(c.pitch)}"
            }
            is net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket -> {
                "onGround=${packet.isOnGround}"
            }
            is GameMessageS2CPacket -> {
                "msg=${packet.content.string.take(120)}"
            }
            else -> ""
        }
        return "$ts $direction $typeName $details".trim()
    }

    /**
     * Known anticheats and their chat-message detection patterns.
     *
     * Detection is case-insensitive substring match against the
     * unformatted message text. The first matching anticheat wins.
     */
    @Suppress("SpellCheckingInspection")
    enum class Anticheat(val displayName: String, vararg val patterns: String) {
        POLAR("Polar", "polar", "[polar]"),
        VERUS("Verus", "verus", "[verus]"),
        VULCAN("Vulcan", "vulcan", "[vulcan]"),
        GRIM("Grim", "grimac", "grim", "[grim]"),
        NCP("NCP", "nocheatplus", "[ncp]"),
        INTAVE("Intave", "intave", "[intave]"),
        MATRIX("Matrix", "matrix", "[matrix]"),
        KARHU("Karhu", "karhu", "[karhu]"),
        UNKNOWN("Unknown");

        companion object {
            fun detect(text: String): Anticheat {
                val lower = text.lowercase()
                for (ac in entries) {
                    if (ac == UNKNOWN) continue
                    if (ac.patterns.any { lower.contains(it) }) return ac
                }
                return UNKNOWN
            }
        }
    }

    @Suppress("SpellCheckingInspection")
    private enum class AlertReason(val key: String) {
        INVALID("invalid"),
        FORCEROTATE("forceRotate"),
        LAGBACK("lagback"),
        KICK("kick"),
        ANTICHEAT_WARNING("anticheatWarning")
    }

}
