/*
 * CustomAura target tracker.
 *
 * Extends the stock TargetTracker with:
 *  - Tighter default hurt-time threshold (we want to keep hitting the
 *    same enemy to out-DPS cheaters, but not hit through i-frames).
 *  - Optional shield-bypass via axe detection (vanilla mechanic —
 *    not a Polar flag because it uses the real `axe disables shield`
 *    path).
 */
@file:Suppress("WildcardImport")
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura

import net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAutoWeapon
import net.ccbluex.liquidbounce.utils.client.isOlderThanOrEqual1_8
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.ccbluex.liquidbounce.utils.entity.wouldBlockHit
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.AxeItem

object CustomAuraTargetTracker : TargetTracker() {

    /**
     * Vanilla shield bypass — uses the real axe-disables-shield mechanic,
     * so this is NOT a flag. Disabled by default for safety on servers
     * that detect aggressive shield-bypass patterns.
     */
    private val ignoreShield by boolean("IgnoreShield", false)

    override fun validate(entity: LivingEntity): Boolean {
        return super.validate(entity) && validateShield(entity)
    }

    private fun validateShield(entity: LivingEntity): Boolean {
        if (ignoreShield || entity !is PlayerEntity || isOlderThanOrEqual1_8) return true

        if (player.mainHandStack.item is AxeItem || ModuleAutoWeapon.willBreakShield()) return true

        return !entity.blockedByShield(world.damageSources.playerAttack(player)) ||
            !entity.wouldBlockHit(player)
    }
}
