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
 * Ported from EDNeko (github.com/RouQingNeko1024/EDNeko, GPL-3.0),
 * `features/module/modules/combat/AntiKnockBack`.
 *
 * EDNeko printed every debug toggle as a chat message. Chat output is dropped in
 * this port; the debug settings are still evaluated and their values are exposed
 * through the NG debug module instead.
 * ---------------------------------------------------------------------------
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.utils.entity.movementForward
import net.ccbluex.liquidbounce.utils.entity.movementSideways
import net.ccbluex.liquidbounce.utils.entity.moving
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket
import net.minecraft.util.Hand
import kotlin.math.sqrt

/**
 * AntiKnockBack module
 *
 * Reduces the knockback taken by multiplying, reversing or cancelling the velocity received from a
 * hit, and by clicking, jumping or backing out of range while in hurt time.
 */
@Suppress("TooManyFunctions", "LargeClass")
object ModuleAntiKnockBack : ClientModule("AntiKnockBack", Category.COMBAT) {

    private const val INTAVE_FACTOR = 0.6

    private val globalMode by enumChoice("GlobalMode", GlobalMode.CUSTOM)
    private val tagState by boolean("TagState", true)
    private val tagMode by enumChoice("TagMode", TagMode.DEFAULT)
    private val customTagText by text("CustomTagText", "")
    private val authorName by boolean("AuthorName", true)
    private val sprintAfterReduce by boolean("SprintAfterReduce", false)

    /**
     * EDNeko read the range from the `KillAura` module through reflection and fell back to `3.0f`.
     * [ModuleKillAura.range] is accessible here, so this only decides which of the two is used.
     */
    private val killAura by boolean("KillAura", true)
    private val killAuraRange by float("KillAuraRange", 3.0f, 0.0f..8.0f)

    private val modifyMode by boolean("ModifyMode", false)
    private val modifyMinHurtTime by int("ModifyMinHurtTime", 10, 0..10)
    private val modifyMaxHurtTime by int("ModifyMaxHurtTime", 10, 0..10)
    private val modifyHorizonFactor by float("ModifyHorizonFactor", 0.0f, -2.0f..2.0f)
    private val modifyVerticalFactor by float("ModifyVerticalFactor", 0.0f, -2.0f..2.0f)
    private val modifyDebug by boolean("ModifyDebug", false)

    private val clickReduce by boolean("ClickReduce", false)
    private val clickMode by enumChoice("ClickMode", ClickMode.PACKET)
    private val clicksPerTime by int("ClicksPerTime", 1, 1..10)
    private val clickReduceWorkTicks by int("ClickReduceWorkTicks", 1, 1..10)
    private val workTicks by int("WorkTicks", 10, 3..10)
    private val extraClickWhenFirstClickReduce by boolean("ExtraClickWhenFirstClickReduce", false)
    private val extraCount by int("ExtraCount", 1, 1..10)
    private val clickExtraReduce by boolean("ClickExtraReduce", false)
    private val clickExtraReduceTime by enumChoice("ClickExtraReduceTime", ClickExtraReduceTime.BEFORE_CLICK)
    private val maxClickExtraReduceHurtTime by int("MaxClickExtraReduceHurtTime", 10, 0..10)
    private val minClickExtraReduceHurtTime by int("MinClickExtraReduceHurtTime", 0, 0..10)
    private val clickExtraReduceFactorOnGround by float("ClickExtraReduceFactorOnGround", 1.0f, 0.0f..1.0f)
    private val clickExtraReduceFactorInAir by float("ClickExtraReduceFactorInAir", 1.0f, 0.0f..1.0f)
    private val clickNoHurtTrigger by boolean("ClickNoHurtTrigger", false)
    private val clickNoHurtDelay by int("ClickNoHurtDelay", 1000, 100..2000)
    private val clickExtraReduceDebug by boolean("ClickExtraReduceDebug", false)

    private val smartBlocking by boolean("SmartBlocking", false)
    private val stbForwardTicks by int("StbForwardTicks", 40, 1..60)
    private val stbTicks by int("StbTicks", 3, 1..10)
    private val stbMinDistance by float("StbMinDistance", 1.0f, 0.0f..5.0f)

    private val keepRange by boolean("KeepRange", false)
    private val krAllowJump by boolean("KrAllowJump", false)
    private val krAllowJumpLevel by float("KrAllowJumpLevel", 0.5f, 0.0f..1.0f)
    private val krDuration by int("KrDuration", 3, 1..10)
    private val krMaxDistance by float("KrMaxDistance", 3.0f, 1.0f..7.0f)
    private val krMinDistance by float("KrMinDistance", 2.5f, 1.0f..7.0f)

