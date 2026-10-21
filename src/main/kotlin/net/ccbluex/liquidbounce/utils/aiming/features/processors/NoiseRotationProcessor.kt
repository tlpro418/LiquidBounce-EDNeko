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
 * Noise-based rotation humanisation.
 *
 * Derived from the *functioning* subset of EDNeko's rotation code
 * (github.com/RouQingNeko1024/EDNeko, GPL-3.0).
 *
 * EDNeko exposes a 3,750-entry "NoisePreset" dropdown. Measured against the
 * shipped jar, only 96 entries resolve to an implementation
 * (`NoisePresets.getPreset`) and the remaining 3,654 fall through to a single
 * `default` branch - the names are harvested from unrelated domains (biomes,
 * succulents, rock types, ML optimisers, RL environments, LLM names) and
 * mechanically duplicated as V2..V5. Of the 96, only a few are real algorithms;
 * several well-named ones are not (its "WaveletNoise" is four sine/cosine
 * harmonics with wavelet names attached, and its "XGBoostAim" trains nothing).
 *
 * Only genuine algorithms are reproduced:
 *
 *  - [NoiseMode.FRACTAL] multi-octave fBm: per octave the amplitude is scaled by
 *    [gain] and the frequency by [lacunarity], then the sum is normalised - the
 *    same construction as EDNeko's `fractalNoisePreset`.
 *  - [NoiseMode.DRIFT] an Ornstein-Uhlenbeck mean-reverting process.
 *  - [NoiseMode.HUMAN] reaction lag, occasional overshoot, micro-correction and
 *    a fatigue term, following EDNeko's `humanSimPreset` idea.
 *  - [NoiseMode.HYBRID] EDNeko's `HybridNoise.getHybridNoiseOffset`: several
 *    layers of Brownian motion mixed with an OU process at weight `1/(layer+1)`,
 *    distance-dependent scaling, then exponential smoothing so the offset stays
 *    temporally coherent instead of jumping every tick.
 *  - [NoiseMode.ENTROPY] EDNeko's `EntropyRotation` control loop: measure how
 *    random the recent output already is (coefficient of variation plus angular
 *    acceleration) and steer it into a target band - damp it when too random,
 *    add controlled jitter when too smooth.
 *
 * Deliberately not reproduced from `HybridNoise`, as they are arbitrary rather
 * than meaningful: `applySpectralProcessing`, the yaw/pitch `axisCoupling`
 * branch, the `scale > 5 ? scale * 1.2 : scale * 0.8` discontinuity, and the
 * waveform dynamic modulation. Its distance range endpoints are also read from
 * the setting once per tick here, rather than being re-randomised on every call.
 *
 * The magnitude tiers are EDNeko's own Vanilla / Legit / SemiBlatant / Blatant
 * presets.
 * ---------------------------------------------------------------------------
 */
