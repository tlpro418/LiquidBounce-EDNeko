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
 * Velocity mode ported from EDNeko (github.com/RouQingNeko1024/EDNeko, GPL-3.0).
 *
 * EDNeko splits knockback handling across three modules - `Velocity`,
 * `Velocity2` and `AntiKnockBack` - all dispatched by a string `Mode` setting.
 * LiquidBounce merged them into one `Velocity` module whose modes are classes,
 * so the ported mode becomes a `VelocityMode` here.
 *
 * EDNeko's `Velocity` mode `Polar` is deliberately NOT ported: it is inert
 * upstream. The string `"polar"` occurs exactly once in `Velocity.java` - its
 * velocity-packet branch, which only assigns `hasReceivedVelocity = true` - the
 * packet is neither cancelled nor modified, no `getMode() == "Polar"` comparison
 * exists, `polarHurtTime` is written but never read, and the per-tick switch that
 * applies the knockback changes has no `polar` case. Its functional polar-named
 * siblings (`OldPolar`, `IntavePolarFlag`) are out of scope.
 * ---------------------------------------------------------------------------
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.velocity.mode

import net.ccbluex.liquidbounce.event.tickHandler

/**
 * Ported from EDNeko `Velocity2`, mode `IntaveStrong`.
 *
 * While the player is in hurt time, horizontal velocity is multiplied by
 * [factor] every tick, so knockback is bled off instead of cancelled outright.
 * EDNeko's setting was `IntaveStrong-Factor`, default 0.6.
 */
object VelocityIntaveStrong : VelocityMode("IntaveStrong") {

    private val factor by float("Factor", 0.6f, 0f..1f)

    @Suppress("unused")
    private val tickHandler = tickHandler {
        if (player.hurtTime > 0) {
            player.velocity.x *= factor.toDouble()
            player.velocity.z *= factor.toDouble()
        }
    }

}
