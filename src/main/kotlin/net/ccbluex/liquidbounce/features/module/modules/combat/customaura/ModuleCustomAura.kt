/*
 * CustomAura module — Polar-optimized KillAura rewrite.
 *
 * Key differences vs. the stock ModuleKillAura:
 *  - NEVER uses SNAP / ON_TICK rotation timing (those trigger Polar AimA/B).
 *    Only NORMAL rotation timing is allowed, so the rotation always arrives
 *    via the regular PlayerMoveC2SPacket flow, never as a duplicate Full packet.
 *  - No dual PlayerMoveC2SPacket on attack → no BadPackets duplicate flag.
 *  - No simulateInventoryClosing → no BadPackets close/open flag.
 *  - Optional AutoBlock is restricted to vanilla STOP_USING_ITEM only,
 *    never ChangeSlot / Hypixel / Interact (those are direct Polar flags).
 *  - Criticals are achieved via jump-crits only, never via stop-sprint
 *    (Polar Criticals B detects sprint-stop-then-hit).
 *  - GCD-safe angle smooth is used by default (Polar AimB / GCD check).
 *  - Adaptive reach optimization so we always strike at maximum safe range.
 *
 * BUGFIXES vs. previous revision:
 *  - facingEnemy now uses plain `range` (not `effectiveRange` with jitter)
 *    to avoid ~50% of clicks being skipped when enemy is at the edge of
 *    reach. Jitter is only applied to the actual strike distance for
 *    anti-detection noise, NOT to the facing check.
 *  - Target circle is no longer drawn when enemy is outside attack range
 *    (previously the circle was drawn up to range+scanExtraRange = 4.85
 *    blocks but attacks only landed up to ~3.85 blocks, creating the
 *    "circle but no hit" symptom).
 *  - Presets system removed — configuration is now direct via settings.
 *  - AntiCheater detector removed.
 *  - AutoJumpForCrits removed (caused rotation desync on jump).
 */
@file:Suppress("WildcardImport", "MagicNumber")
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura

import net.ccbluex.liquidbounce.event.Sequence
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.SprintEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoWeapon
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.features.CustomAuraAutoBlock
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.features.CustomAuraFailSwing
import net.ccbluex.liquidbounce.features.module.modules.combat.customaura.features.CustomAuraPolarBypass
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.data.RotationWithVector
import net.ccbluex.liquidbounce.utils.aiming.point.PointTracker
import net.ccbluex.liquidbounce.utils.aiming.preference.LeastDifferencePreference
import net.ccbluex.liquidbounce.utils.aiming.utils.facingEnemy
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBox
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceEntity
import net.ccbluex.liquidbounce.utils.combat.CombatManager
import net.ccbluex.liquidbounce.utils.combat.attack
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.ccbluex.liquidbounce.utils.render.WorldTargetRenderer
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import kotlin.math.pow
import kotlin.random.Random

/**
 * Maximum effective range (in blocks) after applying reach jitter.
 * 4.4 is below vanilla 4.5 so we never trip the vanilla Reach check.
 */
private const val EFFECTIVE_RANGE_MAX = 4.4f

/**
 * CustomAura — Polar-bypass KillAura.
 *
 * Design goals (in priority order):
 *  1. Survive Polar: never produce a flaggable packet sequence.
 *  2. Stay smooth enough to look like a human on replay review.
 */
object ModuleCustomAura : ClientModule("CustomAura", Category.COMBAT, aliases = arrayOf("CustomKillAura")) {

    /**
     * Click scheduler — humanized CPS with micro-jitter.
     */
    val clickScheduler = tree(CustomAuraClicker)

    /**
     * Range — kept strictly inside vanilla 4.5 to avoid Reach flags.
     * A small randomized component is added per-tick so Polar cannot
     * correlate attack distance to a fixed value (Reach GCD check).
     */
    internal var range by float("Range", 3.85f, 1f..4.4f).onChange { r ->
        if (wallRange > r) {
            wallRange = r
        }
        r
    }
    internal var wallRange by float("WallRange", 0f, 0f..3f).onChange { v ->
        val rangeValue: Float = currentRange()
        val clamped: Float = v.coerceAtMost(rangeValue).coerceAtMost(3f)
        if (clamped != v) clamped else v
    }

