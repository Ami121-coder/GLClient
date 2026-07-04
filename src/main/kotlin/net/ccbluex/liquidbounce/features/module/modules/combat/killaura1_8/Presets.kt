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

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.utils.client.ServerObserver

/**
 * # Presets — anticheat-specific configuration bundles.
 *
 * Bundles the many tunable parameters of [ModuleKillAura1_8] into a
 * small number of named presets, addressing the v3 issue of "20+
 * parameters, no presets, user must tune everything manually".
 *
 * ## Available presets
 *
 * - **POLAR** — strict. enemyPredictTicks=0, reach ≤ 3.0, sprint-reset
 *   always on, desync criticals, real-GCD on, no-fake-ground.
 * - **INTAVE** — moderate. enemyPredictTicks=1, reach ≤ 3.0, log-normal
 *   clicker with high entropy, NPC filter on STRICT.
 * - **HYPIXEL** — semi-strict. enemyPredictTicks=1, reach ≤ 4.0
 *   (Hypixel allows 4.0 with some slack), clicker capped at 12 CPS.
 * - **VANILLA** — bypass-off. Real GCD off, sprint-reset off, no
 *   backtrack, no criticals. Useful for testing on local servers.
 * - **AUTO** — picks one of the above based on [ServerObserver].
 *
 * ## Application
 *
 * Calling [applyTo] mutates the module's settings via the module's
 * internal setter helpers. The module then has full visibility of
 * the changes (so it can log them, re-validate ranges, etc.).
 *
 * Author: Super Z (1.8 PvP rewrite, v4)
 */
object Presets {

    enum class Preset(override val choiceName: String) : NamedChoice {
        POLAR("Polar"),
        INTAVE("Intave"),
        HYPIXEL("Hypixel"),
        VANILLA("Vanilla"),
        AUTO("Auto")
    }

    /**
     * Resolves [Preset.AUTO] to a concrete preset using the current
     * server information. If the server brand / plugins are unknown,
     * defaults to [Preset.INTAVE] (which is safe across most 1.8
     * protocol servers).
     */
    fun resolve(preset: Preset): Preset {
        if (preset != Preset.AUTO) return preset

        val plugins = ServerObserver.plugins
        val address = ServerObserver.serverAddress?.address?.lowercase() ?: ""

        // Detect by plugin list.
        if (plugins != null) {
            if (plugins.any { it.contains("intave", ignoreCase = true) }) return Preset.INTAVE
            if (plugins.any { it.contains("polar", ignoreCase = true) }) return Preset.POLAR
            if (plugins.any { it.contains("hypixel", ignoreCase = true) }) return Preset.HYPIXEL
        }

        // Detect by server address (common 1.8 PvP servers).
        if (address.contains("hypixel")) return Preset.HYPIXEL
        if (address.contains("pika") || address.contains("blocksmc") || address.contains("mineland")) {
            // Pika, BlocksMC, Mineland typically run Polar-like checks.
            return Preset.POLAR
        }

        // Default for unknown 1.8 servers.
        return Preset.INTAVE
    }

    /**
     * Parameter bundle for a preset. The KillAura reads these and
     * applies them to its own settings.
     */
    data class Params(
        val range: Float,
        val wallRange: Float,
        val scanRange: Float,
        val enemyPredictTicks: Int,
        val cps: IntRange,
        val clickPattern: ModuleKillAura1_8.ClickPattern,
        val missRate: Float,
        val maxYawSpeed: Float,
        val maxPitchSpeed: Float,
        val smoothFactor: Float,
        val rotationJitter: Float,
        val microPauseChance: Float,
        val fov: Float,
        val verticalFov: Float,
        val hurtTime: Int,
        val targetRetentionTicks: Int,
        val criticalsMode: ModuleKillAura1_8.CriticalsMode,
        val packetCritChance: Float,
        val minFallDistance: Float,
        val polarSprintReset: Boolean,
        val keepSprint: Boolean,
        val intaveMinInterAttackTicks: Int,
        val intaveMaxYawDeltaPerTick: Float,
        val backtrackEnabled: Boolean,
        val backtrackTicks: Int,
        val realGcdEnabled: Boolean,
        val npcStrictness: NpcFilter.Strictness,
        val reachVariability: Boolean,
        val aimpointMode: ModuleKillAura1_8.AimpointMode,
        val mouseSensitivity: Float,
        val pingCompensationTicks: Int
    )

