/*
 * CustomAura presets — anticheat-specific configuration bundles.
 *
 * Mirrors the pattern of the 1.8 KillAura's Presets.kt but tuned for
 * the modern (1.9+) combat mechanics that CustomAura targets.
 *
 * ## Available presets
 *
 * - **POLAR** — strict. The flagship preset. Range 3.8, wall 0, jump-only
 *   crits WITH auto-jump (rate-limited to 1 jump/600ms so Polar's
 *   jump-pattern correlation check sees a non-periodic signal), tight
 *   PolarBypass noise (0.05°), drift 0.4° @ 0.3Hz, delta clamp
 *   20°/yaw 14°/pitch, FailSwing 200ms, no AutoBlock.
 *
 * - **INTAVE** — moderate. Range 3.9, slightly higher delta clamp, drift
 *   off (Intave doesn't check for perfect-tracking as aggressively).
 *
 * - **HYPIXEL** — semi-strict. Range 3.9, wall 0, drift off, NO
 *   auto-jump (Watchdog detects jump-on-click patterns), NO autoBlock
 *   (Watchdog flags block-then-attack patterns). Safest Hypixel config
 *   that still deals meaningful damage.
 *
 * - **VANILLA** — bypass-off. Max range, no jitter, no drift. Useful for
 *   testing on local servers.
 *
 * - **AUTO** — picks one of the above based on server detection.
 */
@file:Suppress("MagicNumber")
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.ModuleCustomAura.CriticalsMode
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.ModuleCustomAura.RaycastMode
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.features.CustomAuraAutoBlock.BlockMode
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.features.CustomAuraAutoBlock.UnblockMode
import net.ccbluex.liquidbounce.utils.client.ServerObserver

/**
 * Common range/raycast defaults shared across multiple presets.
 */
private const val SCAN_EXTRA_RANGE_START_DEFAULT = 0.5f
private const val SCAN_EXTRA_RANGE_END_DEFAULT = 1.0f
private const val SCAN_EXTRA_RANGE_END_HYPIXEL = 1.5f
private const val SCAN_EXTRA_RANGE_END_VANILLA = 0.5f
private const val FAIL_SWING_RANGE_START_DEFAULT = 1.0f
private const val FAIL_SWING_RANGE_END_DEFAULT = 1.5f
private const val FAIL_SWING_RANGE_START_VANILLA = 0.5f
private const val FAIL_SWING_RANGE_END_VANILLA = 1.0f
private const val FAIL_SWING_INTERVAL_MS_DEFAULT = 200
private const val FAIL_SWING_INTERVAL_MS_INTAVE = 150
private const val FAIL_SWING_INTERVAL_MS_VANILLA = 100
private const val TICK_OFF_ON_ZERO = 0
private const val TICK_OFF_ON_HYPIXEL_END = 1

/**
 * Presets for [ModuleCustomAura].
 *
 * Each preset is a complete configuration bundle that, when applied,
 * configures the module + all its submodules to be safe and effective
 * against a specific anticheat.
 */
object CustomAuraPresets {

    enum class Preset(override val choiceName: String) : NamedChoice {
        POLAR("Polar"),
        INTAVE("Intave"),
        HYPIXEL("Hypixel"),
        VANILLA("Vanilla"),
        AUTO("Auto")
    }

    /**
     * Resolves [Preset.AUTO] to a concrete preset using the current
     * server information. Defaults to [Preset.POLAR] for unknown servers
     * (safest modern choice).
     */
    fun resolve(preset: Preset): Preset {
        if (preset != Preset.AUTO) return preset

        val plugins = ServerObserver.plugins
        val address = ServerObserver.serverAddress?.address?.lowercase() ?: ""

        if (plugins != null) {
            if (plugins.any { it.contains("polar", ignoreCase = true) }) return Preset.POLAR
            if (plugins.any { it.contains("intave", ignoreCase = true) }) return Preset.INTAVE
            if (plugins.any { it.contains("hypixel", ignoreCase = true) }) return Preset.HYPIXEL
        }

        // Detect by server address.
        if (address.contains("hypixel")) return Preset.HYPIXEL
        if (address.contains("pika") || address.contains("blocksmc") ||
            address.contains("mineland") || address.contains("cubecraft")) {
            return Preset.POLAR
        }

        // Safest default for unknown modern servers.
        return Preset.POLAR
    }

