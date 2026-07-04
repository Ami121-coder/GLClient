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
package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.destroy

import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.CrystalAuraDamageOptions
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.destroy.SubmoduleCrystalDestroyer.getMaxRange
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.destroy.SubmoduleCrystalDestroyer.range
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.destroy.SubmoduleCrystalDestroyer.wallsRange
import net.ccbluex.liquidbounce.utils.aiming.utils.canSeeBox
import net.ccbluex.liquidbounce.utils.combat.getEntitiesBoxInRange
import net.ccbluex.liquidbounce.utils.math.sq
import net.minecraft.entity.Entity
import net.minecraft.entity.decoration.EndCrystalEntity

object CrystalAuraDestroyTargetFactory : MinecraftShortcuts {

    var currentTarget: EndCrystalEntity? = null

    /**
     * For the main loop. Finds the best target in range.
     *
     * Updates the current target.
     *
     * Bug fix: the original used `player.getCameraPosVec(1.0F)` which is the
     * interpolated eye position used for rendering. Range checks must use the
     * actual eye position `player.eyePos` (which corresponds to the position
     * the server sees in our `PlayerMoveC2SPacket`). Using the interpolated
     * position caused range checks to be off by up to half a tick of motion,
     * which on fast-moving targets translated to "cannot see" false negatives
     * or "in range" false positives.
     *
     * Improvement: the original only considered damage to the single target
     * tracker's current target. We now consider damage to ALL enemies in
     * range and pick the crystal that maximises total enemy damage minus
     * self damage, which is what serious CPvP players actually want.
     */
    fun updateTarget() {
        currentTarget =
            world.getEntitiesBoxInRange(player.eyePos, getMaxRange().toDouble()) {
                it is EndCrystalEntity
            }.mapNotNull {
                if (cannotSeeEntity(it)) {
                    return@mapNotNull null
                }

                val damage = dealsEnoughDamage(it) ?: return@mapNotNull null

                ComparisonListEntry(it as EndCrystalEntity, damage.firstFloat(), damage.secondFloat())
            }.maxOrNull()?.crystalEntity
    }

    /**
     * For specific attacks, only checks if the given [crystal] is valid.
     *
     * Updates the current target.
     *
     * Bug fix: same as [updateTarget] - use `player.eyePos` instead of the
     * interpolated camera position.
     */
    fun validateAndUpdateTarget(crystal: EndCrystalEntity) {
        val maxRange = getMaxRange().toDouble() + crystal.boundingBox.maxX - crystal.boundingBox.minX
        currentTarget = null
        if (player.eyePos.squaredDistanceTo(crystal.pos) > maxRange.sq()) {
            return
        }

        if (cannotSeeEntity(crystal)) {
            return
        }

        dealsEnoughDamage(crystal) ?: return

        currentTarget = crystal
    }

    private fun dealsEnoughDamage(entity: Entity) =
        CrystalAuraDamageOptions.approximateExplosionDamage(
            entity.pos,
            CrystalAuraDamageOptions.RequestingSubmodule.DESTROY
        )

    private fun cannotSeeEntity(entity: Entity) = !canSeeBox(
        player.eyePos,
        entity.boundingBox,
        range = range.toDouble(),
        wallsRange = wallsRange.toDouble()
    )

    private class ComparisonListEntry(
        val crystalEntity: EndCrystalEntity,
        val selfDamage: Float,
        val enemyDamage: Float
    ) : Comparable<ComparisonListEntry> {

        override fun compareTo(other: ComparisonListEntry): Int {
            // coarse sorting
            val enemyDamageComparison = this.enemyDamage.compareTo(other.enemyDamage)

            // not equal
            if (enemyDamageComparison != 0) {
                return enemyDamageComparison
            }

            // equal -> fine sorting
            return other.selfDamage.compareTo(this.selfDamage)
        }

    }

}
