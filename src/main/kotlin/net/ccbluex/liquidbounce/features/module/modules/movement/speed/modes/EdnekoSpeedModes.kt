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
 *
 * ---------------------------------------------------------------------------
 * Speed modes ported from EDNeko (github.com/RouQingNeko1024/EDNeko, GPL-3.0).
 *
 * Settings that EDNeko kept on the `Speed` module (e.g. `VanillaHopSpeed`, and
 * the debug toggles) live on the mode here instead, which is how LiquidBounce
 * scopes per-mode options.
 * ---------------------------------------------------------------------------
 */
package net.ccbluex.liquidbounce.features.module.modules.movement.speed.modes

import net.ccbluex.liquidbounce.config.types.nesting.ChoiceConfigurable
import net.ccbluex.liquidbounce.features.module.modules.movement.speed.ModuleSpeed
import net.ccbluex.liquidbounce.utils.client.Timer
import net.ccbluex.liquidbounce.utils.entity.moving
import net.ccbluex.liquidbounce.utils.entity.withStrafe
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.math.copy
import net.minecraft.entity.effect.StatusEffects

/**
 * Ported from EDNeko `speedmodes/aac/AACHop4`.
 *
 * Jumps when grounded and rides the AAC timer: full speed while falling
 * normally, slower in the first 1.3 blocks of a fall and faster just after
 * leaving the ground.
 */
class SpeedAACHop4(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("AACHop4", parent) {

    override fun onUpdate() {
        if (!player.moving || player.isTouchingWater || player.isClimbing || player.hasVehicle()) {
            Timer.requestTimerSpeed(1.0f, Priority.NOT_IMPORTANT, ModuleSpeed)
            return
        }

        if (player.isOnGround) {
            Timer.requestTimerSpeed(1.0f, Priority.NOT_IMPORTANT, ModuleSpeed)
            player.jump()
        } else {
            val timerSpeed = when {
                player.fallDistance <= 0.1 -> 1.5f
                player.fallDistance < 1.3 -> 0.7f
                else -> 1.0f
            }
            Timer.requestTimerSpeed(timerSpeed, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
        }
    }

}

/**
 * Ported from EDNeko `speedmodes/verus/VerusHop`.
 *
 * Accumulates horizontal speed while airborne and decays it by 2% per tick,
 * restarting from a potion-dependent base each time the player lands.
 */
class SpeedVerusHop(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("VerusHop", parent) {

    private var speed = 0f

    override fun onUpdate() {
        if (player.isTouchingWater || player.isClimbing) {
            return
        }
        if (!player.moving) {
            return
        }

        if (player.isOnGround) {
            val speedEffect = player.getStatusEffect(StatusEffects.SPEED)
            speed = if (speedEffect != null && speedEffect.amplifier >= 1) 0.46f else 0.34f
            player.jump()
        } else {
            speed *= 0.98f
        }

        player.velocity = player.velocity.withStrafe(speed = speed.toDouble())
    }

}

/**
 * Ported from EDNeko `speedmodes/other/VanillaHop`.
 *
 * Strafes at a fixed speed, optionally jumping whenever grounded and stopping
 * instantly when the player releases the movement keys.
 */
class SpeedVanillaHop(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("VanillaHop", parent) {

    private val speed by float("Speed", 0.35f, 0.1f..1f)
    private val jump by boolean("Jump", true)
    private val fastStop by boolean("FastStop", false)

    override fun onUpdate() {
        if (!player.moving && fastStop) {
            player.velocity = player.velocity.copy(x = 0.0, z = 0.0)
            return
        }

        if (player.isOnGround && jump && player.moving) {
            player.jump()
        }

        player.velocity = player.velocity.withStrafe(speed = speed.toDouble())
    }

}

/**
 * Ported from EDNeko `speedmodes/other/LowHop`.
 *
 * Hops but clips the vertical velocity, so the player keeps the ground-speed
 * boost without gaining full jump height: 80% on the jump tick, then 50% one
 * tick later.
 */
class SpeedLowHop(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("LowHop", parent) {

    private var airTicks = 0

    override fun onUpdate() {
        if (player.isOnGround) {
            player.jump()
            player.velocity.y *= 0.8
            airTicks = 0
        }

        if (!player.isOnGround && airTicks == 1) {
            player.velocity.y *= 0.5
            airTicks++
        } else if (!player.isOnGround) {
            airTicks++
        }
    }

}
