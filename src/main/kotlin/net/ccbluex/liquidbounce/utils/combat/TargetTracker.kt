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
package net.ccbluex.liquidbounce.utils.combat

import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.ccbluex.fastutil.objectLinkedSetOf
import net.ccbluex.liquidbounce.config.types.nesting.Configurable
import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.ValueType.*
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.client.*
import net.ccbluex.liquidbounce.utils.entity.getActualHealth
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.math.sq
import net.ccbluex.liquidbounce.utils.sorting.ComparatorChain
import net.minecraft.block.Blocks
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.mob.Angerable
import net.minecraft.entity.mob.HostileEntity
import net.minecraft.entity.player.PlayerEntity
import java.util.function.Predicate

/**
 * A target tracker to choose the best enemy to attack
 */
open class TargetTracker(
    defaultPriority: TargetPriority = TargetPriority.HEALTH,
    rangeValue: RangedValueProvider = NoneRangedValueProvider
) : TargetSelector(defaultPriority, rangeValue) {

    constructor(defaultPriority: TargetPriority = TargetPriority.HEALTH, range: RangedValue<*>) :
        this(defaultPriority, DummyRangedValueProvider(range))

    var target: LivingEntity? = null

    fun selectFirst(predicate: Predicate<LivingEntity>? = null): LivingEntity? {
        val enemies = targets()
        val selected = if (predicate != null) enemies.firstOrNull(predicate::test) else enemies.firstOrNull()
        return selected.also { this.target = it }
    }

    fun <R> select(evaluator: (LivingEntity) -> R): R? {
        for (enemy in targets()) {
            val value = evaluator(enemy)
            if (value != null) {
                target = enemy
                return value
            }
        }

        reset()
        return null
    }

    fun reset() {
        target = null
    }

    fun validate(predicate: Predicate<LivingEntity>? = null) {
        val target = target ?: return

        if (!validate(target) || predicate != null && !predicate.test(target)) {
            reset()
        }
    }
}

