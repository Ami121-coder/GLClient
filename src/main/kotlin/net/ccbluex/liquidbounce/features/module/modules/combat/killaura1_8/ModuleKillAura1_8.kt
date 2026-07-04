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
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.SprintEvent
import net.ccbluex.liquidbounce.event.events.WorldEntityRemoveEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.utils.facingEnemy
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceEntity
import net.ccbluex.liquidbounce.utils.client.isOlderThanOrEqual1_8
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.network
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.math.sq
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket
import net.minecraft.util.Hand
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * # KillAura1_8 — Native 1.8 PvP Combat Module (v4)
 *
 * v4 supersedes v3 with the following additions and rewrites:
 *
 * ## v4 changes (vs v3 review findings)
 *
 *  - **BacktrackStore** — 3-tick position history per entity. The
 *    KillAura attacks the tick whose hitbox yields the smallest eye
 *    distance, mimicking Augustus / Rise behavior. Compensates 100-
 *    150 ms of ping on Polar / Intave.
 *  - **RealisticGcdSimulator** — replaces `roundToGcd(value/g)*g` with
 *    emulated mouse-delta pipeline: desired rotation → implied mouse
 *    pixels → ±0.5 px Gaussian noise → integer pixels → re-applied
 *    multiplier. Breaks the perfect-grid fingerprint that Polar
 *    Rotation-Heuristics flags.
 *  - **AimPointFinder** with NEAREST_POINT mode — picks the closest
 *    point on the target's hitbox to the player's current crosshair
 *    ray, minimizing rotation delta and matching real player behavior.
 *  - **Vertical FOV** check (pitch delta), in addition to the
 *    horizontal yaw delta already present in v3.
 *  - **NpcFilter** — multi-signal classifier: tab-list presence,
 *    ping, skin texture, gamemode, spawn-age, vertical-velocity
 *    plausibility. Strictness levels: LENIENT / NORMAL / STRICT.
 *  - **CriticalsMode.DESYNC** — position-desync criticals (safer than
 *    PACKET on Polar). Sends an OnGroundOnly=false packet only when
 *    the player is actually airborne, then re-syncs on the next tick.
 *  - **Presets** — POLAR / INTAVE / HYPIXEL / VANILLA / AUTO. AUTO
 *    resolves based on [ServerObserver] plugin list and server address.
 *  - **Reach variability** — instead of a hard 3.0 cap, samples the
 *    effective cap uniformly in [2.7, 3.0] per attack to avoid the
 *    "always exactly 3.0" fingerprint.
 *  - **Clicker** — rewritten with ms-precise background scheduler and
 *    butterfly state machine.
 *
 * ## v3 changes (kept from previous version)
 *
 *  - Sprint-crit fix (STOP_SPRINTING before attack)
 *  - Vanilla swing-before-attack order
 *  - Reach check via raytrace distance (not boxed-distance)
 *  - Wall check via `facingEnemy`
 *  - Aimpoint around torso center (kept as fallback mode)
 *  - Guaranteed rotation reset on disable
 *
 * ## Author: Super Z (1.8 PvP rewrite, v4)
 */
