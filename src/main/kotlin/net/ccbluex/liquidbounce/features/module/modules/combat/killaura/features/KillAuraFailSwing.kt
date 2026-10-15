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
package net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features

import net.ccbluex.liquidbounce.config.types.nesting.NoneChoice
import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraClicker.attack
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura.validateAttack
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraFailSwing.additionalRange
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraNotifyWhenFail.Box
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraNotifyWhenFail.Sound
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.combat.findEnemy
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.minecraft.entity.Entity
import net.minecraft.util.Hand
import kotlin.math.pow

/**
 * Keeps swinging on the click schedule regardless of whether the hit lands.
 *
 * Ported from EDNeko's `KillAuraBypass.FailSwing` option, which defaults to
 * enabled and attacks even when the target is not reachable. Upstream
 * LiquidBounce's version of this toggle was off by default and only faked a swing
 * when the crosshair raycast missed, so the default is changed here to match the
 * option this replaces.
 */
internal object KillAuraFailSwing : ToggleableConfigurable(ModuleKillAura, "FailSwing", true) {

    /**
     * Additional range for fail swing to work
     */
    private val additionalRange by floatRange("AdditionalRange", 2.5f..3f, 0f..10f).onChanged { range ->
        currentAdditionalRange = range.random()
    }
    val mode = choices(this, "NotifyWhenFail", activeIndex = 1) {
        arrayOf(NoneChoice(it), Box, Sound)
    }.apply {
        doNotIncludeAlways()
    }

    /**
     * Current additional range - randomized from [additionalRange].
     * This will change every time we attack an entity.
     */
    private var currentAdditionalRange: Float = this.additionalRange.random()

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent>{
        currentAdditionalRange = this.additionalRange.random()
    }

    /**
     * Swings on the click schedule even though the hit will not land.
     *
     * This follows EDNeko's `KillAuraBypass.FailSwing`, which keeps attacking
     * whenever it has a target and its rotations are active - the target being
     * `!hittable` does not stop the swing from going out. Upstream LiquidBounce
     * instead required the crosshair raycast to *miss* before faking a swing
     * (see the `HitResult.Type.MISS` gate that used to live here), so nothing was
     * sent while an aim was still travelling towards the target. Dropping that
     * gate gives the continuous clicking this option is expected to provide.
     */
    suspend fun dealWithFakeSwing(target: Entity?) {
        if (!enabled || !validateAttack()) {
            return
        }

        val range = ModuleKillAura.range + currentAdditionalRange
        val entity = target ?: world.findEnemy(0f..range.toFloat()) ?: return

        // No raycast-type check: a hit already being in progress must not suppress
        // the swing, and a miss must not be required either.
        if (entity.isRemoved || entity.squaredBoxedDistanceTo(player) > range.pow(2)) {
            return
        }

        // Make it seem like we are blocking
        KillAuraAutoBlock.makeSeemBlock()

        attack {
            // [this.crosshairTarget == null] results in a limited attack speed
            if (interaction.hasLimitedAttackSpeed()) {
                mc.attackCooldown = 10
            }

            player.swingHand(Hand.MAIN_HAND)

            // Notify the user about the failed hit
            KillAuraNotifyWhenFail.notifyForFailedHit(entity, RotationManager.currentRotation
                ?: player.rotation)
            true
        }
    }

}