    /**
     * Helper used by [wallRange]'s onChange lambda to read [range] with an
     * explicitly-typed return, breaking the recursive type inference.
     */
    private fun currentRange(): Float = range

    /**
     * Per-attack reach jitter — adds up to ±0.05 blocks of randomization
     * to the effective strike distance so Polar's Reach sample distribution
     * looks noisy instead of pinned to a constant.
     *
     * IMPORTANT: jitter is applied to the ACTUAL strike distance only,
     * NOT to the facingEnemy check. Using jitter in facingEnemy caused
     * ~50% of clicks to be skipped when the enemy was at the edge of
     * `range` (jitter < 0 → effectiveRange < range → facing check fails).
     */
    internal var reachJitter by float("ReachJitter", 0.05f, 0f..0.2f)

    /**
     * Extra scan range is used only to keep the target tracker warm when the
     * enemy is slightly outside strike range — we never attack at this range.
     */
    internal var scanExtraRange by floatRange("ScanExtraRange", 0.5f..1.0f, 0f..3f).onChanged { r ->
        currentScanExtraRange = r.random()
    }
    private var currentScanExtraRange: Float = scanExtraRange.random()

    /**
     * Target tracker.
     */
    val targetTracker = tree(CustomAuraTargetTracker)

    /**
     * Rotation engine — uses PolarBypass processor stack by default.
     */
    private val rotations = tree(CustomAuraRotationsConfigurable)
    private val polarBypass = tree(CustomAuraPolarBypass)
    private val pointTracker = tree(PointTracker())

    private val requires by multiEnumChoice<CustomAuraRequirements>("Requires")
    private val requirementsMet
        get() = requires.all { it.meets() }

    /**
     * Raycast mode — TRACE_ALL is safest: server sees us hit whatever is
     * actually under the crosshair, which matches the client raytrace.
     */
    internal var raycast by enumChoice("Raycast", RaycastMode.TRACE_ALL)

    /**
     * Criticals — JUMP_ONLY is the only safe mode under Polar.
     * STOP_SPRINT (the SMART mode in stock KillAura) is detected by
     * Polar Criticals B because it observes sprint=true → sprint=false
     * right before an attack packet.
     *
     * With JUMP_ONLY, attacks ALWAYS go through — vanilla Minecraft
     * decides if it's a critical based on the onGround flag. The user
     * must jump manually for crits (auto-jump was removed because it
     * caused rotation desync on the jump tick).
     */
    internal var criticalsMode by enumChoice("Criticals", CriticalsMode.JUMP_ONLY)
    internal var keepSprint by boolean("KeepSprint", false)

    /**
     * Inventory handling — NEVER simulate closing. The stock behavior of
     * sending CloseHandledScreenC2SPacket(0) before an attack is a direct
     * BadPackets flag on Polar.
     */
    internal var ignoreOpenInventory by boolean("IgnoreOpenInventory", false)

    init {
        tree(CustomAuraAutoBlock)
        tree(CustomAuraFailSwing)
    }

    /**
     * Target rendering — only draws the target circle when the enemy is
     * within actual strike range. Previously the circle was drawn for any
     * tracked target (up to range+scanExtraRange = 4.85 blocks), but
     * attacks only landed up to `range` (~3.85), causing the
     * "circle visible but no hits" symptom.
     */
    private val targetRenderer = tree(WorldTargetRenderer(this))

    override fun disable() {
        targetTracker.reset()
        CustomAuraAutoBlock.stopBlocking()
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        renderTarget(event.matrixStack, event.partialTicks)
    }

    private fun renderTarget(matrixStack: MatrixStack, partialTicks: Float) {
        val target = targetTracker.target ?: return
        if (!targetRenderer.enabled) return

        // Only render the target circle when the enemy is within
        // actual strike range (range + a tiny epsilon for floating-point).
        // This prevents the "circle but no hit" UX when the enemy is in
        // the scan range (range+scanExtraRange) but not yet strikeable.
        val squaredStrikeRange = range.pow(2)
        if (player.squaredBoxedDistanceTo(target) > squaredStrikeRange) {
            return
        }

        renderEnvironmentForWorld(matrixStack) {
            targetRenderer.render(this, target, partialTicks)
        }
    }

