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
 */

package net.ccbluex.liquidbounce.utils.aiming.projectiles

import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.entity.PositionExtrapolation
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfo
import net.minecraft.entity.EntityDimensions
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Longest flight time (in ticks) a single candidate shot is simulated for.
 */
private const val MAX_FLIGHT_TICKS = 300

/**
 * Step size (in degrees) of the coarse pitch scan which brackets the solution.
 */
private const val PITCH_SCAN_STEP = 1.0

/**
 * Pitches with a larger absolute value are not considered, because a shot close to vertical has almost no
 * horizontal component and therefore cannot reach a target in front of the shooter.
 */
private const val MAX_PITCH = 89.9

/**
 * Number of bisection steps used to refine a bracketed pitch.
 */
private const val REFINE_STEPS = 24

/**
 * Number of times the target position is predicted before the resulting aim is considered final.
 */
private const val INTERCEPT_ITERATIONS = 8

/**
 * Share of the freshly calculated flight time applied to the prediction horizon per iteration. Damping
 * keeps the horizon from oscillating for fast moving targets.
 */
private const val INTERCEPT_DAMPING = 0.6

/**
 * Prediction horizons changing by less than this (in ticks) are considered converged.
 */
private const val INTERCEPT_EPSILON = 0.001

/**
 * Error reported for shots which never reach the target plane. Only the sign is evaluated.
 */
private const val FALL_SHORT_ERROR = -1.0E6

/**
 * Misses larger than this can only be the [FALL_SHORT_ERROR] sentinel, never a measurement.
 */
private const val REACHED_ERROR_LIMIT = 1.0E5

/**
 * Shots which end up this far below both the shooter and the target are given up on.
 */
private const val FALL_MARGIN = 48.0

/**
 * Squared distance below which the target is considered to be inside the shooter.
 */
private const val MIN_TARGET_DISTANCE_SQUARED = 1.0E-6

/**
 * Shooter momentum below this (squared) is not worth compensating for.
 */
private const val COMPENSATION_THRESHOLD = 1.0E-6

/**
 * Multiplicative drag of a projectile per tick above which the difference to 1.0 is ignored.
 */
private const val DRAG_EPSILON = 1.0E-9

/**
 * Solves the rotation required to hit a target by simulating the actual projectile flight instead of
 * evaluating a closed form formula.
 *
 * Every candidate rotation is run through the same per-tick integration the game uses: the projectile
 * starts at the shooter's eyes, its initial velocity is derived from the rotation, the charge level and
 * the shooter's own momentum, and every following tick it is moved by its velocity before drag and
 * gravity are applied to it. Because the entire flight is simulated, the solution stays exact for every
 * charge level, every distance, every elevation and every amount of projectile drop, where the usual
 * parabolic approximations fall apart at long ranges.
 *
 * For a fixed yaw the shot error is monotonic in the pitch wherever a solution exists, so the pitch is
 * bracketed with a coarse scan and then bisected to full precision. Two brackets can exist - the flat arc
 * and the lobbed arc - and the flat one is always picked because it spends the least time in the air.
 *
 * Moving targets are handled by predicting their position [INTERCEPT_ITERATIONS] times, using the flight
 * time of the previous solution as the prediction horizon. The horizon therefore scales with the distance
 * on its own: a target 60 blocks away is led by the roughly 30 ticks the arrow needs to get there, while
 * a target 5 blocks away is led by almost nothing.
 */
class PredictiveProjectileAngleCalculator : ProjectileAngleCalculator() {

    /**
     * Fraction of the target's height the projectile is aimed at. `0.5` is the middle of the hitbox,
     * `1.0` its very top.
     */
    var aimHeightFraction: Double = 0.5

    override fun calculateAngleFor(
        projectileInfo: TrajectoryInfo,
        sourcePos: Vec3d,
        targetPosFunction: PositionExtrapolation,
        targetShape: EntityDimensions,
    ): Rotation? {
        val momentum = shooterMomentum(projectileInfo)
        val aimOffset = targetShape.height * aimHeightFraction

        val initialTarget = targetPosFunction.getPositionInTicks(0.0).add(0.0, aimOffset, 0.0)
        var interceptTicks = (initialTarget.distanceTo(sourcePos) / projectileInfo.initialVelocity)
            .coerceIn(0.0, MAX_FLIGHT_TICKS.toDouble())

        var solution: Aim? = null

        repeat(INTERCEPT_ITERATIONS) {
            val aimPoint = targetPosFunction.getPositionInTicks(interceptTicks).add(0.0, aimOffset, 0.0)
            val aim = solve(projectileInfo, sourcePos, aimPoint, momentum) ?: return solution?.rotation

            solution = aim

            val delta = aim.flightTicks - interceptTicks
            interceptTicks += delta * INTERCEPT_DAMPING

            if (abs(delta) < INTERCEPT_EPSILON) {
                return aim.rotation
            }
        }

        return solution?.rotation
    }

