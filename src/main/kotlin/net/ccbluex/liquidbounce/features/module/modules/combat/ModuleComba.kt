/*
 * Comba module for LiquidBounce 0.31
 *
 * Улучшенный комбо-ассистент с pre-hit timing, packet-level sprint reset,
 * dual mode (KillAura / Manual / Both) и адаптивным выбором тапа.
 *
 * Архитектура основана на ModuleSuperKnockback, но значительно расширена:
 *   - 4 метода тапа: S/W/Strafe/Shift
 *   - 3 тайминга: Pre-Hit / On-Hit / Post-Hit
 *   - 3 источника: KillAura / Manual / Both
 *   - Packet-level sprint reset через ClientCommandC2SPacket
 *   - Пинг-адаптация таймингов
 *   - CritSync с корректным fall detection
 *   - Strafe с учётом препятствий
 *   - Гауссов рандом вместо ±1 тик
 *   - Anti-stuck защита
 */
@file:Suppress("MagicNumber", "WildcardImport", "UnusedPrivateProperty")
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.event.events.*
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.sequenceHandler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.ModuleCriticals
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.utils.client.*
import net.ccbluex.liquidbounce.utils.entity.ping
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.CRITICAL_MODIFICATION
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.ccbluex.liquidbounce.utils.math.minus
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode.START_SPRINTING
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode.STOP_SPRINTING
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ModuleComba : ClientModule(
    "Comba",
    Category.COMBAT,
    aliases = arrayOf("ComboAssist", "SprintReset")
) {

    // ==================== РЕЖИМ ТАПА ====================
    private val mode by enumChoice("Mode", TapMode.AUTO)

    /**
     * Источник триггера:
     *   KILLAURA  — только хуки от KillAura
     *   MANUAL    — только AttackEntityEvent (ручные клики)
     *   BOTH      — реагировать на оба источника
     */
    private val triggerSource by enumChoice("TriggerSource", TriggerSource.BOTH)

    /**
     * Тайминг относительно удара:
     *   PRE_HIT  — тап за N тиков до удара (рекомендуется)
     *   ON_HIT   — синхронно с ударом
     *   POST_HIT — после удара (классика)
     */
    private val timingMode by enumChoice("Timing", TimingMode.PRE_HIT)
    private val preHitTicks by int("PreHitTicks", 2, 1..5, "ticks")

    // ==================== ТАЙМИНГИ ====================
    private val tapDuration by int("TapDuration", 2, 1..6, "ticks")
    private val tapCooldown by int("TapCooldown", 1, 0..10, "ticks")
    private val randomizeTiming by boolean("RandomizeTiming", true)
    private val randomizationStrength by float("RandomStrength", 1.5f, 0f..4f)

    // ==================== ПИНГ-АДАПТАЦИЯ ====================
    private val pingAdaptation by boolean("PingAdaptation", true)

    // ==================== УСЛОВИЯ ====================
    private val onlyOnGround by boolean("OnlyOnGround", true)
    private val onlyWhileSprinting by boolean("OnlyWhileSprinting", true)
    private val onlyPlayers by boolean("OnlyPlayers", true)
    private val minRange by float("MinRange", 1f, 0f..6f)
    private val maxRange by float("MaxRange", 4f, 0f..6f)

    // ==================== АВТО-РЕЖИМ ====================
    private val distanceThreshold by float("DistanceThreshold", 2.5f, 1f..5f)
    private val adaptiveMode by boolean("Adaptive", true)

    // ==================== STRAFE ====================
    private val strafeDirection by enumChoice("StrafeDirection", StrafeMode.AUTO)
    private val strafeCollisionAware by boolean("StrafeCollisionAware", true)

    // ==================== CRITSYNC ====================
    private val critSync by boolean("CritSync", true)
    private val attackCooldownSync by boolean("AttackCooldownSync", true)
    private val cooldownThreshold by float("CooldownThreshold", 0.6f, 0.1f..1f)

    // ==================== SPRINT RESET ====================
    /**
     * Packet-level reset через ClientCommandC2SPacket — менее палевный.
     */
    private val packetSprintReset by boolean("PacketSprintReset", true)
    private val sprintResetDelay by int("SprintResetDelay", 1, 0..3, "ticks")

    // ==================== COMBO COUNTER ====================
    private val comboCounterEnabled by boolean("ComboCounter", true)
    private val comboTimeout by int("ComboTimeout", 40, 10..80, "ticks")

    // ==================== ИНТЕГРАЦИЯ ====================
    private val killauraIntegration by boolean("KillAuraIntegration", true)
    private val antiStuck by boolean("AntiStuck", true)

    // ==================== STATE ====================
    private var isTapping = false
    private var currentTapType = TapType.NONE
    private var tapTicksRemaining = 0
    private var cooldownTicksRemaining = 0
    private var sprintResetTicksRemaining = 0
    private var pendingSprintRestore = false
    private var sprintWasStoppedViaPacket = false

    // Сохранённое состояние клавиш
    private var wasForward = false
    private var wasBackward = false
    private var wasLeft = false
    private var wasRight = false
    private var wasSneaking = false

    // Pre-hit планирование
    private var preHitCountdown = -1
    private var plannedTarget: Entity? = null

    // Combo counter
    private var comboCount = 0
    private var ticksSinceLastHit = 0
    private var lastTarget: Entity? = null

    // Strafe memory
    private var lastStrafeLeft = false
    private var strafeStreak = 0

    // Adaptive
    private var lastTargetVelocity = 0.0

    // Anti-stuck
    private var stuckCounter = 0

    // RNG — используем java.util.Random, т.к. есть nextGaussian()
    private val rng = Random()

    private enum class TapType {
        NONE, S_TAP, W_TAP, STRAFE_LEFT, STRAFE_RIGHT, SHIFT_TAP
    }

    enum class TapMode(override val choiceName: String) : NamedChoice {
        AUTO("Auto"),
        S_TAP("S-Tap"),
        W_TAP("W-Tap"),
        STRAFE("Strafe"),
        SHIFT_TAP("Shift-Tap")
    }

    enum class TriggerSource(override val choiceName: String) : NamedChoice {
        KILLAURA("KillAura"),
        MANUAL("Manual"),
        BOTH("Both")
    }

    enum class TimingMode(override val choiceName: String) : NamedChoice {
        PRE_HIT("Pre-Hit"),
        ON_HIT("On-Hit"),
        POST_HIT("Post-Hit")
    }

    enum class StrafeMode(override val choiceName: String) : NamedChoice {
        AUTO("Auto"),
        LEFT("Left"),
        RIGHT("Right"),
        RANDOM("Random")
    }

    // ==================== ЖИЗНЕННЫЙ ЦИКЛ ====================

    override fun disable() {
        if (isTapping) restoreMovement()
        if (pendingSprintRestore) restoreSprint()
        resetState()
    }

    private fun resetState() {
        isTapping = false
        currentTapType = TapType.NONE
        tapTicksRemaining = 0
        cooldownTicksRemaining = 0
        sprintResetTicksRemaining = 0
        pendingSprintRestore = false
        sprintWasStoppedViaPacket = false
        wasForward = false
        wasBackward = false
        wasLeft = false
        wasRight = false
        wasSneaking = false
        preHitCountdown = -1
        plannedTarget = null
        comboCount = 0
        ticksSinceLastHit = 0
        lastTarget = null
        lastStrafeLeft = false
        strafeStreak = 0
        lastTargetVelocity = 0.0
        stuckCounter = 0
    }

    // ==================== ОБРАБОТЧИКИ ====================

    /**
     * Post-Hit / On-Hit обработка AttackEntityEvent (ручные клики или пост-Hit KillAura)
     */
    @Suppress("unused")
    private val attackHandler = sequenceHandler<AttackEntityEvent> { event ->
        if (event.isCancelled) return@sequenceHandler

        val target = event.entity
        if (target !is LivingEntity) return@sequenceHandler

        // Combo counter обновляется всегда
        handleComboCounter(target)

        // В Pre-Hit режиме тап уже случился — не обрабатываем
        if (timingMode == TimingMode.PRE_HIT) return@sequenceHandler

        // Только Manual или Both
        if (triggerSource == TriggerSource.KILLAURA) return@sequenceHandler

        handleHitInternal(target)
    }

    /**
     * Главный tick handler — обрабатывает state machine
     */
    @Suppress("unused", "ComplexCondition")
    private val tickHandler = tickHandler {
        if (player.isDead || player.isSpectator) return@tickHandler

        // Anti-stuck
        if (antiStuck) {
            if (isTapping) {
                stuckCounter++
                if (stuckCounter > 100) {
                    restoreMovement()
                    isTapping = false
                    tapTicksRemaining = 0
                    if (pendingSprintRestore) restoreSprint()
                    stuckCounter = 0
                }
            } else {
                stuckCounter = 0
            }
        }

        // Combo timeout
        if (comboCounterEnabled) {
            ticksSinceLastHit++
            if (ticksSinceLastHit > comboTimeout) {
                comboCount = 0
            }
        }

        // Cooldown
        if (cooldownTicksRemaining > 0) cooldownTicksRemaining--

        // Sprint restore
        if (pendingSprintRestore) {
            if (sprintResetTicksRemaining > 0) {
                sprintResetTicksRemaining--
            } else {
                restoreSprint()
            }
        }

        // Pre-hit планирование
        if (preHitCountdown >= 0) {
            val target = plannedTarget
            if (target != null && target.isAlive && !target.isRemoved) {
                if (preHitCountdown == 0 && canTapNow(target)) {
                    handleHitInternal(target)
                }
                preHitCountdown++
                if (preHitCountdown > preHitTicks + 2) {
                    preHitCountdown = -1
                    plannedTarget = null
                }
            } else {
                preHitCountdown = -1
                plannedTarget = null
            }
        }

        // Текущий тап
        if (isTapping) {
            tapTicksRemaining--
            if (tapTicksRemaining <= 0) {
                restoreMovement()
                isTapping = false
                var cd = tapCooldown
                if (randomizeTiming) {
                    val noise = (rng.nextGaussian() * randomizationStrength).roundToInt()
                    cd = (cd + noise).coerceAtLeast(0)
                }
                cooldownTicksRemaining = cd
            }
        }

        // Интеграция с KillAura: проверяем, планирует ли KillAura удар
        if (killauraIntegration && ModuleKillAura.running && triggerSource != TriggerSource.MANUAL) {
            checkKillAuraPreHit()
        }
    }

    /**
     * Блокировка движения во время тапа — приоритетный handler
     */
    @Suppress("unused")
    private val movementHandler = handler<MovementInputEvent> { event ->
        if (!isTapping) return@handler

        val current = event.directionalInput
        val newForward = when (currentTapType) {
            TapType.S_TAP -> false // S нажат — W не нажат
            TapType.SHIFT_TAP -> current.forwards
            else -> false // W отпущен для всех остальных
        }
        val newBackward = currentTapType == TapType.S_TAP
        val newLeft = currentTapType == TapType.STRAFE_LEFT
        val newRight = currentTapType == TapType.STRAFE_RIGHT

        event.directionalInput = DirectionalInput(
            forwards = newForward,
            backwards = newBackward,
            left = newLeft,
            right = newRight
        )
    }

    /**
     * Sprint control — блокируем спринт во время тапа
     */
    @Suppress("unused")
    private val sprintHandler = handler<SprintEvent>(priority = CRITICAL_MODIFICATION) { event ->
        if ((isTapping || pendingSprintRestore) &&
            (event.source == SprintEvent.Source.MOVEMENT_TICK ||
             event.source == SprintEvent.Source.INPUT)) {
            event.sprint = false
        }
    }

    // ==================== KILLAURA PRE-HIT ИНТЕГРАЦИЯ ====================

    /**
     * Проверяет: планирует ли KillAura удар, и если да — планируем pre-hit тап.
     * KillAura делает клик по расписанию clickScheduler — мы можем читать его состояние.
     */
    private fun checkKillAuraPreHit() {
        if (timingMode != TimingMode.PRE_HIT) return
        if (preHitCountdown >= 0) return // уже запланировано

        val target = ModuleKillAura.targetTracker.target ?: return
        if (target !is LivingEntity) return

        // KillAura clicker говорит, что клик будет через preHitTicks тиков
        val willClick = ModuleKillAura.clickScheduler.willClickAt(preHitTicks)
        if (willClick && canTapNow(target)) {
            preHitCountdown = 0
            plannedTarget = target
        }
    }

    // ==================== ВНУТРЕННЯЯ ЛОГИКА ====================

    private fun handleHitInternal(target: Entity) {
        if (player.isDead || player.isSpectator) return
        if (target !is LivingEntity) return
        if (!canTapNow(target)) return

        handleComboCounter(target)
        lastTargetVelocity = getEntityHorizontalSpeed(target)

        val distance = player.distanceTo(target)
        val selectedTap = determineTapType(distance, target) ?: return

        saveKeyStates()

        // Sprint reset
        if (packetSprintReset) {
            stopSprintViaPacket()
        } else if (onlyWhileSprinting && player.isSprinting) {
            player.isSprinting = false
            pendingSprintRestore = true
            sprintResetTicksRemaining = sprintResetDelay
        }

        startTap(selectedTap)
    }

    @Suppress("ComplexCondition")
    private fun canTapNow(target: Entity): Boolean {
        if (onlyOnGround && !player.isOnGround) return false
        if (onlyWhileSprinting && !player.isSprinting) return false
        if (onlyPlayers && target !is PlayerEntity) return false

        val distance = player.distanceTo(target)
        if (minRange > 0f && distance < minRange) return false
        if (maxRange > 0f && distance > maxRange) return false

        if (isTapping) return false
        if (cooldownTicksRemaining > 0) return false

        // CritSync: не тапать если игрок падает (будет крит)
        if (critSync && !player.isOnGround
            && player.velocity.y < 0
            && player.fallDistance > 0) {
            return false
        }

        // CritSync через ModuleCriticals (если активен)
        if (critSync && ModuleCriticals.wouldDoCriticalHit()) {
            return false
        }

        // Кулдаун атаки
        if (attackCooldownSync) {
            val cooldown = player.getAttackCooldownProgress(0.5f)
            if (cooldown < cooldownThreshold) return false
        }

        return true
    }

    private fun handleComboCounter(target: Entity) {
        if (!comboCounterEnabled) return
        if (target == lastTarget && ticksSinceLastHit < comboTimeout) {
            comboCount++
        } else {
            comboCount = 1
        }
        lastTarget = target
        ticksSinceLastHit = 0
    }

    // ==================== ВЫБОР ТИПА ТАПА ====================

    private fun determineTapType(distance: Float, target: LivingEntity): TapType? {
        return when (mode) {
            TapMode.S_TAP -> TapType.S_TAP
            TapMode.W_TAP -> TapType.W_TAP
            TapMode.STRAFE -> determineStrafeDirection(target)
            TapMode.SHIFT_TAP -> TapType.SHIFT_TAP
            TapMode.AUTO -> determineAutoTap(distance, target)
        }
    }

    private fun determineAutoTap(distance: Float, target: LivingEntity): TapType {
        if (!adaptiveMode) {
            return if (distance <= distanceThreshold) TapType.S_TAP else TapType.W_TAP
        }

        val hpRatio = target.health / maxOf(target.maxHealth, 1f)
        val targetLowHP = hpRatio < 0.35f
        val targetMovingFast = lastTargetVelocity > 0.2
        val weHaveLowHP = player.health / player.maxHealth < 0.3f
        val threshold = distanceThreshold

        if (weHaveLowHP) {
            return if (distance <= 2f) TapType.SHIFT_TAP else TapType.S_TAP
        }

        if (targetLowHP) return TapType.W_TAP

        if (targetMovingFast && distance <= 3f) {
            return determineStrafeDirection(target)
        }

        return when {
            distance <= threshold * 0.6f -> TapType.S_TAP
            distance <= threshold -> {
                if ((0f..100f).random() < 60f) TapType.S_TAP else TapType.W_TAP
            }
            else -> TapType.W_TAP
        }
    }

    private fun determineStrafeDirection(target: LivingEntity): TapType {
        return when (strafeDirection) {
            StrafeMode.LEFT -> TapType.STRAFE_LEFT
            StrafeMode.RIGHT -> TapType.STRAFE_RIGHT
            StrafeMode.RANDOM -> if (rng.nextBoolean()) TapType.STRAFE_LEFT else TapType.STRAFE_RIGHT
            StrafeMode.AUTO -> determineSmartStrafe(target)
        }
    }

    /**
     * Умный стрейф: учитывает препятствия и позицию врага.
     */
    private fun determineSmartStrafe(target: LivingEntity): TapType {
        if (strafeCollisionAware) {
            val leftBlocked = checkDirectionBlocked(-1)
            val rightBlocked = checkDirectionBlocked(1)
            if (leftBlocked && !rightBlocked) return TapType.STRAFE_RIGHT
            if (rightBlocked && !leftBlocked) return TapType.STRAFE_LEFT
            if (leftBlocked && rightBlocked) return TapType.W_TAP
        }

        // Если враг смещён — стрейфаем навстречу
        val toTarget = target.pos.subtract(player.pos).normalize()
        val playerLook = player.rotationVector
        val playerRight = Vec3d(-playerLook.z, 0.0, playerLook.x).normalize()
        val dotRight = toTarget.dotProduct(playerRight)

        if (abs(dotRight) > 0.3) {
            return if (dotRight > 0) {
                lastStrafeLeft = true
                strafeStreak = 1
                TapType.STRAFE_LEFT
            } else {
                lastStrafeLeft = false
                strafeStreak = 1
                TapType.STRAFE_RIGHT
            }
        }

        // Anti-streak: после 2 одинаковых подряд — рандом
        if (strafeStreak >= 2) {
            strafeStreak = 0
            return if (rng.nextBoolean()) TapType.STRAFE_LEFT else TapType.STRAFE_RIGHT
        }

        lastStrafeLeft = !lastStrafeLeft
        strafeStreak++
        return if (lastStrafeLeft) TapType.STRAFE_LEFT else TapType.STRAFE_RIGHT
    }

    /**
     * Проверка: заблокировано ли направление стеной.
     */
    private fun checkDirectionBlocked(direction: Int): Boolean {
        val look = player.rotationVector
        val side = Vec3d(-look.z * direction, 0.0, look.x * direction).normalize()
        val checkPos = player.pos.add(side.multiply(1.0))
        val blockPos = BlockPos.ofFloored(checkPos)
        val state = world.getBlockState(blockPos)
        return !state.isAir
    }

    // ==================== ВЫПОЛНЕНИЕ ТАПА ====================

    private fun saveKeyStates() {
        wasForward = player.input.movementForward > 0
        wasBackward = player.input.movementForward < 0
        wasLeft = player.input.movementSideways > 0
        wasRight = player.input.movementSideways < 0
        wasSneaking = player.isSneaking
    }

    private fun startTap(type: TapType) {
        isTapping = true
        currentTapType = type

        var duration = tapDuration
        if (randomizeTiming) {
            val noise = (rng.nextGaussian() * randomizationStrength).roundToInt()
            duration = (duration + noise).coerceAtLeast(1)
        }
        if (pingAdaptation) {
            duration += getPingTicks()
        }
        tapTicksRemaining = duration
    }

    private fun restoreMovement() {
        currentTapType = TapType.NONE
    }

    // ==================== ПАКЕТНЫЙ SPRINT RESET ====================

    /**
     * Отправляет STOP_SPRINTING пакет напрямую через connection.
     */
    private fun stopSprintViaPacket() {
        network.sendPacket(ClientCommandC2SPacket(player, STOP_SPRINTING))
        player.isSprinting = false
        player.lastSprinting = false
        sprintWasStoppedViaPacket = true
        pendingSprintRestore = true
        sprintResetTicksRemaining = sprintResetDelay
    }

    /**
     * Восстанавливает спринт через START_SPRINTING пакет.
     */
    private fun restoreSprint() {
        if (sprintWasStoppedViaPacket) {
            network.sendPacket(ClientCommandC2SPacket(player, START_SPRINTING))
            player.isSprinting = true
            player.lastSprinting = true
            sprintWasStoppedViaPacket = false
        } else {
            player.isSprinting = true
        }
        pendingSprintRestore = false
    }

    // ==================== УТИЛИТЫ ====================

    private fun getEntityHorizontalSpeed(entity: LivingEntity): Double {
        val delta = entity.velocity
        return sqrt(delta.x * delta.x + delta.z * delta.z)
    }

    private fun getPingTicks(): Int {
        return try {
            val ping = player.ping
            (ping / 100.0).roundToInt()
        } catch (_: Exception) {
            0
        }
    }

    // ==================== KILLAURA HOOK API ====================

    /**
     * Хук от KillAura при ударе по цели.
     * Используется для ON_HIT и POST_HIT режимов.
     */
    fun onKillAuraHit(target: Entity) {
        if (!running) return
        if (!killauraIntegration) return
        if (triggerSource == TriggerSource.MANUAL) return

        handleComboCounter(target)

        if (timingMode == TimingMode.POST_HIT || timingMode == TimingMode.ON_HIT) {
            handleHitInternal(target)
        }
    }

    /**
     * Pre-hit хук от KillAura: сообщает, что через ticksToHit тиков будет удар.
     * Используется для PRE_HIT режима.
     */
    fun onKillAuraPreHit(target: Entity, ticksToHit: Int) {
        if (!running) return
        if (!killauraIntegration) return
        if (triggerSource == TriggerSource.MANUAL) return
        if (timingMode != TimingMode.PRE_HIT) return
        if (preHitCountdown >= 0) return

        if (ticksToHit == preHitTicks && canTapNow(target)) {
            preHitCountdown = 0
            plannedTarget = target
        }
    }

    /**
     * Параметр, читаемый KillAura для планирования pre-hit.
     * Возвращает 0 если pre-hit не активен — KillAura может игнорировать.
     */
    val preHitTicksForKillAura: Int
        get() = if (timingMode == TimingMode.PRE_HIT && running) preHitTicks else 0

    // ==================== HUD INFO ====================

    /**
     * Текущий метод тапа для HUD.
     */
    fun getCurrentMethod(): String {
        if (isTapping) {
            return when (currentTapType) {
                TapType.S_TAP -> "S-Tap"
                TapType.W_TAP -> "W-Tap"
                TapType.STRAFE_LEFT -> "Strafe L"
                TapType.STRAFE_RIGHT -> "Strafe R"
                TapType.SHIFT_TAP -> "Shift-Tap"
                TapType.NONE -> "Tap"
            }
        }
        return if (pendingSprintRestore) {
            "Sprint Reset"
        } else if (preHitCountdown >= 0) {
            "Pre-Hit Plan"
        } else {
            "Idle"
        }
    }

    /**
     * Счётчик комбо для HUD.
     */
    fun getComboCount(): Int = comboCount

    /**
     * Информационная строка для HUD / ArrayList.
     */
    fun getInfo(): String {
        return if (comboCounterEnabled && comboCount > 0) {
            "${mode.choiceName} [x$comboCount]"
        } else {
            mode.choiceName
        }
    }
}