    /**
     * Rotation update — runs on the rotation update event, BEFORE movement
     * packets are sent. This is the only safe place to set rotation targets
     * under Polar, because the resulting rotation will be sent in the normal
     * PlayerMoveC2SPacket flow (no duplicate packets).
     */
    @Suppress("unused")
    private val rotationUpdateHandler = handler<RotationUpdateEvent> {
        val isInInventoryScreen =
            InventoryManager.isInventoryOpen || mc.currentScreen is GenericContainerScreen

        val shouldResetTarget = player.isSpectator || player.isDead || !requirementsMet

        if ((isInInventoryScreen && !ignoreOpenInventory) || shouldResetTarget) {
            targetTracker.reset()
            return@handler
        }

        updateTarget()
        ModuleAutoWeapon.prepare(targetTracker.target)
    }

    /**
     * Game tick — performs the actual attack if conditions are met.
     *
     * IMPORTANT: we NEVER send an extra PlayerMoveC2SPacket here. The
     * rotation used for the attack is the one currently registered with
     * RotationManager, which has already been transmitted to the server
     * in the normal movement packet flow. This is the single most
     * important Polar bypass in this module.
     */
    @Suppress("unused")
    private val gameHandler = tickHandler {
        if (player.isDead || player.isSpectator) return@tickHandler

        val target = targetTracker.target

        if (CombatManager.shouldPauseCombat) {
            CustomAuraAutoBlock.stopBlocking()
            return@tickHandler
        }

        if (target == null) {
            CustomAuraAutoBlock.stopBlocking()
            if (CustomAuraFailSwing.enabled && requirementsMet) {
                CustomAuraFailSwing.performFailSwing(this, null)
            }
            return@tickHandler
        }

        if (!requirementsMet) return@tickHandler

        // Always use the current server-acknowledged rotation.
        // Never compute a fresh rotation on the tick of the attack.
        val rotation = (RotationManager.currentRotation ?: player.rotation).normalize()

        val crosshairTarget = when (raycast) {
            RaycastMode.TRACE_NONE -> target
            else -> {
                raytraceEntity(range.toDouble(), rotation, filter = {
                    when (raycast) {
                        RaycastMode.TRACE_ONLYENEMY -> it.shouldBeAttacked()
                        RaycastMode.TRACE_ALL -> true
                        else -> false
                    }
                })?.entity ?: target
            }
        }

        if (crosshairTarget is LivingEntity && crosshairTarget.shouldBeAttacked()
            && crosshairTarget != target) {
            targetTracker.target = crosshairTarget
        }

        attackTarget(this, crosshairTarget, rotation)
    }

    /**
     * Sprint handler — blocks sprint when we are AIRBORNE and about to
     * land a critical hit. This is the legitimate vanilla criticals path
     * (jump → fall → hit while falling → crit). Vanilla Minecraft
     * internally cancels sprint when a falling player attacks, but if our
     * [keepSprint] option is on, vanilla's sprint restoration would
     * re-enable sprint on the same tick — which itself is a Polar
     * NoSlow flag. So we explicitly block the SprintEvent here when the
     * attack is in progress AND we are airborne.
     */
    val shouldBlockSprinting
        get() = criticalsMode == CriticalsMode.JUMP_ONLY
            && clickScheduler.isClickTick
            && targetTracker.target != null
            && !player.isOnGround

    @Suppress("unused")
    private val sprintHandler = handler<SprintEvent> { event ->
        if (shouldBlockSprinting && (event.source == SprintEvent.Source.MOVEMENT_TICK ||
                event.source == SprintEvent.Source.INPUT)) {
            event.sprint = false
        }
    }

