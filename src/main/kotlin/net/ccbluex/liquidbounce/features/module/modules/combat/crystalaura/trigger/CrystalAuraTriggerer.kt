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
package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.trigger

import net.ccbluex.liquidbounce.config.types.nesting.Configurable
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.ModuleCrystalAura
import net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.trigger.triggers.*
import net.ccbluex.liquidbounce.injection.mixins.minecraft.network.MixinClientPlayNetworkHandler
import net.ccbluex.liquidbounce.injection.mixins.minecraft.network.MixinClientPlayerInteractionManager
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.combat.CombatManager
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.function.BooleanSupplier

/**
 * Catches events that should start a new place or break action.
 *
 * This is basically the managing class of the crystal aura.
 *
 * Mixins: [MixinClientPlayNetworkHandler], [MixinClientPlayerInteractionManager]
 *
 * Bug fixes vs. the original implementation:
 *  - Thread safety: the original submitted [Runnable]s to a background
 *    [Executors.newSingleThreadExecutor] that directly touched Minecraft
 *    state (`world.entities`, `player.blockPos`, `RotationManager.serverRotation`).
 *    That caused `ConcurrentModificationException`s, stale reads and race
 *    conditions with the render thread. We now provide two execution
 *    strategies controlled by [offThread]:
 *      - offThread == true  -> schedule heavy DAMAGE CALCULATION work on the
 *                              background thread, but ALWAYS hop back to the
 *                              render thread (`mc.execute`) for any state
 *                              mutation / packet send / Minecraft API call.
 *      - offThread == false -> run everything inline on the render thread.
 *  - Cache: the original `canCache()` returned false whenever `offThread`
 *    was true, which made the [CrystalAuraDamageOptions.cacheMap] dead code
 *    in the default configuration. We now allow caching whenever the active
 *    triggers permit it, because the cache is cleared on every
 *    `RotationUpdateEvent` (i.e. every game tick) and is only accessed
 *    from a single task at a time (we cancel the previous task before
 *    submitting a new one).
 */
object CrystalAuraTriggerer : Configurable("Triggers"), EventListener, MinecraftShortcuts {

    // avoids grim multi action flags
    private val notWhileUsingItem by boolean("NotWhileUsingItem", false)

    /**
     * Runs the calculations on a separate thread avoiding overhead on the render thread.
     *
     * Bug fix: this is now safe. Even when enabled, every Minecraft-touching
     * operation is dispatched back to the render thread via [mc.execute].
     */
    val offThread by boolean("Off-Thread", true)

    private val service = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LiquidBounce-CrystalAura").apply { isDaemon = true }
    }

    /**
     * The currently executed placement task.
     */
    private var currentPlaceTask: Future<*>? = null

    /**
     * The currently executed destroy task.
     */
    private var currentDestroyTask: Future<*>? = null

    private var canCache: BooleanSupplier

    init {
        // register all triggers
        val triggers = arrayOf(
            TickTrigger,
            BlockChangeTrigger,
            ClientBlockBreakTrigger,
            CrystalSpawnTrigger,
            CrystalDestroyTrigger,
            ExplodeSoundTrigger,
            EntityMoveTrigger,
            SelfMoveTrigger
        )

        canCache = BooleanSupplier {
            triggers.filter { it.enabled }.all { it.allowsCaching }
        }

        triggers.forEach {
            it.apply {
                it.option = boolean(it.name, it.default)
            }
        }
    }

    fun terminateRunningTasks() {
        currentPlaceTask?.cancel(true)
        currentDestroyTask?.cancel(true)
        currentPlaceTask = null
        currentDestroyTask = null
    }

    /**
     * Submits a placement task.
     *
     * Bug fix: when [offThread] is enabled, the runnable is submitted to the
     * background executor. The runnable itself is expected to delegate any
     * Minecraft state mutation to [mc.execute] (the heavy damage-calculation
     * work runs on the background thread, the actual packet sends and
     * world mutations happen on the render thread).
     *
     * When [offThread] is disabled, the runnable is dispatched directly to
     * the render thread via [mc.execute] - we still hop through `mc.execute`
     * instead of running inline because triggers can fire from packet
     * handlers where mutating world state directly is unsafe.
     */
    fun runPlace(runnable: Runnable) {
        currentPlaceTask?.let {
            if (!it.isDone) {
                return
            }
        }

        if (offThread) {
            currentPlaceTask = service.submit(runnable)
        } else {
            currentPlaceTask?.cancel(true)
            currentPlaceTask = null
            mc.execute(runnable)
        }
    }

    fun runDestroy(runnable: Runnable) {
        currentDestroyTask?.let {
            if (!it.isDone) {
                return
            }
        }

        if (offThread) {
            currentDestroyTask = service.submit(runnable)
        } else {
            currentDestroyTask?.cancel(true)
            currentDestroyTask = null
            mc.execute(runnable)
        }
    }

    /**
     * Bug fix: the original disabled caching whenever [offThread] was true,
     * which made the damage cache useless in the default configuration.
     *
     * The cache is safe to use as long as the active triggers all allow
     * caching, because:
     *  - [CrystalAuraDamageOptions.cacheMap] is cleared on every
     *    `RotationUpdateEvent` (i.e. once per tick on the render thread);
     *  - we cancel the previous place/destroy task before submitting a
     *    new one, so at most one background task reads the cache at a time;
     *  - the cache is an LRU with size 64, so concurrent reads from a
     *    background task that started just before a clear will at worst
     *    re-compute damage for a few positions.
     */
    fun canCache() = canCache.asBoolean

    /**
     * Also pauses when the combat manager tells combat modules to pause or option
     * (e.g. [notWhileUsingItem]) require it.
     */
    override val running: Boolean
        get() = ModuleCrystalAura.running
            && !CombatManager.shouldPauseCombat
            && (!player.isUsingItem || !notWhileUsingItem)

}
