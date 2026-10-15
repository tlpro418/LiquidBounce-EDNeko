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
 * Grim / Karhu / Polar speed modes ported from EDNeko
 * (github.com/RouQingNeko1024/EDNeko, GPL-3.0).
 *
 * EDNeko's `onStrafe()` and `onMotion()` are folded into `onUpdate` - both ran
 * once per tick there, and LiquidBounce's per-tick handler is the equivalent
 * point. `jumpMovementFactor` (MCP `field_70747_aH`), which Grim, Karhu and
 * Polar all set to 0.0265, has no 1.21.8 counterpart and is omitted.
 * ---------------------------------------------------------------------------
 */
package net.ccbluex.liquidbounce.features.module.modules.movement.speed.modes

import net.ccbluex.liquidbounce.config.types.nesting.ChoiceConfigurable
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerJumpEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.movement.speed.ModuleSpeed
import net.ccbluex.liquidbounce.utils.client.Timer
import net.ccbluex.liquidbounce.utils.entity.movementForward
import net.ccbluex.liquidbounce.utils.entity.movementSideways
import net.ccbluex.liquidbounce.utils.entity.moving
import net.ccbluex.liquidbounce.utils.entity.withStrafe
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.block.Blocks
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ported from EDNeko `speedmodes/grim/Grim`.
 *
 * Jumps while grounded, boosts the timer every third airborne tick, optionally
 * pins downward velocity and spoofs `onGround` on the outgoing move packet.
 */
class SpeedGrim(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("Grim", parent) {

    private val timerBoost by boolean("TimerBoost", true)
    private val airTimer by float("AirTimer", 1.15f, 0.5f..3f)
    private val pullDown by boolean("PullDown", true)
    private val pullDownTicks by int("PullDownTicks", 8, 1..20)
    private val spoofGround by boolean("SpoofGround", true)

    private var airTick = 0

    override fun onUpdate() {
        player.isSprinting = player.input.movementForward > 0.8

        if (player.isTouchingWater || player.isClimbing || !player.moving) {
            Timer.requestTimerSpeed(1.0f, Priority.NOT_IMPORTANT, ModuleSpeed)
            airTick = 0
            return
        }

        if (player.isOnGround) {
            airTick = 0
        } else {
            airTick++
        }

        Timer.requestTimerSpeed(
            if (timerBoost) {
                if (player.isOnGround) 1.0f else if (airTick % 3 == 0) airTimer else 1.0f
            } else {
                1.0f
            },
            Priority.IMPORTANT_FOR_USAGE_1,
            ModuleSpeed
        )

        if (pullDown && !player.isOnGround && airTick >= pullDownTicks && player.velocity.y < 0.0) {
            player.velocity.y = -0.1523351824467155
        }

        // EDNeko's onStrafe: hop while grounded and moving.
        if (player.isOnGround && player.moving) {
            player.jump()
        }
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet as? PlayerMoveC2SPacket ?: return@handler
        if (spoofGround && shouldSpoofGround()) {
            packet.onGround = true
        }
    }

    private fun shouldSpoofGround(): Boolean =
        !player.isOnGround && player.fallDistance < 0.5f && player.velocity.y < 0.0

}

/**
 * Ported from EDNeko `speedmodes/grim/GrimCollide`.
 *
 * Pushes the player away from any living entity its own inflated bounding box
 * overlaps, which Grim accepts as collision-driven movement.
 */
class SpeedGrimCollide(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("GrimCollide", parent) {

    private val boostSpeed by float("BoostSpeed", 0.05f, 0f..1f)

    override fun onUpdate() {
        if (player.input.movementForward == 0.0f && player.input.movementSideways == 0.0f) {
            return
        }

        val box = player.boundingBox.expand(1.0, 0.0, 1.0)
        var collisions = 0
        for (entity in world.entities) {
            if (!canCauseSpeed(entity) || !box.intersects(entity.boundingBox)) {
                continue
            }
            collisions++
        }

        if (collisions > 0) {
            val yaw = Math.toRadians(player.yaw.toDouble())
            val boost = boostSpeed * collisions
            player.velocity.x -= sin(yaw) * boost
            player.velocity.z += cos(yaw) * boost
        }
    }

    private fun canCauseSpeed(entity: Entity): Boolean =
        entity != player && entity is LivingEntity && entity !is ArmorStandEntity

}

/**
 * Ported from EDNeko `speedmodes/grim/NewGrim`.
 *
 * Alternates the timer between 1.025 and 0.99 on odd/even ticks, optionally
 * nudging horizontal velocity with it, and forces sprinting while moving.
 */
class SpeedNewGrim(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("NewGrim", parent) {

    private val motionBoost by boolean("MotionBoost", true)

    override fun onUpdate() {
        if (player.isOnGround && player.moving) {
            player.jump()
        }

        if (player.age % 2 == 1) {
            Timer.requestTimerSpeed(1.025f, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
            if (motionBoost) {
                factorXZ(1.01)
            }
        } else {
            Timer.requestTimerSpeed(0.99f, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
            if (motionBoost) {
                factorXZ(0.99)
            }
        }

        if (!player.isSprinting && !player.isSneaking && player.moving && !player.hasVehicle()) {
            player.isSprinting = player.input.movementForward > 0.8
        }
    }

}

/**
 * Ported from EDNeko `speedmodes/polar/Polar`.
 *
 * While descending, flips vertical velocity between -0.1 and -0.16 each tick,
 * which Polar's prediction sees as a consistent fall.
 */
class SpeedPolar(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("Polar", parent) {

    private var polarTick = 0

    override fun onUpdate() {
        if (player.velocity.y <= -0.1) {
            polarTick++
            player.velocity.y = if (polarTick % 2 == 0) -0.1 else -0.16
        } else {
            polarTick = 0
        }
    }

}

/**
 * Ported from EDNeko `speedmodes/karhu/Karhu`.
 *
 * Hops while moving and stops horizontal movement as soon as the keys are
 * released; the jump motion is chosen by the block the player stands on.
 */
class SpeedKarhu(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("Karhu", parent) {

    private var wasSpeed = false

    override fun onUpdate() {
        if (player.moving) {
            if (player.isOnGround) {
                player.jump()
            }
            wasSpeed = true
        } else if (wasSpeed) {
            stopXZ()
            wasSpeed = false
        }
    }

    override fun onJump(event: PlayerJumpEvent) {
        val below = world.getBlockState(player.blockPos.down()).block
        event.motion = when (below) {
            Blocks.WATER, Blocks.LAVA -> 0.0f
            Blocks.SLIME_BLOCK -> 0.39f
            else -> 0.415f
        }
    }

}

/**
 * Ported from EDNeko `speedmodes/karhu/Karhu2`.
 *
 * Jumps while grounded and then subtracts 0.135 from the jump velocity, giving
 * the low hop Karhu expects.
 */
class SpeedKarhu2(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("Karhu2", parent) {

    override fun onUpdate() {
        if (player.isOnGround && player.moving) {
            player.jump()
            player.velocity.y -= 0.135
        }
    }

}
