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
package net.ccbluex.liquidbounce.utils.clicking

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.nesting.Configurable
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.KeybindIsPressedEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugParameter
import net.ccbluex.liquidbounce.utils.clicking.pattern.ClickPattern
import net.ccbluex.liquidbounce.utils.clicking.pattern.patterns.*
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.entity.hasCooldown
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.minecraft.client.option.KeyBinding
import net.minecraft.entity.LivingEntity
import net.minecraft.util.hit.EntityHitResult
import java.util.*
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An attack scheduler
 *
 * Minecraft is counting every click until it handles all inputs.
 * code:
 * while (this.options.keyAttack.wasPressed()) {
 *     this.doAttack();
 * }
 * @see [MinecraftClient.handleInputEvents]
 *
 * We are simulating this behaviour by calculating how many times we could have been clicked in the meantime of a tick.
 * This allows us to predict future actions and behave accordingly.
 */
open class Clicker<T>(
    val parent: T,
    val keyBinding: KeyBinding,
    val itemCooldown: ItemCooldown? = ItemCooldown(),
    maxCps: Int = 60,
    name: String = "Clicker"
) : Configurable(name, aliases = listOf("ClickScheduler")), EventListener where T : EventListener {

    companion object {
        internal val RNG = Random()
        private const val DEFAULT_CYCLE_LENGTH = 20
        private var lastClickTime = 0L
        private val lastClickPassed
            get() = System.currentTimeMillis() - lastClickTime
    }

    // Options
    private val cps by intRange("CPS", 5..8, 1..maxCps, "clicks")
        .onChanged {
            fill()
        }

    private val pattern by enumChoice("Technique", ClickPatterns.STABILIZED)
        .onChanged {
            fill()
        }

    /**
     * How the CPS range is determined. Ported from EDNeko's `KillAura-CPS`.
     */
    private val cpsMode by enumChoice("CPSMode", CpsMode.SIMPLE)
        .onChanged {
            fill()
        }

    /**
     * Lower bound of the adaptive CPS range, and the range it starts from before adapting.
     */
    private val adaptiveBaseCps by intRange("AdaptiveBaseCPS", 5..8, 1..30)
        .onChanged {
            fill()
        }

    /**
     * Upper bound the adaptive range may grow to.
     */
    private val adaptiveMaxCps by int("AdaptiveMaxCPS", 16, 8..50)

    /**
     * How strongly a single adaptation moves the upper bound. EDNeko's `AdaptiveLearningRate`.
     */
    private val adaptiveLearningRate by float("AdaptiveLearningRate", 0.15f, 0.01f..1f)

    /**
     * Base adaptation strength before the distance variance is taken into account.
     */
    private val adaptiveSensitivity by float("AdaptiveSensitivity", 0.5f, 0.1f..2f)

    /**
     * How many distance samples the adaptation looks at.
     */
    private val adaptiveMemory by int("AdaptiveMemory", 20, 5..100)

    /**
     * Recent distances to the target, used by [computeAdaptiveCps].
     */
    private val adaptiveHistory = ArrayDeque<Float>()

    init {
        itemCooldown?.let(this::tree)
    }

    /**
     * When missing a hit, Minecraft has a cooldown before you can attack again.
     * This option will consider the cooldown before attacking again.
     *
     * This is useful for anti-cheats that detect if you are ignoring this cooldown.
     * Applies to the FailSwing feature as well.
     */
    private val attackCooldown: Value<Boolean>? = if (keyBinding == mc.options.attackKey) {
        boolean("AttackCooldown", true)
    } else {
        null
    }

    private val passesAttackCooldown
        get() = !(attackCooldown?.get() == true && mc.attackCooldown > 0)

    private val clickArray = RollingClickArray(DEFAULT_CYCLE_LENGTH, 2)

    init {
        fill()
    }

    // Clicks that were executed by [click] in the current tick
    var clickAmount: Int? = null
        private set

    val isClickTick: Boolean
        get() = willClickAt(0)

    val ticksUntilClick: Int
        get() {
            for (i in 0 until clickArray.iterations) {
                if (willClickAt(i)) {
                    return i
                }
            }

            return clickArray.iterations
        }

    fun willClickAt(tick: Int = 1) = getClickAmount(tick) > 0

    fun getClickAmount(tick: Int = 0): Int {
        if (isEnforcedClick()) {
            return 1
        }
        return clickArray.get(tick)
    }

    private fun isEnforcedClick(tick: Int = 0): Boolean {
        val hasCooldown = player.hasCooldown
        debugParameter("HasCooldown") { hasCooldown }
        if (hasCooldown && itemCooldown?.isCooldownPassed(tick) == true) {
            return true
        }

        return lastClickPassed + (tick * 50L) >= 1000L
    }

    @Suppress("unused")
    private val keybindIsPressedHandler = handler<KeybindIsPressedEvent> { event ->
        val clickAmount = this.clickAmount ?: return@handler

        // It turns out, we only want to do this with [attackKey], otherwise
        // [useKey] will do unexpected things.
        if (keyBinding == mc.options.attackKey && event.keyBinding == keyBinding) {
            // We want to simulate the click in order to
            // allow the game to handle the logic as if we clicked
            event.isPressed = clickAmount > 0
        }
    }

    /**
     * Clicks [cps] times per call (tick). If the cooldown is not passed, it will not click.
     * [block] should return true if the click was successful. Otherwise, it will not count as a click.
     */
    fun click(block: () -> Boolean) {
        val clicks = getClickAmount()

        debugParameter("Current Clicks") { clicks }
        debugParameter("Peek Clicks") { clickArray.get(1) }
        debugParameter("Last Click Passed") { lastClickPassed }
        debugParameter("Attack Cooldown") { mc.attackCooldown }
        debugParameter("Item Cooldown") { itemCooldown?.cooldownProgress() ?: 0.0f }

        var clickAmount = 0

        repeat(clicks) {
            if (!passesAttackCooldown) {
                return@repeat
            }

            if (itemCooldown?.isCooldownPassed() != false && block()) {
                clickAmount++
                itemCooldown?.newCooldown()
                lastClickTime = System.currentTimeMillis()
            }
        }

        this.clickAmount = clickAmount
    }

    @Suppress("unused")
    private val gameHandler = handler<GameTickEvent>(
        priority = EventPriorityConvention.FIRST_PRIORITY
    ) {
        clickAmount = null

        if (clickArray.advance()) {
            val cycleArray = IntArray(DEFAULT_CYCLE_LENGTH)
            pattern.pattern.fill(cycleArray, effectiveCps(), this)
            clickArray.push(cycleArray)
        }

        debugParameter("Click Technique") { pattern.choiceName }
        debugParameter("Click Array") {
            clickArray.array.withIndex().joinToString { (i, v) ->
                if (i == clickArray.head) "*$v" else v.toString()
            }
        }
    }

    private fun fill() {
        clickArray.clear()
        val cycleArray = IntArray(DEFAULT_CYCLE_LENGTH)
        repeat(clickArray.iterations) {
            Arrays.fill(cycleArray, 0)
            pattern.pattern.fill(cycleArray, effectiveCps(), this)
            clickArray.push(cycleArray)
            clickArray.advance(DEFAULT_CYCLE_LENGTH)
        }
    }

    /**
     * CPS range used for the next click cycle.
     */
    private fun effectiveCps(): IntRange =
        if (cpsMode == CpsMode.ADAPTIVE) computeAdaptiveCps() else cps

    /**
     * Faithful port of EDNeko's `KillAuraCPS.computeAdaptiveCPS`.
     *
     * The further away the target is and the more that distance fluctuates, the higher the upper bound
     * of the CPS range climbs - up to [adaptiveMaxCps]. The lower bound always stays at
     * [adaptiveBaseCps]'s start.
     *
     * EDNEko's `AdaptiveResponseTime` is deliberately not exposed: its own `computeAdaptiveCPS` never
     * reads it, so there is nothing to port and inventing a use would change the behaviour.
     */
    private fun computeAdaptiveCps(): IntRange {
        val target = (mc.crosshairTarget as? EntityHitResult)?.entity as? LivingEntity

        if (target != null) {
            adaptiveHistory.addLast(player.distanceTo(target))

            while (adaptiveHistory.size > adaptiveMemory) {
                adaptiveHistory.removeFirst()
            }
        }

        if (adaptiveHistory.isEmpty()) {
            return adaptiveBaseCps
        }

        val average = adaptiveHistory.average().toFloat()
        val variance = adaptiveHistory.sumOf { ((it - average) * (it - average)).toDouble() }
            .div(adaptiveHistory.size)
            .toFloat()

        val sensitivity = adaptiveSensitivity * (1f + variance / 10f)
        val span = adaptiveMaxCps - adaptiveBaseCps.first
        val extra = (sensitivity * adaptiveLearningRate * span).roundToInt()

        return adaptiveBaseCps.first..min(adaptiveBaseCps.last + extra, adaptiveMaxCps)
    }

    override fun parent() = parent

    @Suppress("unused")
    enum class ClickPatterns(
        override val choiceName: String,
        val pattern: ClickPattern
    ) : NamedChoice {
        STABILIZED("Stabilized", StabilizedPattern),
        EFFICIENT("Efficient", EfficientPattern),
        SPAMMING("Spamming", SpammingPattern),
        DOUBLE_CLICK("DoubleClick", DoubleClickPattern),
        DRAG("Drag", DragPattern),
        BUTTERFLY("Butterfly", ButterflyPattern),
        NORMAL_DISTRIBUTION("NormalDistribution", NormalDistributionPattern);
    }

    /**
     * How the CPS range is chosen, ported from EDNeko's `KillAura-CPS` mode list. Only the modes that
     * were asked for are kept; EDNEko's remaining ones (Dynamic, Wave, Burst, Step, Jitter, Spike, Smart,
     * Reactive, Momentum, Fatigue, Combo, Stamina) are not ported.
     */
    @Suppress("unused")
    enum class CpsMode(override val choiceName: String) : NamedChoice {
        SIMPLE("Simple"),
        ADAPTIVE("Adaptive"),
    }

}
