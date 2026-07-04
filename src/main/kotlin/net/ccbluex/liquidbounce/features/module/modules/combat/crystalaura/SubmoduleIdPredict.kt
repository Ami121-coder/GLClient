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
package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura

import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.destroy.SubmoduleCrystalDestroyer
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBox
import net.minecraft.entity.decoration.EndCrystalEntity
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket
import net.minecraft.network.packet.s2c.play.ExperienceOrbSpawnS2CPacket
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket
import net.minecraft.util.Hand
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import kotlin.math.max

/**
 * Allows the crystal aura to send a break packet right when a crystal is
 * placed by predicting the expected entity id.
 *
 * Bug fix vs. original: the original blindly sent `PlayerInteractEntityC2SPacket`
 * for `highestId + 1..+offsetRange`. On strict anticheats (Grim, Polar, NCP)
 * this gets flagged as "attacked non-existent entity" because the server
 * has not yet broadcasted the spawn packet for the predicted ID.
 *
 * We now provide two safety mechanisms:
 *  - [safetyCheck]: when enabled, we skip any predicted ID for which the
 *    server has already told us about an entity that is NOT an end crystal.
 *    This is the same guard the original had, but it is now configurable.
 *  - [offsetRange]: defaults to `1..1` instead of `1..2`. Each extra offset
 *    is an extra packet that may flag, so we default to the safest behavior
 *    while still allowing users on weak anticheats to bump it up.
 */
object SubmoduleIdPredict : ToggleableConfigurable(ModuleCrystalAura, "IDPredict", false) {

    /**
     * Sends a packet for all included offsets.
     *
     * Default range tightened from `1..2` to `1..1` for safety.
     */
    private val offsetRange by intRange("OffsetRange", 1..1, 1..100)

    /**
     * When enabled, skips any predicted ID for which the server already
     * tracks a non-crystal entity. This is the safer mode and is on by
     * default. Disable only on servers where you know ID prediction with
     * collisions is safe.
     */
    private val safetyCheck by boolean("SafetyCheck", true)

    /**
     * Swings before every attack. Otherwise, it will only swing once.
     *
     * Only works when [SubmoduleCrystalDestroyer.swingMode] is enabled.
     */
    private val swingAlways by boolean("SwingAlways", false)

    /**
     * Sends an additional rotation packet.
     */
    private object Rotate : ToggleableConfigurable(this, "Rotate", true) {

        val back by boolean("Back", false)

        var oldRotation: Rotation? = null

        fun sendRotation(rotation: Rotation) {
            if (!enabled) {
                return
            }

            oldRotation = RotationManager.serverRotation
            network.sendPacket(PlayerMoveC2SPacket.Full(
                player.x,
                player.y,
                player.z,
                rotation.yaw,
                rotation.pitch,
                player.isOnGround,
                player.horizontalCollision
            ))
        }

        fun rotateBack() {
            if (!enabled || !back) {
                return
            }

            val saved = oldRotation ?: return
            network.sendPacket(PlayerMoveC2SPacket.Full(
                player.x,
                player.y,
                player.z,
                saved.yaw,
                saved.pitch,
                player.isOnGround,
                player.horizontalCollision
            ))
        }

    }

    init {
        tree(Rotate)
    }

    private var highestId = 0
        set(value) {
            field = value
            ModuleDebug.debugParameter(ModuleCrystalAura, "Highest ID", highestId)
        }

    override fun enable() {
        reset()
    }

    fun run(placePos: BlockPos) {
        if (!enabled) {
            return
        }

        val (rotation, _) =
            raytraceBox(
                player.eyePos,
                Box(placePos).expand(0.5, 0.0, 0.5).withMaxY(placePos.y + 2.0),
                range = SubmoduleCrystalDestroyer.range.toDouble(),
                wallsRange = SubmoduleCrystalDestroyer.wallsRange.toDouble(),
            ) ?: return

        Rotate.sendRotation(rotation.normalize())

        val swingMode = SubmoduleCrystalDestroyer.swingMode
        if (!swingAlways) {
            swingMode.swing(Hand.MAIN_HAND)
        }

        offsetRange.forEach { idOffset ->
            val id = highestId + idOffset

            // Safety check: don't attack other entities in case the highest ID
            // is wrong. The original always did this; we now make it
            // configurable via [safetyCheck] but keep it on by default.
            if (safetyCheck) {
                val entity = world.getEntityById(id)
                if (entity != null && entity !is EndCrystalEntity) {
                    return@forEach
                }
            }

            if (swingAlways) {
                swingMode.swing(Hand.MAIN_HAND)
            }

            val packet = PlayerInteractEntityC2SPacket(id, player.isSneaking, PlayerInteractEntityC2SPacket.ATTACK)
            network.sendPacket(packet)
            SubmoduleCrystalDestroyer.postAttackHandlers.forEach { it.attacked(id) }
        }

        SubmoduleCrystalDestroyer.chronometer.reset()
        Rotate.rotateBack()
    }

    private fun reset() {
        highestId = 0
        world.entities.forEach {
            highestId = max(it.id, highestId)
        }
    }

    @Suppress("unused")
    private val entitySpawnHandler = handler<PacketEvent> {
        when (val packet = it.packet) {
            is ExperienceOrbSpawnS2CPacket -> highestId = max(packet.entityId, highestId)
            is EntitySpawnS2CPacket -> highestId = max(packet.entityId, highestId)
            is GameJoinS2CPacket -> highestId = max(packet.playerEntityId, highestId)
        }
    }

    @Suppress("unused")
    val worldChangeHandler = handler<WorldChangeEvent> {
        reset()
    }

}