    /**
     * The momentum the shooter hands over to the projectile on spawn, mirroring the vanilla
     * `setVelocity(Entity, ...)` implementation which adds the shooter's velocity to the projectile.
     */
    private fun shooterMomentum(projectileInfo: TrajectoryInfo): Vec3d {
        if (!projectileInfo.copiesPlayerVelocity) {
            return Vec3d.ZERO
        }

        return Vec3d(
            player.velocity.x,
            if (player.isOnGround) 0.0 else player.velocity.y,
            player.velocity.z,
        )
    }

    private fun solve(
        projectileInfo: TrajectoryInfo,
        sourcePos: Vec3d,
        aimPoint: Vec3d,
        momentum: Vec3d,
    ): Aim? {
        val straightLine = aimPoint.subtract(sourcePos)
        if (straightLine.lengthSquared() < MIN_TARGET_DISTANCE_SQUARED) {
            return null
        }

        val horizontal = Vec3d(straightLine.x, 0.0, straightLine.z)
        // Water drag only kicks in once the projectile itself is submerged, which cannot be known upfront
        // without raycasting every single simulated tick. The shooter's own state is used as the proxy.
        val drag = if (player.isTouchingWater) projectileInfo.dragInWater else projectileInfo.drag

        val yaw = if (horizontal.lengthSquared() < MIN_TARGET_DISTANCE_SQUARED) {
            player.yaw
        } else {
            Rotation.fromRotationVec(horizontal).yaw
        }

        val direct = ballisticsFor(projectileInfo, sourcePos, aimPoint, drag, yaw, momentum).scan()

        if (momentum.horizontalLengthSquared() < COMPENSATION_THRESHOLD) {
            return direct
        }

        // The shooter's own momentum is added to the projectile, so the direction the projectile ends up
        // travelling in is not exactly the direction the player looks in. Compensate for the share of the
        // travelled distance the momentum is responsible for.
        val lead = dragFactor(drag, direct.flightTicks)
        val compensated = horizontal.subtract(momentum.x * lead, 0.0, momentum.z * lead)
        if (compensated.lengthSquared() < MIN_TARGET_DISTANCE_SQUARED) {
            return direct
        }

        val compensatedYaw = Rotation.fromRotationVec(compensated).yaw
        return ballisticsFor(projectileInfo, sourcePos, aimPoint, drag, compensatedYaw, momentum).scan()
    }

    private fun ballisticsFor(
        projectileInfo: TrajectoryInfo,
        sourcePos: Vec3d,
        aimPoint: Vec3d,
        drag: Double,
        yaw: Float,
        momentum: Vec3d,
    ) = Ballistics(
        info = projectileInfo,
        source = sourcePos,
        aimPoint = aimPoint,
        drag = drag,
        yaw = yaw,
        momentum = momentum,
    )

    /**
     * Total share of the initial velocity still left after [ticks] ticks of multiplicative drag, which is
     * the geometric series `1 + drag + drag^2 + ... + drag^(ticks - 1)`.
     */
    private fun dragFactor(drag: Double, ticks: Double): Double {
        if (abs(1.0 - drag) < DRAG_EPSILON) {
            return ticks
        }

        return (1.0 - drag.pow(ticks)) / (1.0 - drag)
    }

}

private data class Aim(val rotation: Rotation, val flightTicks: Double)

private data class Shot(val error: Double, val flightTicks: Double, val reach: Double)

/**
 * A single ballistic setup: everything about a shot except the pitch, which is what gets solved for.
 */
