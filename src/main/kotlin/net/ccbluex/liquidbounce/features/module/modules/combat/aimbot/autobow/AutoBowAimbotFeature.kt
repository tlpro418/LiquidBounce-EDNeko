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

package net.ccbluex.liquidbounce.features.module.modules.combat.aimbot.autobow

import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.modules.combat.aimbot.ModuleAutoBow
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsConfigurable
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.projectiles.PredictiveProjectileAngleCalculator
import net.ccbluex.liquidbounce.utils.combat.TargetPriority
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.ccbluex.liquidbounce.utils.entity.LinearPositionExtrapolation
import net.ccbluex.liquidbounce.utils.entity.PlayerSimulationCache
import net.ccbluex.liquidbounce.utils.entity.PositionExtrapolation
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.render.OverlayTargetRenderer
import net.ccbluex.liquidbounce.utils.render.trajectory.TrajectoryInfo
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.BowItem
import net.minecraft.item.CrossbowItem
import net.minecraft.item.TridentItem
import net.minecraft.util.math.Vec3d
import kotlin.math.roundToInt

/**
 * Ticks a bow has to be drawn for to deliver full power.
 */
private const val FULL_CHARGE_TICKS = 20

/**
 * Vertical offset of the arrow spawn position relative to the shooter's eyes, as used by the vanilla bow.
 */
private const val ARROW_SPAWN_OFFSET = 0.1

/**
 * Upper bound of the prediction horizon. A fully drawn bow carries over a hundred blocks, which takes
 * around 75 ticks, so this leaves plenty of headroom while keeping the player simulation bounded.
 */
private const val MAX_PREDICTION_TICKS = 120

/**
 * Corrects the aim towards the enemies around the player, leading moving targets by the time the arrow
 * needs to reach them.
 *
 * The bow is never drawn by this feature - it only takes over while the player is already holding the bow
 * drawn back, and by default only once it is drawn to full power. How the actual turn is performed is
 * fully configurable and shared with the combat rotation presets.
 */
object AutoBowAimbotFeature : ToggleableConfigurable(ModuleAutoBow, "BowAimbot", true) {

    // Target
    val targetTracker = TargetTracker(TargetPriority.DISTANCE)

    /**
     * The very same rotation engine the kill aura uses, including the short stop, fail, noise and
     * randomization processors which are only available to combat specific configurings.
     */
    val rotationConfigurable = RotationsConfigurable(this, combatSpecific = true)

    /**
     * Whether the bow has to be drawn to full power before the aim is corrected.
     */
    private val fullChargeOnly by boolean("FullChargeOnly", true)

    /**
     * Point of the target's hitbox the arrow is aimed at, as a fraction of its height.
     */
    private val aimHeight by float("AimHeight", 0.5f, 0f..1f)

    private val angleCalculator = PredictiveProjectileAngleCalculator()

    init {
        tree(targetTracker)
        tree(rotationConfigurable)
    }

    private val targetRenderer = tree(OverlayTargetRenderer(ModuleAutoBow))

    @Suppress("unused")
    private val tickRepeatable = tickHandler {
        targetTracker.reset()

        val projectileInfo = currentProjectileInfo() ?: return@tickHandler
        // Arrows leave the bow slightly below the eyes, which matters at long ranges.
        val sourcePos = Vec3d(player.x, player.eyeY - ARROW_SPAWN_OFFSET, player.z)

        angleCalculator.aimHeightFraction = aimHeight.toDouble()

        var rotation: Rotation? = null
        targetTracker.selectFirst { enemy ->
            rotation = angleCalculator.calculateAngleFor(
                projectileInfo = projectileInfo,
                sourcePos = sourcePos,
                targetPosFunction = predictionFor(enemy),
                targetShape = enemy.dimensions,
            )

            rotation != null
        } ?: return@tickHandler

        RotationManager.setRotationTarget(
            rotation!!,
            priority = Priority.IMPORTANT_FOR_USAGE_1,
            provider = ModuleAutoBow,
            configurable = rotationConfigurable
        )
    }

    /**
     * Trajectory of the projectile the player is currently holding, or `null` if nothing ready to be fired
     * is held.
     */
    private fun currentProjectileInfo(): TrajectoryInfo? {
        val activeStack = player.activeItem ?: return null

        return when (activeStack.item) {
            is BowItem -> {
                if (fullChargeOnly && player.itemUseTime < FULL_CHARGE_TICKS) {
                    return null
                }

                TrajectoryInfo.bowWithUsageDuration(player.itemUseTime)
            }
            is CrossbowItem -> if (CrossbowItem.isCharged(activeStack)) TrajectoryInfo.BOW_FULL_PULL else null
            is TridentItem -> TrajectoryInfo.TRIDENT
            else -> null
        }
    }

    /**
     * Predicts where the enemy is going to be by the time the arrow arrives.
     *
     * The horizon has no artificial cap, unlike the default player extrapolation which stops at 30 ticks:
     * a fully drawn bow carries well over a hundred blocks, and an enemy which keeps running for that
     * long has to be led by all of it.
     */
    private fun predictionFor(enemy: LivingEntity): PositionExtrapolation {
        if (enemy !is PlayerEntity) {
            return LinearPositionExtrapolation(enemy)
        }

        val simulation = PlayerSimulationCache.getSimulationForOtherPlayers(enemy)

        return object : PositionExtrapolation {
            override fun getPositionInTicks(ticks: Double): Vec3d =
                simulation.getSnapshotAt(ticks.roundToInt().coerceIn(0, MAX_PREDICTION_TICKS)).pos
        }
    }

    @Suppress("unused")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val target = targetTracker.target ?: return@handler

        with(event.context) {
            targetRenderer.render(target, event.tickDelta)
        }
    }

}
