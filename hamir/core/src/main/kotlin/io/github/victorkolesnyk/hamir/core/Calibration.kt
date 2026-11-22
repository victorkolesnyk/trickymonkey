package io.github.victorkolesnyk.hamir.core

import kotlin.math.abs
import kotlin.math.max

enum class CalibrationFailure {
    NEED_MORE_VOLUME,
    MIC_CLIPPING,
    NOISE_NOT_AUDIBLE,
    NOT_CONVERGING,

    /** The noise is too loud even at the minimum digital gain: the media volume has to come down. */
    NEED_LESS_VOLUME,
}

sealed class CalibrationStep {
    /** Play again with [gainDb] and measure once more. */
    data class Adjust(val gainDb: Double) : CalibrationStep()
    data class Done(val gainDb: Double, val achievedSnrDb: Double) : CalibrationStep()
    data class Fail(val reason: CalibrationFailure) : CalibrationStep()
}

/**
 * Iterative search for the digital gain that puts the noise [targetSnrDb] relative to speech
 * at the microphone. SNR = speech - noise, so targetSnrDb = -5 means noise 5 dB above speech.
 *
 * The solver counts the measurements it has been fed; use a fresh instance (or [reset]) for
 * every calibration run. Not thread-safe.
 */
class CalibrationSolver(
    val targetSnrDb: Double,
    val speechDbfs: Double,
    val ambientDbfs: Double,
    val minGainDb: Double = -40.0,
    val maxGainDb: Double = 12.0,
    val toleranceDb: Double = 1.0,
    val maxIterations: Int = 8,
) {
    init {
        require(targetSnrDb.isFinite() && speechDbfs.isFinite() && ambientDbfs.isFinite()) { "levels must be finite" }
        require(minGainDb <= maxGainDb) { "minGainDb must not exceed maxGainDb" }
        require(toleranceDb >= 0.0) { "toleranceDb must not be negative" }
    }

    val targetNoiseDbfs: Double get() = speechDbfs - targetSnrDb

    /** Number of measurements consumed by [next] so far. */
    var iterations: Int = 0
        private set

    /** Forgets the iteration count so the instance can drive another calibration run. */
    fun reset() {
        iterations = 0
    }

    /**
     * Feed one measurement taken while the noise played at [currentGainDb].
     *
     * Decision order:
     * 1. [currentGainDb] outside minGainDb..maxGainDb -> the measurement is not usable, Adjust to the
     *    nearest limit (or Fail(NOT_CONVERGING) if this was measurement number maxIterations).
     *    A Done therefore never carries a gain outside the range;
     * 2. peak above [MIC_CLIP_PEAK_DBFS] -> Fail(MIC_CLIPPING): the measurement cannot be trusted;
     * 3. the target noise level lies so close to the ambient level that no measurement could be
     *    both within tolerance of it and [AUDIBLE_MARGIN_DB] above ambient -> Fail(NOISE_NOT_AUDIBLE);
     * 4. |measured - target| <= tolerance and the noise is at least [AUDIBLE_MARGIN_DB] above
     *    ambient -> Done with the current gain. A reading that is on target only because the
     *    ambient noise alone reaches the target is not a result;
     * 5. already at maxGainDb and the noise is less than [AUDIBLE_MARGIN_DB] above ambient -> Fail(NOISE_NOT_AUDIBLE);
     * 6. the required gain current + (target - measured) is above maxGainDb -> Fail(NEED_MORE_VOLUME),
     *    with two exceptions that return Adjust(maxGainDb) instead: the noise is not yet
     *    distinguishable from ambient (the estimate is meaningless, so audibility is tested at
     *    full gain first), or the shortfall at maxGainDb would be within tolerance;
     * 7. already at minGainDb and still too loud -> Fail(NEED_LESS_VOLUME);
     * 8. this was measurement number maxIterations, or the gain would not move -> Fail(NOT_CONVERGING);
     * 9. otherwise Adjust(required gain limited to minGainDb..maxGainDb).
     *
     * In steps 6 and 9 "target" is the target noise level, or ambient + [AUDIBLE_MARGIN_DB] when
     * that is higher (possible only when the two are within tolerance of each other).
     */
    fun next(currentGainDb: Double, measuredNoiseDbfs: Double, measuredPeakDbfs: Double): CalibrationStep {
        require(!currentGainDb.isNaN() && !measuredNoiseDbfs.isNaN() && !measuredPeakDbfs.isNaN()) {
            "measurements must not be NaN"
        }
        iterations++
        if (currentGainDb < minGainDb - GAIN_EPSILON_DB || currentGainDb > maxGainDb + GAIN_EPSILON_DB) {
            if (iterations >= maxIterations) return CalibrationStep.Fail(CalibrationFailure.NOT_CONVERGING)
            return CalibrationStep.Adjust(currentGainDb.coerceIn(minGainDb, maxGainDb))
        }
        if (measuredPeakDbfs > MIC_CLIP_PEAK_DBFS) return CalibrationStep.Fail(CalibrationFailure.MIC_CLIPPING)

        val audibleLevel = ambientDbfs + AUDIBLE_MARGIN_DB
        if (targetNoiseDbfs + toleranceDb < audibleLevel) return CalibrationStep.Fail(CalibrationFailure.NOISE_NOT_AUDIBLE)

        val audible = measuredNoiseDbfs >= audibleLevel
        if (audible && abs(targetNoiseDbfs - measuredNoiseDbfs) <= toleranceDb) {
            return CalibrationStep.Done(currentGainDb.coerceIn(minGainDb, maxGainDb), speechDbfs - measuredNoiseDbfs)
        }

        val atMaxGain = currentGainDb >= maxGainDb - GAIN_EPSILON_DB
        if (atMaxGain && !audible) return CalibrationStep.Fail(CalibrationFailure.NOISE_NOT_AUDIBLE)

        val error = max(targetNoiseDbfs, audibleLevel) - measuredNoiseDbfs
        val required = currentGainDb + error
        var nextGain = required.coerceIn(minGainDb, maxGainDb)
        if (required > maxGainDb) {
            val worthTryingMax = !atMaxGain && (!audible || required - maxGainDb <= toleranceDb)
            if (!worthTryingMax) return CalibrationStep.Fail(CalibrationFailure.NEED_MORE_VOLUME)
            nextGain = maxGainDb
        }
        val atMinGain = currentGainDb <= minGainDb + GAIN_EPSILON_DB
        if (atMinGain && required < minGainDb) return CalibrationStep.Fail(CalibrationFailure.NEED_LESS_VOLUME)

        if (iterations >= maxIterations) return CalibrationStep.Fail(CalibrationFailure.NOT_CONVERGING)
        if (abs(nextGain - currentGainDb) < GAIN_EPSILON_DB) return CalibrationStep.Fail(CalibrationFailure.NOT_CONVERGING)
        return CalibrationStep.Adjust(nextGain)
    }

    companion object {
        /** A measurement whose peak is above this level is treated as clipped. */
        const val MIC_CLIP_PEAK_DBFS = -1.0

        /** The noise must exceed the ambient level by this much to count as audible. */
        const val AUDIBLE_MARGIN_DB = 3.0

        private const val GAIN_EPSILON_DB = 1e-6
    }
}