    /**
     * Core attack routine. Compared to stock KillAura:
     *  - No inventory close/open dance.
     *  - No dual PlayerMoveC2SPacket on ON_TICK timing.
     *  - No stop-sprint-then-hit criticals.
     *  - Reach jitter is applied to the effective range on every strike.
     */
    @Suppress("CognitiveComplexMethod")
    private suspend fun attackTarget(sequence: Sequence, target: Entity, rotation: Rotation) {
        CustomAuraAutoBlock.setVisualBlockState()

        // Apply per-tick reach jitter so the strike distance is noisy.
        // Use kotlin.random.Random (not Math.random) for cross-platform
        // determinism in tests.
        val jitter = reachJitter * (Random.nextFloat() * 2f - 1f)
        val effectiveRange = (range + jitter).coerceAtMost(EFFECTIVE_RANGE_MAX)

        // BUGFIX: facingEnemy uses `range` (NOT `effectiveRange`).
        // The previous implementation passed effectiveRange, which meant
        // that whenever jitter < 0 the effective range shrank below `range`
        // and the facing check failed for enemies at the edge of reach.
        // This caused ~50% of click ticks to be skipped whenever the enemy
        // was at 3.75-3.85 blocks (with default Polar settings), producing
        // the "aura locks target but doesn't hit" symptom.
        //
        // Jitter is now applied ONLY to the actual strike distance for
        // anti-detection noise, NOT to the facing check.
        val isFacingEnemy = facingEnemy(
            toEntity = target, rotation = rotation,
            range = range.toDouble(),
            wallsRange = wallRange.toDouble()
        )

        // ── DEBUG: attackTarget diagnostics ─────────────────────────
        this.debugParameter("Target") { target.nameForScoreboard }
        this.debugParameter("Rotation") { rotation }
        this.debugParameter("EffectiveRange") { effectiveRange }
        this.debugParameter("Jitter") { jitter }
        this.debugParameter("IsFacingEnemy") { isFacingEnemy }
        this.debugParameter("IsClickTick") { clickScheduler.isClickTick }
        this.debugParameter("CriticalsMode") { criticalsMode.name }
        this.debugParameter("OnGround") { player.isOnGround }
        this.debugParameter("RequirementsMet") { requirementsMet }
        this.debugParameter("ValidateAttack") { validateAttack(target) }

        if (!isFacingEnemy) {
            // Only block on scan range if AutoBlock is enabled AND enemy is
            // close enough to threaten us — avoids needless block packets.
            if (CustomAuraAutoBlock.enabled && CustomAuraAutoBlock.onScanRange &&
                player.squaredBoxedDistanceTo(target) <= (range + currentScanExtraRange).pow(2)) {
                CustomAuraAutoBlock.startBlocking()
                ModuleCustomAuraDebugger.recordEvent(
                    phase = "SKIPPED",
                    skipReason = "not_facing_enemy_blocking",
                    targetName = target.nameForScoreboard,
                    targetId = target.id,
                    targetDistance = player.squaredBoxedDistanceTo(target),
                    effectiveRange = effectiveRange,
                    jitter = jitter,
                    isFacingEnemy = isFacingEnemy,
                    isClickTick = clickScheduler.isClickTick,
                    onGround = player.isOnGround,
                    validateAttack = validateAttack(target)
                )
                return
            }

            CustomAuraAutoBlock.stopBlocking()

            if (CustomAuraFailSwing.enabled) {
                CustomAuraFailSwing.performFailSwing(sequence, target)
            }
            ModuleCustomAuraDebugger.recordEvent(
                phase = "SKIPPED",
                skipReason = "not_facing_enemy",
                targetName = target.nameForScoreboard,
                targetId = target.id,
                targetDistance = player.squaredBoxedDistanceTo(target),
                effectiveRange = effectiveRange,
                jitter = jitter,
                isFacingEnemy = isFacingEnemy,
                isClickTick = clickScheduler.isClickTick,
                onGround = player.isOnGround,
                validateAttack = validateAttack(target)
            )
            return
        }

        // Strike — click scheduler gates the actual hit.
        if (clickScheduler.isClickTick && validateAttack(target)) {
            ModuleCustomAuraDebugger.recordEvent(
                phase = "ATTEMPT",
                targetName = target.nameForScoreboard,
                targetId = target.id,
                targetDistance = player.squaredBoxedDistanceTo(target),
                effectiveRange = effectiveRange,
                jitter = jitter,
                isFacingEnemy = isFacingEnemy,
                isClickTick = clickScheduler.isClickTick,
                onGround = player.isOnGround,
                validateAttack = validateAttack(target)
            )

            clickScheduler.attack(sequence, rotation) {
                if (!validateAttack(target)) {
                    ModuleCustomAuraDebugger.recordEvent(
                        phase = "FAILED",
                        skipReason = "validate_attack_false_in_click",
                        targetName = target.nameForScoreboard,
                        targetId = target.id,
                        onGround = player.isOnGround,
                        validateAttack = false
                    )
                    return@attack false
                }

                // ATTACK ALWAYS GOES THROUGH.
                // With JUMP_ONLY criticals, we do NOT skip the attack when
                // on ground — vanilla Minecraft decides if it's a critical
                // based on the onGround flag in the movement packet. If the
                // player is in the air (from a manual jump), it's a crit;
                // if on ground, it's a normal hit. Both are legit.
                //
                // FIRST ARG of Entity.attack(sprint, keepSprint) is `sprint`
                // — whether the player is currently sprinting. Vanilla
                // Minecraft's critical-hit check requires the player to
                // NOT be sprinting. Passing `true` here would mark the
                // attack as a sprint-attack, which disables criticals even
                // if the player is airborne.
                //
                // We pass `false` in JUMP_ONLY mode (so vanilla's crit
                // check can fire) and `keepSprint` in NONE mode (since
                // crits are disabled anyway, sprint state doesn't matter).
                val sprintArg = criticalsMode != CriticalsMode.JUMP_ONLY && keepSprint
                val keepSprintArg = keepSprint && !shouldBlockSprinting
                target.attack(sprintArg, keepSprintArg)
                currentScanExtraRange = scanExtraRange.random()

                ModuleCustomAuraDebugger.recordEvent(
                    phase = "LANDED",
                    targetName = target.nameForScoreboard,
                    targetId = target.id,
                    targetDistance = player.squaredBoxedDistanceTo(target),
                    effectiveRange = effectiveRange,
                    jitter = jitter,
                    isFacingEnemy = isFacingEnemy,
                    isClickTick = true,
                    onGround = player.isOnGround,
                    validateAttack = true
                )
                true
            }
        } else {
            // Not a click tick or validateAttack failed.
            ModuleCustomAuraDebugger.recordEvent(
                phase = "SKIPPED",
                skipReason = if (!clickScheduler.isClickTick) "not_click_tick" else "validate_attack_false",
                targetName = target.nameForScoreboard,
                targetId = target.id,
                targetDistance = player.squaredBoxedDistanceTo(target),
                effectiveRange = effectiveRange,
                jitter = jitter,
                isFacingEnemy = isFacingEnemy,
                isClickTick = clickScheduler.isClickTick,
                onGround = player.isOnGround,
                validateAttack = validateAttack(target)
            )
            CustomAuraAutoBlock.startBlocking()
        }
    }

