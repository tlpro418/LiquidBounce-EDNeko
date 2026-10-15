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
 * Rotation randomisation.
 *
 * Ported from EDNeko b6's `utils/rotation/RandomizationSettings`
 * (github.com/RouQingNeko1024/EDNeko, GPL-3.0), together with the helpers it
 * delegates to in `utils/rotation/randomizations` (`HybridNoise`,
 * `ChaoticRandomization`, `MiniDisturbanceSystem`) and the private
 * `PerlinNoise` / `HarmonicAnalyzer` / `SpectralFilter` / `TrajectoryDatabase`
 * companions.
 *
 * In b6 the whole layer lived on one `RandomizationSettings` object that was
 * handed the rotation and offset it in place; here it is a [RotationProcessor]
 * so it composes with LiquidBounce's existing angle smooths instead of
 * replacing them. `process` reproduces b6's dispatch site: the per-axis offset
 * is added on top of `targetRotation`, with pitch clamped to +-90 degrees.
 *
 * Deviation from b6, documented inline where it matters: b6 reads
 * `RotationUtils.getLastRotations()[2]` as its reference rotation, which
 * LiquidBounce's rotation engine has no equivalent of. A three-slot history of
 * the rotations passed into `process` supplies the same quantity.
 *
 * The `AugustusXIntave` pattern is named after the Augustus client upstream.
 * ---------------------------------------------------------------------------
 */
package net.ccbluex.liquidbounce.utils.aiming.features.processors

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.config.types.nesting.Configurable
import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil.angleDifference
import net.ccbluex.liquidbounce.utils.entity.moving
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.util.hit.EntityHitResult
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