    private val jumpReset by boolean("JumpReset", false)
    private val badEnvironmentCheck by boolean("BadEnvironmentCheck", false)
    private val jumpResetMinRange by float("JumpResetMinRange", 0.0f, 0.0f..10.0f)
    private val doubleJump by boolean("DoubleJump", false)
    private val onlyWhenSprint by boolean("OnlyWhenSprint", false)
    private val jumpSprintControl by enumChoice("JumpSprintControl", JumpSprintControl.NONE)
    private val jumpMode by enumChoice("JumpMode", JumpMode.VANILLA_JUMP)
    private val ticksUntilJump by int("TicksUntilJump", 0, 0..100)
    private val jumpProbability by int("JumpProbability", 100, 0..100)
    private val jumpMaxHurtTime by int("JumpMaxHurtTime", 9, 0..10)
    private val jumpMinHurtTime by int("JumpMinHurtTime", 9, 0..10)
    private val jumpExtraReduce by boolean("JumpExtraReduce", false)
    private val jumpExtraReduceTime by enumChoice("JumpExtraReduceTime", JumpExtraReduceTime.BEFORE_JUMP)
    private val jumpExtraReduceFactor by float("JumpExtraReduceFactor", 1.0f, 0.0f..1.0f)
    private val jumpExtraReduceDebug by boolean("JumpExtraReduceDebug", false)

    private val attackReduce by boolean("AttackReduce", false)
    private val tryForwardAfterReduce by boolean("TryForwardAfterReduce", false)
    private val attackFactorOnGround by float("AttackFactorOnGround", 60.0f, 0.0f..100.0f)
    private val attackFactorInAir by float("AttackFactorInAir", 60.0f, 0.0f..100.0f)
    private val attackReduceYFactor by float("AttackReduceYFactor", 1.0f, 0.0f..1.0f)
    private val maxReduceHurtTime by int("MaxReduceHurtTime", 9, 1..10)
    private val minReduceHurtTime by int("MinReduceHurtTime", 9, 1..10)
    private val firstReduceDelay by int("FirstReduceDelay", 0, 0..200)
    private val multiReduce by boolean("MultiReducePerHurtTime", false)
    private val reduceTriggerHurtTimeDelay by int("ReduceTriggerHurtTimeDelay", 0, 0..10)
    private val maxMultiReduceTimes by int("MaxMultiReduceTimes", 3, 1..10)
    private val firstReduceExtra by boolean("FirstReduceExtra", false)
    private val firstReduceExtraFactor by float("FirstReduceExtraFactor", 0.0f, -100.0f..100.0f)
    private val progressiveFactor by boolean("ProgressiveFactor", false)
    private val progressiveMode by enumChoice("ProgressiveMode", ProgressiveMode.DECREASE)
    private val progressiveStep by float("ProgressiveStep", 5.0f, 0.0f..200.0f)
    private val progressiveMaxFactor by float("ProgressiveMaxFactor", 100.0f, -200.0f..200.0f)
    private val progressiveMinFactor by float("ProgressiveMinFactor", 0.0f, -200.0f..200.0f)
    private val attackReduceDebug by boolean("AttackReduceDebug", false)

    private val reversalMode by boolean("ReversalMode", false)
    private val reversalTriggerDelay by int("ReversalTriggerDelay", 2, 0..5)
    private val maxReversalHurtTime by int("MaxReversalHurtTime", 9, 1..10)
    private val minReversalHurtTime by int("MinReversalHurtTime", 9, 1..10)
    private val reversalXFactor by float("ReversalXFactor", 0.5f, -1.0f..1.0f)
    private val reversalZFactor by float("ReversalZFactor", 0.5f, -1.0f..1.0f)
    private val reversalOnlyMoving by boolean("ReversalOnlyMoving", false)
    private val reversalOnlyInAir by boolean("ReversalOnlyInAir", false)

    private val matrixReduce by boolean("MatrixReduce", false)
    private val matrixReduceFactor by float("MatrixReduceFactor", 0.6f, 0.0f..1.0f)
    private val matrixReduceDebug by boolean("MatrixReduceDebug", false)

    private var attackTicks = 0
    private var hurtTimeCounter = 0
    private var checkTicks = 0
    private var timerA = 0
    private var timerB = 0
    private var timerC = 0
    private var hasHurt = 0
    private var tHurtTime = 0