    /**
     * Parameter bundle for a preset.
     *
     * Every field maps directly to a setting on [ModuleCustomAura] or one
     * of its submodules. [ModuleCustomAura.applyPreset] reads these and
     * writes them to the corresponding settings.
     */
    data class Params(
        // ── Main module ──────────────────────────────────────────────
        val range: Float,
        val wallRange: Float,
        val reachJitter: Float,
        val scanExtraRangeStart: Float,
        val scanExtraRangeEnd: Float,
        val raycast: RaycastMode,
        val criticalsMode: CriticalsMode,
        val autoJumpForCrits: Boolean,
        val keepSprint: Boolean,
        val ignoreOpenInventory: Boolean,

        // ── PolarBypass ──────────────────────────────────────────────
        val polarBypassEnabled: Boolean,
        val maxYawDelta: Float,
        val maxPitchDelta: Float,
        val noiseStddev: Float,
        val driftAmplitude: Float,
        val driftFrequency: Float,

        // ── AutoBlock ────────────────────────────────────────────────
        val autoBlockEnabled: Boolean,
        val blockMode: BlockMode,
        val unblockMode: UnblockMode,
        val tickOffStart: Int,
        val tickOffEnd: Int,
        val tickOnStart: Int,
        val tickOnEnd: Int,

        // ── FailSwing ────────────────────────────────────────────────
        val failSwingEnabled: Boolean,
        val failSwingAdditionalRangeStart: Float,
        val failSwingAdditionalRangeEnd: Float,
        val failSwingMinIntervalMs: Int,

        // ── AntiCheater ──────────────────────────────────────────────
        val antiCheaterEnabled: Boolean,
        val snapThreshold: Float,
        val cpsThreshold: Float,
        val trackingTicksThreshold: Int,
        val longRangeHit: Float
    )