@Suppress("MagicNumber", "TooManyFunctions")
class EdnekoRandomizationProcessor(owner: EventListener? = null)
    : ToggleableConfigurable(owner, "Randomization", false), RotationProcessor {

    private var noiseTime = 0L
    private var perlinSeed = (0f..1f).random() * 1000f
    private var advancedProgress = 0f
    private val trajectoryRecording = ArrayList<Rotation>()
    private val disturbanceSystem = MiniDisturbanceSystem()
    private val spectralHistory = ArrayDeque<FloatArray>()
    private var spectralUpdateTime = 0L
    private val harmonicAnalyzer = HarmonicAnalyzer()
    private val spectralFilter = SpectralFilter()
    private val trajectoryDB = TrajectoryDatabase()

    /**
     * b6's `RotationUtils.getLastRotations()[2]`. Index 0 is the newest slot,
     * so the slot read by the ported offset helpers is the oldest of three.
     */
    private val rotationHistory = arrayOfNulls<Rotation>(3)

    /** Index into [RotationSystem.entries], used by the `Switch` chaos mode. */
    private var chaoticSystemIndex = 0

    /**
     * The selected pattern, exposed to the settings groups' visibility gates.
     * Those gates are registered while this instance is still being built, so
     * the safe default is returned until every declaration has run.
     */
    internal fun patternValue(): RandomizationPattern =
        if (settingsReady) widgetGroup.pattern else RandomizationPattern.NONE

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation {
        if (!this.running) {
            return targetRotation
        }

        if (widgetGroup.stopOnUserNotMoving && !playerMoving()) {
            return targetRotation
        }

        if (widgetGroup.onlyWorkWhenCantAttack && aimingAtEntity()) {
            return targetRotation
        }

        val reference = referenceRotation()

        // b6's `All/RandomizationApplyChance` gate.
        if ((0f..1f).random() > widgetGroup.applyChance.random()) {
            rememberRotation(targetRotation)
            return targetRotation
        }

        var rotation = targetRotation
        when (patternValue()) {
            RandomizationPattern.AUGUSTUS_X_INTAVE -> {
                rotation = Rotation(
                    rotation.yaw + augustusXIntaveOffset(0, rotation, reference),
                    rotation.pitch + augustusXIntaveOffset(1, rotation, reference)
                )
            }

            RandomizationPattern.ADVANCED -> processAdvanced(rotation)
            RandomizationPattern.PERLIN -> rotation = processPerlin(rotation, reference)
            RandomizationPattern.TRUE_RANDOM -> rotation = processTrueRandom(rotation)
            RandomizationPattern.NOISE -> {
                rotation = Rotation(
                    rotation.yaw + noiseOffset(0),
                    rotation.pitch + noiseOffset(1)
                )
            }

            RandomizationPattern.ZIG_ZAG -> rotation = processZigZag(rotation, reference)
            RandomizationPattern.HYBRID_NOISE -> rotation = processHybridNoise(rotation, rotationTarget, reference)
            RandomizationPattern.CHAOTIC -> rotation = processChaotic(rotation)
            else -> rotation = processDefault(rotation, reference)
        }

        applyMiniDisturbanceIfEnabled(rotation)

        val result = Rotation(rotation.yaw, rotation.pitch.coerceIn(-90f, 90f))
        rememberRotation(result)
        performPostProcessing()
        return result
    }

    private fun playerMoving(): Boolean = MinecraftClient.getInstance().player?.moving ?: false

    /** b6 reads the crosshair target entity; LiquidBounce exposes it via the hit result. */
    private fun targetEntity(): Entity? =
        (MinecraftClient.getInstance().crosshairTarget as? EntityHitResult)?.entity

    private fun aimingAtEntity(): Boolean = targetEntity() != null

    // The `None` pattern falls into b6's `default` branch.

    private fun processDefault(rotation: Rotation, reference: Rotation): Rotation {
        val yawStep = if ((0f..1f).random() < widgetGroup.yawChance.random()) {
            widgetGroup.yawSpeedIncrease.random() / 100f * angleDelta(rotation.yaw, reference.yaw)
        } else {
            0f
        }

        val pitchStep = if ((0f..1f).random() < widgetGroup.pitchChance.random()) {
            widgetGroup.pitchRange.random() + sign(angleDelta(rotation.pitch, reference.pitch))
        } else {
            0f
        }

        return Rotation(rotation.yaw + yawStep, rotation.pitch + pitchStep)
    }

    private fun processPerlin(rotation: Rotation, reference: Rotation): Rotation {
        val adjusted = Rotation(
            rotation.yaw + perlinOffset(0),
            rotation.pitch + perlinOffset(1)
        )

        val stuck = abs(angleDelta(adjusted.yaw, reference.yaw)) < 0.5f
        return if (stuck) {
            Rotation(adjusted.yaw + perlinOffset(0) * 1.5f, adjusted.pitch)
        } else {
            adjusted
        }
    }

    private fun processTrueRandom(rotation: Rotation): Rotation {
        val smoothFactor = advancedGroup.trueRandomSmoothness.random()
        val newYaw = rotation.yaw + trueRandomOffset(0)
        val newPitch = rotation.pitch + trueRandomOffset(1)

        return Rotation(
            lerp(rotation.yaw, newYaw, 1f / smoothFactor.toFloat()),
            lerp(rotation.pitch, newPitch, 1f / smoothFactor.toFloat())
        )
    }

    private fun processZigZag(rotation: Rotation, reference: Rotation): Rotation {
        val pitchSign = sign(angleDelta(rotation.pitch, reference.pitch))
            .takeIf { it != 0f } ?: (-1..1).random().toFloat()
        val yawSign = sign(angleDelta(rotation.yaw, reference.yaw))
            .takeIf { it != 0f } ?: (-1..1).random().toFloat()

        val yawStep = if ((0f..1f).random() < widgetGroup.yawChance.random()) {
            widgetGroup.yawRange.random() * yawSign
        } else {
            0f
        }

        val pitchStep = if ((0f..1f).random() < widgetGroup.pitchChance.random()) {
            widgetGroup.pitchRange.random() * pitchSign
        } else {
            0f
        }

        return Rotation(rotation.yaw + yawStep, rotation.pitch + pitchStep)
    }

    private fun processHybridNoise(
        rotation: Rotation,
        rotationTarget: RotationTarget,
        reference: Rotation
    ): Rotation {
        val distance = distanceToTarget(rotationTarget)
        val hybridNoise = HybridNoise()

        val adjusted = Rotation(
            rotation.yaw + hybridNoise.hybridNoiseOffset(0, distance),
            rotation.pitch + hybridNoise.hybridNoiseOffset(1, distance)
        )

        val stuck = abs(angleDelta(adjusted.yaw, reference.yaw)) < 0.3f
        return if (stuck) {
            Rotation(adjusted.yaw + gaussianRandom(0f, 0.5f), adjusted.pitch)
        } else {
            adjusted
        }
    }

    private fun processChaotic(rotation: Rotation): Rotation {
        val chaoticNoise = ChaoticRandomization()

        val adjusted = Rotation(
            rotation.yaw + chaoticNoise.enhancedChaoticOffset(0),
            rotation.pitch + chaoticNoise.enhancedChaoticOffset(1)
        )

        chaoticNoise.enhancedAimContext()?.let { applyContextualFineTuning(adjusted, it) }

        if ((0f..1f).random() < 0.3f) {
            adjusted.yaw += gaussianRandom(0f, 0.2f)
            adjusted.pitch += gaussianRandom(0f, 0.1f)
        }

        return adjusted
    }

    /**
     * Augustus' Intave, in its three sub-modes. Named after the Augustus client
     * upstream; b6 exposes `AugustusXIntave/RandomStrength` and
     * `AugustusXIntave/Mode`.
     */
    private fun augustusXIntaveOffset(axis: Int, currentRotation: Rotation, targetRotation: Rotation): Float {
        val currentAngle = if (axis == 0) currentRotation.yaw else currentRotation.pitch
        val targetAngle = if (axis == 0) targetRotation.yaw else targetRotation.pitch
        val angleDiff = if (axis == 0) {
            angleDelta(targetAngle, currentAngle)
        } else {
            targetAngle - currentAngle
        }

        return when (intaveGroup.mode) {
            IntaveMode.BASIC -> intaveWave(axis)

            IntaveMode.ONLY_ROTATION -> if (abs(angleDiff) > 1f) {
                intaveWave(axis)
            } else {
                (-0.5f..0.5f).random() * intaveGroup.randomStrength.random() * 0.1f
            }

            IntaveMode.DOUBLED -> {
                val random1 = (-1f..1f).random()
                val random2 = (-1f..1f).random()
                min(random1, random2) +
                    (max(random1, random2) - min(random1, random2)) * intaveGroup.randomStrength.random()
            }
        }
    }

    private fun intaveWave(axis: Int): Float {
        val time = System.currentTimeMillis().toFloat() / 1000f
        val sinInput = if (axis == 0) time * 0.5f else time * 0.3f
        return (1f..2f).random() * sin(sinInput * PI.toFloat()) * intaveGroup.randomStrength.random()
    }

    /** b6's `MiniDisturbanceSystem`, applied after the pattern dispatch. */
    private fun applyMiniDisturbanceIfEnabled(rotation: Rotation) {
        if (!widgetGroup.disturbance) {
            return
        }

        val lastRotations = lastRotationsForDisturbance()
        val disturbed = disturbanceSystem.applyDisturbanceToRotation(
            rotation,
            lastRotations,
            widgetGroup.disturbanceSize.random(),
            widgetGroup.disturbanceLowRate.random(),
            widgetGroup.disturbanceHighRate.random(),
            widgetGroup.disturbanceInterval.random()
        )

        rotation.yaw = disturbed.yaw
        rotation.pitch = disturbed.pitch
    }

    private fun lastRotationsForDisturbance(): Array<Rotation> {
        val newer = rotationHistory[1]
        if (newer != null) {
            return arrayOf(newer, rotationHistory[2] ?: newer)
        }

        return arrayOf(RotationManager.actualServerRotation)
    }

    private fun performPostProcessing() {
        if (trajectoryRecording.size > 100) {
            trajectoryRecording.clear()
        }
    }

    // ---------------------------------------------------------------------
    // Per-pattern offset helpers
    // ---------------------------------------------------------------------

    private fun gaussianRandom(mean: Float, sigma: Float): Float {
        val u1 = (0f..1f).random().coerceAtLeast(1e-6f)
        val u2 = (0f..1f).random()
        val z0 = sqrt(-2.0 * ln(u1.toDouble())) * cos(2.0 * PI * u2)
        return (z0 * sigma + mean).toFloat()
    }

    private fun trueRandomOffset(axis: Int): Float = when (axis) {
        0 -> ((0f..1f).random() * 2f - 1f) * advancedGroup.trueRandomStrength.random()
        1 -> ((0f..1f).random() * 2f - 1f) * advancedGroup.trueRandomStrength.random() * 0.5f
        else -> 0f
    }

    private fun noiseOffset(axis: Int): Float {
        noiseTime += (10 + (0..20).random()).toLong()
        val t = noiseTime.toDouble() * 0.001 * advancedGroup.noiseSpeed.random()

        return when (axis) {
            0 -> ((sin(t * 1.3) * 0.6 + sin(t * 0.7) * 0.4) * advancedGroup.noiseScale.random()).toFloat()
            1 -> ((cos(t * 0.9) * 0.5 - cos(t * 0.4) * 0.3) * advancedGroup.noiseScale.random() * 0.5f).toFloat()
            else -> 0f
        }
    }

    private fun perlinOffset(axis: Int): Float {
        val time = System.currentTimeMillis().toFloat() / 2000f

        val baseNoise = when (axis) {
            0 -> {
                val x = perlinSeed + time * 0.3f
                PerlinNoise.fractalNoise(x, 0f, 0f, octaves.random(), perlinPersistence.random()) *
                    perlinScale.random()
            }

            1 -> {
                val y = perlinSeed + time * 0.2f
                PerlinNoise.fractalNoise(0f, y, 0f, octaves.random(), perlinPersistence.random()) *
                    perlinScale.random()
            }

            else -> 0f
        }

        val offset = when (axis) {
            0 -> baseNoise * perlinYawScale.random()
            1 -> baseNoise * perlinPitchScale.random()
            else -> 0f
        }

        if (abs(offset) < 0.3f) {
            // Getting stuck produces a flat line, so a small jitter is added.
            val direction = if ((0..1).random() == 1) 0.5f else -0.5f
            val scale = when (axis) {
                0 -> perlinYawScale.random()
                1 -> perlinPitchScale.random()
                else -> 1f
            }
            return direction * scale
        }

        return offset
    }

    fun lerp(from: Float, to: Float, delta: Float): Float =
        from + (to - from) * delta.coerceIn(0f, 1f)

    // ---------------------------------------------------------------------
    // Perlin noise (b6's private companion object)
    // ---------------------------------------------------------------------

    private object PerlinNoise {

        private val permutation = intArrayOf(
            151, 160, 137, 91, 90, 15, 131, 13, 201, 95, 96, 53, 194, 233, 7, 225, 140, 36, 103, 30, 69, 142,
            8, 99, 37, 240, 21, 10, 23, 190, 6, 148, 247, 120, 234, 75, 0, 26, 197, 62, 94, 252, 219, 203,
            117, 35, 11, 32, 57, 177, 33, 88, 237, 149, 56, 87, 174, 20, 125, 136, 171, 168, 68, 175, 74, 165,
            71, 134, 139, 48, 27, 166, 77, 146, 158, 231, 83, 111, 229, 122, 60, 211, 133, 230, 220, 105, 92, 41,
            55, 46, 245, 40, 244, 102, 143, 54, 65, 25, 63, 161, 1, 216, 80, 73, 209, 76, 132, 187, 208, 89, 18,
            169, 200, 196, 135, 130, 116, 188, 159, 86, 164, 100, 109, 198, 173, 186, 3, 64, 52, 217, 226, 250,
            124, 123, 5, 202, 38, 147, 118, 126, 255, 82, 85, 212, 207, 206, 59, 227, 47, 16, 58, 17, 182, 189,
            28, 42, 223, 183, 170, 213, 119, 248, 152, 2, 44, 154, 163, 70, 221, 153, 101, 155, 167, 43, 172, 9,
            129, 22, 39, 253, 19, 98, 108, 110, 79, 113, 224, 232, 178, 185, 112, 104, 218, 246, 97, 228, 251, 34,
            242, 193, 238, 210, 144, 12, 191, 179, 162, 241, 81, 51, 145, 235, 249, 14, 239, 107, 49, 192, 214, 31,
            181, 199, 106, 157, 184, 84, 204, 176, 115, 121, 50, 45, 127, 4, 150, 254, 138, 236, 205, 93, 222, 114,
            67, 29, 24, 72, 243, 141, 128, 195, 78, 66, 215, 61, 156, 180
        )

        private val p = IntArray(512) { permutation[it and 0xFF] }

        private fun fade(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

        private fun lerp(t: Float, a: Float, b: Float): Float = a + t * (b - a)

        private fun grad(hash: Int, x: Float, y: Float, z: Float): Float {
            val h = hash and 0xF
            val u = if (h < 8) x else y
            val v = if (h < 4) y else if (h == 12 || h == 14) x else z
            return (if ((h and 1) == 0) u else -u) + (if ((h and 2) == 0) v else -v)
        }

        fun noise(x: Float, y: Float, z: Float): Float {
            val xi = floor(x).toInt() and 0xFF
            val yi = floor(y).toInt() and 0xFF
            val zi = floor(z).toInt() and 0xFF
            val xf = x - floor(x)
            val yf = y - floor(y)
            val zf = z - floor(z)
            val u = fade(xf)
            val v = fade(yf)
            val w = fade(zf)
            val aaa = p[p[p[xi] + yi] + zi]
            val aba = p[p[p[xi] + yi + 1] + zi]
            val aab = p[p[p[xi] + yi] + zi + 1]
            val abb = p[p[p[xi] + yi + 1] + zi + 1]
            val baa = p[p[p[xi + 1] + yi] + zi]
            val bba = p[p[p[xi + 1] + yi + 1] + zi]
            val bab = p[p[p[xi + 1] + yi] + zi + 1]
            val bbb = p[p[p[xi + 1] + yi + 1] + zi + 1]
            val x1 = lerp(u, grad(aaa, xf, yf, zf), grad(baa, xf - 1f, yf, zf))
            val x2 = lerp(u, grad(aba, xf, yf - 1f, zf), grad(bba, xf - 1f, yf - 1f, zf))
            val y1 = lerp(v, x1, x2)
            val x3 = lerp(u, grad(aab, xf, yf, zf - 1f), grad(bab, xf - 1f, yf, zf - 1f))
            val x4 = lerp(u, grad(abb, xf, yf - 1f, zf - 1f), grad(bbb, xf - 1f, yf - 1f, zf - 1f))
            val y2 = lerp(v, x3, x4)
            return lerp(w, y1, y2)
        }

        fun fractalNoise(x: Float, y: Float, z: Float, octaves: Int, persistence: Float): Float {
            var total = 0f
            var frequency = 1f
            var amplitude = 1f
            var maxValue = 0f

            repeat(octaves) {
                total += noise(x * frequency, y * frequency, z * frequency) * amplitude
                maxValue += amplitude
                amplitude *= persistence
                frequency *= 2f
            }

            return total / maxValue
        }
    }

    // ---------------------------------------------------------------------
    // Hybrid noise (b6's `randomizations/HybridNoise`)
    // ---------------------------------------------------------------------

    private inner class HybridNoise {

        private var lastJitterTime = 0L
        private var lastAimStartTime = System.currentTimeMillis()
        private val lastHybridNoise = floatArrayOf(0f, 0f)

        fun hybridNoiseOffset(axis: Int, distanceToTarget: Float): Float {
            val timeBase = System.currentTimeMillis().toFloat() / 1000f
            val fatigueFactor = fatigueFactor()

            var combinedNoise = 0f
            val layerCount = hybridGroup.layerCount.random()
            for (layer in 0 until layerCount) {
                val layerWeight = 1f / (layer + 1).toFloat()
                val mixRatio = hybridGroup.brownianToOuRatio.random().coerceIn(0.001f, 0.999f)

                val brownian = brownianMotionNoise(
                    hybridGroup.mean.random() * fatigueFactor,
                    hybridGroup.sigma.random() * (layer + 1),
                    hybridGroup.dt.random()
                ) * (1f - mixRatio)

                val ou = ouProcessNoise(
                    if (layer == 0) lastHybridNoise[axis] else combinedNoise,
                    hybridGroup.mean.random() * fatigueFactor,
                    hybridGroup.theta.random(),
                    hybridGroup.sigma.random(),
                    hybridGroup.dt.random()
                ) * mixRatio

                combinedNoise += (brownian + ou) * layerWeight
            }

            val modulated = dynamicModulation(combinedNoise, timeBase) * fatigueFactor
            val spectralNoise = applySpectralProcessing(modulated, axis, timeBase)
            val coupledNoise = applyAxisCoupling(spectralNoise, axis)

            val scale = hybridGroup.globalScale.random()
            var result = coupledNoise * (if (scale > 5f) scale * 1.2f else scale * 0.8f)

            result *= distanceFactor(distanceToTarget)

            result = when (axis) {
                0 -> result
                1 -> result / hybridGroup.yawPitchRatio.random().coerceAtLeast(0.1f)
                else -> 0f
            }

            result += gaussianRandom(0f, hybridGroup.microJitter.random())

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastJitterTime > 1000L && (0f..1f).random() < hybridGroup.jitterProbability.random()) {
                lastJitterTime = currentTime
                result += gaussianRandom(0f, hybridGroup.jitterStrength.random())
            }

            val moving = MinecraftClient.getInstance().player?.moving ?: false
            if (!moving) {
                result *= min(1.2f, 1f + hybridGroup.microJitter.random())
            }

            val smoothFactor = (1f / (hybridGroup.smoothness.random() * fatigueFactor)).coerceIn(0.05f, 0.95f)
            lastHybridNoise[axis] = lerp(lastHybridNoise[axis], result, smoothFactor)

            if (moving) {
                lastAimStartTime = System.currentTimeMillis()
            }

            return lastHybridNoise[axis].coerceIn(-30f, 30f)
        }

        private fun fatigueFactor(): Float {
            val elapsed = System.currentTimeMillis() - lastAimStartTime
            return when {
                elapsed > 10_000L -> 1.8f
                elapsed > 5_000L -> 1.4f
                else -> 1.0f
            }
        }

        private fun dynamicModulation(combinedNoise: Float, timeBase: Float): Float {
            val frequency = hybridGroup.modulationFrequency.random()
            val amplitude = hybridGroup.modulationAmplitude.random()

            return when (hybridGroup.modulationType) {
                ModulationType.SINE -> {
                    val phase = timeBase * frequency * PI.toFloat() * 2f
                    combinedNoise * (1f + amplitude * sin(phase))
                }

                ModulationType.SAWTOOTH -> {
                    val phase = timeBase * frequency % 1f
                    combinedNoise * (1f + amplitude * (phase * 2f - 1f))
                }

                ModulationType.SQUARE -> {
                    val phase = timeBase * frequency % 1f > 0.5f
                    combinedNoise * (1f + amplitude * (if (phase) 1f else -1f))
                }

                ModulationType.RANDOM -> if (timeBase * frequency % 1f < 0.1f) {
                    combinedNoise * (1f + amplitude * ((0f..1f).random() * 2f - 1f))
                } else {
                    combinedNoise
                }
            }
        }

        /**
         * b6 couples the two axes to each other: yaw noise can leak into the
         * pitch pass and vice versa.
         */
        private fun applyAxisCoupling(noise: Float, axis: Int): Float {
            val coupling = hybridGroup.coupling.random()

            return when {
                axis == 0 && (0f..1f).random() < coupling ->
                    noise * (1f + (0f..1f).random() * coupling)

                axis == 1 && (0f..1f).random() < coupling * 0.5f ->
                    noise * (1f - (0f..1f).random() * coupling * 0.3f)

                else -> noise
            }
        }

        private fun distanceFactor(distanceToTarget: Float): Float {
            if (distanceToTarget <= 0f) {
                return 1f
            }

            val minDistance = hybridGroup.distanceRangeMin.random()
            val maxDistance = hybridGroup.distanceRangeMax.random()
            val minMultiplier = hybridGroup.distanceFactorMin.random()
            val maxMultiplier = hybridGroup.distanceFactorMax.random()

            return when {
                distanceToTarget <= minDistance -> minMultiplier
                distanceToTarget >= maxDistance -> maxMultiplier
                else -> lerp(
                    minMultiplier,
                    maxMultiplier,
                    (distanceToTarget - minDistance) / (maxDistance - minDistance)
                )
            }
        }

        private fun brownianMotionNoise(mean: Float, sigma: Float, dt: Float): Float {
            val u = (0f..1f).random().coerceAtLeast(1e-6f)
            val z = sqrt(dt.toDouble()) * sqrt(-2.0 * ln(u.toDouble())) *
                cos(2.0 * PI * (0f..1f).random())
            return (z * sigma + mean).toFloat()
        }

        private fun ouProcessNoise(current: Float, mean: Float, theta: Float, sigma: Float, dt: Float): Float {
            val drift = theta * (mean - current) * dt
            val diffusion = sigma * sqrt(dt.toDouble()).toFloat() * gaussianRandom(0f, 1f)
            return drift + diffusion
        }
    }

    // ---------------------------------------------------------------------
    // Spectral processing, shared by the hybrid noise pattern
    // ---------------------------------------------------------------------

    fun applySpectralProcessing(input: Float, axis: Int, timeBase: Float): Float {
        updateSpectralHistory(input, axis)

        var processed = input * hybridGroup.spectralDensity.random()
        processed = spectralFilter.applyFilter(
            processed,
            hybridGroup.frequencyBands.choiceName,
            hybridGroup.cutoffLow.random(),
            hybridGroup.cutoffHigh.random(),
            hybridGroup.spectralSlope.random()
        )

        if (hybridGroup.harmonicBoost.random() > 0.01f) {
            val harmonics = harmonicAnalyzer.analyze(processed, timeBase)
            for (i in harmonics.indices) {
                if (i >= 3) {
                    continue
                }
                processed += harmonics[i] * hybridGroup.harmonicBoost.random() * (0.8f / (i + 1).toFloat())
            }
        }

        val spectralPattern = detectSpectralPattern()
        val patternFactor = when (spectralPattern) {
            "low_freq" -> 1.1f
            "high_freq" -> 0.9f
            else -> 1.0f
        }
        processed *= patternFactor
        processed = if (axis == 0) {
            applyYawPatternFactor(processed, spectralPattern)
        } else {
            applyPitchPatternFactor(processed, spectralPattern)
        }

        return applySpectralDispersion(processed, axis)
    }

    private fun applyYawPatternFactor(processed: Float, spectralPattern: String): Float = when (spectralPattern) {
        "low_freq" -> processed * 0.95f
        "high_freq" -> processed * 1.05f
        else -> processed
    }

    private fun applyPitchPatternFactor(processed: Float, spectralPattern: String): Float {
        val highFreqReduction = if (spectralPattern == "high_freq") 0.6f else 0.7f
        return processed * (1f - hybridGroup.spectralSlope.random() * 0.1f * highFreqReduction)
    }

    private fun updateSpectralHistory(signal: Float, axis: Int) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - spectralUpdateTime <= 16L) {
            return
        }

        if (spectralHistory.size >= 128) {
            spectralHistory.removeFirst()
        }

        val freqData = FloatArray(64)
        for (i in freqData.indices) {
            val freq = (i + 1).toFloat() * 0.1f
            freqData[i] = signal * sin(currentTime.toFloat() * 0.001f * freq * (1f + axis * 0.2f))
        }

        spectralHistory.addLast(freqData)
        spectralUpdateTime = currentTime
    }

    private fun applySpectralDispersion(signal: Float, axis: Int): Float {
        if (spectralHistory.size < 32) {
            return signal
        }

        val recentData = spectralHistory.toList().takeLast(32)
        var spectralSpread = 0f
        for (i in 0 until 64) {
            var binSum = 0f
            for (frame in recentData) {
                binSum += abs(frame[i])
            }
            spectralSpread += binSum / recentData.size.toFloat() * (i + 1).toFloat()
        }

        val avgSpread = spectralSpread / 64f
        if (avgSpread < 0.1f) {
            val dispersion = (0.2f - avgSpread) * 2f
            return signal * (1f + dispersion * (0.3f + axis * 0.1f))
        }

        return signal
    }

    private fun calculateSpectralCentroid(): Float {
        if (spectralHistory.isEmpty()) {
            return 0.5f
        }

        var totalEnergy = 0f
        var weightedSum = 0f
        val latest = spectralHistory.last()

        for (i in latest.indices) {
            val energy = abs(latest[i])
            totalEnergy += energy
            weightedSum += energy * i.toFloat()
        }

        return if (totalEnergy > 0f) weightedSum / totalEnergy / latest.size.toFloat() else 0.5f
    }

    private fun detectSpectralPattern(): String {
        if (spectralHistory.size < 16) {
            return "random"
        }

        val centroid = calculateSpectralCentroid()
        return when {
            centroid < 0.3f -> "low_freq"
            centroid > 0.7f -> "high_freq"
            else -> "balanced"
        }
    }

    private inner class HarmonicAnalyzer {

        private val harmonicAmplitudes = FloatArray(8)
        private val harmonicPhases = FloatArray(8)
        private val history = ArrayDeque<Float>()

        fun analyze(signal: Float, time: Float): FloatArray {
            history.addLast(signal)
            if (history.size > 512) {
                history.removeFirst()
            }

            if (history.size >= 64) {
                val samples = history.toFloatArray()
                for (i in 1..8) {
                    harmonicAmplitudes[i - 1] = detectHarmonic(samples, i, time)
                    harmonicPhases[i - 1] = time * i % (PI.toFloat() * 2f)
                }
            }

            return harmonicAmplitudes.copyOf()
        }

        private fun detectHarmonic(samples: FloatArray, harmonic: Int, time: Float): Float {
            var sum = 0f
            val freq = harmonic.toFloat() * 0.1f

            for (i in samples.indices) {
                val t = i.toFloat() / samples.size.toFloat() * 2f * PI.toFloat()
                sum += samples[i] * sin(freq * t + time * 0.01f)
            }

            return abs(sum / samples.size.toFloat()) * 2f
        }
    }

    private inner class SpectralFilter {

        private val buffer = FloatArray(4)
        private var lastOutput = 0f

        fun applyFilter(
            input: Float,
            filterType: String,
            cutoffLow: Float,
            cutoffHigh: Float,
            slope: Float
        ): Float {
            buffer[3] = buffer[2]
            buffer[2] = buffer[1]
            buffer[1] = buffer[0]
            buffer[0] = input

            return when (filterType) {
                "LowPass" -> lowPass(input, cutoffLow, slope)
                "HighPass" -> highPass(input, cutoffHigh, slope)
                "BandPass" -> bandPass(input, cutoffLow, cutoffHigh, slope)
                "Notch" -> notchFilter(input, (cutoffLow + cutoffHigh) / 2f, slope * 0.5f)
                else -> input
            }
        }

        private fun lowPass(input: Float, cutoff: Float, slope: Float): Float {
            val alpha = (1f / (1f + slope * cutoff)).coerceIn(0.01f, 0.99f)
            lastOutput = lastOutput * alpha + input * (1f - alpha)
            return lastOutput
        }

        private fun highPass(input: Float, cutoff: Float, slope: Float): Float {
            val alpha = (slope * cutoff).coerceIn(0.01f, 0.99f)
            lastOutput = alpha * (lastOutput + input - buffer[1])
            return lastOutput
        }

        private fun bandPass(input: Float, cutoffLow: Float, cutoffHigh: Float, slope: Float): Float {
            val low = lowPass(input, cutoffLow, slope)
            return highPass(low, cutoffHigh, slope)
        }

        private fun notchFilter(input: Float, centerFreq: Float, slope: Float): Float {
            val omega = PI.toFloat() * 2f * centerFreq
            val alpha = sin(omega / 2f) * slope
            val a0 = 1f + alpha
            val a1 = -2f * cos(omega)
            val a2 = 1f - alpha
            // b6 declares b0 and b2 as booleans, so both coefficients are 1.0f.
            val b0 = 1f
            val b1 = -2f * cos(omega)
            val b2 = 1f
            return b0 / a0 * input + b1 / a0 * buffer[0] + b2 / a0 * buffer[1] -
                a1 / a0 * lastOutput - a2 / a0 * buffer[2]
        }
    }

    // ---------------------------------------------------------------------
    // Chaotic pattern (b6's `randomizations/ChaoticRandomization`)
    // ---------------------------------------------------------------------

    private inner class ChaoticRandomization {

        private var lorenzX = 0.1f
        private var lorenzY = 0f
        private var lorenzZ = 0f
        private var rosslerX = 0.1f
        private var rosslerY = 0f
        private var rosslerZ = 0f
        private var henonX = 0.1f
        private var henonY = 0f
        private var doublePendulumTheta1 = 1.5707964f
        private var doublePendulumTheta2 = 1.5707964f
        private var doublePendulumOmega1 = 0f
        private var doublePendulumOmega2 = 0f
        private var lastChaoticTime = System.currentTimeMillis()
        private val chaoticHistory = ArrayDeque<FloatArray>()
        private var customChaosX = 0.1f
        private var customChaosY = 0f
        private var customChaosZ = 0f
        private var switchSystemState = 0f
        private var switchDirection = 1f
        private var systemTransitionProgress = 0f
        private var previousSystem = RotationSystem.LORENZ
        private var lorenzSigma = 10f
        private var lorenzRho = 28f
        private var lorenzBeta = 2.6666667f
        private var rosslerA = 0.2f
        private var rosslerB = 0.2f
        private var rosslerC = 5.7f
        private var lastHumanBehaviorTime = System.currentTimeMillis()
        private var currentBehaviorPattern = "normal"
        private var behaviorTransitionProgress = 0f
        private val behaviorPatterns = listOf("normal", "aggressive", "precise", "erratic", "fatigued")
        private var nextPatternChangeTime = System.currentTimeMillis() + (30_000L..120_000L).random()
        private var lastResetTime = System.currentTimeMillis()
        private var combatStartTime = System.currentTimeMillis()

        fun enhancedChaoticOffset(axis: Int): Float {
            if (!chaoticGroup.antiMl) {
                return trueRandomChaoticOffset(axis)
            }

            var offset = trueRandomChaoticOffset(axis)
            offset = applyPatternRandomization(offset, axis)
            offset = applyTemporalNoise(offset, axis)
            if (chaoticGroup.behaviorMimicry) {
                offset = applyBehaviorMimicry(offset, axis)
            }
            if (chaoticGroup.humanLikeTransitions) {
                offset = applyHumanLikeTransitions(offset, axis)
            }
            offset = applyDynamicSystemSwitching(offset, axis)

            return offset.coerceIn(-25f, 25f)
        }

        fun enhancedAimContext(): EnhancedAimContext? {
            val target = targetEntity() ?: return null
            val player = MinecraftClient.getInstance().player ?: return null

            val distance = player.distanceTo(target)
            val targetSpeed = sqrt(target.velocity.x * target.velocity.x + target.velocity.z * target.velocity.z)
            val playerSpeed = sqrt(player.velocity.x * player.velocity.x + player.velocity.z * player.velocity.z)
            val combatDuration = System.currentTimeMillis() - combatStartTime
            val threatLevel = calculateThreatLevel(player, target)

            return EnhancedAimContext(
                distance.toFloat(),
                targetSpeed.toFloat(),
                playerSpeed.toFloat(),
                combatDuration,
                0.5f,
                threatLevel
            )
        }

        private fun calculateThreatLevel(
            player: net.minecraft.client.network.ClientPlayerEntity,
            target: Entity
        ): Float {
            var threat = max(10f - player.distanceTo(target), 0f) * 0.1f
            val near = player.boundingBox.expand(10.0, 10.0, 10.0)
            val nearbyEnemies = player.world.getOtherEntities(player, near) { entity ->
                entity is LivingEntity && entity !== player && entity !== target
            }.size
            threat += nearbyEnemies.toFloat() * 0.2f
            return threat.coerceIn(0f, 1f)
        }

        private fun applyPatternRandomization(offset: Float, axis: Int): Float {
            if ((0f..1f).random() >= chaoticGroup.patternRandomization.random()) {
                return offset
            }

            val axisFactor = if (axis == 1) 0.7f else 1.0f
            return offset * (0.5f + (0f..1f).random() * 1.5f) * axisFactor
        }

        private fun applyTemporalNoise(offset: Float, axis: Int): Float {
            val axisNoiseFactor = if (axis == 1) 0.6f else 1.0f
            val timeNoise = gaussianRandom(0f, chaoticGroup.temporalNoise.random() * axisNoiseFactor)
            return offset * (1f + timeNoise * 0.1f)
        }

        private fun applyBehaviorMimicry(offset: Float, axis: Int): Float {
            updateBehaviorPattern()

            val baseMultiplier = when (currentBehaviorPattern) {
                "aggressive" -> 1.3f
                "precise" -> 0.7f
                "erratic" -> 0.5f + (0f..1f).random() * 1.5f
                "fatigued" -> 0.8f
                else -> 1.0f
            }

            val axisBehaviorFactor = if (axis == 1) 0.8f else 1.0f
            val adjustedMultiplier = if (currentBehaviorPattern == "fatigued") {
                baseMultiplier + gaussianRandom(0f, 0.5f) * axisBehaviorFactor
            } else {
                baseMultiplier * axisBehaviorFactor
            }

            return offset * adjustedMultiplier
        }

        private fun applyHumanLikeTransitions(offset: Float, axis: Int): Float {
            val reactionTime = if (axis == 1) 0.002f else 0.001f
            val reactionVariation = sin(System.currentTimeMillis().toFloat() * reactionTime) * 0.2f
            val axisAmplitude = if (axis == 1) 0.15f else 0.2f
            return offset * (1f + reactionVariation * axisAmplitude)
        }

        private fun applyDynamicSystemSwitching(offset: Float, axis: Int): Float {
            if (chaoticGroup.dynamicMixing && (0f..1f).random() < chaoticGroup.autoSwitch.random()) {
                rotateChaoticSystem()
            }

            return if (chaoticGroup.dynamicMixing) dynamicMixedOffset(axis) else offset
        }

        private fun updateBehaviorPattern() {
            val currentTime = System.currentTimeMillis()

            if (currentTime >= nextPatternChangeTime) {
                val newPattern = behaviorPatterns.random()
                if (newPattern != currentBehaviorPattern) {
                    currentBehaviorPattern = newPattern
                    behaviorTransitionProgress = 0f
                    lastHumanBehaviorTime = currentTime
                    nextPatternChangeTime = currentTime + (30_000L..120_000L).random()
                }
            }

            val timeSinceLastChange = if (lastHumanBehaviorTime != 0L) currentTime - lastHumanBehaviorTime else 0L
            behaviorTransitionProgress = (timeSinceLastChange.toFloat() / 5000f).coerceIn(0f, 1f)
        }

        /**
         * b6 rotates the `Chaotic/System` setting itself, so the switch is
         * reflected in the config; the currently selected entry supplies the
         * starting index.
         */
        private fun rotateChaoticSystem() {
            previousSystem = chaoticGroup.system
            chaoticSystemIndex = (chaoticGroup.system.ordinal + 1) % RotationSystem.entries.size
            chaoticGroup.system = RotationSystem.entries[chaoticSystemIndex]
            systemTransitionProgress = 0f
        }

        private fun dynamicMixedOffset(axis: Int): Float {
            val weights = if (chaoticGroup.adaptiveWeights) {
                calculateAdaptiveWeights()
            } else {
                floatArrayOf(0.2f, 0.2f, 0.15f, 0.15f, 0.2f, 0.1f)
            }

            return lorenzOffset(axis) * weights[0] +
                rosslerOffset(axis) * weights[1] +
                henonOffset(axis) * weights[2] +
                pendulumOffset(axis) * weights[3] +
                customChaoticOffset(axis) * weights[4] +
                switchOffset(axis) * weights[5]
        }

        private fun calculateAdaptiveWeights(): FloatArray {
            val player = MinecraftClient.getInstance().player
                ?: return floatArrayOf(0.17f, 0.17f, 0.16f, 0.16f, 0.17f, 0.17f)

            val target = targetEntity() ?: return floatArrayOf(0.2f, 0.2f, 0.15f, 0.15f, 0.2f, 0.1f)

            val distance = player.distanceTo(target)
            return when {
                distance < 3.0f -> floatArrayOf(0.3f, 0.2f, 0.1f, 0.1f, 0.2f, 0.1f)
                distance > 15.0f -> floatArrayOf(0.15f, 0.25f, 0.2f, 0.15f, 0.15f, 0.1f)
                else -> floatArrayOf(0.2f, 0.2f, 0.15f, 0.15f, 0.2f, 0.1f)
            }
        }

        private fun trueRandomChaoticOffset(axis: Int): Float {
            val chaoticPart = chaoticOffset(axis)

            val randomPart = when (axis) {
                0 -> gaussianRandom(0f, chaoticGroup.intensity.random() * 0.5f)
                1 -> gaussianRandom(0f, chaoticGroup.intensity.random() * 0.3f)
                else -> 0f
            }

            val jumpPart = if ((0f..1f).random() < 0.02f) {
                gaussianRandom(0f, chaoticGroup.jumpIntensity.random())
            } else {
                0f
            }

            val mixRatio = chaoticGroup.noiseMix.random()
            return chaoticPart * (1f - mixRatio) + (randomPart + jumpPart) * mixRatio
        }

        private fun chaoticOffset(axis: Int): Float {
            val currentTime = System.currentTimeMillis()
            val dt = (currentTime - lastChaoticTime).toFloat() / 1000f
            lastChaoticTime = currentTime
            updateChaoticSystems(dt.coerceIn(0.001f, 0.1f))

            val baseOffset = when (chaoticGroup.system) {
                RotationSystem.LORENZ -> lorenzOffset(axis)
                RotationSystem.ROSSLER -> rosslerOffset(axis)
                RotationSystem.HENON -> henonOffset(axis)
                RotationSystem.DOUBLE_PENDULUM -> pendulumOffset(axis)
                RotationSystem.MULTI_CHAOTIC -> multiChaoticOffset(axis)
                RotationSystem.SWITCH -> switchOffset(axis)
            }

            var offset = baseOffset * chaoticGroup.intensity.random() * 10f

            if (axis == 1 && (0f..1f).random() < chaoticGroup.coupling.random()) {
                offset *= 0.3f
            }

            if (chaoticGroup.adaptive) {
                offset = applyAdaptiveChaos(offset, axis)
            }

            return offset.coerceIn(-25f, 25f)
        }

        private fun lorenzOffset(axis: Int): Float = when (axis) {
            0 -> lorenzX * 0.1f
            1 -> lorenzY * 0.05f
            else -> 0f
        }

        private fun rosslerOffset(axis: Int): Float = when (axis) {
            0 -> rosslerX * 0.2f
            1 -> rosslerY * 0.1f
            else -> 0f
        }

        private fun henonOffset(axis: Int): Float = when (axis) {
            0 -> henonX * 0.5f
            1 -> henonY * 0.3f
            else -> 0f
        }

        private fun pendulumOffset(axis: Int): Float {
            val angle = when (axis) {
                0 -> doublePendulumTheta1
                1 -> doublePendulumTheta2
                else -> 0f
            }
            return angle % (PI.toFloat() * 2f) * 2f
        }

        private fun multiChaoticOffset(axis: Int): Float =
            lorenzOffset(axis) * 0.3f +
                rosslerOffset(axis) * 0.25f +
                henonOffset(axis) * 0.2f +
                pendulumOffset(axis) * 0.15f +
                customChaoticOffset(axis) * 0.1f

        private fun switchOffset(axis: Int): Float {
            val scale = when (axis) {
                0 -> 3f
                1 -> 1.5f
                else -> 0f
            }
            return switchSystemState * scale
        }

        private fun customChaoticOffset(axis: Int): Float = when (axis) {
            0 -> (customChaosX * 0.8f + sin(customChaosY) * 0.2f) * 2f
            1 -> (customChaosY * 0.6f + cos(customChaosZ) * 0.4f) * 1.5f
            else -> 0f
        }

        private fun applyAdaptiveChaos(offset: Float, axis: Int): Float {
            val player = MinecraftClient.getInstance().player ?: return offset

            val baseSensitivity: Float
            val stability: Float
            if (axis == 0) {
                baseSensitivity = 1.0f
                stability = 0.8f
            } else {
                baseSensitivity = 0.7f
                stability = 0.9f
            }

            val stateFactor = when {
                player.isSneaking -> 0.4f * stability
                player.isSprinting -> 1.3f * (1.1f - stability)
                player.moving -> 0.9f
                else -> 0.6f * stability
            }

            val target = targetEntity()
            val distance = if (target != null) player.distanceTo(target) else 10f

            val distanceFactor = when {
                distance < 3.0f -> 1.6f - stability * 0.3f
                distance > 15.0f -> 0.5f + stability * 0.3f
                else -> 1.0f
            }

            return offset * baseSensitivity * stateFactor * distanceFactor
        }

        private fun updateChaoticSystems(dt: Float) {
            val effectiveDt = dt * chaoticGroup.speed.random()
            maybeResetChaoticSystem()

            when (chaoticGroup.system) {
                RotationSystem.LORENZ -> updateLorenz(effectiveDt)
                RotationSystem.ROSSLER -> updateRossler(effectiveDt)
                RotationSystem.HENON -> updateHenon()
                RotationSystem.DOUBLE_PENDULUM -> updateDoublePendulum(effectiveDt)
                RotationSystem.MULTI_CHAOTIC -> updateMultiChaotic(effectiveDt)
                RotationSystem.SWITCH -> updateSwitchSystem(effectiveDt)
            }

            updateAllChaoticSystems(effectiveDt)
            evolveChaoticParameters()

            if (chaoticGroup.memory) {
                saveChaoticHistory()
            }
        }

        private fun updateAllChaoticSystems(dt: Float) {
            updateLorenz(dt * 0.5f)
            updateRossler(dt * 0.6f)
            updateHenon()
            updateDoublePendulum(dt * 0.8f)
            updateCustomChaoticSystem(dt * 0.7f)
        }

        private fun updateLorenz(dt: Float) {
            val dx = lorenzSigma * (lorenzY - lorenzX) * dt
            val dy = (lorenzX * (lorenzRho - lorenzZ) - lorenzY) * dt
            val dz = (lorenzX * lorenzY - lorenzBeta * lorenzZ) * dt
            val forceRandomness = chaoticGroup.paramRandomness.random() * 0.1f

            lorenzX += dx + gaussianRandom(0f, 0.01f) * forceRandomness
            lorenzY += dy + gaussianRandom(0f, 0.01f) * forceRandomness
            lorenzZ += dz + gaussianRandom(0f, 0.005f) * forceRandomness
        }

        private fun updateRossler(dt: Float) {
            val dx = (-rosslerY - rosslerZ) * dt
            val dy = (rosslerX + rosslerA * rosslerY) * dt
            val dz = (rosslerB + rosslerZ * (rosslerX - rosslerC)) * dt
            val forceRandomness = chaoticGroup.paramRandomness.random() * 0.1f

            rosslerX += dx + gaussianRandom(0f, 0.01f) * forceRandomness
            rosslerY += dy + gaussianRandom(0f, 0.01f) * forceRandomness
            rosslerZ += dz + gaussianRandom(0f, 0.005f) * forceRandomness
        }

        private fun updateHenon() {
            val newX = 1f - rosslerA * henonX * henonX + henonY
            val newY = rosslerB * henonX
            val noise = chaoticGroup.paramRandomness.random() * 0.05f

            henonX = newX + gaussianRandom(0f, noise)
            henonY = newY + gaussianRandom(0f, noise * 0.5f)
        }

        private fun updateDoublePendulum(dt: Float) {
            val num1 = -29.400002f * sin(doublePendulumTheta1)
            val num2 = -9.8f * sin(doublePendulumTheta1 - 2f * doublePendulumTheta2)
            val num3 = -2f * sin(doublePendulumTheta1 - doublePendulumTheta2) * 1f
            val num4 = doublePendulumOmega2 * doublePendulumOmega2 * 1f +
                doublePendulumOmega1 * doublePendulumOmega1 * 1f *
                cos(doublePendulumTheta1 - doublePendulumTheta2)
            val den = 1f * (3f - 1f * cos(2f * doublePendulumTheta1 - 2f * doublePendulumTheta2))
            val alpha1 = (num1 + num2 + num3 * num4) / den

            val num5 = 2f * sin(doublePendulumTheta1 - doublePendulumTheta2)
            val num6 = doublePendulumOmega1 * doublePendulumOmega1 * 1f * 2f
            val num7 = 19.6f * cos(doublePendulumTheta1)
            val num8 = doublePendulumOmega2 * doublePendulumOmega2 * 1f * 1f *
                cos(doublePendulumTheta1 - doublePendulumTheta2)
            val den2 = 1f * (3f - 1f * cos(2f * doublePendulumTheta1 - 2f * doublePendulumTheta2))
            val alpha2 = num5 * (num6 + num7 + num8) / den2

            val forceRandomness = chaoticGroup.paramRandomness.random() * 0.5f
            doublePendulumOmega1 += alpha1 * dt + gaussianRandom(0f, 0.1f) * forceRandomness
            doublePendulumOmega2 += alpha2 * dt + gaussianRandom(0f, 0.1f) * forceRandomness
            doublePendulumTheta1 += doublePendulumOmega1 * dt
            doublePendulumTheta2 += doublePendulumOmega2 * dt
        }

        private fun updateMultiChaotic(dt: Float) {
            updateLorenz(dt * 0.3f)
            updateRossler(dt * 0.4f)
            updateHenon()
            updateCustomChaoticSystem(dt * 0.3f)
        }

        private fun updateSwitchSystem(dt: Float) {
            switchSystemState += switchDirection * dt * 5f
            if (abs(switchSystemState) > 1f) {
                switchDirection *= -1f
                switchSystemState = switchSystemState.coerceIn(-1f, 1f)
            }

            if ((0f..1f).random() < 0.1f) {
                switchSystemState += gaussianRandom(0f, 0.1f)
            }
        }

        private fun updateCustomChaoticSystem(dt: Float) {
            val a = 1.5f + gaussianRandom(0f, 0.2f) * chaoticGroup.paramRandomness.random()
            val b = 0.8f + gaussianRandom(0f, 0.1f) * chaoticGroup.paramRandomness.random()
            val c = 1.2f + gaussianRandom(0f, 0.15f) * chaoticGroup.paramRandomness.random()

            val dx = (a * (customChaosY - customChaosX) + c * sin(customChaosZ)) * dt
            val dy = (customChaosX * (b - customChaosZ) - customChaosY + a * cos(customChaosX)) * dt
            val dz = (customChaosX * customChaosY - c * customChaosZ + b * sin(customChaosY)) * dt

            customChaosX += dx + gaussianRandom(0f, 0.01f) * chaoticGroup.paramRandomness.random()
            customChaosY += dy + gaussianRandom(0f, 0.01f) * chaoticGroup.paramRandomness.random()
            customChaosZ += dz + gaussianRandom(0f, 0.005f) * chaoticGroup.paramRandomness.random()
        }

        private fun evolveChaoticParameters() {
            if (!chaoticGroup.paramEvolution) {
                return
            }

            val evolutionRate = chaoticGroup.evolutionRate.random()
            lorenzSigma += gaussianRandom(0f, 0.01f) * evolutionRate
            lorenzRho += gaussianRandom(0f, 0.02f) * evolutionRate
            lorenzBeta += gaussianRandom(0f, 0.005f) * evolutionRate
            rosslerA += gaussianRandom(0f, 0.005f) * evolutionRate
            rosslerB += gaussianRandom(0f, 0.005f) * evolutionRate
            rosslerC += gaussianRandom(0f, 0.01f) * evolutionRate

            lorenzSigma = lorenzSigma.coerceIn(8f, 12f)
            lorenzRho = lorenzRho.coerceIn(20f, 35f)
            lorenzBeta = lorenzBeta.coerceIn(2f, 3f)
            rosslerA = rosslerA.coerceIn(0.1f, 0.3f)
            rosslerB = rosslerB.coerceIn(0.1f, 0.3f)
            rosslerC = rosslerC.coerceIn(4f, 7f)
        }

        private fun saveChaoticHistory() {
            val state = floatArrayOf(
                lorenzX, lorenzY, lorenzZ, rosslerX, rosslerY, rosslerZ, henonX, henonY,
                doublePendulumTheta1, doublePendulumTheta2, customChaosX, customChaosY, customChaosZ,
                switchSystemState
            )

            if (chaoticHistory.size >= 100) {
                chaoticHistory.removeFirst()
            }
            chaoticHistory.addLast(state)
        }

        private fun maybeResetChaoticSystem() {
            if ((0f..1f).random() < chaoticGroup.resetChance.random()) {
                lastResetTime = System.currentTimeMillis()
                resetSystemState(chaoticGroup.system)
            }
        }

        @Suppress("CyclomaticComplexMethod")
        private fun resetSystemState(system: RotationSystem) {
            when (system) {
                RotationSystem.LORENZ -> {
                    lorenzX = gaussianRandom(0f, 1f)
                    lorenzY = gaussianRandom(0f, 1f)
                    lorenzZ = gaussianRandom(0f, 1f)
                }

                RotationSystem.ROSSLER -> {
                    rosslerX = gaussianRandom(0f, 0.5f)
                    rosslerY = gaussianRandom(0f, 0.5f)
                    rosslerZ = gaussianRandom(0f, 0.5f)
                }

                RotationSystem.HENON -> {
                    henonX = gaussianRandom(0f, 0.3f)
                    henonY = gaussianRandom(0f, 0.3f)
                }

                RotationSystem.DOUBLE_PENDULUM -> {
                    doublePendulumTheta1 = gaussianRandom(0.7853982f, 0.3926991f)
                    doublePendulumTheta2 = gaussianRandom(0.7853982f, 0.3926991f)
                    doublePendulumOmega1 = gaussianRandom(0f, 1f)
                    doublePendulumOmega2 = gaussianRandom(0f, 1f)
                }

                RotationSystem.SWITCH -> {
                    switchSystemState = 0f
                    switchDirection = if ((0..1).random() == 1) 1f else -1f
                }

                RotationSystem.MULTI_CHAOTIC -> Unit
            }
        }
    }

    private fun applyContextualFineTuning(rotation: Rotation, context: EnhancedAimContext) {
        if (context.combatDuration > 30_000L) {
            rotation.yaw *= 0.95f
            rotation.pitch *= 0.95f
        } else if (context.threatLevel > 0.7f) {
            rotation.yaw *= 1.1f
            rotation.pitch *= 1.05f
        }
    }

    // ---------------------------------------------------------------------
    // Advanced pattern: trajectory database (b6's private companion)
    // ---------------------------------------------------------------------

    private fun processAdvanced(rotation: Rotation) {
        if (trajectoryDB.count() < 10) {
            loadDefaultTrajectories()
        }

        val currentTarget = referenceRotation()
        val useContextAware = advancedGroup.contextAware
        val currentContext = if (useContextAware) currentAimContext() else null

        var candidates = trajectoryDB.findSimilar(currentTarget, advancedGroup.trajectoryCount.random(), currentContext)
        if (candidates.isEmpty()) {
            loadDefaultTrajectories()
            candidates = trajectoryDB.findSimilar(currentTarget, 5, currentContext)
        }

        val effectiveNoise = advancedGroup.noiseLevel.random().coerceIn(0f, 0.15f)
        val selected = selectOptimalTrajectory(candidates, currentContext, useContextAware)

        val points = selected.points
        if (points.size >= 4) {
            val progress = (advancedProgress % points.size).toInt().coerceAtMost(points.size - 4)
            val t = (advancedProgress - progress).coerceIn(0f, 1f)

            if (advancedGroup.interpolation == Interpolation.CATMULL_ROM) {
                rotation.yaw = catmullRomInterpolate(points, progress, t, 0)
                rotation.pitch = catmullRomInterpolate(points, progress, t, 1) * 0.8f
            } else {
                rotation.yaw = hermiteInterpolate(
                    points[progress].yaw, points[progress + 1].yaw,
                    points[progress + 2].yaw, points[progress + 3].yaw,
                    t, advancedGroup.smoothness.random()
                )
                rotation.pitch = hermiteInterpolate(
                    points[progress].pitch, points[progress + 1].pitch,
                    points[progress + 2].pitch, points[progress + 3].pitch,
                    t, advancedGroup.smoothness.random() * 0.8f
                )
            }
        }

        val baseSpeed = 0.1f + effectiveNoise * 2f
        val finalSpeed = if (advancedGroup.adaptiveSpeed && currentContext != null) {
            baseSpeed * (currentContext.distance / 10f).coerceIn(0.5f, 2f)
        } else {
            baseSpeed
        }

        advancedProgress += finalSpeed.coerceIn(0.05f, 0.3f)
        recordCurrentTrajectory(rotation, currentContext)
    }

    private fun selectOptimalTrajectory(
        candidates: List<RotationTrajectory>,
        context: AimContext?,
        useContextAware: Boolean
    ): RotationTrajectory {
        var best = candidates.first()

        for (candidate in candidates) {
            val bestScore = trajectoryScore(best, context, useContextAware)
            val candidateScore = trajectoryScore(candidate, context, useContextAware)

            if (bestScore < candidateScore) {
                best = candidate
            }
        }

        return best
    }

    private fun trajectoryScore(
        trajectory: RotationTrajectory,
        context: AimContext?,
        useContextAware: Boolean
    ): Float {
        var score = trajectory.score

        if (useContextAware && context != null) {
            val trajectoryContext = trajectory.context
            if (trajectoryContext != null) {
                if (abs(trajectoryContext.distance - context.distance) < 3f) {
                    score += 0.3f
                }
                if (abs(trajectoryContext.targetSpeed - context.targetSpeed).toDouble() < 0.5) {
                    score += 0.2f
                }
            }
        }

        return score + ((0f..1f).random() * 2f - 1f) * advancedGroup.noiseLevel.random()
    }

    private fun catmullRomInterpolate(points: List<Rotation>, index: Int, t: Float, axis: Int): Float {
        val p0 = points.getOrNull(index - 1) ?: points[index]
        val p1 = points[index]
        val p2 = points[index + 1]
        val p3 = points.getOrNull(index + 2) ?: points[index + 1]

        val v0 = if (axis == 0) p0.yaw else p0.pitch
        val v1 = if (axis == 0) p1.yaw else p1.pitch
        val v2 = if (axis == 0) p2.yaw else p2.pitch
        val v3 = if (axis == 0) p3.yaw else p3.pitch

        return catmullRom(t, v0, v1, v2, v3)
    }

    private fun catmullRom(t: Float, v0: Float, v1: Float, v2: Float, v3: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (
            2f * v1 + (-v0 + v2) * t +
                (2f * v0 - 5f * v1 + 4f * v2 - v3) * t2 +
                (-v0 + 3f * v1 - 3f * v2 + v3) * t3
            )
    }

    private fun hermiteInterpolate(
        y0: Float,
        y1: Float,
        y2: Float,
        y3: Float,
        mu: Float,
        tension: Float
    ): Float {
        val m0 = (y1 - y0) * tension
        val m1 = (y3 - y2) * tension
        val mu2 = mu * mu
        val mu3 = mu2 * mu

        return (2f * mu3 - 3f * mu2 + 1f) * y1 +
            (mu3 - 2f * mu2 + mu) * m0 +
            (-2f * mu3 + 3f * mu2) * y2 +
            (mu3 - mu2) * m1
    }

    private fun recordCurrentTrajectory(rotation: Rotation, context: AimContext?) {
        trajectoryRecording.add(Rotation(rotation.yaw, rotation.pitch))

        if (trajectoryRecording.size <= 50) {
            return
        }

        trajectoryDB.addTrajectory(
            RotationTrajectory(
                trajectoryRecording.first(),
                trajectoryRecording.toList(),
                context,
                calculateTrajectoryScore(trajectoryRecording)
            ),
            advancedGroup.trajectoryMemory.random()
        )
        trajectoryRecording.clear()
    }

    private fun calculateTrajectoryScore(points: List<Rotation>): Float {
        var score = 1f

        for (i in 1 until points.size) {
            val deltaYaw = abs(angleDelta(points[i].yaw, points[i - 1].yaw))
            val deltaPitch = abs(points[i].pitch - points[i - 1].pitch)

            if (deltaYaw > 30f || deltaPitch > 15f) {
                score *= 0.7f
                continue
            }

            if (deltaYaw < 0.5 && deltaPitch < 0.3) {
                score *= 1.05f
            }
        }

        return score.coerceIn(0.1f, 1.5f)
    }

    private fun loadDefaultTrajectories() {
        val trajectories = listOf(
            listOf(
                Rotation(0f, 0f), Rotation(2f, 1f), Rotation(4f, 2f), Rotation(6f, 1f), Rotation(8f, 0f)
            ) to AimContext(5f, 0.5f, 0.3f),
            listOf(
                Rotation(0f, 0f), Rotation(12f, 4f), Rotation(20f, 2f), Rotation(15f, 1f), Rotation(10f, 0f)
            ) to AimContext(3f, 2f, 1f),
            listOf(
                Rotation(0f, 0f), Rotation(1f, 0.3f), Rotation(0f, 0f), Rotation(-1f, 0.2f), Rotation(0f, 0f)
            ) to AimContext(8f, 0.1f, 0f),
            listOf(
                Rotation(0f, 0f), Rotation(5f, 2f), Rotation(10f, 3f), Rotation(15f, 2f), Rotation(20f, 1f)
            ) to AimContext(12f, 0f, 0f)
        )

        for ((points, context) in trajectories) {
            trajectoryDB.addTrajectory(
                RotationTrajectory(points.first(), points, context, 1.5f),
                advancedGroup.trajectoryMemory.random()
            )
        }
    }

    private fun distanceToTarget(rotationTarget: RotationTarget): Float {
        val entity = rotationTarget.entity ?: return 0f
        val player = MinecraftClient.getInstance().player ?: return 0f
        return player.distanceTo(entity)
    }

    private fun currentAimContext(): AimContext? {
        val target = targetEntity() ?: return null
        val player = MinecraftClient.getInstance().player ?: return null

        val targetSpeed = sqrt(target.velocity.x * target.velocity.x + target.velocity.z * target.velocity.z)
        val playerSpeed = sqrt(player.velocity.x * player.velocity.x + player.velocity.z * player.velocity.z)

        return AimContext(player.distanceTo(target), targetSpeed.toFloat(), playerSpeed.toFloat())
    }

    /** Index 0 is the newest rotation, matching b6's `getLastRotations()[2]` read. */
    private fun referenceRotation(): Rotation =
        rotationHistory[2] ?: rotationHistory[1] ?: rotationHistory[0] ?: Rotation.ZERO

    /**
     * b6 shifts one new entry in per tick and reads three ticks back; the
     * processor runs once per tick, so this keeps the same three-slot history.
     */
    private fun rememberRotation(rotation: Rotation) {
        rotationHistory[2] = rotationHistory[1]
        rotationHistory[1] = rotationHistory[0]
        rotationHistory[0] = rotation
    }

    /**
     * b6's `RotationUtils.angleDifference`, `wrapDegrees(a - b)`. The mapped
     * LiquidBounce helper returns a `Double`; the original was `Float`, so the
     * narrowing is done here to keep the arithmetic identical.
     */
    private fun angleDelta(a: Float, b: Float): Float = angleDifference(a, b).toFloat()

    private data class AimContext(
        val distance: Float,
        val targetSpeed: Float,
        val playerSpeed: Float
    )

    private data class EnhancedAimContext(
        val distance: Float,
        val targetSpeed: Float,
        val playerSpeed: Float,
        val combatDuration: Long,
        val recentHitRate: Float,
        val threatLevel: Float
    )

    private class RotationTrajectory(
        val start: Rotation,
        val points: List<Rotation>,
        val context: AimContext?,
        val score: Float,
        val creationTime: Long = System.currentTimeMillis(),
        var lastUsedTime: Long = System.currentTimeMillis()
    ) {
        override fun toString(): String =
            "RotationTrajectory(start=$start, points=$points, score=$score, age=${lastUsedTime - creationTime})"
    }

    private class TrajectoryDatabase {

        private val trajectories = ArrayList<RotationTrajectory>()
        private val rng = java.util.Random()

        fun count(): Int = trajectories.size

        fun findSimilar(current: Rotation, count: Int, context: AimContext?): List<RotationTrajectory> {
            if (trajectories.isEmpty()) {
                return emptyList()
            }

            return trajectories.sortedBy { similarity(it, current, context) }.take(count)
        }

        private fun similarity(trajectory: RotationTrajectory, current: Rotation, context: AimContext?): Float {
            var score = abs(trajectory.start.yaw - current.yaw) + abs(trajectory.start.pitch - current.pitch)

            if (context != null) {
                val trajectoryContext = trajectory.context
                score += abs(trajectoryContext?.distance?.minus(context.distance) ?: 0f) * 0.1f
                score += abs(trajectoryContext?.targetSpeed?.minus(context.targetSpeed) ?: 0f) * 0.2f
            }

            return score
        }

        fun addTrajectory(trajectory: RotationTrajectory, maxMemory: Int) {
            if (trajectories.size > maxMemory) {
                trajectories.removeAt(rng.nextInt(trajectories.size))
            }

            cleanupOldTrajectories(maxMemory)
            trajectories.add(trajectory)
        }

        private fun cleanupOldTrajectories(maxMemory: Int) {
            if (trajectories.size <= maxMemory) {
                return
            }

            val now = System.currentTimeMillis()
            trajectories.sortWith(compareBy { (now - it.lastUsedTime).toFloat() * (1f - it.score) })

            val toRemove = trajectories.size - maxMemory
            repeat(toRemove) {
                trajectories.removeAt(0)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Disturbance (b6's `randomizations/MiniDisturbanceSystem`)
    // ---------------------------------------------------------------------

    private class MiniDisturbanceSystem {

        private var tickCounter = 0
        private var yawCore = DisturbanceCore(0.1f, 0.3f, 30)
        private var pitchCore = DisturbanceCore(0.1f, 0.3f, 30)

        fun applyDisturbanceToRotation(
            currentRotation: Rotation,
            lastRotations: Array<Rotation>,
            size: Int,
            lowRate: Float,
            highRate: Float,
            interval: Int
        ): Rotation {
            ensureInitialized(size, lowRate, highRate)

            val player = MinecraftClient.getInstance().player
            val playerMoving = player?.moving ?: false
            val enemyMoving = calculateEnemyMoving()

            val disturbedRotation = Rotation(currentRotation.yaw, currentRotation.pitch)

            tickCounter++
            if (tickCounter >= interval) {
                tickCounter = 0
                executeSmartDisturbance(lastRotations)
            }

            applyDisturbanceOffsets(disturbedRotation, lastRotations, playerMoving, enemyMoving)
            return disturbedRotation
        }

        private fun ensureInitialized(size: Int, lowRate: Float, highRate: Float) {
            if (yawCore.size != size) {
                yawCore = DisturbanceCore(lowRate, highRate, size)
                pitchCore = DisturbanceCore(lowRate, highRate, size)
                return
            }

            yawCore.updateRates(lowRate, highRate)
            pitchCore.updateRates(lowRate, highRate)
        }

        private fun calculateEnemyMoving(): Boolean {
            // targetEntity() lives on the outer class; this nested class is no longer
            // inner, so the lookup is inlined here.
            val target = (MinecraftClient.getInstance().crosshairTarget as? EntityHitResult)?.entity
                ?: return false
            return sqrt(target.velocity.x * target.velocity.x + target.velocity.z * target.velocity.z) > 0.01
        }

        private fun executeSmartDisturbance(lastRotations: Array<Rotation>) {
            if (lastRotations.size < 2) {
                return
            }

            val lastYawSpeed = calculateAngleSpeed(lastRotations[0].yaw, lastRotations[1].yaw)
            val lastPitchSpeed = abs(lastRotations[0].pitch - lastRotations[1].pitch)
            yawCore.disturb(lastYawSpeed)
            pitchCore.disturb(lastPitchSpeed)
        }

        private fun applyDisturbanceOffsets(
            rotation: Rotation,
            lastRotations: Array<Rotation>,
            playerMoving: Boolean,
            enemyMoving: Boolean
        ) {
            if (lastRotations.isEmpty()) {
                return
            }

            val lastRotation = lastRotations[0]
            val yawDiff = abs(rotation.yaw - lastRotation.yaw)
            val pitchDiff = abs(rotation.pitch - lastRotation.pitch)

            rotation.yaw += yawCore.getDisturbance(yawDiff, playerMoving, enemyMoving)
            rotation.pitch += pitchCore.getDisturbance(pitchDiff, playerMoving, enemyMoving)
        }

        private fun calculateAngleSpeed(current: Float, last: Float): Float {
            var diff = current - last
            diff = (diff + 180f) % 360f - 180f
            return abs(diff)
        }

        private class DisturbanceCore(
            private var lowRate: Float,
            private var highRate: Float,
            val size: Int
        ) {

            private val dataArray = BooleanArray(size)

            fun updateRates(newLowRate: Float, newHighRate: Float) {
                lowRate = newLowRate
                highRate = newHighRate
            }

            fun disturb(speed: Float) {
                val at = (speed.toInt() % size).coerceIn(0, size - 1)

                var i = at
                while (i > -1 && Math.random() < lowRate) {
                    dataArray[i] = !dataArray[i]
                    i--
                }

                i = at
                while (i < size && Math.random() < highRate) {
                    dataArray[i] = !dataArray[i]
                    i++
                }
            }

            fun getDisturbance(angleDiff: Float, playerMoving: Boolean, enemyMoving: Boolean): Float {
                val at = (angleDiff.toInt() % size).coerceIn(0, size - 1)
                var result = 0.0

                var i = at
                while (i > -1) {
                    val weight = lowRate.toDouble().pow(at - i)
                    result += (if (dataArray[i]) 1.0 else 0.0) * weight
                    i--
                }

                i = at
                while (i < size) {
                    val weight = highRate.toDouble().pow(i - at)
                    result -= (if (dataArray[i]) 1.0 else 0.0) * weight
                    i++
                }

                val disturbance = result.toFloat()
                if (!playerMoving && !enemyMoving && disturbance != -1f) {
                    return disturbance / (disturbance + 1f)
                }

                return disturbance
            }
        }
    }

    // ---------------------------------------------------------------------
    // Settings, grouped the way b6's `/`-prefixed names group them
    // ---------------------------------------------------------------------

    private class RandomizationGroup : Configurable("General") {
        val pattern by enumChoice("RandomizationPattern", RandomizationPattern.NONE)
        val applyChance by floatRange("RandomizationApplyChance", 0.7f..1.0f, 0.0f..1.0f)

        val yawChance by floatRange("YawRandomizationChance", 0.8f..1.0f, 0.0f..1.0f)
        val yawRange by floatRange("YawRandomizationRange", 5.0f..10.0f, 0.0f..30.0f)
        val yawSpeedIncrease by intRange("YawSpeedIncreaseMultiplier", 50..120, 0..500, "%")

        val pitchChance by floatRange("PitchRandomizationChance", 0.8f..1.0f, 0.0f..1.0f)
        val pitchRange by floatRange("PitchRandomizationRange", 2.0f..5.0f, 0.0f..30.0f)

        val stopOnUserNotMoving by boolean("StopOnUserNotisMoving", false)
        val onlyWorkWhenCantAttack by boolean("OnlyWorkWhenCantAttackEntity", false)

        val disturbance by boolean("DisturbanceRandom", false)
        val disturbanceSize by intRange("Disturbance/Size", 20..30, 10..50)
        val disturbanceLowRate by floatRange("Disturbance/LowRate", 0.1f..0.3f, 0.0f..1.0f)
        val disturbanceHighRate by floatRange("Disturbance/HighRate", 0.1f..0.3f, 0.0f..1.0f)
        val disturbanceInterval by intRange("Disturbance/Interval", 3..5, 1..20)

        init {
            // b6 keeps these out of the menu unless a pattern is actually selected.
        }



    }

    private class ChaoticGroup : Configurable("Chaotic") {
        var system by enumChoice("System", RotationSystem.LORENZ)
        val intensity by floatRange("Intensity", 0.5f..1.5f, 0.1f..5.0f)
        val speed by floatRange("Speed", 0.01f..0.05f, 0.001f..0.2f)
        val coupling by floatRange("Coupling", 0.1f..0.3f, 0.0f..1.0f)
        val memory by boolean("Memory", true)
        val adaptive by boolean("Adaptive", true)

        val resetChance by floatRange("Random/ResetChance", 0.01f..0.05f, 0.0f..0.2f)
        val jumpIntensity by floatRange("Random/JumpIntensity", 0.5f..2.0f, 0.0f..5.0f)
        val noiseMix by floatRange("Random/NoiseMix", 0.1f..0.3f, 0.0f..0.5f)
        val paramRandomness by floatRange("Random/ParamRandomness", 0.1f..0.3f, 0.0f..1.0f)

        val antiMl by boolean("AntiML", true)
        val patternRandomization by floatRange("AntiML/PatternRandomization", 0.1f..0.3f, 0.0f..1.0f)
        val temporalNoise by floatRange("AntiML/TemporalNoise", 0.05f..0.15f, 0.0f..0.5f)
        val behaviorMimicry by boolean("AntiML/BehaviorMimicry", true)
        val humanLikeTransitions by boolean("AntiML/HumanLikeTransitions", true)

        val dynamicMixing by boolean("Advanced/DynamicMixing", true)
        val autoSwitch by floatRange("Advanced/AutoSwitch", 0.01f..0.05f, 0.0f..0.2f)
        val adaptiveWeights by boolean("Advanced/AdaptiveWeights", true)
        val paramEvolution by boolean("Advanced/ParamEvolution", true)
        val evolutionRate by floatRange("Advanced/EvolutionRate", 0.001f..0.01f, 0.0f..0.1f)


    }

    private class IntaveGroup : Configurable("AugustusXIntave") {
        val randomStrength by floatRange("RandomStrength", 1.0f..1.0f, 0.0f..10.0f)
        val mode by enumChoice("Mode", IntaveMode.BASIC)

    }

    private class HybridNoiseGroup : Configurable("HybridNoise") {
        val mean by floatRange("Basic/Mean", -0.5f..0.5f, -5.0f..5.0f)
        val sigma by floatRange("Basic/Sigma", 0.05f..0.15f, 0.0f..10.0f)

        val spectralDensity by floatRange("Spectral/Density", 0.5f..1.5f, 0.1f..3.0f)
        val frequencyBands by enumChoice("Spectral/Bands", FrequencyBand.FULL)
        val cutoffLow by floatRange("Spectral/CutoffLow", 0.2f..0.5f, 0.01f..2.0f)
        val cutoffHigh by floatRange("Spectral/CutoffHigh", 1.0f..2.0f, 0.1f..5.0f)
        val spectralSlope by floatRange("Spectral/Slope", 1.0f..3.0f, 0.5f..6.0f)
        val harmonicBoost by floatRange("Spectral/HarmonicBoost", 0.0f..0.3f, 0.0f..1.0f)

        val theta by floatRange("Process/Theta", 0.3f..0.7f, 0.0f..10.0f)
        val dt by floatRange("Process/DT", 0.01f..0.05f, 0.0f..5.0f)
        val globalScale by floatRange("Process/GlobalScale", 3.0f..8.0f, 0.0f..100.0f)

        val brownianToOuRatio by floatRange("Mix/BrownianToOURatio", 0.3f..0.7f, 0.0f..1.0f)
        val layerCount by intRange("Mix/LayerCount", 2..4, 1..15)

        val modulationType by enumChoice("Dynamic/ModulationType", ModulationType.SINE)
        val modulationAmplitude by floatRange("Dynamic/ModAmp", 0.1f..0.5f, 0.0f..2.0f)
        val modulationFrequency by floatRange("Dynamic/ModFreq", 0.5f..2.0f, 0.0f..10.0f)

        val yawPitchRatio by floatRange("Axis/YawPitchRatio", 1.5f..3.0f, 0.0f..10.0f)
        val coupling by floatRange("Axis/Coupling", 0.0f..0.5f, 0.0f..1.0f)

        val distanceFactorMin by floatRange("Distance/MinMultiplier", 0.5f..0.8f, 0.0f..2.0f)
        val distanceFactorMax by floatRange("Distance/MaxMultiplier", 1.2f..1.5f, 0.0f..3.0f)
        val distanceRangeMin by floatRange("Distance/RangeMin", 2.0f..4.0f, 0.0f..20.0f)
        val distanceRangeMax by floatRange("Distance/RangeMax", 8.0f..12.0f, 0.0f..50.0f)

        val jitterProbability by floatRange("Advanced/JitterProb", 0.05f..0.2f, 0.0f..1.0f)
        val jitterStrength by floatRange("Advanced/JitterStr", 1.0f..3.0f, 0.0f..180.0f)
        val microJitter by floatRange("MicroJitter", 0.1f..0.3f, 0.0f..5.0f)
        val smoothness by floatRange("Advanced/Smoothness", 0.7f..1.3f, 0.0f..5.0f)

    }

    private class AdvancedGroup : Configurable("Advanced") {
        val trajectoryCount by intRange("Trajectories", 4..6, 1..20)
        val noiseLevel by floatRange("NoiseLevel", 0.01f..0.1f, 0.0f..1.0f)
        val smoothness by floatRange("Smoothness", 0.7f..1.0f, 0.1f..4.0f)
        val contextAware by boolean("ContextAware", true)
        val trajectoryMemory by intRange("TrajectoryMemory", 1000..2000, 100..5000)
        val interpolation by enumChoice("Interpolation", Interpolation.CATMULL_ROM)
        val adaptiveSpeed by boolean("AdaptiveSpeed", true)

        val trueRandomStrength by floatRange("TrueRandomStrength", 3.0f..8.0f, 0.0f..30.0f)
        val trueRandomSmoothness by intRange("TrueRandomSmoothness", 1..3, 1..10)
        val noiseSpeed by floatRange("NoiseSpeed", 0.01f..0.05f, 0.0f..1.0f)
        val noiseScale by floatRange("NoiseScale", 3.0f..8.0f, 0.0f..60.0f)

    }

    private val octaves by intRange("PerlinOctaves", 3..4, 1..10)
    private val perlinPersistence by floatRange("PerlinPersistence", 0.7f..0.8f, 0.1f..2.0f)
    private val perlinScale by floatRange("PerlinScale", 2.0f..5.0f, 0.5f..30.0f)
    private val perlinYawScale by floatRange("Perlin/YawScale", 5.0f..8.0f, 0.1f..40.0f)
    private val perlinPitchScale by floatRange("Perlin/PitchScale", 3.0f..5.0f, 0.1f..30.0f)

    /**
     * The settings groups are built last: their visibility gates read this
     * processor's own pattern, which only exists once the declarations above
     * have run. [settingsReady] keeps such a gate from materialising a group
     * before the instance it needs is complete.
     */
    private var settingsReady = false
    private val widgetGroup by lazy { RandomizationGroup() }
    private val chaoticGroup by lazy { ChaoticGroup() }
    private val intaveGroup by lazy { IntaveGroup() }
    private val hybridGroup by lazy { HybridNoiseGroup() }
    private val advancedGroup by lazy { AdvancedGroup() }

    @Suppress("LongMethod")
    private fun setupSettings() {
        tree(widgetGroup)
        tree(chaoticGroup)
        tree(intaveGroup)
        tree(hybridGroup)
        tree(advancedGroup)


        settingsReady = true
    }

    init {
        setupSettings()
        loadDefaultTrajectories()
    }

    enum class RandomizationPattern(override val choiceName: String) : NamedChoice {
        NONE("None"),
        ZIG_ZAG("Zig-Zag"),
        LAZY_FLICK("LazyFlick"),
        NOISE("Noise"),
        TRUE_RANDOM("TrueRandom"),
        PERLIN("Perlin"),
        ADVANCED("Advanced"),
        HYBRID_NOISE("HybridNoise"),
        AUGUSTUS_X_INTAVE("AugustusXIntave"),
        CHAOTIC("Chaotic")
    }

    enum class RotationSystem(override val choiceName: String) : NamedChoice {
        LORENZ("Lorenz"),
        ROSSLER("Rossler"),
        HENON("Henon"),
        DOUBLE_PENDULUM("DoublePendulum"),
        MULTI_CHAOTIC("MultiChaotic"),
        SWITCH("Switch")
    }

    enum class IntaveMode(override val choiceName: String) : NamedChoice {
        BASIC("Basic"),
        ONLY_ROTATION("OnlyRotation"),
        DOUBLED("Doubled")
    }

    enum class FrequencyBand(override val choiceName: String) : NamedChoice {
        FULL("Full"),
        LOW_PASS("LowPass"),
        HIGH_PASS("HighPass"),
        BAND_PASS("BandPass"),
        NOTCH("Notch")
    }

    enum class ModulationType(override val choiceName: String) : NamedChoice {
        SINE("Sine"),
        SAWTOOTH("Sawtooth"),
        SQUARE("Square"),
        RANDOM("Random")
    }

    enum class Interpolation(override val choiceName: String) : NamedChoice {
        HERMITE("Hermite"),
        CATMULL_ROM("CatmullRom")
    }

}