    /**
     * EDNeko only ever assigned `0` to this field, so the condition reading it is never true
     * upstream. Kept as-is to stay faithful to the decompiled source.
     */
    private var generalSpeed = 0
    private var allowTimerB = false
    private var allowS = false
    private var allowJump = false
    private var allowTimerC = false
    private var hasReceivedVelocity = false
    private var hasReceivedVelocity2 = false
    private var hasReceivedVelocity4 = false
    private var jumpCooldownTicks = 0
    private var isAttacking = false
    private var isAttackingTicks = 0
    private var lastHurtTime = 0L
    private var lastReduceHurtTime = 0
    private var isFirstReduce = true
    private var hasTriggeredExtraClick = false
    private var multiReduceCount = 0
    private var firstReduceStartTime = 0L
    private var canTriggerFirstReduce = false
    private var reversalTicks = 0
    private var isReversalActive = false
    private var triggeredReversal = false
    private var target: Entity? = null

    private val range: Float
        get() = if (killAura) ModuleKillAura.range else killAuraRange

    private val clickRate: Int
        get() = if (extraClickWhenFirstClickReduce) {
            clicksPerTime * 2 * clickReduceWorkTicks + extraCount * 2
        } else {
            clicksPerTime * 2 * clickReduceWorkTicks
        }

    override val tag: String?
        get() {
            val tags = mutableListOf<String>()

            if (tagMode == TagMode.CUSTOM) {
                tags.add(customTagText)
            }

            tags.addAll(defaultTags())

            if (tagMode == TagMode.DEFAULT && globalMode == GlobalMode.INTAVE) {
                tags.add("Intave")
            }

            return tags.joinToString(" | ").ifEmpty { null }
        }

    private fun defaultTags(): List<String> {
        if (!tagState || tagMode != TagMode.DEFAULT || globalMode != GlobalMode.CUSTOM) {
            return emptyList()
        }

        val tags = mutableListOf<String>()

        if (authorName) {
            tags.add("BusyFixer/FireFly")
        }
        if (modifyMode) {
            tags.add("Modify H:${(modifyHorizonFactor * 100).toInt()}% V:${(modifyVerticalFactor * 100).toInt()}%")
        }
        if (keepRange) {
            tags.add("AutoWtap ${krDuration}ticks")
        }
        if (clickReduce) {
            tags.add("ClickReduce $clickRate/s")
        }
        if (attackReduce) {
            tags.add("AttackReduce G:${attackFactorOnGround.toInt()}%/A:${attackFactorInAir.toInt()}%")
        }
        if (jumpReset) {
            tags.add("JumpReset $jumpProbability%")
        }
        if (matrixReduce) {
            tags.add("MatrixReduce ${(matrixReduceFactor * 100).toInt()}%")
        }

        return tags
    }

    override fun onEnabled() = resetState()

    override fun onDisabled() = resetState()

    private fun resetState() {
        attackTicks = 0
        checkTicks = 0
        hasReceivedVelocity = false
        hasReceivedVelocity2 = false
        allowJump = false
        jumpCooldownTicks = 0
        isAttacking = false
        isAttackingTicks = 0
        isFirstReduce = true
        multiReduceCount = 0
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet

        when {
            packet is EntityVelocityUpdateS2CPacket && packet.entityId == player.id -> {
                hasReceivedVelocity = true
                hasReceivedVelocity2 = true
                hasReceivedVelocity4 = true
                resetVelocityTracking()
            }

            packet is ExplosionS2CPacket -> {
                hasReceivedVelocity = true
                hasReceivedVelocity2 = false
                hasReceivedVelocity4 = false
                resetVelocityTracking()
            }
        }
    }

    private fun resetVelocityTracking() {
        lastHurtTime = System.currentTimeMillis()
        isFirstReduce = true
        hasTriggeredExtraClick = false
        firstReduceStartTime = 0L
        canTriggerFirstReduce = false
        triggeredReversal = false
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        target = event.entity
        attackTicks++
        isAttacking = true
        isAttackingTicks = 0

        if (attackReduce && hasReceivedVelocity2 && globalMode == GlobalMode.CUSTOM) {
            applyAttackReduce()
        }

        if (reversalMode && hasReceivedVelocity4 && globalMode == GlobalMode.CUSTOM && !applyReversal()) {
            return@handler
        }

        if (globalMode == GlobalMode.INTAVE && hasReceivedVelocity2 && player.hurtTime == 9) {
            applyIntaveSprintReduce()
        }
    }

    private fun applyIntaveSprintReduce() {
        player.velocity.x *= INTAVE_FACTOR
        player.velocity.z *= INTAVE_FACTOR

        if (sprintAfterReduce) {
            player.isSprinting = true
        }
    }

