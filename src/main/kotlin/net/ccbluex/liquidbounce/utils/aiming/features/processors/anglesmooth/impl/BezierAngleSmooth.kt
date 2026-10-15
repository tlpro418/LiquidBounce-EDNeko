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
 * Cubic Bezier aim path.
 *
 * Derived from EDNeko's `utils/rotation/rotationAdditions/BezierRotation`
 * (github.com/RouQingNeko1024/EDNeko, GPL-3.0), which is one of the few parts of
 * its rotation subsystem that is a real algorithm rather than a generated choice
 * entry. LiquidBounce's stock angle smooths (Linear, Sigmoid, Interpolation,
 * Acceleration, Minarai) all approach the target along a straight line per axis;
 * this one curves the path, with the two control points placed at one and two
 * thirds of the pitch delta exactly as the original does.
 *
 * Faithful to the original:
 *  - `bezierBlend` is the smoothstep `t * t * (3 - 2t)`.
 *  - the pitch component is a genuine cubic Bezier evaluated at the blended `t`.
 *  - yaw is interpolated across the wrapped (shortest) delta.
 *
 * Deviations, both documented inline:
 *  - the original's `fatigueRotation` cross-applies the yaw and pitch fatigue
 *    factors to each other (a yaw overflow scales the pitch delta and vice
 *    versa). That looks like a bug, so the fatigue limit is applied per axis.
 *  - the original's setting defaults are not recoverable from the decompiled
 *    constructor, so the defaults here are chosen to be usable out of the box.
 * ---------------------------------------------------------------------------
 */
package net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl

import net.ccbluex.liquidbounce.config.types.nesting.ChoiceConfigurable
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.AngleSmooth
import net.ccbluex.liquidbounce.utils.kotlin.random
import kotlin.math.abs

class BezierAngleSmooth(parent: ChoiceConfigurable<*>) : AngleSmooth("Bezier", parent) {

    private val bezierSpeed by float("BezierSpeed", 1.0f, 0.1f..4.0f)
    private val yawStep by floatRange("YawStep", 0.35f..0.6f, 0.01f..1f)
    private val pitchStep by floatRange("PitchStep", 0.35f..0.6f, 0.01f..1f)

    /** Per-tick cap on how far a single axis may move, in degrees. */
    private val useFatigue by boolean("Fatigue", false)
    private val maxYawChange by float("MaxYawChange", 45f, 1f..180f, "°")
    private val maxPitchChange by float("MaxPitchChange", 30f, 1f..180f, "°")

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation = step(currentRotation, targetRotation)

    override fun calculateTicks(currentRotation: Rotation, targetRotation: Rotation): Int {
        var simulated = currentRotation
        var ticks = -1

        do {
            simulated = step(simulated, targetRotation)
            ticks++
        } while (!simulated.approximatelyEquals(targetRotation) && ticks < 80)

        return ticks
    }

    /**
     * One tick of the Bezier path. Kept separate from [process] because the path
     * depends only on the current and target rotations, which lets
     * [calculateTicks] simulate without fabricating a [RotationTarget].
     */
    private fun step(currentRotation: Rotation, targetRotation: Rotation): Rotation {
        val speed = bezierSpeed
        val yawT = (yawStep.random() * speed).coerceIn(0f, 1f)
        val pitchT = (pitchStep.random() * speed).coerceIn(0f, 1f)

        // Shortest way round, so crossing the -180/180 seam does not spin.
        val yawDelta = wrapDegrees(targetRotation.yaw - currentRotation.yaw)
        val pitchDelta = targetRotation.pitch - currentRotation.pitch

        // Control points at one and two thirds of the pitch delta.
        val control1 = currentRotation.pitch + pitchDelta / 3f
        val control2 = currentRotation.pitch + pitchDelta * 2f / 3f

        val yaw = currentRotation.yaw + yawDelta * bezierBlend(yawT)
        val pitch = cubicBezier(
            currentRotation.pitch, control1, control2, targetRotation.pitch,
            bezierBlend(pitchT)
        )

        val limitedYaw = if (useFatigue) {
            limitChange(yaw - currentRotation.yaw, maxYawChange)
        } else {
            yaw - currentRotation.yaw
        }
        val limitedPitch = if (useFatigue) {
            limitChange(pitch - currentRotation.pitch, maxPitchChange)
        } else {
            pitch - currentRotation.pitch
        }

        return Rotation(
            currentRotation.yaw + limitedYaw,
            (currentRotation.pitch + limitedPitch).coerceIn(-90f, 90f)
        )
    }

    /** The original's easing: smoothstep, `t * t * (3 - 2t)`. */
    private fun bezierBlend(t: Float): Float = t * t * (3f - 2f * t)

    /** Standard cubic Bezier evaluation at `t`. */
    private fun cubicBezier(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val u = 1f - t
        return u * u * u * p0 +
            3f * u * u * t * p1 +
            3f * u * t * t * p2 +
            t * t * t * p3
    }

    /**
     * Clamps a per-tick delta to `limit`, easing the tail off as the requested
     * delta approaches the limit so the aim decelerates instead of clipping.
     */
    private fun limitChange(delta: Float, limit: Float): Float {
        val clamped = delta.coerceIn(-limit, limit)
        if (abs(delta) < limit) {
            return clamped
        }
        val overflow = abs(delta % limit) / limit
        return clamped * (1f - overflow)
    }

    private fun wrapDegrees(angle: Float): Float {
        var wrapped = angle % 360f
        if (wrapped >= 180f) {
            wrapped -= 360f
        }
        if (wrapped < -180f) {
            wrapped += 360f
        }
        return wrapped
    }

}