    fun paramsFor(preset: Preset): Params = when (preset) {
        Preset.POLAR -> Params(
            range = 3.0f,
            wallRange = 2.5f,
            scanRange = 4.5f,
            enemyPredictTicks = 0,
            cps = 9..11,
            clickPattern = ModuleKillAura1_8.ClickPattern.BINOMIAL,
            missRate = 8f,
            maxYawSpeed = 55f,
            maxPitchSpeed = 40f,
            smoothFactor = 5.0f,
            rotationJitter = 0.5f,
            microPauseChance = 5f,
            fov = 90f,
            verticalFov = 60f,
            hurtTime = 9,
            targetRetentionTicks = 10,
            criticalsMode = ModuleKillAura1_8.CriticalsMode.DESYNC,
            packetCritChance = 0.0f,
            minFallDistance = 0.1f,
            polarSprintReset = true,
            keepSprint = false,
            intaveMinInterAttackTicks = 1,
            intaveMaxYawDeltaPerTick = 55f,
            backtrackEnabled = true,
            backtrackTicks = 2,
            realGcdEnabled = true,
            npcStrictness = NpcFilter.Strictness.STRICT,
            reachVariability = true,
            aimpointMode = ModuleKillAura1_8.AimpointMode.NEAREST_POINT,
            mouseSensitivity = 0.6f,
            pingCompensationTicks = 1
        )

        Preset.INTAVE -> Params(
            range = 3.0f,
            wallRange = 2.5f,
            scanRange = 4.5f,
            enemyPredictTicks = 1,
            cps = 9..12,
            clickPattern = ModuleKillAura1_8.ClickPattern.NORMAL,
            missRate = 6f,
            maxYawSpeed = 65f,
            maxPitchSpeed = 50f,
            smoothFactor = 4.0f,
            rotationJitter = 0.4f,
            microPauseChance = 4f,
            fov = 100f,
            verticalFov = 70f,
            hurtTime = 9,
            targetRetentionTicks = 15,
            criticalsMode = ModuleKillAura1_8.CriticalsMode.PACKET,
            packetCritChance = 0.3f,
            minFallDistance = 0.1f,
            polarSprintReset = true,
            keepSprint = false,
            intaveMinInterAttackTicks = 2,
            intaveMaxYawDeltaPerTick = 70f,
            backtrackEnabled = true,
            backtrackTicks = 3,
            realGcdEnabled = true,
            npcStrictness = NpcFilter.Strictness.STRICT,
            reachVariability = true,
            aimpointMode = ModuleKillAura1_8.AimpointMode.NEAREST_POINT,
            mouseSensitivity = 0.6f,
            pingCompensationTicks = 2
        )

        Preset.HYPIXEL -> Params(
            range = 4.0f,
            wallRange = 3.0f,
            scanRange = 5.5f,
            enemyPredictTicks = 1,
            cps = 10..12,
            clickPattern = ModuleKillAura1_8.ClickPattern.BINOMIAL,
            missRate = 5f,
            maxYawSpeed = 80f,
            maxPitchSpeed = 60f,
            smoothFactor = 3.5f,
            rotationJitter = 0.3f,
            microPauseChance = 3f,
            fov = 120f,
            verticalFov = 80f,
            hurtTime = 10,
            targetRetentionTicks = 20,
            criticalsMode = ModuleKillAura1_8.CriticalsMode.DESYNC,
            packetCritChance = 0.0f,
            minFallDistance = 0.1f,
            polarSprintReset = true,
            keepSprint = false,
            intaveMinInterAttackTicks = 1,
            intaveMaxYawDeltaPerTick = 90f,
            backtrackEnabled = true,
            backtrackTicks = 2,
            realGcdEnabled = true,
            npcStrictness = NpcFilter.Strictness.NORMAL,
            reachVariability = false,
            aimpointMode = ModuleKillAura1_8.AimpointMode.NEAREST_POINT,
            mouseSensitivity = 0.5f,
            pingCompensationTicks = 1
        )

        Preset.VANILLA -> Params(
            range = 4.5f,
            wallRange = 4.5f,
            scanRange = 6.0f,
            enemyPredictTicks = 1,
            cps = 8..10,
            clickPattern = ModuleKillAura1_8.ClickPattern.NORMAL,
            missRate = 0f,
            maxYawSpeed = 180f,
            maxPitchSpeed = 180f,
            smoothFactor = 1.0f,
            rotationJitter = 0f,
            microPauseChance = 0f,
            fov = 180f,
            verticalFov = 180f,
            hurtTime = 10,
            targetRetentionTicks = 5,
            criticalsMode = ModuleKillAura1_8.CriticalsMode.NONE,
            packetCritChance = 0.0f,
            minFallDistance = 0.1f,
            polarSprintReset = false,
            keepSprint = true,
            intaveMinInterAttackTicks = 1,
            intaveMaxYawDeltaPerTick = 180f,
            backtrackEnabled = false,
            backtrackTicks = 0,
            realGcdEnabled = false,
            npcStrictness = NpcFilter.Strictness.LENIENT,
            reachVariability = false,
            aimpointMode = ModuleKillAura1_8.AimpointMode.CENTER,
            mouseSensitivity = 0.5f,
            pingCompensationTicks = 0
        )

        Preset.AUTO -> paramsFor(resolve(Preset.AUTO))
    }
}