    /**
     * EDNeko returned from the whole update when `ReversalOnlyInAir` or `ReversalOnlyMoving`
     * vetoed the reversal, skipping everything that follows. `false` mirrors that early return.
     */
    private fun applyReversal(): Boolean {
        if (!isReversalActive && player.hurtTime >= minReversalHurtTime &&
            player.hurtTime <= maxReversalHurtTime && !triggeredReversal
        ) {
            reversalTicks = 0
            triggeredReversal = true
            isReversalActive = true
        }

        if (!isReversalActive) {
            return true
        }

        reversalTicks++

        if (reversalTicks < reversalTriggerDelay) {
            return true
        }

        if (reversalVetoes()) {
            isReversalActive = false
            return false
        }

        player.velocity.x *= -reversalXFactor.toDouble()
        player.velocity.z *= -reversalZFactor.toDouble()

        debugOutput(attackReduceDebug) {
            "[Reversal] XFactor: ${(reversalXFactor * 100).toInt()}% ZFactor: " +
                "${(reversalZFactor * 100).toInt()}%"
        }

        if (tryForwardAfterReduce) {
            mc.options.forwardKey.isPressed = true
        }

        isReversalActive = false
        return true
    }

    private fun reversalVetoes(): Boolean =
        (reversalOnlyInAir && player.isOnGround) || (reversalOnlyMoving && !player.moving)

    private fun applyAttackReduce() {
        val currentHurtTime = player.hurtTime
        var canTriggerReduce = true

        if (currentHurtTime < minReduceHurtTime || currentHurtTime > maxReduceHurtTime) {
            multiReduceCount = 0
            firstReduceStartTime = 0L
            canTriggerFirstReduce = false
        }

        updateFirstReduceWindow(currentHurtTime, announce = true)

        if (reduceTriggerHurtTimeDelay >= 1 && multiReduce && lastReduceHurtTime > 0) {
            val requiredDelay = reduceTriggerHurtTimeDelay
            canTriggerReduce = currentHurtTime <= lastReduceHurtTime - requiredDelay ||
                currentHurtTime >= lastReduceHurtTime + requiredDelay
        }

        if (multiReduce && multiReduceCount >= maxMultiReduceTimes) {
            canTriggerReduce = false
            debugOutput(attackReduceDebug) { "Max reduce times reached: $multiReduceCount" }
        }

        if (!canTriggerReduce) {
            return
        }

        if (currentHurtTime < minReduceHurtTime || currentHurtTime > maxReduceHurtTime) {
            return
        }

        if (multiReduceCount <= 0 && !canTriggerFirstReduce) {
            return
        }

        reduceVelocity(currentHurtTime)
    }

    private fun updateFirstReduceWindow(currentHurtTime: Int, announce: Boolean) {
        if (canTriggerFirstReduce) {
            return
        }

        if (currentHurtTime < minReduceHurtTime || currentHurtTime > maxReduceHurtTime) {
            return
        }

        if (firstReduceStartTime == 0L) {
            firstReduceStartTime = System.currentTimeMillis()
        }

        if (System.currentTimeMillis() - firstReduceStartTime < firstReduceDelay.toLong()) {
            return
        }

        canTriggerFirstReduce = true
        firstReduceStartTime = 0L

        if (announce) {
            debugOutput(attackReduceDebug) { "First reduce delay ended | Delay: $firstReduceDelay ms" }
        }
    }

    private fun reduceVelocity(currentHurtTime: Int) {
        var currentFactorOnGround = attackFactorOnGround
        var currentFactorInAir = attackFactorInAir

        if (progressiveFactor && multiReduce) {
            val step = progressiveStep
            val minFactor = progressiveMinFactor
            val maxFactor = progressiveMaxFactor

            if (progressiveMode == ProgressiveMode.DECREASE) {
                currentFactorOnGround = maxOf(minFactor, attackFactorOnGround - step * multiReduceCount)
                currentFactorInAir = maxOf(minFactor, attackFactorInAir - step * multiReduceCount)
            } else {
                currentFactorOnGround = minOf(maxFactor, attackFactorOnGround + step * multiReduceCount)
                currentFactorInAir = minOf(maxFactor, attackFactorInAir + step * multiReduceCount)
            }

            debugOutput(attackReduceDebug) {
                "Progressive | ${progressiveMode.choiceName} | OnGround: $currentFactorOnGround% " +
                    "| InAir: $currentFactorInAir%"
            }

            if (tryForwardAfterReduce) {
                mc.options.forwardKey.isPressed = true
            }
        }

        if (firstReduceExtra && isFirstReduce) {
            applyFirstReduceExtra(currentFactorOnGround, currentFactorInAir)
        } else if (!firstReduceExtra || !isFirstReduce) {
            applyNormalReduce(currentFactorOnGround, currentFactorInAir, currentHurtTime)
        }

        player.velocity.y *= attackReduceYFactor.toDouble()
        lastReduceHurtTime = currentHurtTime
        multiReduceCount++

        if (!multiReduce) {
            hasReceivedVelocity2 = false
        }
    }