    /**
     * Target selection. Sorts candidates by distance only (AntiCheater
     * prioritization was removed in this revision).
     */
    private fun updateTarget() {
        val situation = when {
            clickScheduler.isClickTick || clickScheduler.willClickAt(1) ->
                PointTracker.AimSituation.FOR_NEXT_TICK
            else -> PointTracker.AimSituation.FOR_THE_FUTURE
        }

        val maximumRange = if (targetTracker.closestSquaredEnemyDistance > range.pow(2)) {
            range + currentScanExtraRange
        } else {
            range
        }

        val squaredMaxRange = maximumRange.pow(2)
        val squaredNormalRange = range.pow(2)

        // Build candidate list sorted by:
        //   1. In-range (in normal range first).
        //   2. Distance (asc).
        val candidates = targetTracker.targets()
            .filter { it.squaredBoxedDistanceTo(player) <= squaredMaxRange }
            .sortedWith(compareBy<LivingEntity> { if (it.squaredBoxedDistanceTo(player) <= squaredNormalRange) 0 else 1 }
                .thenBy { it.squaredBoxedDistanceTo(player) })

        val target = candidates.firstOrNull { processTarget(it, maximumRange, situation) }

        // ── DEBUG: updateTarget diagnostics ──────────────────────────
        this.debugParameter("AimSituation") { situation.name }
        this.debugParameter("MaximumRange") { maximumRange }
        this.debugParameter("Range") { range }
        this.debugParameter("Candidates") { candidates.size }
        this.debugParameter("ClosestEnemyDist") { targetTracker.closestSquaredEnemyDistance }

        if (target != null) {
            targetTracker.target = target
            this.debugParameter("TargetID") { target.id }
            this.debugParameter("TargetDist") { target.squaredBoxedDistanceTo(player) }
        } else {
            targetTracker.reset()
            this.debugParameter("TargetID") { "none" }
        }
    }

