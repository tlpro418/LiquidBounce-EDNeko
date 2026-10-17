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
package net.ccbluex.liquidbounce.features.module.modules.world

import net.ccbluex.liquidbounce.event.events.BlockAttackEvent
import net.ccbluex.liquidbounce.event.events.BlockBreakingProgressEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.client.interaction
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket
import net.minecraft.registry.Registries
import net.minecraft.util.math.BlockPos

/**
 * NoGlassBreak module
 *
 * Makes it impossible to break glass. Useful on servers whose anti-cheat incorrectly flags breaking
 * glass, where a single accidental block would get you banned.
 *
 * Glass is recognised by its registry identifier containing `glass`. That covers plain glass, glass
 * panes, all sixteen stained variants of both and tinted glass, without a hard-coded list that would
 * have to be kept in sync with the game.
 *
 * Breaking is vetoed at every layer it can happen on:
 *  - [BlockAttackEvent] stops the initial click (and with it creative-mode instant breaking),
 *  - [BlockBreakingProgressEvent] stops the per-tick progress, which is what actually matters while the
 *    attack key is held down,
 *  - [doBreak]'s immediate branch is vetoed too, because it builds its packets by hand,
 *  - and as a backstop every outgoing [PlayerActionC2SPacket] that targets glass is dropped outright.
 *
 * Because none of them runs, no `START_DESTROY_BLOCK` or `STOP_DESTROY_BLOCK` packet leaves the client
 * for a glass block, and no local break progress accumulates either.
 */
object ModuleNoGlassBreak : ClientModule(
    "NoGlassBreak",
    Category.WORLD,
    aliases = listOf("AntiGlassBreak", "NoGlassMine", "GlassGuard")
) {

    /**
     * Also protect glass panes.
     */
    private val panes by boolean("Panes", true)

    /**
     * Also protect stained glass and stained glass panes.
     */
    private val stained by boolean("Stained", true)

    /**
     * Glass block the veto was last applied to, so pending progress is only reset once per transition
     * instead of every single tick.
     */
    private var vetoedPos: BlockPos? = null

    @Suppress("unused")
    private val blockAttackHandler = handler<BlockAttackEvent> { event ->
        if (isGlass(event.pos)) {
            event.cancelEvent()
        }
    }

    @Suppress("unused")
    private val breakProgressHandler = handler<BlockBreakingProgressEvent> { event ->
        if (!isGlass(event.pos)) {
            vetoedPos = null
            return@handler
        }

        event.cancelEvent()

        // Vanilla resets the progress of the previously mined block as soon as the crosshair moves onto
        // another one. Skipping the method entirely would keep that progress alive, so the old block
        // would break the instant it is looked at again - which is exactly the packet pattern of
        // FastBreak. Resetting it here keeps the sequence identical to vanilla.
        if (vetoedPos != event.pos) {
            vetoedPos = event.pos
            interaction.cancelBlockBreaking()
        }
    }

    /**
     * Last line of defence.
     *
     * Drops any outgoing destroy packet that targets glass, no matter which code path built it - a module
     * that assembles its packets by hand, a script, or something not yet written. Runs at
     * [EventPriorityConvention.FINAL_DECISION] so it can object to every handler that ran before it.
     *
     * Only the three destroy actions are filtered. `DROP_ITEM`, `RELEASE_USE_ITEM` and
     * `SWAP_ITEM_WITH_OFFHAND` carry a position as well (usually the origin) and must never be dropped.
     */
    @Suppress("unused")
    private val packetHandler = handler<PacketEvent>(priority = EventPriorityConvention.FINAL_DECISION) { event ->
        if (event.origin != TransferOrigin.OUTGOING) {
            return@handler
        }

        val packet = event.packet as? PlayerActionC2SPacket ?: return@handler

        when (packet.action) {
            PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
            PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK -> Unit

            else -> return@handler
        }

        if (isGlass(packet.pos)) {
            event.cancelEvent()
        }
    }

    private fun isGlass(pos: BlockPos): Boolean {
        val path = Registries.BLOCK.getId(world.getBlockState(pos).block).path

        if (!path.contains("glass")) {
            return false
        }

        if (!panes && path.contains("pane")) {
            return false
        }

        if (!stained && path.contains("stained")) {
            return false
        }

        return true
    }

}