    private fun applyFirstReduceExtra(groundFactor: Float, airFactor: Float) {
        val ground = groundFactor / 100.0f - firstReduceExtraFactor / 100.0f
        val air = airFactor / 100.0f - firstReduceExtraFactor / 100.0f
        val factor = if (player.isOnGround) ground else air

        player.velocity.x *= factor.toDouble()
        player.velocity.z *= factor.toDouble()

        debugOutput(attackReduceDebug) {
            "First reduce factor | OnGround: ${ground * 100}% | InAir: ${air * 100}% | HurtTime: ${player.hurtTime}"
        }

        if (tryForwardAfterReduce) {
            mc.options.forwardKey.isPressed = true
        }

        if (sprintAfterReduce) {
            player.isSprinting = true
        }

        isFirstReduce = false
    }

    private fun applyNormalReduce(groundFactor: Float, airFactor: Float, currentHurtTime: Int) {
        if (player.isOnGround) {
            player.velocity.x *= (groundFactor / 100.0f).toDouble()
            player.velocity.z *= (groundFactor / 100.0f).toDouble()

            debugOutput(attackReduceDebug) {
                "OnGroundReduce | HurtTime: $currentHurtTime | Factor: $groundFactor% | " +
                    "Count: ${multiReduceCount + 1}/$maxMultiReduceTimes"
            }
        } else {
            player.velocity.x *= (airFactor / 100.0f).toDouble()
            player.velocity.z *= (airFactor / 100.0f).toDouble()

            debugOutput(attackReduceDebug) {
                "InAirReduce | HurtTime: $currentHurtTime | Factor: $airFactor% | " +
                    "Count: ${multiReduceCount + 1}/$maxMultiReduceTimes"
            }

            if (tryForwardAfterReduce) {
                mc.options.forwardKey.isPressed = true
            }
        }

        if (sprintAfterReduce) {
            player.isSprinting = true
        }
    }

    @Suppress("unused")
    private val tickHandler = tickHandler {
        val currentTarget = target ?: return@tickHandler

        val dx = currentTarget.x - player.x
        val dz = currentTarget.z - player.z
        val distanceToTarget = mol(dx, dz, 0.0)

        updateTimers()

        if (globalMode == GlobalMode.CUSTOM) {
            updateFirstReduceWindow(player.hurtTime, announce = false)
            handleModify()
            handleMatrix()
        }

        if (hasReceivedVelocity && globalMode == GlobalMode.INTAVE) {
            if (isBadEnvironment()) {
                return@tickHandler
            }

            if (canIntaveAutoJump()) {
                player.jump()
                hasReceivedVelocity = false
            }
        }

        if (jumpReset && hasReceivedVelocity && globalMode == GlobalMode.CUSTOM) {
            if (!handleJumpReset(distanceToTarget)) {
                return@tickHandler
            }
        }

        if (clickReduce && globalMode == GlobalMode.CUSTOM) {
            handleClickReduce(currentTarget)
        }

        if (smartBlocking && globalMode == GlobalMode.CUSTOM) {
            handleSmartBlocking(dx, dz)
        }

        if (keepRange) {
            handleKeepRange(currentTarget, dx, dz)
        }

        updateAttackTicks()
    }

    private fun updateTimers() {
        if (player.hurtTime != 0) {
            hurtTimeCounter++
        } else {
            hurtTimeCounter = 0
        }

        val livingTarget = target as? LivingEntity
        if (livingTarget != null && livingTarget.hurtTime == 0) {
            tHurtTime = 0
        } else {
            tHurtTime++
        }

        if (isAttacking) {
            isAttackingTicks++
            if (isAttackingTicks >= 2) {
                isAttacking = false
                isAttackingTicks = 0
            }
        }

        if (jumpCooldownTicks > 0) {
            jumpCooldownTicks--
        }
    }

    private fun updateAttackTicks() {
        if (attackTicks <= 0) {
            return
        }

        if (attackTicks > 1) {
            checkTicks = 0
            attackTicks = 1
            return
        }

        checkTicks++
        if (checkTicks > workTicks) {
            checkTicks = 0
            attackTicks = 0
        }
    }

    private fun handleModify() {
        if (!modifyMode || player.hurtTime <= 0) {
            return
        }

        if (player.hurtTime < modifyMinHurtTime || player.hurtTime > modifyMaxHurtTime) {
            return
        }

        player.velocity.x *= modifyHorizonFactor.toDouble()
        player.velocity.z *= modifyHorizonFactor.toDouble()
        player.velocity.y *= modifyVerticalFactor.toDouble()

        debugOutput(modifyDebug) {
            "Modify | Horizon: ${(modifyHorizonFactor * 100).toInt()}% | " +
                "Vertical: ${(modifyVerticalFactor * 100).toInt()}%"
        }

        if (sprintAfterReduce) {
            player.isSprinting = true
        }
    }

