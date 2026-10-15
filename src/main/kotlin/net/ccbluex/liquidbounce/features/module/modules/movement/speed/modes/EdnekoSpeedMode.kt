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
 * Base class for the speed modes ported from EDNeko
 * (github.com/RouQingNeko1024/EDNeko, GPL-3.0).
 *
 * EDNeko's `SpeedMode` is a plain class whose hooks the `Speed` module invokes
 * by hand (`onUpdate`, `onMove`, `onJump`, `onTick`, ...). LiquidBounce modes are
 * `Choice`es that are themselves `EventListener`s, so the hooks are registered
 * here once and subclasses just override the ones they need. Ported mode bodies
 * therefore stay close to the original.
 *
 * 1.8.9 -> 1.21.8 translations applied throughout:
 *
 *  | EDNeko (MCP 1.8.9)                        | LiquidBounce (Yarn 1.21.8)                    |
 *  |-------------------------------------------|-----------------------------------------------|
 *  | `mc.timer.timerSpeed = x`                 | `Timer.requestTimerSpeed(x, ..., ModuleSpeed)`|
 *  | `player.motionX/Y/Z *= f`                 | `player.velocity.x/y/z *= f`                  |
 *  | `MovementUtils.strafe(speed)`             | `player.velocity = velocity.withStrafe(speed)`|
 *  | `PlayerExtension.isMoving(player)`        | `player.moving`                               |
 *  | `PlayerExtension.tryJump(player)`         | `player.jump()`                               |
 *  | `player.isInLiquid`                       | `player.isTouchingWater`                      |
 *  | `player.func_70617_f_()` (on ladder)      | `player.isClimbing`                           |
 *  | `player.field_70143_R` (fall distance)    | `player.fallDistance`                         |
 *  | `player.func_70644_a(Potion.moveSpeed)`   | `player.getStatusEffect(StatusEffects.SPEED)` |
 * ---------------------------------------------------------------------------
 */
package net.ccbluex.liquidbounce.features.module.modules.movement.speed.modes

import net.ccbluex.liquidbounce.config.types.nesting.Choice
import net.ccbluex.liquidbounce.config.types.nesting.ChoiceConfigurable
import net.ccbluex.liquidbounce.event.events.PlayerJumpEvent
import net.ccbluex.liquidbounce.event.events.PlayerMoveEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.utils.math.copy

abstract class EdnekoSpeedMode(name: String, override val parent: ChoiceConfigurable<*>) : Choice(name) {

    /** EDNeko's `onUpdate` - runs once per client tick. */
    protected open fun onUpdate() {
    }

    /** EDNeko's `onMove` - runs when the player moves. */
    protected open fun onMove(event: PlayerMoveEvent) {
    }

    /** EDNeko's `onJump` - runs when the player jumps. */
    protected open fun onJump(event: PlayerJumpEvent) {
    }

    @Suppress("unused")
    private val updateHandler = tickHandler {
        onUpdate()
    }

    @Suppress("unused")
    private val moveHandler = handler<PlayerMoveEvent> { event ->
        onMove(event)
    }

    @Suppress("unused")
    private val jumpHandler = handler<PlayerJumpEvent> { event ->
        onJump(event)
    }

    /** EDNeko's `PlayerExtension.stopXZ`. */
    protected fun stopXZ() {
        player.velocity = player.velocity.copy(x = 0.0, z = 0.0)
    }

    /** EDNeko's `SomeUtil.reduceXZ` / `factorXZ`: scales horizontal velocity. */
    protected fun factorXZ(factor: Double) {
        player.velocity.x *= factor
        player.velocity.z *= factor
    }

    protected companion object {
        /** The NCP base move speed EDNeko's `MovementUtils.getBaseMoveSpeed()` returns. */
        const val BASE_MOVE_SPEED = 0.2873
    }

}
