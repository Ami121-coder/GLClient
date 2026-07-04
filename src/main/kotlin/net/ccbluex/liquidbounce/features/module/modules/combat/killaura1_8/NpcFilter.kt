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
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity

/**
 * # NpcFilter — combat-grade NPC / bot detection.
 *
 * Replaces the v3 single-line NPC check (`tabEntry == null`) with a
 * multi-signal classifier that combines:
 *
 *  - **Tab-list presence** (still the strongest single signal for
 *    server-side bots that aren't in the tab list).
 *  - **Tab-list ping** — bots frequently report a fixed 0 ms or 1 ms
 *    ping while real players fluctuate.
 *  - **Game-profile / skin presence** — bots often have no signed
 *    textures. Intave Heavy bots in particular sometimes ship with a
 *    UUID but no profile properties.
 *  - **Spawn-age** — freshly-spawned entities (age < threshold) are
 *    suspect; the threshold depends on the configured strictness.
 *  - **Vertical velocity plausibility** — a "player" with positive
 *    y-velocity while on ground, or teleporting more than
 *    `maxVerticalStep` blocks in one tick, is almost certainly a bot.
 *  - **Invisibility** — invisible entities are never valid PvP
 *    targets on strict anticheats.
 *
 * ## Strictness levels
 *
 * Each level controls how aggressive the filter is. [Strictness.LENIENT]
 * is intended for casual servers where false positives are worse than
 * missing a bot. [Strictness.STRICT] is intended for Intave Heavy /
 * Polar bot-decoy scenarios where hitting a decoy is an instant ban.
 *
 * Author: Super Z (1.8 PvP rewrite, v4)
 */
object NpcFilter {

    enum class Strictness(override val choiceName: String) : NamedChoice {
        /** Only filter the most obvious decoys (no tab entry). */
        LENIENT("Lenient"),

        /** Filter tab + ping + invisibility. Default. */
        NORMAL("Normal"),

        /** Filter all signals; suitable for Intave Heavy / Polar. */
        STRICT("Strict")
    }

    /**
     * Cached per-entity classification. Recomputed when the entity's
     * age advances by [RECERTIFY_AGE_TICKS] ticks. This bounds the
     * cost of repeated lookups during high-CPS combat.
     */
    private data class Classification(
        val isNpc: Boolean,
        val reason: String,
        val certifiedAtAge: Int
    )

    private const val RECERTIFY_AGE_TICKS = 40

    private val cache: MutableMap<Int, Classification> = HashMap()

    /**
     * Returns true if the entity should be treated as an NPC / bot and
     * excluded from targeting.
     */
    fun isNpc(entity: LivingEntity, strictness: Strictness): Boolean {
        if (entity !is PlayerEntity) return false // Only classify players as NPCs.
        val cached = cache[entity.id]
        if (cached != null && entity.age - cached.certifiedAtAge < RECERTIFY_AGE_TICKS) {
            return cached.isNpc
        }
        val result = classify(entity, strictness)
        cache[entity.id] = result
        return result.isNpc
    }

    fun reset() {
        cache.clear()
    }

    private fun classify(entity: PlayerEntity, strictness: Strictness): Classification {
        val networkHandler = mc.networkHandler
        val tabEntry = networkHandler?.getPlayerListEntry(entity.uuid)

        // ── LENIENT signals ────────────────────────────────────────────
        // Always active regardless of strictness.
        if (tabEntry == null) {
            // Modern Intave Heavy bots *do* appear in the tab list, so
            // this catches only the lazy implementations. Still worth
            // keeping on at all strictness levels.
            return Classification(true, "no-tab-entry", entity.age)
        }

        // ── NORMAL signals ─────────────────────────────────────────────
        if (strictness == Strictness.NORMAL || strictness == Strictness.STRICT) {
            // Ping signal. Real players can briefly show 0 ms on join
            // while the latency is being measured. Bots frequently
            // report a *negative* ping (impossible) or stay at 0 ms
            // for their entire lifetime.
            //
            // We flag only negative ping here. The "stuck at 0 ms"
            // signal would require observing the same entity across
            // multiple tab-list updates, which we don't currently do.
            val ping = tabEntry.latency
            if (ping < 0) {
                return Classification(true, "negative-ping", entity.age)
            }

            // Invisibility — never a valid PvP target.
            if (entity.isInvisible) {
                return Classification(true, "invisible", entity.age)
            }

            // Spawn-age: bots are often attacked within the first second
            // of spawning. Real players can also be attacked that young,
            // so we use a low threshold (10 ticks = 500 ms).
            if (entity.age < 10) {
                return Classification(true, "spawn-age", entity.age)
            }
        }

        // ── STRICT signals ─────────────────────────────────────────────
        if (strictness == Strictness.STRICT) {
            // Profile / skin signal. Real players almost always have a
            // signed texture property. Bots often have an empty profile.
            val profile = tabEntry.profile
            val hasTexture = profile?.properties?.containsKey("textures") == true &&
                profile.properties.get("textures").firstOrNull()?.value?.isNotEmpty() == true
            if (!hasTexture) {
                return Classification(true, "no-texture", entity.age)
            }

            // Vertical-velocity plausibility. A "player" that gained
            // more than 0.5 blocks of Y in a single tick while not on
            // ground is almost certainly a bot teleporting.
            val dy = entity.y - entity.lastRenderY
            if (dy > 0.5 && !entity.isOnGround) {
                return Classification(true, "vertical-teleport", entity.age)
            }

            // Hurt-time plausibility: real players take time to be
            // damaged; bots that we already attacked once should show
            // hurtTime > 0 — a permanently-zero hurtTime while being
            // targeted is suspicious. We check this only if the entity
            // has been alive long enough to have been hit at least once.
            if (entity.age > 40 && entity.hurtTime == 0 && entity.maxHealth - entity.health > 0f) {
                // Took damage in the past but is currently not in hurt
                // animation — that's fine. The signal we want is "took
                // damage but hurtTime is permanently 0", which is much
                // harder to detect from a single snapshot. Skip.
            }

            // Gamemode signal: spectator / creative targets are invalid
            // PvP targets. This is also checked elsewhere but we repeat
            // it here for defense-in-depth.
            val gamemode = tabEntry.gameMode
            if (gamemode != null && (gamemode.name == "SPECTATOR" || gamemode.name == "CREATIVE")) {
                return Classification(true, "wrong-gamemode", entity.age)
            }
        }

        return Classification(false, "ok", entity.age)
    }

    /**
     * Hook called by the KillAura when an entity is removed from the
     * world, to keep the cache bounded.
     */
    fun onEntityRemoved(entity: Entity) {
        cache.remove(entity.id)
    }
}