    /**
     * The flagship **Polar** preset.
     *
     * Tuned to be the safest configuration that still deals meaningful
     * damage on Polar-protected servers.
     *
     *  - range 3.8 — well inside vanilla 4.5, avoids Reach flags
     *  - wallRange 0 — no through-wall hits (AimC)
     *  - reachJitter 0.05 — breaks Reach GCD correlation
     *  - PolarBypass ON with 0.05° gaussian noise — destroys AimB GCD
     *  - drift 0.4° @ 0.3Hz — breaks AimC perfect-tracking
     *  - delta clamp 20°/yaw, 14°/pitch — well under AimA snap threshold
     *  - JUMP_ONLY crits WITH autoJumpForCrits — vanilla-correct crits
     *    via jump→fall→hit. Rate-limited to 1 jump / 600ms so Polar's
     *    jump-pattern correlation check sees a non-periodic signal.
     *    The previous preset had autoJumpForCrits=false, which meant
     *    ZERO crits unless the user manually jumped — making the aura
     *    strictly worse than a vanilla player in 1.9+ PvP.
     *  - keepSprint OFF — no NoSlow flag
     *  - AutoBlock OFF — avoids all AutoBlock A/B/C patterns
     *  - FailSwing 200ms rate-limit — no BadPackets swing spam
     *  - AntiCheater ON — prioritize killing competing cheaters
     */
    fun paramsFor(preset: Preset): Params = when (preset) {
        Preset.POLAR -> Params(
            range = 3.8f,
            wallRange = 0f,
            reachJitter = 0.05f,
            scanExtraRangeStart = SCAN_EXTRA_RANGE_START_DEFAULT,
            scanExtraRangeEnd = SCAN_EXTRA_RANGE_END_DEFAULT,
            raycast = RaycastMode.TRACE_ALL,
            criticalsMode = CriticalsMode.JUMP_ONLY,
            autoJumpForCrits = true,
            keepSprint = false,
            ignoreOpenInventory = false,

            polarBypassEnabled = true,
            maxYawDelta = 20f,
            maxPitchDelta = 14f,
            noiseStddev = 0.05f,
            driftAmplitude = 0.4f,
            driftFrequency = 0.3f,

            autoBlockEnabled = false,
            blockMode = BlockMode.BASIC,
            unblockMode = UnblockMode.STOP_USING_ITEM,
            tickOffStart = TICK_OFF_ON_ZERO,
            tickOffEnd = TICK_OFF_ON_ZERO,
            tickOnStart = TICK_OFF_ON_ZERO,
            tickOnEnd = TICK_OFF_ON_ZERO,

            failSwingEnabled = true,
            failSwingAdditionalRangeStart = FAIL_SWING_RANGE_START_DEFAULT,
            failSwingAdditionalRangeEnd = FAIL_SWING_RANGE_END_DEFAULT,
            failSwingMinIntervalMs = FAIL_SWING_INTERVAL_MS_DEFAULT,

            antiCheaterEnabled = true,
            snapThreshold = 60f,
            cpsThreshold = 14f,
            trackingTicksThreshold = 20,
            longRangeHit = 3.8f
        )

        Preset.INTAVE -> Params(
            range = 3.9f,
            wallRange = 0f,
            reachJitter = 0.04f,
            scanExtraRangeStart = SCAN_EXTRA_RANGE_START_DEFAULT,
            scanExtraRangeEnd = SCAN_EXTRA_RANGE_END_DEFAULT,
            raycast = RaycastMode.TRACE_ALL,
            criticalsMode = CriticalsMode.JUMP_ONLY,
            autoJumpForCrits = true,
            keepSprint = false,
            ignoreOpenInventory = false,

            polarBypassEnabled = true,
            maxYawDelta = 25f,
            maxPitchDelta = 18f,
            noiseStddev = 0.04f,
            driftAmplitude = 0f,
            driftFrequency = 0f,

            autoBlockEnabled = false,
            blockMode = BlockMode.BASIC,
            unblockMode = UnblockMode.STOP_USING_ITEM,
            tickOffStart = TICK_OFF_ON_ZERO,
            tickOffEnd = TICK_OFF_ON_ZERO,
            tickOnStart = TICK_OFF_ON_ZERO,
            tickOnEnd = TICK_OFF_ON_ZERO,

            failSwingEnabled = true,
            failSwingAdditionalRangeStart = FAIL_SWING_RANGE_START_DEFAULT,
            failSwingAdditionalRangeEnd = FAIL_SWING_RANGE_END_DEFAULT,
            failSwingMinIntervalMs = FAIL_SWING_INTERVAL_MS_INTAVE,

            antiCheaterEnabled = true,
            snapThreshold = 65f,
            cpsThreshold = 13f,
            trackingTicksThreshold = 25,
            longRangeHit = 3.9f
        )

        Preset.HYPIXEL -> Params(
            range = 3.9f,
            wallRange = 0f,
            reachJitter = 0.03f,
            scanExtraRangeStart = SCAN_EXTRA_RANGE_START_DEFAULT,
            scanExtraRangeEnd = SCAN_EXTRA_RANGE_END_HYPIXEL,
            raycast = RaycastMode.TRACE_ALL,
            criticalsMode = CriticalsMode.JUMP_ONLY,
            // NO auto-jump on Hypixel — Watchdog's MovementPattern check
            // correlates "jump on click tick" with KillAura signatures.
            // The user must jump manually for crits. The previous preset
            // had autoJumpForCrits=true, which was a near-guaranteed ban
            // on the first fight.
            autoJumpForCrits = false,
            keepSprint = false,
            ignoreOpenInventory = false,

            polarBypassEnabled = true,
            maxYawDelta = 25f,
            maxPitchDelta = 18f,
            noiseStddev = 0.03f,
            driftAmplitude = 0f,
            driftFrequency = 0f,

            // NO autoBlock on Hypixel — Watchdog's AutoBlock check flags
            // the block→attack→unblock pattern even with vanilla
            // STOP_USING_ITEM. The previous preset had autoBlockEnabled=true,
            // which combined with autoJumpForCrits=true made the preset
            // a ban-on-sight config.
            autoBlockEnabled = false,
            blockMode = BlockMode.BASIC,
            unblockMode = UnblockMode.STOP_USING_ITEM,
            tickOffStart = TICK_OFF_ON_ZERO,
            tickOffEnd = TICK_OFF_ON_HYPIXEL_END,
            tickOnStart = TICK_OFF_ON_ZERO,
            tickOnEnd = TICK_OFF_ON_HYPIXEL_END,

            failSwingEnabled = false,
            failSwingAdditionalRangeStart = FAIL_SWING_RANGE_START_DEFAULT,
            failSwingAdditionalRangeEnd = FAIL_SWING_RANGE_END_DEFAULT,
            failSwingMinIntervalMs = FAIL_SWING_INTERVAL_MS_DEFAULT,

            antiCheaterEnabled = true,
            snapThreshold = 70f,
            cpsThreshold = 12f,
            trackingTicksThreshold = 30,
            longRangeHit = 4.0f
        )

        Preset.VANILLA -> Params(
            range = 4.4f,
            wallRange = 0f,
            reachJitter = 0f,
            scanExtraRangeStart = 0f,
            scanExtraRangeEnd = SCAN_EXTRA_RANGE_END_VANILLA,
            raycast = RaycastMode.TRACE_ALL,
            criticalsMode = CriticalsMode.NONE,
            autoJumpForCrits = false,
            keepSprint = true,
            ignoreOpenInventory = false,

            polarBypassEnabled = false,
            maxYawDelta = 45f,
            maxPitchDelta = 30f,
            noiseStddev = 0f,
            driftAmplitude = 0f,
            driftFrequency = 0f,

            autoBlockEnabled = false,
            blockMode = BlockMode.BASIC,
            unblockMode = UnblockMode.STOP_USING_ITEM,
            tickOffStart = TICK_OFF_ON_ZERO,
            tickOffEnd = TICK_OFF_ON_ZERO,
            tickOnStart = TICK_OFF_ON_ZERO,
            tickOnEnd = TICK_OFF_ON_ZERO,

            failSwingEnabled = false,
            failSwingAdditionalRangeStart = FAIL_SWING_RANGE_START_VANILLA,
            failSwingAdditionalRangeEnd = FAIL_SWING_RANGE_END_VANILLA,
            failSwingMinIntervalMs = FAIL_SWING_INTERVAL_MS_VANILLA,

            antiCheaterEnabled = false,
            snapThreshold = 60f,
            cpsThreshold = 14f,
            trackingTicksThreshold = 20,
            longRangeHit = 3.8f
        )

        Preset.AUTO -> paramsFor(resolve(Preset.AUTO))
    }
}