    private fun handleMatrix() {
        if (!matrixReduce || player.hurtTime <= 0) {
            return
        }

        if (player.input.movementForward == 0.0f && player.input.movementSideways == 0.0f) {
            return
        }

        player.velocity.x *= matrixReduceFactor.toDouble()
        player.velocity.z *= matrixReduceFactor.toDouble()

        debugOutput(matrixReduceDebug) { "Matrix Reduced | HurtTime: ${player.hurtTime}" }
    }

    private fun isBadEnvironment(): Boolean =
        player.isTouchingWater || player.isInLava || player.hasVehicle() || player.isOnFire

    private fun canIntaveAutoJump(): Boolean {
        if (!player.isOnGround || player.hurtTime != 9 || !player.isSprinting) {
            return false
        }

        return Math.random() * 100.0 <= 20.0
    }

    private fun jumpEnvironmentVetoes(): Boolean = badEnvironmentCheck && isBadEnvironment()

    private fun canJumpReset(distanceToTarget: Double): Boolean {
        if (!player.isOnGround) {
            return false
        }

        if (player.hurtTime < jumpMinHurtTime || player.hurtTime > jumpMaxHurtTime) {
            return false
        }

        return jumpCooldownTicks <= 0 && distanceToTarget >= jumpResetMinRange.toDouble()
    }

    /**
     * `false` mirrors EDNeko returning from the whole update when [OnlyWhenSprint] vetoes the jump.
     */
    private fun handleJumpReset(distanceToTarget: Double): Boolean {
        if (jumpEnvironmentVetoes()) {
            debugOutput(jumpExtraReduceDebug) { "[Jump] Canceled due to bad environment" }
            return true
        }

        if (!canJumpReset(distanceToTarget)) {
            return true
        }

        if (onlyWhenSprint && !player.isSprinting) {
            return false
        }

        if (Math.random() * 100.0 <= jumpProbability.toDouble()) {
            performJump()
        }

        return true
    }

    private fun performJump() {
        if (jumpExtraReduce && jumpExtraReduceTime == JumpExtraReduceTime.BEFORE_JUMP) {
            player.velocity.x *= jumpExtraReduceFactor.toDouble()
            player.velocity.z *= jumpExtraReduceFactor.toDouble()

            debugOutput(jumpExtraReduceDebug) {
                "[JumpExtra] Pre-Reduce | Factor: ${(jumpExtraReduceFactor * 100).toInt()}%"
            }
        }

        if (jumpMode == JumpMode.VANILLA_JUMP) {
            player.jump()

            if (doubleJump) {
                player.jump()
                debugOutput(jumpExtraReduceDebug) { "[Jump] DoubleJump | HurtTime: ${player.hurtTime}" }
            }

            debugOutput(jumpExtraReduceDebug) { "[Jump] VanillaJump | HurtTime: ${player.hurtTime}" }
        } else if (jumpMode == JumpMode.PACKET_JUMP) {
            player.velocity.y = 0.42
            debugOutput(jumpExtraReduceDebug) { "[Jump] PacketJump | HurtTime: ${player.hurtTime}" }
        }

        allowJump = true

        if (jumpExtraReduce && jumpExtraReduceTime == JumpExtraReduceTime.AFTER_JUMP) {
            player.velocity.x *= jumpExtraReduceFactor.toDouble()
            player.velocity.z *= jumpExtraReduceFactor.toDouble()

            debugOutput(jumpExtraReduceDebug) {
                "[JumpExtra] Post-Reduce | Factor: ${(jumpExtraReduceFactor * 100).toInt()}%"
            }
        }

        if (jumpSprintControl == JumpSprintControl.STOP) {
            player.isSprinting = false
        }

        if (jumpSprintControl == JumpSprintControl.SPRINT) {
            player.isSprinting = true
        }

        debugOutput(jumpExtraReduceDebug) { "[Jump] Sprint Control: ${player.isSprinting}" }

        hasReceivedVelocity = false
        jumpCooldownTicks = ticksUntilJump
    }

    private fun canTriggerNoHurtClick(currentTime: Long, distance: Float): Boolean {
        if (!clickNoHurtTrigger || player.hurtTime != 0 || !isAttacking) {
            return false
        }

        if (currentTime - lastHurtTime < clickNoHurtDelay.toLong()) {
            return false
        }

        return distance <= range
    }