package net.ccbluex.liquidbounce.utils.aiming.features.processors

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.config.types.nesting.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.minecraft.client.MinecraftClient
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Suppress("MagicNumber", "TooManyFunctions")
class NoiseRotationProcessor(owner: EventListener? = null)
    : ToggleableConfigurable(owner, "Noise", false), RotationProcessor {

    private val mode by enumChoice("Mode", NoiseMode.FRACTAL)
    private val intensity by enumChoice("Intensity", Intensity.SEMI_BLATANT)

    /** Multiplier applied to the pitch component relative to yaw. */
    private val pitchRatio by float("PitchRatio", 0.55f, 0.1f..1.0f)

    // Fractal (fBm) parameters
    private val octaves by int("Octaves", 6, 1..8)
    private val lacunarity by float("Lacunarity", 2.0f, 1.1f..4.0f)
    private val gain by float("Gain", 0.5f, 0.1f..0.9f)
    private val frequency by float("Frequency", 0.05f, 0.001f..0.5f)

    // Ornstein-Uhlenbeck parameters (Drift mode)
    private val theta by float("Theta", 0.2f, 0.01f..1.0f)
    private val sigma by float("Sigma", 0.35f, 0.01f..1.0f)

    // Human parameters
    private val overshootChance by int("OvershootChance", 5, 0..100, "%")
    private val microCorrection by float("MicroCorrection", 0.15f, 0.0f..1.0f)
    private val reactionSpeed by float("ReactionSpeed", 0.01f, 0.001f..0.2f)
    private val fatigueRate by float("FatigueRate", 0.001f, 0.0f..0.05f)

    // Hybrid parameters
    private val hybridLayers by intRange("HybridLayers", 1..4, 1..8)
    private val hybridMix by floatRange("HybridMixRatio", 0.3f..0.7f, 0.001f..0.999f)
    private val hybridMean by floatRange("HybridMean", 0f..0f, -1f..1f)
    private val hybridSigma by floatRange("HybridSigma", 0.2f..0.5f, 0.01f..2f)
    private val hybridTheta by floatRange("HybridTheta", 0.1f..0.4f, 0.01f..1f)
    private val hybridDt by floatRange("HybridDt", 0.05f..0.2f, 0.001f..0.5f)
    private val hybridSmoothness by floatRange("HybridSmoothness", 5f..15f, 1f..50f)
    private val hybridMicroJitter by floatRange("HybridMicroJitter", 0f..0.3f, 0f..2f)

    /** Distance band over which the noise is scaled, and its min/max multipliers. */
    private val distanceRange by floatRange("DistanceRange", 1f..6f, 0f..32f)
    private val distanceFactor by floatRange("DistanceFactor", 0.5f..1.5f, 0f..3f)

    // Entropy controller parameters
    private val entropyTarget by float("EntropyTarget", 0.5f, 0f..1f)
    private val entropyTolerance by float("EntropyTolerance", 0.15f, 0.01f..0.5f)
    private val entropyAdjustmentSpeed by float("EntropyAdjustmentSpeed", 1f, 0.1f..3f)
    private val entropySmoothing by float("EntropySmoothing", 0.5f, 0f..1f)
    private val entropyRandomness by float("EntropyRandomness", 0.5f, 0f..1f)

    private var tick = 0
    private var ouState = 0.0f
    private var fatigue = 0.0f

    /** Per-axis persistent state for [NoiseMode.HYBRID]; index 0 is yaw, 1 is pitch. */
    private val hybridState = floatArrayOf(0f, 0f)
    private var aimStartTime = System.currentTimeMillis()

    /**
     * Rolling window of emitted offsets in degrees, for the entropy controller.
     * Degrees (not the normalised value) are stored so that the direction-entropy
     * divisor carries the same meaning as it does in the original.
     */
    private val entropyHistory = ArrayDeque<Float>()
    private var smoothedEntropy = 0.5f

    private companion object {
        /** How many recent offsets the entropy controller reasons over. */
        const val ENTROPY_WINDOW = 20

        /** Per-tick weight of the entropy moving average. */
        const val ENTROPY_SMOOTHING_RATE = 0.1f
    }

    override fun process(
        rotationTarget: RotationTarget,
        currentRotation: Rotation,
        targetRotation: Rotation
    ): Rotation {
        if (!this.running) {
            return targetRotation
        }

        tick++

        val distance = distanceToTarget(rotationTarget)

        // `Intensity.multiplier` is EDNeko's own amplitude multiplier and its
        // uniform source ranged over +-(multiplier / 2), so normalised noise in
        // [-1, 1] is scaled by half the multiplier to match it.
        //
        // The `Legit` preset is the exception: it ships absolute degrees, so it deliberately bypasses
        // both the intensity multiplier and the pitch ratio.
        val isLegit = mode == NoiseMode.LEGIT
        val scale = if (isLegit) 1f else intensity.multiplier / 2f
        val ratio = if (isLegit) 1f else pitchRatio

        val (yawNoise, pitchNoise) = when (mode) {
            NoiseMode.FRACTAL -> fractal().let { it to it }
            NoiseMode.DRIFT -> drift().let { it to it }
            NoiseMode.HUMAN -> human().let { it to it }
            NoiseMode.HYBRID -> hybridAxis(0, distance) to hybridAxis(1, distance)
            NoiseMode.ENTROPY -> entropyNoise(scale).let { it to it }
            NoiseMode.LEGIT -> legitNoise()
        }

        return Rotation(
            targetRotation.yaw + yawNoise * scale,
            (targetRotation.pitch + pitchNoise * scale * ratio).coerceIn(-90f, 90f)
        )
    }

    /**
     * Faithful port of EDNeko's `NoisePresets` `Legit` preset.
     *
     * Uniform jitter of +-0.15 deg on the yaw and +-0.075 deg on the pitch, plus a slow +-0.05 deg sine
     * drift on the yaw whose period is about 63 ticks.
     */
    private fun legitNoise(): Pair<Float, Float> {
        val yawJitter = ((0f..1f).random() - 0.5f) * 0.3f
        val pitchJitter = ((0f..1f).random() - 0.5f) * 0.15f
        val microAdjustment = sin(tick * 0.1f) * 0.05f

        return (yawJitter + microAdjustment) to pitchJitter
    }

    /** Multi-octave fractal noise, normalised back into roughly [-1, 1]. */
    private fun fractal(): Float {
        var sum = 0.0f
        var amplitude = 1.0f
        var octaveFrequency = 1.0f
        var totalAmplitude = 0.0f

        for (octave in 0 until octaves) {
            // The per-octave phase offset keeps the octaves from lining up.
            sum += sin(tick * frequency * octaveFrequency + octave * 1.7f) * amplitude
            totalAmplitude += amplitude
            amplitude *= gain
            octaveFrequency *= lacunarity
        }

        return if (totalAmplitude > 0f) sum / totalAmplitude else 0f
    }

    /**
     * Ornstein-Uhlenbeck process: `x += theta * (0 - x) + sigma * noise`.
     *
     * The mean-reversion term pulls the offset back to zero, so the aim drifts
     * rather than wandering off.
     */
    private fun drift(): Float {
        ouState += theta * (0f - ouState) + sigma * (-1f..1f).random() * 0.5f
        ouState = ouState.coerceIn(-1f, 1f)
        return ouState
    }

    /** Reaction lag, occasional overshoot, micro-correction and fatigue. */
    private fun human(): Float {
        fatigue = (fatigue + fatigueRate).coerceAtMost(1f)

        val reaction = sin(tick * reactionSpeed) * 0.5f
        val overshoot = if (overshootChance > (0..100).random()) {
            (-1f..1f).random() * 0.4f
        } else {
            0f
        }
        val correction = (-1f..1f).random() * microCorrection

        // Fatigue damps the jitter the longer the aura stays engaged.
        return (reaction + overshoot + correction) * (1f - fatigue)
    }

    /**
     * One axis of EDNeko's hybrid noise.
     *
     * Layers are accumulated with weight `1 / (layer + 1)`, each mixing Brownian
     * motion with an OU step. The result is scaled by how far the target is, then
     * run through an exponential moving average so consecutive ticks stay
     * correlated - without that the aim would just be white noise.
     */
    private fun hybridAxis(axis: Int, distance: Float): Float {
        val fatigueFactor = fatigueFactor()

        var combined = 0f
        val layers = hybridLayers.random()
        for (layer in 0 until layers) {
            val weight = 1f / (layer + 1).toFloat()
            val mixRatio = hybridMix.random().coerceIn(0.001f, 0.999f)

            val brownian = brownianMotion(
                hybridMean.random() * fatigueFactor,
                hybridSigma.random() * (layer + 1),
                hybridDt.random()
            ) * (1f - mixRatio)

            val ou = ouProcessNoise(
                // The first layer continues this axis' own state, deeper layers
                // continue the running sum, as the original does.
                if (layer == 0) hybridState[axis] else combined,
                hybridMean.random() * fatigueFactor,
                hybridTheta.random(),
                hybridSigma.random(),
                hybridDt.random()
            ) * mixRatio

            combined += (brownian + ou) * weight
        }

        combined *= fatigueFactor
        combined *= distanceScale(distance)
        combined += gaussianRandom() * hybridMicroJitter.random()

        val smoothFactor = (1f / (hybridSmoothness.random() * fatigueFactor)).coerceIn(0.05f, 0.95f)
        hybridState[axis] = lerp(hybridState[axis], combined, smoothFactor)

        // The original clamps the offset to +-30 degrees.
        return hybridState[axis].coerceIn(-30f, 30f)
    }

    /** Longer engagements get noisier, matching the original's 5s/10s steps. */
    private fun fatigueFactor(): Float {
        val elapsed = System.currentTimeMillis() - aimStartTime
        return when {
            elapsed > 10_000L -> 1.8f
            elapsed > 5_000L -> 1.4f
            else -> 1.0f
        }
    }

    /**
     * Piecewise-linear scaling of the noise against distance to the target:
     * [distanceFactor]`.start` at [distanceRange]`.start`, `.endInclusive` at
     * `.endInclusive`.
     */
    private fun distanceScale(distance: Float): Float {
        if (distance <= 0f) {
            return 1f
        }
        val minDistance = distanceRange.start
        val maxDistance = distanceRange.endInclusive
        val minMultiplier = distanceFactor.start
        val maxMultiplier = distanceFactor.endInclusive

        return when {
            distance <= minDistance -> minMultiplier
            distance >= maxDistance -> maxMultiplier
            else -> lerp(minMultiplier, maxMultiplier, (distance - minDistance) / (maxDistance - minDistance))
        }
    }

    private fun distanceToTarget(rotationTarget: RotationTarget): Float {
        val entity = rotationTarget.entity ?: return 0f
        val player = MinecraftClient.getInstance().player ?: return 0f
        return player.distanceTo(entity)
    }

    /** Brownian step: a Box-Muller normal scaled by `sqrt(dt)`, offset by `mean`. */
    private fun brownianMotion(mean: Float, sigma: Float, dt: Float): Float {
        val u = (0f..1f).random().coerceAtLeast(1e-6f)
        val z = sqrt(dt.toDouble()) * sqrt(-2.0 * ln(u.toDouble())) * cos(2.0 * PI * (0f..1f).random())
        return (z * sigma + mean).toFloat()
    }

    /** Euler-Maruyama step of an OU process. */
    private fun ouProcessNoise(current: Float, mean: Float, theta: Float, sigma: Float, dt: Float): Float {
        val drift = theta * (mean - current) * dt
        val diffusion = sigma * sqrt(dt.toDouble()).toFloat() * gaussianRandom()
        return drift + diffusion
    }

    /** Standard normal via Box-Muller. */
    private fun gaussianRandom(): Float {
        val u1 = (0f..1f).random().coerceAtLeast(1e-6f)
        val u2 = (0f..1f).random()
        return (sqrt(-2.0 * ln(u1.toDouble())) * cos(2.0 * PI * u2)).toFloat()
    }

    private fun lerp(from: Float, to: Float, delta: Float): Float = from + (to - from) * delta

    /**
     * Self-regulating noise, from EDNeko's `EntropyRotation`.
     *
     * The controller measures how random the recent output already is and steers
     * it into a target band: too random and the wander is damped, too smooth and
     * controlled jitter is added. Both measures come from the original:
     *
     *  - velocity entropy - the coefficient of variation of successive offsets,
     *    i.e. how uneven the movement is;
     *  - direction entropy - the mean absolute change in velocity (angular
     *    acceleration) relative to 15 degrees.
     *
     * `|deviation| / tolerance` is raised to the power 1.5 and scaled by the
     * adjustment speed, matching the original's response curve.
     *
     * The original kept a 60-sample history refreshed every 50 ms and tapped
     * `RotationUtils` for the recent rotations; here the window is
     * [ENTROPY_WINDOW] of this processor's own emitted offsets, sampled once per
     * tick, so the controller is self-contained.
     */
    private fun entropyNoise(scale: Float): Float {
        val base = drift()

        entropyHistory.addLast(base * scale)
        while (entropyHistory.size > ENTROPY_WINDOW) {
            entropyHistory.removeFirst()
        }

        smoothedEntropy = lerp(smoothedEntropy, currentEntropy(), ENTROPY_SMOOTHING_RATE)

        val tolerance = entropyTolerance.coerceAtLeast(0.001f)
        val deviation = smoothedEntropy - entropyTarget
        if (abs(deviation) <= tolerance) {
            return base
        }

        val strength = ((abs(deviation) / tolerance).pow(1.5f) * entropyAdjustmentSpeed).coerceIn(0f, 1f)

        return if (deviation > 0f) {
            // Too random: damp the wander.
            base * (1f - strength * entropySmoothing * 0.3f)
        } else {
            // Too smooth: add controlled jitter.
            val randomness = strength * entropyRandomness
            base * (1f + randomness * 0.2f) + (-1f..1f).random() * randomness * 0.5f
        }
    }

    private fun currentEntropy(): Float {
        if (entropyHistory.size < 3) {
            return 0.5f
        }
        return velocityEntropy() * 0.6f + directionEntropy() * 0.4f
    }

    /** Coefficient of variation of successive offsets. */
    private fun velocityEntropy(): Float {
        val samples = entropyHistory.toList()
        val velocities = (1 until samples.size).map { samples[it] - samples[it - 1] }
        if (velocities.isEmpty()) {
            return 0.5f
        }
        // The original divides by the signed mean and bails out when it is zero;
        // the magnitude keeps the measure defined when offsets average out.
        val mean = velocities.average().toFloat()
        if (mean == 0f) {
            return 0.5f
        }
        val variance = velocities.map { (it - mean) * (it - mean) }.average().toFloat()
        return (sqrt(variance.toDouble()).toFloat() / abs(mean)).coerceIn(0f, 1f)
    }

    /** Mean absolute change in velocity, relative to the original's 15 degrees. */
    private fun directionEntropy(): Float {
        val samples = entropyHistory.toList()
        if (samples.size < 3) {
            return 0.5f
        }
        val changes = (2 until samples.size).map {
            abs((samples[it - 1] - samples[it - 2]) - (samples[it] - samples[it - 1]))
        }
        if (changes.isEmpty()) {
            return 0.5f
        }
        return (changes.average().toFloat() / 15f).coerceIn(0f, 1f)
    }

    enum class NoiseMode(override val choiceName: String) : NamedChoice {
        FRACTAL("Fractal"),
        DRIFT("Drift"),
        HUMAN("Human"),
        HYBRID("Hybrid"),
        ENTROPY("Entropy"),

        /**
         * EDNeko's `Legit` noise preset. Amplitudes are absolute degrees, so [Intensity] and
         * `PitchRatio` do not apply to it.
         */
        LEGIT("Legit")
    }

    /**
     * Magnitudes taken from EDNeko's `Vanilla / Legit / SemiBlatant / Blatant`
     * presets, which multiplied a uniform source by these values.
     */
    enum class Intensity(override val choiceName: String, val multiplier: Float) : NamedChoice {
        VANILLA("Vanilla", 0.05f),
        LEGIT("Legit", 0.30f),
        SEMI_BLATANT("SemiBlatant", 1.50f),
        BLATANT("Blatant", 5.00f)
    }

}
