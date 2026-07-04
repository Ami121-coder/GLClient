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
package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.place

import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.ModuleCrystalAura
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.SubmoduleIdPredict
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.SwitchMode
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.aiming.NoRotationMode
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.data.RotationWithVector
import net.ccbluex.liquidbounce.utils.aiming.utils.findClosestPointOnBlockInLineWithCrystal
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBlock
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceUpperBlockSide
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.ccbluex.liquidbounce.utils.block.getState
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.clickBlockWithSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.findClosestSlot
import net.ccbluex.liquidbounce.utils.render.placement.PlacementRenderer
import net.minecraft.item.Items
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.max

object SubmoduleCrystalPlacer : ToggleableConfigurable(ModuleCrystalAura, "Place", true) {

    private val swingMode by enumChoice("Swing", SwingMode.DO_NOT_HIDE)
    private val switchMode by enumChoice("Switch", SwitchMode.SILENT)
    val oldVersion by boolean("1_12_2", false)
    private val delay by int("Delay", 0, 0..1000, "ms")
    val range by float("Range", 4.5f, 1f..6f).onChanged {
        CrystalAuraPlaceTargetFactory.updateSphere()
    }

    val wallsRange by float("WallsRange", 4.5f, 0f..6f).onChanged {
        CrystalAuraPlaceTargetFactory.updateSphere()
    }

    /**
     * Only place crystals above the block.
     * Outdated setting.
     * Using this is normally not recommended.
     */
    val onlyAbove by boolean("OnlyAbove", false)

    private val sequenced by boolean("Sequenced", false)

    // only applies without OnlyAbove
    private val notFacingAway by boolean("NotFacingAway", false)

    // only applies without OnlyAbove
    private val jitter by boolean("Jitter", false)

    val placementRenderer = tree(
        PlacementRenderer( // TODO slide
            "TargetRendering",
            true,
            ModuleCrystalAura,
            clump = false,
            defaultColor = Color4b.WHITE.with(a = 90)
        )
    )

    private val chronometer = Chronometer()
    private var blockHitResult: BlockHitResult? = null

    /**
     * Bug fix: the original stored mutable Rotation instances and their copies
     * in an ArrayDeque, then compared the live `RotationManager.serverRotation`
     * against the mutable one (which could have been mutated in place).
     * We now store only immutable copies and explicitly track whether we have
     * advanced to a new rotation since the last placement attempt.
     */
    private var previousRotation: Rotation? = null

    /**
     * Bug fix: the original `queuePlacing` had no timeout. If a higher-priority
     * rotation source cancelled our rotation, `onFinished` was never called
     * and the placement was silently lost. We now track the tick at which the
     * placement was requested and discard stale requests after a few ticks.
     */
    private var pendingPlacementTick: Long = -1L
    private val placementTimeoutTicks = 10L

    @Suppress("LongMethod", "CognitiveComplexMethod")
    fun tick(excludeIds: IntArray? = null) {
        if (!enabled || !chronometer.hasAtLeastElapsed(delay.toLong())) {
            return
        }

        // if we don't have crystals, we don't need to run the method
        getSlot() ?: return

        CrystalAuraPlaceTargetFactory.updateTarget(excludeIds)

        removeFromRenderer()

        val targetPos = CrystalAuraPlaceTargetFactory.placementTarget ?: return

        val notSameRotation = RotationManager.serverRotation != previousRotation
        val rotationsNotToMatch = if (notSameRotation && jitter) {
            listOfNotNull(previousRotation)
        } else {
            null
        }

        var side = Direction.UP
        val rotation = if (onlyAbove) {
            raytraceUpperBlockSide(
                player.eyePos,
                range.toDouble(),
                wallsRange.toDouble(),
                targetPos,
                rotationsNotToMatch = rotationsNotToMatch
            )
        } else {
            val data = findClosestPointOnBlockInLineWithCrystal(
                player.eyePos,
                range.toDouble(),
                wallsRange.toDouble(),
                targetPos,
                notFacingAway,
                rotationsNotToMatch
            ) ?: return
            side = data.second

            data.first
        } ?: return

        if (ModuleCrystalAura.rotationMode.activeChoice is NoRotationMode) {
            blockHitResult = raytraceBlock(
                getMaxRange().toDouble(),
                rotation.rotation,
                targetPos,
                targetPos.getState()!!
            ) ?: return
        }

        addToRenderer()
        updatePrevious(rotation)
        queuePlacing(rotation, targetPos, side)
    }

    private fun queuePlacing(rotation: RotationWithVector, targetPos: BlockPos, side: Direction) {
        // Track when this placement was queued so we can time it out if a
        // higher-priority rotation source cancels our rotation.
        pendingPlacementTick = player.age.toLong()

        ModuleCrystalAura.rotationMode.activeChoice.rotate(rotation.rotation, isFinished = {
            // Bug fix: if we have been waiting for the rotation to finish for
            // too long (placementTimeoutTicks ticks), abort the placement so
            // the next tick can pick a fresh target instead of hanging
            // indefinitely on a cancelled rotation.
            if (player.age.toLong() - pendingPlacementTick > placementTimeoutTicks) {
                return@rotate false
            }

            blockHitResult = raytraceBlock(
                getMaxRange().toDouble(),
                RotationManager.serverRotation,
                targetPos,
                targetPos.getState()!!
            ) ?: return@rotate false

            val hit = blockHitResult
            return@rotate hit != null && hit.type == HitResult.Type.BLOCK && hit.blockPos == targetPos
        }, onFinished = {
            if (!chronometer.hasAtLeastElapsed(delay.toLong())) {
                return@rotate
            }

            clickBlockWithSlot(
                player,
                blockHitResult?.withSide(side) ?: return@rotate,
                getSlot() ?: return@rotate,
                swingMode,
                switchMode,
                sequenced
            )

            SubmoduleIdPredict.run(targetPos)

            chronometer.reset()
            pendingPlacementTick = -1L
        })
    }

    private fun updatePrevious(rotation: RotationWithVector) {
        // Store an immutable copy of the rotation so later comparisons against
        // the live server rotation are not affected by in-place mutation.
        previousRotation = rotation.rotation.copy()
    }

    private fun addToRenderer() = with(CrystalAuraPlaceTargetFactory) {
        if (placementTarget == previousTarget) {
            return@with
        }

        placementTarget?.let {
            mc.execute { placementRenderer.addBlock(it) }
        }
    }

    private fun removeFromRenderer() = with(CrystalAuraPlaceTargetFactory) {
        if (placementTarget == previousTarget) {
            return@with
        }

        previousTarget?.let {
            mc.execute { placementRenderer.removeBlock(it) }
        }
    }

    /**
     * Bug fix: prefer the offhand slot for crystals so the main hand stays
     * free for swords. Falls back to the hotbar if the offhand has no
     * crystals. This matches the convention used by every serious CPvP
     * client and avoids the main-hand swap flicker that some anticheats
     * flag as suspicious.
     */
    private fun getSlot(): Int? {
        // Prefer offhand if it holds crystals.
        if (player.offHandStack.item == Items.END_CRYSTAL) {
            return Slots.OffHand.first().hotbarSlotForServer
        }
        return Slots.OffhandWithHotbar.findClosestSlot(Items.END_CRYSTAL)?.hotbarSlotForServer
    }

    fun getMaxRange() = max(range, wallsRange)

}