    private fun clickTarget(currentTarget: Entity) {
        if (clickMode == ClickMode.PACKET) {
            player.swingHand(Hand.MAIN_HAND)
            network.sendPacket(PlayerInteractEntityC2SPacket.attack(currentTarget, player.isSneaking))
        } else {
            mc.options.attackKey.isPressed = true
            mc.options.attackKey.isPressed = false
        }
    }

    private fun handleClickReduce(currentTarget: Entity) {
        val currentTime = System.currentTimeMillis()
        val distance = player.distanceTo(currentTarget)

        if (canTriggerNoHurtClick(currentTime, distance)) {
            performNoHurtClicks(currentTarget)
            lastHurtTime = currentTime
        }

        if (player.hurtTime != 0 && hurtTimeCounter <= clickReduceWorkTicks && distance <= range) {
            performHurtTimeClicks(currentTarget)
        }
    }

    private fun performNoHurtClicks(currentTarget: Entity) {
        for (tick in 0 until clickReduceWorkTicks) {
            for (click in 0 until clicksPerTime) {
                clickTarget(currentTarget)
                isAttacking = true

                debugOutput(clickExtraReduceDebug) {
                    "[NoHurtClick] Clicked | Tick: $tick | Click: $click"
                }

                if (extraClickWhenFirstClickReduce && !hasTriggeredExtraClick) {
                    noHurtExtraClicks(currentTarget, tick)
                }
            }
        }
    }

    private fun noHurtExtraClicks(currentTarget: Entity, tick: Int) {
        for (i in 0 until extraCount) {
            if (clickMode == ClickMode.PACKET) {
                player.swingHand(Hand.MAIN_HAND)
                network.sendPacket(PlayerInteractEntityC2SPacket.attack(currentTarget, player.isSneaking))
            } else {
                // EDNeko sends a use-item packet in this branch only.
                mc.options.attackKey.isPressed = true
                interaction.interactItem(player, Hand.MAIN_HAND)
                mc.options.attackKey.isPressed = false
            }

            if (sprintAfterReduce) {
                player.isSprinting = true
            }

            debugOutput(clickExtraReduceDebug) { "[NoHurtClick] Extra Click | Tick: $tick | Extra: $i" }
        }

        hasTriggeredExtraClick = true
    }

    private fun performHurtTimeClicks(currentTarget: Entity) {
        for (click in 0 until clicksPerTime) {
            val beforeClick = isClickExtraReduceWindow() && clickExtraReduce &&
                clickExtraReduceTime == ClickExtraReduceTime.BEFORE_CLICK
            if (beforeClick) {
                reduceClickVelocity()

                debugOutput(clickExtraReduceDebug) {
                    "[ExtraReduce] BeforeClick | HurtTime: ${player.hurtTime}"
                }
            }

            clickTarget(currentTarget)

            if (sprintAfterReduce) {
                player.isSprinting = true
            }

            isAttacking = true

            debugOutput(clickExtraReduceDebug) { "[NormalClick] Clicked | HurtTime: ${player.hurtTime}" }

            if (extraClickWhenFirstClickReduce && !hasTriggeredExtraClick) {
                hurtExtraClicks(currentTarget, click)
            }

            val afterClick = isClickExtraReduceWindow() && clickExtraReduce &&
                clickExtraReduceTime == ClickExtraReduceTime.AFTER_CLICK
            if (afterClick) {
                reduceClickVelocity()

                debugOutput(clickExtraReduceDebug) {
                    "[ExtraReduce] AfterClick | HurtTime: ${player.hurtTime}"
                }
            }
        }
    }

    private fun hurtExtraClicks(currentTarget: Entity, click: Int) {
        for (i in 0 until extraCount) {
            clickTarget(currentTarget)

            if (sprintAfterReduce) {
                player.isSprinting = true
            }

            debugOutput(clickExtraReduceDebug) {
                "[NormalClick] Extra Click | Click: $click | Extra: $i"
            }
        }

        hasTriggeredExtraClick = true
    }

    private fun isClickExtraReduceWindow(): Boolean =
        player.hurtTime >= minClickExtraReduceHurtTime && player.hurtTime <= maxClickExtraReduceHurtTime

    private fun reduceClickVelocity() {
        val factor = if (player.isOnGround) clickExtraReduceFactorOnGround else clickExtraReduceFactorInAir
        player.velocity.x *= factor.toDouble()
        player.velocity.z *= factor.toDouble()
    }