private class Ballistics(
    private val info: TrajectoryInfo,
    private val source: Vec3d,
    private val aimPoint: Vec3d,
    private val drag: Double,
    private val yaw: Float,
    private val momentum: Vec3d,
) {

    /**
     * Unit vector pointing from the shooter to the target.
     */
    private val forward: Vec3d = aimPoint.subtract(source).normalize()

    /**
     * Unit vector perpendicular to [forward], lying in the vertical plane. The perpendicular offset of the
     * projectile from the aim line, called the error, is measured along it.
     */
    private val up: Vec3d

    /**
     * Distance between shooter and target along [forward].
     */
    private val distance: Double = aimPoint.distanceTo(source)

    private val floorY: Double

    init {
        val right = forward.crossProduct(Vec3d(0.0, 1.0, 0.0))
        up = if (right.lengthSquared() < MIN_TARGET_DISTANCE_SQUARED) {
            // Looking straight up or down, so any perpendicular works.
            Vec3d(0.0, 0.0, 1.0)
        } else {
            right.crossProduct(forward).normalize()
        }

        floorY = min(source.y, aimPoint.y) - FALL_MARGIN
    }

    fun simulate(pitch: Double): Shot {
        val yawRadians = Math.toRadians(yaw.toDouble())
        val pitchRadians = Math.toRadians(pitch)
        // The vanilla velocity construction uses the rolled pitch for the vertical component only.
        val rollRadians = Math.toRadians(pitch + info.roll)

        var velocityX = -sin(yawRadians) * cos(pitchRadians)
        var velocityY = -sin(rollRadians)
        var velocityZ = cos(yawRadians) * cos(pitchRadians)

        val unitLength = sqrt(velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ)
        if (unitLength > 0.0) {
            val scale = info.initialVelocity / unitLength
            velocityX *= scale
            velocityY *= scale
            velocityZ *= scale
        }

        velocityX += momentum.x
        velocityY += momentum.y
        velocityZ += momentum.z

        var x = source.x
        var y = source.y
        var z = source.z

        var previousProgress = 0.0
        var previousError = 0.0

        for (tick in 1..MAX_FLIGHT_TICKS) {
            x += velocityX
            y += velocityY
            z += velocityZ

            val progress = (x - source.x) * forward.x + (y - source.y) * forward.y + (z - source.z) * forward.z
            val error = (x - aimPoint.x) * up.x + (y - aimPoint.y) * up.y + (z - aimPoint.z) * up.z

            if (progress >= distance) {
                val span = progress - previousProgress
                val fraction = if (span <= 0.0) 1.0 else (distance - previousProgress) / span

                return Shot(previousError + (error - previousError) * fraction, tick - 1 + fraction, distance)
            }

            if (y < floorY) {
                return Shot(FALL_SHORT_ERROR, tick.toDouble(), progress)
            }

            previousProgress = progress
            previousError = error

            // Drag and gravity are applied after the projectile has been moved, exactly like
            // AbstractArrow#tick does it.
            velocityX *= drag
            velocityY = velocityY * drag - info.gravity
            velocityZ *= drag
        }

        return Shot(FALL_SHORT_ERROR, MAX_FLIGHT_TICKS.toDouble(), previousProgress)
    }

    /**
     * Finds the flat arc which reaches the target.
     *
     * If no shot reaches the target plane at all, the one which gets the closest is used, and if even that
     * is out of the question the one which carries the furthest - the target is simply beyond what the bow
     * can do, and this is the closest the shooter can get to it.
     */
    fun scan(): Aim {
        var lowPitch = -MAX_PITCH
        var lowShot = simulate(lowPitch)

        var furthestPitch = lowPitch
        var furthestReach = lowShot.reach
        var furthestFlightTicks = lowShot.flightTicks

        var closestPitch = lowPitch
        var closestError = abs(lowShot.error)
        var closestFlightTicks = lowShot.flightTicks

        var candidate: Aim? = null

        var pitch = -MAX_PITCH + PITCH_SCAN_STEP
        while (pitch <= MAX_PITCH) {
            val shot = simulate(pitch)

            if ((lowShot.error < 0.0) != (shot.error < 0.0)) {
                // Two brackets may show up for a reachable target - the flat arc and the lobbed one. The
                // flat arc is the one which spends the least time in the air, and it is the only one that
                // still works against a target which keeps moving.
                val refined = refine(lowPitch, pitch, lowShot.error < 0.0)
                if (candidate == null || refined.flightTicks < candidate.flightTicks) {
                    candidate = refined
                }
            }

            if (abs(shot.error) < closestError) {
                closestError = abs(shot.error)
                closestPitch = pitch
                closestFlightTicks = shot.flightTicks
            }

            if (shot.reach > furthestReach) {
                furthestReach = shot.reach
                furthestPitch = pitch
                furthestFlightTicks = shot.flightTicks
            }

            lowPitch = pitch
            lowShot = shot
            pitch += PITCH_SCAN_STEP
        }

        val fallback = if (closestError < REACHED_ERROR_LIMIT) {
            Aim(Rotation(yaw, closestPitch.toFloat()), closestFlightTicks)
        } else {
            Aim(Rotation(yaw, furthestPitch.toFloat()), furthestFlightTicks)
        }

        return candidate ?: fallback
    }

    /**
     * Narrows a pitch bracket which contains a solution down to floating point precision.
     *
     * @param ascending whether the shot error rises with the pitch inside the bracket.
     */
    private fun refine(fromPitch: Double, toPitch: Double, ascending: Boolean): Aim {
        var low = fromPitch
        var high = toPitch

        repeat(REFINE_STEPS) {
            val middle = (low + high) / 2.0

            if ((simulate(middle).error < 0.0) == ascending) {
                low = middle
            } else {
                high = middle
            }
        }

        val pitch = (low + high) / 2.0
        return Aim(Rotation(yaw, pitch.toFloat()), simulate(pitch).flightTicks)
    }

}