open class TargetSelector(
    defaultPriority: TargetPriority = TargetPriority.HEALTH,
    rangeValue: RangedValueProvider = NoneRangedValueProvider
) : Configurable("Target") {

    constructor(defaultPriority: TargetPriority = TargetPriority.HEALTH, range: RangedValue<*>) :
        this(defaultPriority, DummyRangedValueProvider(range))

    var closestSquaredEnemyDistance: Double = 0.0
        private set

    private val range = rangeValue.register(this)
    private val fov by float("FOV", 180f, 0f..180f)
    private val hurtTime by int("HurtTime", 10, 0..10)

    @Suppress("unused", "UnusedPrivateProperty")
    private val priority by multiEnumChoice(
        name = "Priority",
        default = objectLinkedSetOf(TargetPriority.TYPE, defaultPriority),
        canBeNone = false,
    ).onChanged { set ->
        comparator = ComparatorChain(comparisonFunctions = set.toTypedArray())
    }

    private var comparator: Comparator<in LivingEntity> = TargetPriority.TYPE

    /**
     * How a target is picked out of the sorted candidates. Ported from EDNeko's
     * `KillAura-Targeting > TargetMode`.
     *
     *  - [TargetMode.SINGLE] always takes the best candidate (LiquidBounce's original behaviour),
     *  - [TargetMode.SWITCH] cycles through the candidates instead of staring at one enemy,
     *  - [TargetMode.MULTI] attacks several candidates within the same click.
     */
    private val targetMode by enumChoice("TargetMode", TargetMode.SINGLE)

    /**
     * A candidate is only considered in [TargetMode.SWITCH] while it is within this angle of the
     * crosshair, so the aura does not spin around wildly.
     */
    private val maxSwitchFov by float("MaxSwitchFOV", 90f, 30f..180f)

    /**
     * Ticks an already attacked candidate is skipped in [TargetMode.SWITCH] before it becomes eligible
     * again. EDNEko expresses this in milliseconds; ticks are used here because the whole simulation is
     * tick based.
     */
    private val switchDelay by int("SwitchDelay", 15, 1..1000, "ticks")

    /**
     * Maximum amount of extra enemies attacked per click in [TargetMode.MULTI]. `0` means no limit.
     */
    private val limitedMultiTargets by int("LimitedMultiTargets", 0, 0..50)

    /**
     * Entity id to the tick it was attacked on, used to rotate through candidates in [TargetMode.SWITCH].
     */
    private val switchedTargets = HashMap<Int, Int>()

    /**
     * Whether multiple enemies should be attacked per click.
     */
    val isMultiTargetMode: Boolean
        get() = targetMode == TargetMode.MULTI

    /**
     * Amount of extra enemies to attack per click, `0` for unlimited.
     */
    val multiTargetLimit: Int
        get() = if (limitedMultiTargets <= 0) 0 else limitedMultiTargets

    /**
     * Marks [entity] as attacked, so [TargetMode.SWITCH] moves on to the next candidate.
     */
    fun markSwitched(entity: LivingEntity) {
        if (targetMode == TargetMode.SWITCH) {
            switchedTargets[entity.id] = player.age
        }
    }

    /**
     * Counts available targets.
     */
    fun countTargets(): Int = world.entities.count { entity ->
        entity is LivingEntity && validate(entity)
    }

    /**
     * Update should be called to always pick the best target out of the current world context
     */
    fun targets(): MutableList<LivingEntity> {
        val entities = ObjectArrayList<LivingEntity>()

        for (entity in world.entities) {
            if (entity is LivingEntity && validate(entity)) {
                entities.add(entity)
            }
        }

        if (entities.isEmpty) {
            return entities
        }

        entities.sortWith(this.comparator)

        // Update max distance squared
        closestSquaredEnemyDistance = entities.minOf { it.squaredBoxedDistanceTo(player) }

        return applyTargetMode(entities)
    }

    /**
     * Applies [targetMode] to the sorted candidate list.
     */
    private fun applyTargetMode(entities: MutableList<LivingEntity>): MutableList<LivingEntity> =
        when (targetMode) {
            TargetMode.SINGLE -> entities
            TargetMode.SWITCH -> switchCandidates(entities)
            TargetMode.MULTI -> limitCandidates(entities)
        }

    /**
     * Drops candidates that were attacked within [switchDelay] ticks or that sit too far outside the
     * crosshair, so the aura rotates through the available enemies instead of staring at one. Once
     * everything has been attacked the round restarts.
     */
    private fun switchCandidates(entities: MutableList<LivingEntity>): MutableList<LivingEntity> {
        val now = player.age

        switchedTargets.entries.removeIf { now - it.value >= switchDelay }

        val available = ObjectArrayList<LivingEntity>()
        for (entity in entities) {
            if (switchedTargets.containsKey(entity.id)) {
                continue
            }
            // The best candidate is always kept. Filtering it out as well would leave an enemy that
            // happens to sit outside MaxSwitchFOV completely unaimed, because the aura would never turn
            // towards it in the first place - a self-locking loop.
            if (available.isNotEmpty() && RotationUtil.crosshairAngleToEntity(entity) > maxSwitchFov) {
                continue
            }
            available.add(entity)
        }

        if (available.isEmpty()) {
            switchedTargets.clear()
            return entities
        }

        return available
    }

    /**
     * Caps the candidate list to [limitedMultiTargets] entries, `0` keeps everything.
     */
    private fun limitCandidates(entities: MutableList<LivingEntity>): MutableList<LivingEntity> =
        if (limitedMultiTargets <= 0 || entities.size <= limitedMultiTargets) {
            entities
        } else {
            ObjectArrayList(entities.subList(0, limitedMultiTargets))
        }

    open fun validate(entity: LivingEntity) =
        entity !== player
            && !entity.isRemoved
            && entity.hurtTime <= hurtTime
            && validateRange(entity)
            && entity.shouldBeAttacked()
            && fov >= RotationUtil.crosshairAngleToEntity(entity)

    private fun validateRange(entity: LivingEntity): Boolean {
        if (range == null) return true

        val distanceSq = entity.squaredBoxedDistanceTo(player)
        val range = range.get()

        @Suppress("UNCHECKED_CAST")
        return when (this.range.valueType) {
            FLOAT -> distanceSq <= (range as Float).sq()
            FLOAT_RANGE ->
                distanceSq >= (range as ClosedFloatingPointRange<Float>).start.sq()
                && distanceSq <= range.endInclusive.sq()
            INT -> distanceSq <= (range as Int).sq()
            INT_RANGE -> distanceSq >= (range as IntRange).first.sq() && distanceSq <= range.last.sq()
            else -> true
        }
    }

    val maxRange: Float
        get() {
            if (range == null) return Float.MAX_VALUE

            val value = range.get()

            @Suppress("UNCHECKED_CAST")
            return when (range.valueType) {
                FLOAT -> value as Float
                FLOAT_RANGE -> (value as ClosedFloatingPointRange<Float>).endInclusive
                INT -> (value as Int).toFloat()
                INT_RANGE -> (value as IntRange).last.toFloat()
                else -> Float.MAX_VALUE
            }
        }

}