object ModuleKillAura1_8 : ClientModule(
    "KillAura1_8",
    Category.COMBAT,
    aliases = arrayOf("KA1_8", "KillAura18"),
    disableOnQuit = true
) {

    // ── Range ───────────────────────────────────────────────────────────────
    var range by float("Range", 3.0f, 1f..6f)
    var scanRange by float("ScanRange", 4.5f, 0f..8f)
    var wallRange by float("WallRange", 2.5f, 0f..6f)

    /**
     * Number of ticks to extrapolate the target's position forward when
     * computing the hit point. Set to 1 for Intave, 0 for Polar strict.
     */
    var enemyPredictTicks by int("EnemyPredictTicks", 1, 0..5)

    /**
     * Ping compensation: extrapolates target position by N additional
     * ticks based on the player's network ping. Set to 0 to disable.
     */
    var pingCompensationTicks by int("PingCompensationTicks", 1, 0..5)

    // ── Click scheduling ────────────────────────────────────────────────────
    var cps by intRange("CPS", 9..11, 1..20, "cps")
    var clickPattern by enumChoice("ClickPattern", ClickPattern.BINOMIAL)
    var ignoreVanillaCooldown by boolean("IgnoreVanillaCooldown", true)

    /**
     * Probability of intentionally skipping an attack even when the
     * target is in range and facing. Simulates human miss rate.
     */
    var missRate by float("MissRate", 8f, 0f..30f, "%")

    // ── Rotations ───────────────────────────────────────────────────────────
    /**
     * Mouse sensitivity (0..1) for GCD computation. Must match your
     * actual Minecraft sensitivity for the RealisticGcdSimulator to
     * produce plausible rotations.
     */
    var mouseSensitivity by float("MouseSensitivity", 0.6f, 0f..1f)

    /**
     * When true, rotations pass through [RealisticGcdSimulator]
     * (emulated mouse-delta pipeline). When false, raw target rotation
     * is sent directly.
     */
    var realGcdEnabled by boolean("RealGCD", true)

    var maxYawSpeed by float("MaxYawSpeed", 55f, 5f..180f)
    var maxPitchSpeed by float("MaxPitchSpeed", 40f, 5f..180f)
    var smoothFactor by float("SmoothFactor", 5.0f, 0.5f..20f)
    var rotationJitter by float("RotationJitter", 0.5f, 0f..5f)
    var microPauseChance by float("MicroPauseChance", 5f, 0f..30f, "%")

    // ── Target selection ────────────────────────────────────────────────────
    var fov by float("FOV", 90f, 0f..180f)

    /**
     * Vertical FOV cap (pitch delta in degrees). v3 lacked this and
     * could attack targets far below/above the player, which Polar
     * flags as "unrealistic pitch attack".
     */
    var verticalFov by float("VerticalFOV", 60f, 0f..180f)

    var hurtTime by int("HurtTime", 9, 0..10)
    var priority by enumChoice("Priority", TargetPriority.DISTANCE)
    var targetRetentionTicks by int("TargetRetention", 20, 1..100, "ticks")

    /**
     * NPC / bot filter strictness. STRICT is required for Intave Heavy
     * and Polar bot decoys.
     */
    var npcStrictness by enumChoice("NpcStrictness", NpcFilter.Strictness.NORMAL)

    // ── Critical hits ───────────────────────────────────────────────────────
    var criticalsMode by enumChoice("Criticals", CriticalsMode.DESYNC)
    var minFallDistance by float("MinFallDistance", 0.1f, 0f..1f)
    var packetCritChance by float("PacketCritChance", 0.4f, 0f..1f)

    /**
     * Aimpoint selection mode. NEAREST_POINT picks the closest point
     * on the hitbox to the player's crosshair ray (recommended).
     */
    var aimpointMode by enumChoice("Aimpoint", AimpointMode.NEAREST_POINT)

    // ── Bypass ──────────────────────────────────────────────────────────────
    /**
     * ALWAYS send STOP_SPRINTING before attack when sprinting. Required
     * on Polar — without it, every crit is flagged as a sprint-crit
     * hack. No reason to ever disable on a strict anticheat.
     */
    var polarSprintReset by boolean("PolarSprintReset", true)

    /**
     * Intave-specific: minimum number of ticks between two attacks.
     */
    var intaveMinInterAttackTicks by int("IntaveMinInterAttackTicks", 1, 1..5)

    /**
     * Intave-specific: maximum yaw delta per tick while sprinting.
     */
    var intaveMaxYawDeltaPerTick by float("IntaveMaxYawDelta", 55f, 10f..180f)

    /**
     * When true, the [BacktrackStore] is queried for the best historical
     * hitbox to attack. Compensates 100-150 ms of ping.
     */
    var backtrackEnabled by boolean("Backtrack", true)

    /**
     * Maximum number of ticks to look back when finding the best
     * backtrack snapshot. Capped at [BacktrackStore.maxHistoryTicks].
     */
    var backtrackTicks by int("BacktrackTicks", 2, 1..BacktrackStore.maxHistoryTicks)

    /**
     * When true, samples the effective reach cap from a Gaussian
     * distribution (μ=2.85, σ=0.08, clamped to [2.7, 3.0]) per attack
     * to avoid the "always exactly 3.0" reach fingerprint that Polar
     * `Reach-C` flags on long sessions. The Gaussian shape matches
     * real player hit-distance distributions better than uniform.
     */
    var reachVariability by boolean("ReachVariability", true)

    // ── Behavior toggles ────────────────────────────────────────────────────
    /**
     * Whether to keep sprinting after an attack. On 1.8, sprint-crits
     * are impossible in vanilla, so this should be FALSE on strict AC.
     */
    var keepSprint by boolean("KeepSprint", false)

    var require1_8 by boolean("Require1_8", true)
    var suspendOriginalKillAura by boolean("SuspendOriginalKillAura", true)

    // ── Presets ─────────────────────────────────────────────────────────────
    /**
     * Selecting a preset (other than AUTO) immediately applies its
     * parameter bundle to the module's settings.
     */
    var preset by enumChoice("Preset", Presets.Preset.AUTO).onChanged { p ->
        applyPreset(p)
    }

    // ── Internal state ──────────────────────────────────────────────────────
    private var currentTarget: LivingEntity? = null
    private var targetLockedTicks = 0
    private var lastAttackTick = -100
    private var currentRotation: Rotation? = null
    private var wasSprintingBeforeAttack = false
    private var originalWasEnabled = false

    /**
     * Best historical snapshot for the current target, recomputed each
     * tick in [computeRotation]. Read by the tick handler to perform
     * the reach check against the backtrack hitbox (which is the actual
     * hitbox the server will see for the chosen tick).
     */
    @Volatile
    private var currentBacktrackHit: BacktrackStore.HistoricalHit? = null

    private val clicker = KillAura1_8Clicker { cps }
    private val rng = java.util.Random(System.currentTimeMillis())

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun enable() {
        super.enable()
        if (suspendOriginalKillAura && ModuleKillAura.running) {
            ModuleKillAura.enabled = false
            originalWasEnabled = true
        }
        BacktrackStore.reset()
        NpcFilter.reset()
    }

    override fun disable() {
        super.disable()
        if (originalWasEnabled) {
            ModuleKillAura.enabled = true
            originalWasEnabled = false
        }
        currentTarget = null
        currentRotation = null
        currentBacktrackHit = null
        targetLockedTicks = 0
        lastAttackTick = -100
        wasSprintingBeforeAttack = false
        clicker.reset()
    }

    // ── Preset application ──────────────────────────────────────────────────

    /**
     * Applies a preset's parameter bundle to the module's settings.
     *
     * Each setting is written via its delegated `setValue` operator,
     * which calls the underlying `Value.set()` method and triggers
     * any registered `onChange` listeners.
     *
     * Settings that the user has manually overridden since module
     * enable will be overwritten — this is intentional, since selecting
     * a preset is an explicit user action.
     */
    fun applyPreset(p: Presets.Preset) {
        val resolved = Presets.resolve(p)
        val params = Presets.paramsFor(resolved)

        // Apply each parameter via the delegated setter.
        range = params.range
        wallRange = params.wallRange
        scanRange = params.scanRange
        enemyPredictTicks = params.enemyPredictTicks
        pingCompensationTicks = params.pingCompensationTicks
        cps = params.cps
        clickPattern = params.clickPattern
        missRate = params.missRate
        mouseSensitivity = params.mouseSensitivity
        realGcdEnabled = params.realGcdEnabled
        maxYawSpeed = params.maxYawSpeed
        maxPitchSpeed = params.maxPitchSpeed
        smoothFactor = params.smoothFactor
        rotationJitter = params.rotationJitter
        microPauseChance = params.microPauseChance
        fov = params.fov
        verticalFov = params.verticalFov
        hurtTime = params.hurtTime
        targetRetentionTicks = params.targetRetentionTicks
        npcStrictness = params.npcStrictness
        criticalsMode = params.criticalsMode
        packetCritChance = params.packetCritChance
        minFallDistance = params.minFallDistance
        aimpointMode = params.aimpointMode
        polarSprintReset = params.polarSprintReset
        keepSprint = params.keepSprint
        intaveMinInterAttackTicks = params.intaveMinInterAttackTicks
        intaveMaxYawDeltaPerTick = params.intaveMaxYawDeltaPerTick
        backtrackEnabled = params.backtrackEnabled
        backtrackTicks = params.backtrackTicks
        reachVariability = params.reachVariability
    }

    /**
     * Returns the parameters of the currently-selected preset. Useful
     * for the KillAura to consult when an individual setting has been
     * left at its default value.
     */
    fun currentPresetParams(): Presets.Params =
        Presets.paramsFor(Presets.resolve(preset))

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun shouldRun(): Boolean {
        if (require1_8 && !isOlderThanOrEqual1_8) return false
        if (player.isDead || player.isSpectator) return false
        return true
    }

    /**
     * Selects the best target using the configured [priority]. Applies
     * the [NpcFilter] and the basic validity checks (distance, FOV,
     * hurt-time).
     */
    private fun selectTarget(): LivingEntity? {
        val sticky = currentTarget
        if (sticky != null && targetLockedTicks < targetRetentionTicks) {
            if (isTargetValid(sticky)) {
                targetLockedTicks++
                return sticky
            }
        }

        val newTarget = world.entities
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { isTargetValid(it) }
            .sortedBy { entity ->
                when (priority) {
                    TargetPriority.DISTANCE -> entity.squaredBoxedDistanceTo(player)
                    TargetPriority.HEALTH -> entity.health.toDouble()
                    TargetPriority.DIRECTION -> yawDeltaTo(entity).toDouble()
                    TargetPriority.HURT_TIME -> entity.hurtTime.toDouble()
                }
            }
            .firstOrNull()

        currentTarget = newTarget
        targetLockedTicks = 0
        return newTarget
    }

    /**
     * Validates a target. Combines the v3 basic checks with the v4
     * [NpcFilter] multi-signal classifier.
     */
    private fun isTargetValid(entity: LivingEntity): Boolean {
        if (entity == player) return false
        if (entity.isRemoved) return false
        if (!entity.shouldBeAttacked()) return false
        if (entity.hurtTime > hurtTime) return false

        // NPC / bot filtering (replaces v3's inline checks).
        if (NpcFilter.isNpc(entity, npcStrictness)) return false

        val distSq = entity.squaredBoxedDistanceTo(player)
        val maxScan = maxOf(range, scanRange)
        if (distSq > maxScan.sq()) return false

        // Horizontal FOV check.
        if (fov < 180f) {
            val delta = yawDeltaTo(entity)
            if (delta > fov) return false
        }
        // Vertical FOV check (v4 addition).
        if (verticalFov < 180f) {
            val delta = pitchDeltaTo(entity)
            if (delta > verticalFov) return false
        }
        return true
    }

    /**
     * Computes the yaw delta from the player to the entity, using the
     * entity's bounding-box CENTER (not entity.pos which is the feet
     * position).
     */
    private fun yawDeltaTo(entity: Entity): Float {
        val center = entity.boundingBox.center
        val delta = center.subtract(player.eyePos)
        val targetYaw = Math.toDegrees(atan2(delta.z, delta.x)).toFloat() - 90f
        var diff = abs(targetYaw - player.yaw) % 360f
        if (diff > 180f) diff = 360f - diff
        return diff
    }

    /**
     * Computes the pitch delta from the player to the entity's torso
     * center. v4 addition.
     */
    private fun pitchDeltaTo(entity: Entity): Float {
        val center = entity.boundingBox.center
        val delta = center.subtract(player.eyePos)
        val horizontal = sqrt(delta.x * delta.x + delta.z * delta.z)
        val targetPitch = -Math.toDegrees(atan2(delta.y, horizontal)).toFloat()
        return abs(targetPitch - player.pitch)
    }

    /**
     * Computes the rotation needed to aim at the target's hitbox.
     *
     * v4 changes:
     *  - Aimpoint selection delegated to [AimPointFinder] with the
     *    configured mode (NEAREST_POINT recommended).
     *  - Real-GCD applied via [RealisticGcdSimulator] instead of the
     *    v3 `roundToGcd` quantizer.
     *  - Backtrack: if [backtrackEnabled] and a historical snapshot
     *    is meaningfully closer, aim at that snapshot's hitbox.
     */
    private fun computeRotation(target: LivingEntity): Rotation {
        val eyePos = player.eyePos
        val lookDir = Vec3d.fromPolar(player.pitch, player.yaw)

        // v4: Select the effective hitbox (current vs backtrack).
        // This also updates [currentBacktrackHit] so the tick handler
        // can use the same hitbox for the reach/raytrace check.
        val effectiveBox = selectEffectiveHitbox(target, eyePos)

        // v4: Aimpoint on the effective hitbox.
        val aimPoint = findAimPointOnBox(eyePos, lookDir, effectiveBox)

        // Apply prediction + ping compensation. We extrapolate the
        // target's *current* velocity — backtrack snapshots are not
        // extrapolated because they represent past positions.
        val predictTicksTotal = enemyPredictTicks + pingCompensationTicks
        val predictedOffset = if (predictTicksTotal > 0) {
            val v = target.velocity
            val maxV = 1.0
            Vec3d(
                v.x.coerceIn(-maxV, maxV) * predictTicksTotal,
                v.y.coerceIn(-maxV, maxV) * predictTicksTotal,
                v.z.coerceIn(-maxV, maxV) * predictTicksTotal
            )
        } else {
            Vec3d.ZERO
        }
        val finalAimPoint = aimPoint.add(predictedOffset)

        // Target rotation.
        val delta = finalAimPoint.subtract(eyePos)
        val targetYaw = Math.toDegrees(atan2(delta.z, delta.x)).toFloat() - 90f
        val targetPitch = (-Math.toDegrees(
            atan2(delta.y, sqrt(delta.x * delta.x + delta.z * delta.z))
        )).toFloat()

        val base = currentRotation ?: Rotation(player.yaw, player.pitch)
        val yawDelta = wrapDegrees(targetYaw - base.yaw)
        val pitchDelta = (targetPitch - base.pitch).coerceIn(-90f, 90f)

        val yawStep = sigmoidStep(yawDelta, maxYawSpeed, smoothFactor)
        val pitchStep = sigmoidStep(pitchDelta, maxPitchSpeed, smoothFactor)

        // Micro-pause: with [microPauseChance] probability, skip the
        // rotation update for this tick. Simulates wrist fatigue.
        val doUpdate = rng.nextFloat() * 100f >= microPauseChance
        val yaw = if (doUpdate) wrapDegrees(base.yaw + yawStep) else base.yaw
        val pitch = if (doUpdate) (base.pitch + pitchStep).coerceIn(-90f, 90f) else base.pitch

        // Realistic GCD via emulated mouse-delta pipeline.
        val desired = Rotation(yaw, pitch)
        val finalRotation = if (realGcdEnabled) {
            RealisticGcdSimulator.simulate(
                current = base,
                target = desired,
                sensitivity = mouseSensitivity
            )
        } else {
            desired
        }

        return finalRotation
    }

    /**
     * Picks the best hitbox to attack: either the target's *current*
     * hitbox, or the best historical snapshot from [BacktrackStore] if
     * it is meaningfully closer (by at least 0.05 blocks).
     *
     * Updates [currentBacktrackHit] so the tick handler can perform the
     * reach check against the same hitbox.
     */
    private fun selectEffectiveHitbox(
        target: LivingEntity,
        eyePos: Vec3d
    ): net.minecraft.util.math.Box {
        val currentBox = target.boundingBox
        if (!backtrackEnabled) {
            currentBacktrackHit = null
            return currentBox
        }

        val backtrackHit = BacktrackStore.bestSnapshot(target, eyePos, maxAgeTicks = backtrackTicks)
        if (backtrackHit == null) {
            currentBacktrackHit = null
            return currentBox
        }

        val currentNearest = clampToBox(eyePos, currentBox)
        val currentDist = eyePos.distanceTo(currentNearest)

        if (backtrackHit.eyeDistance < currentDist - 0.05) {
            currentBacktrackHit = backtrackHit
            return backtrackHit.box
        }

        currentBacktrackHit = null
        return currentBox
    }

    /**
     * Computes the aimpoint on the given hitbox, using the configured
     * [aimpointMode]. Centralized here so both the rotation computation
     * and the tick handler can use the same logic.
     */
    private fun findAimPointOnBox(
        eyePos: Vec3d,
        lookDir: Vec3d,
        box: net.minecraft.util.math.Box
    ): Vec3d {
        val mode = aimpointMode.toFinderMode()
        val point = when (mode) {
            AimPointFinder.Mode.CENTER -> box.center
            AimPointFinder.Mode.TORSO_SPHERE -> {
                val center = box.center
                val offX = (rng.nextDouble() - 0.5) * 0.6
                val offY = (rng.nextDouble() - 0.5) * 0.4
                val offZ = (rng.nextDouble() - 0.5) * 0.6
                Vec3d(center.x + offX, center.y + offY, center.z + offZ)
            }
            AimPointFinder.Mode.NEAREST_POINT -> {
                val faceHit = rayTraceBoxFaces(eyePos, lookDir, box)
                faceHit ?: clampToBox(eyePos, box)
            }
        }

        // Tiny jitter to break determinism.
        val jx = (rng.nextDouble() - 0.5) * 0.1
        val jy = (rng.nextDouble() - 0.5) * 0.1
        val jz = (rng.nextDouble() - 0.5) * 0.1
        return Vec3d(point.x + jx, point.y + jy, point.z + jz)
    }

    /**
     * Sigmoid-based rotation step. Caps the per-tick rotation to
     * [maxSpeed], with a soft-start curve governed by [steepness].
     */
    private fun sigmoidStep(delta: Float, maxSpeed: Float, steepness: Float): Float {
        val sign = if (delta > 0) 1f else -1f
        val absDelta = abs(delta)
        val sigmoid = 2f / (1f + kotlin.math.exp(-steepness * absDelta / 30f)) - 1f
        val step = sign * min(absDelta, sigmoid * maxSpeed)
        val cap = if (player.isSprinting && intaveMaxYawDeltaPerTick < maxSpeed) {
            intaveMaxYawDeltaPerTick
        } else {
            maxSpeed
        }
        return step.coerceIn(-cap, cap)
    }

    private fun wrapDegrees(degrees: Float): Float {
        var d = degrees % 360f
        if (d >= 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private fun clampToBox(eye: Vec3d, box: net.minecraft.util.math.Box): Vec3d {
        return Vec3d(
            eye.x.coerceIn(box.minX, box.maxX),
            eye.y.coerceIn(box.minY, box.maxY),
            eye.z.coerceIn(box.minZ, box.maxZ)
        )
    }

    /**
     * Ray-traces the look ray against the 6 faces of [box] and returns
     * the nearest valid intersection point. Mirrors the implementation
     * in [AimPointFinder] but is exposed here so the rotation
     * computation can use the backtrack hitbox without going through
     * the AimPointFinder's entity-based API.
     */
    private fun rayTraceBoxFaces(
        origin: Vec3d,
        dir: Vec3d,
        box: net.minecraft.util.math.Box
    ): Vec3d? {
        val eps = 1.0e-9
        val ts = doubleArrayOf(
            (box.minX - origin.x) / if (dir.x != 0.0) dir.x else eps,
            (box.maxX - origin.x) / if (dir.x != 0.0) dir.x else eps,
            (box.minY - origin.y) / if (dir.y != 0.0) dir.y else eps,
            (box.maxY - origin.y) / if (dir.y != 0.0) dir.y else eps,
            (box.minZ - origin.z) / if (dir.z != 0.0) dir.z else eps,
            (box.maxZ - origin.z) / if (dir.z != 0.0) dir.z else eps
        )

        var bestT = Double.MAX_VALUE
        var bestPoint: Vec3d? = null
        for (t in ts) {
            if (t.isNaN() || t.isInfinite() || t < 0.0 || t >= bestT) continue
            val px = origin.x + dir.x * t
            val py = origin.y + dir.y * t
            val pz = origin.z + dir.z * t
            val e = 1.0e-6
            if (px < box.minX - e || px > box.maxX + e) continue
            if (py < box.minY - e || py > box.maxY + e) continue
            if (pz < box.minZ - e || pz > box.maxZ + e) continue
            bestT = t
            bestPoint = Vec3d(px, py, pz)
        }
        return bestPoint
    }

    /**
     * Critical-hit preparation.
     *
     * v4 modes:
     *  - **JUMP**: vanilla — does NOT force jumps. The player must be
     *    naturally airborne (jumped or fallen) for crits to apply.
     *  - **PACKET**: sends an `OnGroundOnly(false)` packet regardless of
     *    actual ground state. Effective on weak anticheats but flagged
     *    by Polar `FakeGround` and Intave `Critical`.
     *  - **DESYNC**: sends an `OnGroundOnly(false)` packet ONLY when the
     *    player is actually airborne (`fallDistance > 0` AND not on
     *    ground). This refreshes the server's view of the player's
     *    ground state to ensure the next attack registers as a crit.
     *    Safer than PACKET because we are NOT lying about ground state
     *    — we are merely re-asserting the truth. Still flagged on
     *    Polar if spammed, so use sparingly.
     *  - **NONE**: no critical hit forcing.
     */
    private fun prepareCriticalHit() {
        when (criticalsMode) {
            CriticalsMode.JUMP -> {
                // Vanilla — no packet forcing. Player must be airborne
                // naturally for crits to apply.
            }
            CriticalsMode.PACKET -> {
                if (rng.nextFloat() < packetCritChance && player.fallDistance < minFallDistance) {
                    network.sendPacket(PlayerMoveC2SPacket.OnGroundOnly(false, player.horizontalCollision))
                }
            }
            CriticalsMode.DESYNC -> {
                // Re-assert airborne state ONLY when actually airborne:
                // fallDistance > 0 (we have started falling) AND not on
                // ground (we have not landed yet). This avoids Polar's
                // `FakeGround` check, which looks for `OnGroundOnly=false`
                // packets sent while the player's position indicates
                // they are on the ground.
                if (player.fallDistance > minFallDistance && !player.isOnGround) {
                    network.sendPacket(PlayerMoveC2SPacket.OnGroundOnly(false, player.horizontalCollision))
                }
            }
            CriticalsMode.NONE -> {
                // No critical hit forcing.
            }
        }
    }

    private fun shouldAttackThisTick(): Boolean {
        val tickGap = player.age - lastAttackTick
        if (tickGap < intaveMinInterAttackTicks) return false
        if (!ignoreVanillaCooldown && mc.attackCooldown > 0) return false
        if (!clicker.shouldClickThisTick()) return false
        if (rng.nextFloat() * 100f < missRate) return false
        return true
    }

    /**
     * Performs the actual attack. v3 logic kept; v4 wraps it so we
     * can pass the backtrack hitbox to the reach check.
     */
    private fun attack(target: Entity, rotation: Rotation) {
        wasSprintingBeforeAttack = player.isSprinting

        // Polar: ALWAYS send STOP_SPRINTING before attack if sprinting.
        if (polarSprintReset && wasSprintingBeforeAttack) {
            network.sendPacket(
                ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.STOP_SPRINTING)
            )
            player.isSprinting = false
        }

        // Vanilla 1.8 order: swing BEFORE attack.
        player.swingHand(Hand.MAIN_HAND)

        // Attack packet
        network.sendPacket(PlayerInteractEntityC2SPacket.attack(target, player.isSneaking))

        mc.attackCooldown = 0
        player.resetLastAttackedTicks()
        lastAttackTick = player.age

        // Re-sprint if we were sprinting and KeepSprint is on
        if (keepSprint && wasSprintingBeforeAttack) {
            network.sendPacket(
                ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.START_SPRINTING)
            )
            player.isSprinting = true
        }
    }

    // ── Event handlers ──────────────────────────────────────────────────────

    @Suppress("unused")
    private val rotationUpdateHandler = handler<RotationUpdateEvent> {
        if (!shouldRun()) {
            currentTarget = null
            targetLockedTicks = 0
            return@handler
        }

        val target = selectTarget() ?: run {
            currentTarget = null
            targetLockedTicks = 0
            return@handler
        }

        val rotation = computeRotation(target)
        currentRotation = rotation

        RotationManager.setRotationTarget(
            RotationTarget(
                rotation = rotation,
                entity = target,
                processors = emptyList(),
                ticksUntilReset = 5,
                resetThreshold = 2f,
                considerInventory = false,
                movementCorrection = MovementCorrection.SILENT
            ),
            priority = Priority.IMPORTANT_FOR_USAGE_1,
            provider = this
        )
    }

    @Suppress("unused")
    private val tickHandler = tickHandler {
        if (!shouldRun()) return@tickHandler

        // Update backtrack history every tick (independent of target).
        BacktrackStore.tick(world.entities)

        // Advance clicker state.
        clicker.tick()
        // Keep clicker pattern in sync with the configured setting.
        clicker.pattern = clickPattern

        val target = currentTarget ?: return@tickHandler
        if (!shouldAttackThisTick()) return@tickHandler

        val rotation = currentRotation ?: return@tickHandler

        // ── Backtrack-aware hit determination ──
        // If we have a backtrack hit, the effective hitbox is the
        // backtrack hitbox. We compute the nearest point on that box
        // and check that the raytrace rotation actually intersects it.
        val backtrackHit = currentBacktrackHit
        val eyePos = player.eyePos

        if (backtrackHit != null) {
            // Reach check against the backtrack hitbox.
            val reachCap = sampleEffectiveReachCap()
            if (backtrackHit.eyeDistance > reachCap) return@tickHandler

            // Compute the aimpoint on the backtrack hitbox (same logic
            // as in computeRotation) so the wall check uses the exact
            // point we're attacking, not just the geometric nearest.
            val lookDir = rotation.directionVector
            val aimPointOnBox = findAimPointOnBox(eyePos, lookDir, backtrackHit.box)

            // Wall check: ensure line-of-sight to the aimpoint.
            if (!canSeePoint(eyePos, aimPointOnBox)) return@tickHandler

            // Rotation check: the configured rotation must point
            // close enough to the backtrack hitbox that the server's
            // raytrace will hit it. We use a small tolerance (~3°).
            val aimDir = aimPointOnBox.subtract(eyePos).normalize()
            val cosAngle = lookDir.dotProduct(aimDir).coerceIn(-1.0, 1.0)
            val angleDeg = Math.toDegrees(kotlin.math.acos(cosAngle))
            if (angleDeg > 3.0) return@tickHandler
        } else {
            // No backtrack: use the standard v3 raytrace-based check.
            val isFacing = facingEnemy(
                toEntity = target,
                rotation = rotation,
                range = range.toDouble(),
                wallsRange = wallRange.toDouble()
            )
            if (!isFacing) return@tickHandler

            val crosshairTarget = raytraceEntity(range.toDouble(), rotation) { it == target }
            val hitTarget = crosshairTarget?.entity ?: return@tickHandler
            if (hitTarget != target) return@tickHandler

            val hitDistance = crosshairTarget.pos.distanceTo(eyePos)
            val reachCap = sampleEffectiveReachCap()
            if (hitDistance > reachCap) return@tickHandler
        }

        prepareCriticalHit()
        attack(target, rotation)
        clicker.onClicked()
    }

    /**
     * Samples the effective reach cap for this attack.
     *
     * If [reachVariability] is enabled, samples from a Gaussian
     * distribution centered at 2.85 with σ=0.08, clamped to [2.7, 3.0].
     * This produces a more human-like distribution of hit distances
     * than a uniform [2.7, 3.0] sample, which Polar `Reach-C` can
     * fingerprint.
     */
    private fun sampleEffectiveReachCap(): Double {
        if (!reachVariability) return 3.0
        val gaussian = rng.nextGaussian() * 0.08 + 2.85
        return gaussian.coerceIn(2.7, 3.0)
    }

    /**
     * Returns true if there are no opaque blocks between [from] and [to].
     * Uses the world raycast (same as `facingEnemy`'s wall check).
     */
    private fun canSeePoint(from: Vec3d, to: Vec3d): Boolean {
        val world = mc.world ?: return false
        val context = net.minecraft.world.RaycastContext(
            from, to,
            net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
            net.minecraft.world.RaycastContext.FluidHandling.NONE,
            mc.player ?: return false
        )
        return world.raycast(context)?.type == net.minecraft.util.hit.HitResult.Type.MISS
    }

    @Suppress("unused")
    private val entityRemoveHandler = handler<WorldEntityRemoveEvent> { event ->
        // v4: clear stale NPC filter cache entries when entities leave
        // the world. Prevents unbounded memory growth on long sessions
        // with high entity churn.
        NpcFilter.onEntityRemoved(event.entity)
    }

    @Suppress("unused")
    private val sprintHandler = handler<SprintEvent> { event ->
        // v4: only block sprint on the tick of an actual attack, not
        // continuously while a target is visible. v3 blocked sprint
        // for as long as any target existed, which produced visible
        // start-stop-start-stop behavior to spectators.
        //
        // We approximate "tick of an actual attack" by checking whether
        // we are within the inter-attack window.
        if (!keepSprint && currentTarget != null) {
            val tickGap = player.age - lastAttackTick
            if (tickGap <= 1) {
                if (event.source == SprintEvent.Source.MOVEMENT_TICK ||
                    event.source == SprintEvent.Source.INPUT) {
                    event.sprint = false
                }
            }
        }
    }

    // ── Enums ───────────────────────────────────────────────────────────────

    enum class ClickPattern(override val choiceName: String) : NamedChoice {
        BUTTERFLY("Butterfly"),
        BINOMIAL("Binomial"),
        NORMAL("Normal"),
    }

    enum class TargetPriority(override val choiceName: String) : NamedChoice {
        DISTANCE("Distance"),
        HEALTH("Health"),
        DIRECTION("Direction"),
        HURT_TIME("HurtTime"),
    }

    enum class CriticalsMode(override val choiceName: String) : NamedChoice {
        JUMP("Jump"),
        PACKET("Packet"),
        DESYNC("Desync"),
        NONE("None"),
    }

    enum class AimpointMode(override val choiceName: String) : NamedChoice {
        NEAREST_POINT("NearestPoint"),
        TORSO_SPHERE("TorsoSphere"),
        CENTER("Center");

        fun toFinderMode(): AimPointFinder.Mode = when (this) {
            NEAREST_POINT -> AimPointFinder.Mode.NEAREST_POINT
            TORSO_SPHERE -> AimPointFinder.Mode.TORSO_SPHERE
            CENTER -> AimPointFinder.Mode.CENTER
        }
    }
}
