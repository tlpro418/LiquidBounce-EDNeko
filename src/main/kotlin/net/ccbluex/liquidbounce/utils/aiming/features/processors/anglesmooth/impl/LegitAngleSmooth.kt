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
 *
 */
package net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl

import net.ccbluex.liquidbounce.config.types.nesting.ChoiceConfigurable
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.AngleSmooth
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.minecraft.entity.Entity
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Port of EDNeko's `Legitimize` rotation, meaning `RotationUtils.performAngleChange` with
 * `legitimize = true` together with the `LegitFactor` strength range.
 *
 * The step of a tick is not simply the remaining delta clamped to a speed. It is clamped first, then
 * blended with the step of the previous tick, which gives the rotation momentum: on a large turn it
 * keeps moving past the target and the following tick pulls it back, while small corrections stay
 * smooth because the step changes gradually instead of jumping to the full delta.
 */
@Suppress("MagicNumber")
class LegitAngleSmooth(parent: ChoiceConfigurable<*>) : AngleSmooth("Legit", parent) {

    /**
     * Turn speed in degrees per tick before [factor] is applied.
     */
    private val horizontalSpeed by float("HorizontalSpeed", 180f, 1f..180f)
    private val verticalSpeed by float("VerticalSpeed", 180f, 1f..180f)

    /**
     * EDNEko's `LegitFactor`, the strength of the whole effect. `0` pins the rotation in place, `1`
     * keeps the configured speed and anything above lets the rotation run faster and overshoot further.
     */
    private val factor by floatRange("LegitFactor", 0.9f..1.1f, 0.0f..2.0f)

    /**
     * How much of the remaining delta is mixed into the previous step. Lower values keep more momentum,
     * so the overshoot lasts longer.
     */
    private val smoothing by floatRange("Smoothing", 0.3f..0.7f, 0.0f..1.0f)

    /**
     * Remaining angle at which the rotation settles onto the target instead of overshooting again.
     */
    private val minRotationDifference by float("MinRotationDifference", 0.5f, 0f..10f)

    private var lastEntity: Entity? = null
    private var lastYawStep = 0f
    private var lastPitchStep = 0f

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation {
        // A new target must not inherit the momentum of the previous fight
        if (rotationTarget.entity !== lastEntity) {
            lastEntity = rotationTarget.entity
            lastYawStep = 0f
            lastPitchStep = 0f
        }

        val delta = currentRotation.rotationDeltaTo(targetRotation)

        val yawStep = step(delta.deltaYaw, lastYawStep, horizontalSpeed)
        val pitchStep = step(delta.deltaPitch, lastPitchStep, verticalSpeed)

        lastYawStep = yawStep
        lastPitchStep = pitchStep

        return Rotation(
            currentRotation.yaw + yawStep,
            (currentRotation.pitch + pitchStep).coerceIn(-90f, 90f)
        )
    }

    /**
     * One axis of EDNEko's `performAngleChange` with `legitimize = true`.
     */
    private fun step(delta: Float, lastStep: Float, speed: Float): Float {
        if (delta == 0f) {
            return 0f
        }

        val limit = speed * factor.random()

        // Clamp to the turn speed first, exactly like the original does
        val clamped = delta.coerceIn(-limit, limit)

        // Then blend in the momentum of the previous tick. A resulting step larger than the remaining
        // delta is precisely what makes the rotation overshoot and correct itself on the next tick.
        val blended = lastStep + (clamped - lastStep) * smoothing.random()

        return if (abs(blended) <= minRotationDifference && abs(clamped) < abs(delta)) {
            delta
        } else {
            blended
        }
    }

    override fun calculateTicks(currentRotation: Rotation, targetRotation: Rotation): Int {
        val slowest = max(horizontalSpeed, verticalSpeed) * factor.start

        if (slowest <= 0f) {
            return 80
        }

        val delta = currentRotation.rotationDeltaTo(targetRotation)
        val distance = max(abs(delta.deltaYaw), abs(delta.deltaPitch))

        return ceil(distance / slowest).toInt().coerceIn(1, 80)
    }

}