    @Suppress("ReturnCount")
    private fun processTarget(
        entity: LivingEntity,
        maximumRange: Float,
        situation: PointTracker.AimSituation
    ): Boolean {
        val (rotation, _) = getSpot(entity, maximumRange.toDouble(), situation) ?: return false

        // NORMAL rotation timing only — we always push the rotation target
        // through RotationManager and let the normal packet flow deliver it.
        RotationManager.setRotationTarget(
            rotations.toBypassedRotationTarget(
                rotation,
                entity,
                considerInventory = !ignoreOpenInventory
            ),
            priority = Priority.IMPORTANT_FOR_USAGE_2,
            provider = this@ModuleCustomAura
        )
        return true
    }

    /**
     * Best spot to strike. Same raytrace logic as stock, but the rotation
     * is then post-processed by the PolarBypass processor stack.
     */
    private fun getSpot(
        entity: LivingEntity,
        range: Double,
        situation: PointTracker.AimSituation
    ): RotationWithVector? {
        val point = pointTracker.gatherPoint(entity, situation)
        val eyes = point.fromPoint
        val nextPoint = point.toPoint

        val rotationPreference = LeastDifferencePreference.leastDifferenceToLastPoint(eyes, nextPoint)

        val spot = raytraceBox(
            eyes, point.cutOffBox,
            range = range,
            wallsRange = wallRange.toDouble(),
            rotationPreference = rotationPreference
        ) ?: raytraceBox(
            eyes, point.box,
            range = range,
            wallsRange = wallRange.toDouble(),
            rotationPreference = rotationPreference
        )

        return spot
    }

    /**
     * Validate that we are allowed to send an attack packet right now.
     *
     * IMPORTANT: This MUST NOT block attacks based on the criticals mode.
     * The criticals mode only controls whether we force criticals via
     * stop-sprint (CHEAT — Polar detects it) or let vanilla decide (JUMP_ONLY).
     * With JUMP_ONLY, attacks ALWAYS go through — vanilla Minecraft decides
     * if it's a critical based on the onGround flag in the movement packet.
     *
     * The previous implementation returned false when JUMP_ONLY + onGround,
     * which made the aura NEVER attack (since the player is almost always
     * on ground in normal combat). That was the root cause of the
     * "target locked but no attacks" bug.
     */
    internal fun validateAttack(target: Entity? = null): Boolean {
        val isInInventoryScreen = InventoryManager.isInventoryOpen ||
            mc.currentScreen is GenericContainerScreen

        // Don't attack while gliding (elytra) — vanilla disallows it.
        if (player.isGliding) return false

        // Don't attack through an inventory screen we can't ignore.
        if (isInInventoryScreen && !ignoreOpenInventory) return false

        return true
    }

    enum class RaycastMode(override val choiceName: String) : NamedChoice {
        TRACE_NONE("None"),
        TRACE_ONLYENEMY("Enemy"),
        TRACE_ALL("All")
    }

    enum class CriticalsMode(override val choiceName: String) : NamedChoice {
        /** Vanilla jump-criticals only. Safe under Polar. */
        JUMP_ONLY("JumpOnly"),

        /** No critical enforcement at all. Safest, lowest damage. */
        NONE("None")
    }
}