enum class TargetPriority(override val choiceName: String) : NamedChoice, Comparator<LivingEntity> {
    /**
     * Player first
     */
    TYPE("Type") {
        private fun weight(entity: LivingEntity): Int =
            when (entity) {
                is PlayerEntity -> 0
                is HostileEntity -> 1
                is Angerable if entity.angryAt == player.uuid -> 2
                else -> Int.MAX_VALUE
            }

        override fun compare(o1: LivingEntity, o2: LivingEntity): Int =
            weight(o1) compareTo weight(o2)
    },

    /**
     * Lowest health first
     */
    HEALTH("Health") {
        override fun compare(o1: LivingEntity, o2: LivingEntity): Int =
            o1.getActualHealth() compareTo o2.getActualHealth()
    },

    /**
     * Closest to you first
     */
    DISTANCE("Distance") {
        override fun compare(o1: LivingEntity, o2: LivingEntity): Int =
            o1.squaredBoxedDistanceTo(player) compareTo o2.squaredBoxedDistanceTo(player)
    },

    /**
     * Closest to your crosshair first
     */
    DIRECTION("Direction") {
        override fun compare(o1: LivingEntity, o2: LivingEntity): Int =
            RotationUtil.crosshairAngleToEntity(o1) compareTo RotationUtil.crosshairAngleToEntity(o2)
    },

    /**
     * With the lowest hurt time first
     */
    HURT_TIME("HurtTime") {
        override fun compare(o1: LivingEntity, o2: LivingEntity): Int =
            o1.hurtTime compareTo o2.hurtTime
    },

    /**
     * Oldest entity first
     */
    AGE("Age") {
        override fun compare(o1: LivingEntity, o2: LivingEntity): Int =
            o2.age compareTo o1.age
    },

    /**
     * Least armour first. Ported from EDNeko's `KillAura-Targeting` priority of the same name.
     */
    ARMOR("Armor") {
        override fun compare(o1: LivingEntity, o2: LivingEntity): Int =
            o1.armor compareTo o2.armor
    },

    /**
     * Least absorption hearts first. Ported from EDNeko's `KillAura-Targeting`.
     */
    HEALTH_ABSORPTION("HealthAbsorption") {
        override fun compare(o1: LivingEntity, o2: LivingEntity): Int =
            o1.absorptionAmount compareTo o2.absorptionAmount
    },

    /**
     * Without a regeneration effect first, then by its amplifier. Ported from EDNeko's
     * `KillAura-Targeting`.
     */
    REGEN_AMPLIFIER("RegenAmplifier") {
        private fun amplifier(entity: LivingEntity): Int =
            entity.getStatusEffect(StatusEffects.REGENERATION)?.amplifier ?: -1

        override fun compare(o1: LivingEntity, o2: LivingEntity): Int =
            amplifier(o1) compareTo amplifier(o2)
    },

    /**
     * Targets that are not sitting on a ladder first. Ported from EDNeko's `KillAura-Targeting`.
     */
    ON_LADDER("OnLadder") {
        override fun compare(o1: LivingEntity, o2: LivingEntity): Int =
            o1.isClimbing compareTo o2.isClimbing
    },

    /**
     * Targets that are not standing in a liquid first. Ported from EDNeko's `KillAura-Targeting`.
     */
    IN_LIQUID("InLiquid") {
        private fun inLiquid(entity: LivingEntity): Boolean =
            entity.isTouchingWater || entity.isInLava

        override fun compare(o1: LivingEntity, o2: LivingEntity): Int =
            inLiquid(o1) compareTo inLiquid(o2)
    },

    /**
     * Targets that are not stuck in a cobweb first. Ported from EDNeko's `KillAura-Targeting`.
     */
    IN_WEB("InWeb") {
        private fun inWeb(entity: LivingEntity): Boolean =
            world.getBlockState(entity.blockPos).isOf(Blocks.COBWEB)

        override fun compare(o1: LivingEntity, o2: LivingEntity): Int =
            inWeb(o1) compareTo inWeb(o2)
    },
}

/**
 * How [TargetTracker] picks a target out of the sorted candidates. Ported from EDNeko's
 * `KillAura-Targeting > TargetMode`.
 */
enum class TargetMode(override val choiceName: String) : NamedChoice {
    /**
     * Always the best candidate.
     */
    SINGLE("Single"),

    /**
     * Rotate through the candidates, skipping ones that were attacked recently.
     */
    SWITCH("Switch"),

    /**
     * Attack several candidates within the same click.
     */
    MULTI("Multi"),
}
