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
 * Intave speed modes ported from EDNeko
 * (github.com/RouQingNeko1024/EDNeko, GPL-3.0).
 *
 * EDNeko dispatched these from `onMotion`, `onTick` and `onUpdate`; all three
 * ran once per tick there and are folded into `onUpdate` here. Its module-level
 * settings become mode-local settings, and its `MovementUtils.getAirTicks()` is
 * tracked locally.
 * ---------------------------------------------------------------------------
 */
package net.ccbluex.liquidbounce.features.module.modules.movement.speed.modes

import net.ccbluex.liquidbounce.config.types.nesting.ChoiceConfigurable
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.movement.speed.ModuleSpeed
import net.ccbluex.liquidbounce.utils.client.Timer
import net.ccbluex.liquidbounce.utils.entity.moving
import net.ccbluex.liquidbounce.utils.entity.withStrafe
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket

/**
 * Ported from EDNeko `speedmodes/intave/Intave1255`.
 *
 * Three grounded hops that suppress the XZ motion and spoof `onGround=false`,
 * then a damage-triggered burst that strafes at 3.2x base speed for at most 16
 * ticks before switching the module back off.
 *
 * Note: the original guarded its damage flag with
 * `if (hurtTime > 0 && hasDamaged) hasDamaged = true`, so the flag could never
 * become true on its own and the burst was unreachable. That looks like a bug,
 * so the flag is simply set once the player has been hurt; this is the only
 * behavioural change in this mode.
 */
class SpeedIntave1255(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("Intave12.5.5", parent) {

    private var stage = 0
    private var ticks = 0
    private var spoofOffGround = false

    override fun onUpdate() {
        if (stage < 3) {
            stopXZ()
            spoofOffGround = true
            if (player.isOnGround) {
                player.jump()
                stage++
            }
            return
        }

        spoofOffGround = false
        if (player.hurtTime > 0) {
            damaged = true
        }
        if (!damaged) {
            return
        }

        ticks++
        if (player.isOnGround) {
            player.velocity = player.velocity.withStrafe(speed = BASE_MOVE_SPEED * 3.2)
        } else {
            stopXZ()
        }

        if (ticks > 16) {
            stopXZ()
            ModuleSpeed.enabled = false
        }
    }

    private var damaged = false

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet
        if (spoofOffGround && packet is PlayerMoveC2SPacket) {
            packet.onGround = false
        }
    }

}

/**
 * Ported from EDNeko `speedmodes/intave/Intave1309`.
 *
 * Jumps while grounded and moving, then removes 0.2 from the jump velocity.
 */
