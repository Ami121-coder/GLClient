/*
 * CustomAura activation requirements.
 *
 * Same enum pattern as the stock KillAuraRequirements but with safer
 * defaults baked into the module: the module uses `multiEnumChoice` with
 * NO default entries enabled, so the user must explicitly opt-in to each
 * requirement. This is more conservative than the stock module which
 * runs unconditionally.
 */
@file:Suppress("unused")
package net.ccbluex.liquidbounce.features.module.modules.combat.customaura

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.utils.client.isOlderThanOrEqual1_8
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.ccbluex.liquidbounce.utils.input.InputTracker.wasPressedRecently
import net.minecraft.item.AxeItem
import net.minecraft.item.Item
import net.minecraft.item.MaceItem
import net.minecraft.item.SwordItem

enum class CustomAuraRequirements(
    override val choiceName: String,
    val meets: () -> Boolean
) : NamedChoice {
    /**
     * Only attack while the user is holding the attack key OR has pressed
     * it in the last 250ms. This makes the aura look like a "click assist"
     * rather than a fully autonomous aimbot, which dramatically reduces
     * Polar flag probability because the click train correlates with a
     * real input event.
     */
    CLICK("Click", {
        mc.options.attackKey.isPressedOnAny || mc.options.attackKey.wasPressedRecently(250)
    }),

    /**
     * Only attack with a real weapon in the main hand. Polar sometimes
     * flags attack packets that deal zero or non-weapon damage because
     * the damage source type is anomalous.
     */
    WEAPON("Weapon", {
        player.inventory.mainHandStack.item.isWeapon()
    }),

    /**
     * Skip targets whose main hand item has a custom display name — those
     * are often quest NPCs / server-special entities that should not be
     * hit by a combat module anyway.
     */
    VANILLA_NAME("VanillaName", {
        player.inventory.mainHandStack.customName == null
    }),

    /**
     * Do not attack while breaking a block — the vanilla client cannot
     * send both a block-break update and an attack in the same tick
     * cleanly, so Polar flags the combination.
     */
    NOT_BREAKING("NotBreaking", {
        mc.interactionManager?.isBreakingBlock == false
    });
}

private fun Item.isWeapon() =
    this is SwordItem || (!isOlderThanOrEqual1_8 && this is AxeItem) || this is MaceItem