    private fun handleSmartBlocking(dx: Double, dz: Double) {
        val distance = mol(dx, dz, 0.0)

        if (shouldStartSmartBlocking(distance)) {
            mc.options.useKey.isPressed = true
            hasHurt = 1
        }

        if (timerA > stbForwardTicks && hasHurt > 0) {
            allowTimerB = true
        }

        if (allowTimerB) {
            timerB++
        }

        if (timerB > stbTicks) {
            mc.options.useKey.isPressed = false
            timerA = 0
            hasHurt = 0
            timerB = 0
            allowTimerB = false
        }

        if (player.input.movementForward > 0.0f && hurtTimeCounter == 0 && player.isOnGround) {
            timerA++
        }

        if (player.input.movementForward <= 0.0f) {
            timerA = 0
        }
    }

    private fun shouldStartSmartBlocking(distance: Double): Boolean {
        if (distance > stbMinDistance.toDouble() || attackTicks <= 0) {
            return false
        }

        if (hurtTimeCounter <= 7 || hurtTimeCounter >= 9) {
            return false
        }

        return timerA > stbForwardTicks
    }

    private fun handleKeepRange(currentTarget: Entity, dx: Double, dz: Double) {
        val killAuraState = ModuleKillAura.running
        val distance = mol(dx, dz, 0.0)
        val targetSpeed = mol(currentTarget.velocity.x, currentTarget.velocity.z, 0.0)

        if (shouldKeepRange(killAuraState, distance)) {
            mc.options.backKey.isPressed = true
            allowS = true
            allowTimerC = true
            generalSpeed = 0
        }

        if (allowTimerC) {
            timerC++
        }

        if (allowS && timerC > krDuration && (player.input.movementForward <= 0.0f || !killAuraState)) {
            mc.options.backKey.isPressed = false
            allowS = false
            timerC = 0
            allowTimerC = false
        }

        if (allowS && (distance > 0.1 + range || hurtTimeCounter > 0)) {
            mc.options.backKey.isPressed = false
            allowS = false
            timerC = 0
            allowTimerC = false
        }

        if (canKeepRangeJump(targetSpeed, distance)) {
            player.jump()
            allowJump = true
        }

        if (allowJump && !player.isOnGround) {
            mc.options.jumpKey.isPressed = false
            allowJump = false
        }
    }

    private fun shouldKeepRange(killAuraState: Boolean, distance: Double): Boolean {
        if (!killAuraState || generalSpeed <= 0 || mc.options.backKey.isPressed) {
            return false
        }

        if (player.input.movementForward <= 0.0f) {
            return false
        }

        return distance > krMinDistance && distance <= krMaxDistance
    }

    private fun canKeepRangeJump(targetSpeed: Double, distance: Double): Boolean {
        if (!krAllowJump || allowJump || !player.isOnGround) {
            return false
        }

        if (player.input.movementForward <= 0.0f || mc.options.jumpKey.isPressed) {
            return false
        }

        if (tHurtTime <= 0 || targetSpeed <= krAllowJumpLevel) {
            return false
        }

        return distance > krMaxDistance && distance <= 1.0 + krMaxDistance
    }

    private fun mol(x: Double, y: Double, z: Double): Double = sqrt(x * x + y * y + z * z)

    /**
     * EDNeko printed this to chat. [net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug]
     * is used instead, so the debug toggles still have an effect without spamming chat.
     */
    private fun debugOutput(enabled: Boolean, message: () -> Any?) {
        if (enabled) {
            debugParameter("AntiKnockBack", message)
        }
    }

    private enum class GlobalMode(override val choiceName: String) : NamedChoice {
        CUSTOM("Custom"),
        INTAVE("Intave"),
    }

    private enum class TagMode(override val choiceName: String) : NamedChoice {
        DEFAULT("Default"),
        CUSTOM("Custom"),
    }

    private enum class ClickMode(override val choiceName: String) : NamedChoice {
        PACKET("Packet"),
        SIMULATION("Simulation"),
    }

    private enum class ClickExtraReduceTime(override val choiceName: String) : NamedChoice {
        BEFORE_CLICK("BeforeClick"),
        AFTER_CLICK("AfterClick"),
    }

    private enum class JumpSprintControl(override val choiceName: String) : NamedChoice {
        NONE("None"),
        SPRINT("Sprint"),
        STOP("Stop"),
    }

    private enum class JumpMode(override val choiceName: String) : NamedChoice {
        PACKET_JUMP("PacketJump"),
        VANILLA_JUMP("VanillaJump"),
    }

    private enum class JumpExtraReduceTime(override val choiceName: String) : NamedChoice {
        BEFORE_JUMP("BeforeJump"),
        AFTER_JUMP("AfterJump"),
    }

    private enum class ProgressiveMode(override val choiceName: String) : NamedChoice {
        DECREASE("Decrease"),
        INCREASE("Increase"),
    }

}