class SpeedIntave1309(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("Intave13.0.9", parent) {

    override fun onUpdate() {
        if (!player.moving) {
            return
        }
        if (player.isOnGround) {
            player.jump()
            player.velocity.y -= 0.2
        }
    }

}

/**
 * Ported from EDNeko `speedmodes/intave/Intave140xA`.
 *
 * Rides an extreme timer: 300x for the single tick every 20 while grounded,
 * 0.05x otherwise.
 */
class SpeedIntave140xA(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("Intave14.0.x-A", parent) {

    override fun onUpdate() {
        if (!player.moving) {
            return
        }

        val timerSpeed = if (player.isOnGround && player.age % 20 == 0) 300.0f else 0.05f
        Timer.requestTimerSpeed(timerSpeed, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
    }

}

/**
 * Ported from EDNeko `speedmodes/intave/Intave140xB`.
 *
 * Jumps every `IntaveBTick` ticks while grounded under a 0.1x timer, and floats
 * at 1.75x while airborne.
 */
class SpeedIntave140xB(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("Intave14.0.x-B", parent) {

    private val intaveBTick by int("IntaveBTick", 5, 1..30)

    override fun onUpdate() {
        if (!player.moving) {
            return
        }

        if (player.isOnGround) {
            if (player.age % intaveBTick == 0) {
                player.jump()
            }
            Timer.requestTimerSpeed(0.1f, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
        } else {
            Timer.requestTimerSpeed(1.75f, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
        }
    }

}

/**
 * Ported from EDNeko `speedmodes/intave/Intave141x`.
 *
 * Hops while grounded and, once moving upwards, scales horizontal velocity by
 * 1.0015 per tick.
 */
class SpeedIntave141x(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("Intave14.1.x", parent) {

    override fun onUpdate() {
        if (!player.moving || player.isTouchingWater || player.isInLava || player.isClimbing) {
            return
        }

        if (player.isOnGround) {
            player.jump()
        }

        if (player.velocity.y > 0.003 && player.isSprinting) {
            factorXZ(1.0015)
        }
    }

}

/**
 * Ported from EDNeko `speedmodes/intave/Intave14New`.
 *
 * Jumps while grounded, then boosts horizontal velocity by 4% on the first
 * airborne tick and 2% on ticks two to four, with an optional 1.002x timer.
 */
class SpeedIntave14New(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("Intave14.8.4", parent) {

    private val useTimer by boolean("UseTimer", true)

    private var airTicks = 0

    override fun onUpdate() {
        if (player.isOnGround && player.moving) {
            player.jump()
        }

        airTicks = if (player.isOnGround) 0 else airTicks + 1

        when (airTicks) {
            1 -> factorXZ(1.04)
            2, 3, 4 -> factorXZ(1.02)
        }

        Timer.requestTimerSpeed(
            if (useTimer) 1.002f else 1.0f,
            Priority.NOT_IMPORTANT,
            ModuleSpeed
        )
    }

}

/**
 * Ported from EDNeko `speedmodes/intave/Intave2`.
 *
 * Optional ground strafe, a hop, and a 1.0015x horizontal boost under a 1.06x
 * timer while moving upwards.
 */
class SpeedIntave2(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("Intave2", parent) {

    private val groundStrafe by boolean("GroundStrafe", false)

    override fun onUpdate() {
        if (!player.moving) {
            return
        }

        if (player.isOnGround) {
            Timer.requestTimerSpeed(1.0f, Priority.NOT_IMPORTANT, ModuleSpeed)
            if (groundStrafe) {
                player.velocity = player.velocity.withStrafe(speed = 0.0)
            }
            player.jump()
        }

        if (player.velocity.y > 0.003) {
            factorXZ(1.0015)
            Timer.requestTimerSpeed(1.06f, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
        }
    }

}

/**
 * Ported from EDNeko `speedmodes/intave/Intave3`.
 *
 * Hops while grounded and picks the timer from the current fall distance:
 * 1.4x just after leaving the ground, 0.7x in the first 1.3 blocks and 1.0x
 * beyond that.
 */
class SpeedIntave3(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("Intave3", parent) {

    override fun onUpdate() {
        if (!player.moving) {
            return
        }

        if (player.isOnGround) {
            player.jump()
        }

        val timerSpeed = when {
            player.fallDistance >= 1.3 -> 1.0f
            player.fallDistance > 0.1 -> 0.7f
            !player.isOnGround && player.fallDistance <= 0.1 -> 1.4f
            else -> 1.0f
        }
        Timer.requestTimerSpeed(timerSpeed, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
    }

}

/**
 * Ported from EDNeko `speedmodes/intave/IntaveHop`.
 *
 * Same shape as [SpeedIntave2] but always resets the timer on the ground and
 * exposes the strafe toggle under EDNeko's own name.
 */
class SpeedIntaveHop(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("IntaveHop", parent) {

    private val groundStrafe by boolean("IntaveHopStrafe", false)

    override fun onUpdate() {
        if (!player.moving) {
            return
        }

        if (player.isOnGround) {
            Timer.requestTimerSpeed(1.0f, Priority.NOT_IMPORTANT, ModuleSpeed)
            if (groundStrafe) {
                player.velocity = player.velocity.withStrafe(speed = 0.0)
            }
            player.jump()
        }

        if (player.velocity.y > 0.003) {
            factorXZ(1.0015)
            Timer.requestTimerSpeed(1.06f, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
        }
    }

}

/**
 * Ported from EDNeko `speedmodes/intave/IntaveHop14`.
 *
 * A ~0.42 jump (nudged down in low-hop mode) with separate ground and air
 * timers, optional strafing and a 0.3% horizontal boost per tick while moving
 * upwards.
 */
class SpeedIntaveHop14(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("IntaveHop14", parent) {

    private val intaveLowHop by boolean("IntaveLowHop", false)
    private val strafeStrength by float("StrafeStrength", 1.0f, 0f..2f)
    private val groundTimer by float("GroundTimer", 1.0f, 0.1f..3f)
    private val airTimer by float("AirTimer", 1.0f, 0.1f..3f)
    private val boost by boolean("Boost", true)
    private val initialBoostMultiplier by float("InitialBoostMultiplier", 1.0f, 0f..5f)

    override fun onUpdate() {
        if (!player.moving || player.isTouchingWater || player.isClimbing) {
            return
        }

        if (player.isOnGround) {
            player.velocity.y = 0.42 - if (intaveLowHop) 1.7E-14 else 0.0
            if (player.isSprinting) {
                player.velocity = player.velocity.withStrafe(strength = strafeStrength.toDouble())
            }
            Timer.requestTimerSpeed(groundTimer, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
        } else {
            Timer.requestTimerSpeed(airTimer, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
        }

        if (boost && player.velocity.y > 0.003 && player.isSprinting) {
            factorXZ(1.0 + 0.003 * initialBoostMultiplier)
        }
    }

}

/**
 * Ported from EDNeko `speedmodes/intave/IntaveNew`.
 *
 * Cycles horizontal scaling and the timer by ground state / boost state, with an
 * optional auto-jump that sets vertical velocity to 0.22.
 */
class SpeedIntaveNew(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("IntaveNew", parent) {

    private val intaveNewAutoJump by boolean("IntaveNewAutoJump", true)

    private var boosted = false

    override fun onUpdate() {
        if (!player.moving) {
            // Release any boost we applied; the original only reset when the
            // timer still held one of its own values.
            Timer.requestTimerSpeed(1.0f, Priority.NOT_IMPORTANT, ModuleSpeed)
            return
        }

        if (player.isOnGround && !mc.options.jumpKey.isPressed && intaveNewAutoJump) {
            player.velocity.y = 0.22
        }

        if (player.isOnGround) {
            factorXZ(1.00666666666)
            Timer.requestTimerSpeed(1.1f, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
            boosted = false
        } else if (!boosted) {
            factorXZ(1.05)
            Timer.requestTimerSpeed(0.95f, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
            boosted = true
        } else {
            factorXZ(1.0015)
            Timer.requestTimerSpeed(1.0015f, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
        }
    }

}

/**
 * Ported from EDNeko `speedmodes/intave/IntaveTimer`.
 *
 * Hops while grounded, then rides a 1.7x timer just after take-off, 0.8x below
 * 1.3 blocks of fall and 1.0x beyond.
 */
class SpeedIntaveTimer(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("IntaveTimer", parent) {

    override fun onUpdate() {
        if (!player.moving) {
            return
        }

        if (player.isOnGround) {
            player.jump()
            return
        }

        val timerSpeed = when {
            player.fallDistance <= 0.1 -> 1.7f
            player.fallDistance < 1.3 -> 0.8f
            else -> 1.0f
        }
        Timer.requestTimerSpeed(timerSpeed, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
    }

}

/**
 * Ported from EDNeko `speedmodes/intave/IntaveTimerHop`.
 *
 * Hops while grounded and rides the same fall-distance timer ladder as
 * [SpeedIntaveTimer], but only reaches 1.4x in the first tenth of a block.
 */
class SpeedIntaveTimerHop(override val parent: ChoiceConfigurable<*>) : EdnekoSpeedMode("IntaveTimerHop", parent) {

    override fun onUpdate() {
        if (!player.moving) {
            return
        }

        if (player.isOnGround) {
            player.jump()
        }

        val timerSpeed = when {
            !player.isOnGround && player.fallDistance <= 0.1 -> 1.4f
            player.fallDistance > 0.1 && player.fallDistance < 1.3 -> 0.7f
            player.fallDistance >= 1.3 -> 1.0f
            else -> 1.0f
        }
        Timer.requestTimerSpeed(timerSpeed, Priority.IMPORTANT_FOR_USAGE_1, ModuleSpeed)
    }

}
