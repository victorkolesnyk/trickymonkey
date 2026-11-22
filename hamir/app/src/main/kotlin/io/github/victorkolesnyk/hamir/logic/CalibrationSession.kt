package io.github.victorkolesnyk.hamir.logic

import io.github.victorkolesnyk.hamir.core.CalibrationFailure
import io.github.victorkolesnyk.hamir.core.CalibrationSolver
import io.github.victorkolesnyk.hamir.core.CalibrationStep

/** One solver step; lets tests replace the core solver. */
fun interface NoiseSolver {
    fun next(currentGainDb: Double, measuredNoiseDbfs: Double, measuredPeakDbfs: Double): CalibrationStep
}

data class CalibrationOutcome(
    val gainDb: Double,
    val speechDbfs: Double,
    val ambientDbfs: Double,
    val noiseDbfs: Double,
    val achievedSnrDb: Double,
)

sealed class CalibrationPhase {
    /** Step 1: measure 2 s of silence. */
    object Ambient : CalibrationPhase()

    /**
     * Step 2: measure 8 s of speech.
     * @param rejectedMarginDb when the previous attempt was too close to ambient: by how many dB speech exceeded it
     */
    data class Speech(val attempt: Int, val rejectedMarginDb: Double?) : CalibrationPhase()

    /** Step 3: play the noise at [gainDb] and measure. [iteration] starts at 1. */
    data class Noise(val gainDb: Double, val iteration: Int, val lastMeasuredDbfs: Double?) : CalibrationPhase()

    data class Done(val outcome: CalibrationOutcome) : CalibrationPhase()
    data class Failed(val reason: CalibrationFailure) : CalibrationPhase()
}

/**
 * State machine of the calibration wizard (SPEC section 7). Holds no audio; the caller measures
 * and feeds levels in. Not thread-safe: drive it from one thread.
 */
class CalibrationSession(
    val targetSnrDb: Double,
    private val startGainDb: Double = DEFAULT_START_GAIN_DB,
    private val solverFactory: (speechDbfs: Double, ambientDbfs: Double) -> NoiseSolver = { speech, ambient ->
        val solver = CalibrationSolver(targetSnrDb, speech, ambient)
        NoiseSolver { gain, noise, peak -> solver.next(gain, noise, peak) }
    },
) {
    var phase: CalibrationPhase = CalibrationPhase.Ambient
        private set

    var ambientDbfs: Double = Double.NaN
        private set

    var speechDbfs: Double = Double.NaN
        private set

    private var solver: NoiseSolver? = null

    /** Level the noise has to reach at the microphone; NaN before speech was accepted. */
    val targetNoiseDbfs: Double get() = speechDbfs - targetSnrDb

    fun submitAmbient(levelDbfs: Double): CalibrationPhase {
        check(phase is CalibrationPhase.Ambient) { "not in the ambient phase" }
        ambientDbfs = levelDbfs
        phase = CalibrationPhase.Speech(attempt = 1, rejectedMarginDb = null)
        return phase
    }

    /** Speech must be at least [MIN_SPEECH_MARGIN_DB] above ambient, otherwise the step repeats. */
    fun submitSpeech(activeSpeechDbfs: Double): CalibrationPhase {
        val current = phase as? CalibrationPhase.Speech ?: error("not in the speech phase")
        val margin = activeSpeechDbfs - ambientDbfs
        if (activeSpeechDbfs.isNaN() || margin < MIN_SPEECH_MARGIN_DB) {
            phase = CalibrationPhase.Speech(current.attempt + 1, if (margin.isNaN()) 0.0 else margin)
            return phase
        }
        speechDbfs = activeSpeechDbfs
        startNoise()
        return phase
    }

    fun submitNoise(measuredDbfs: Double, peakDbfs: Double): CalibrationPhase {
        val current = phase as? CalibrationPhase.Noise ?: error("not in the noise phase")
        val activeSolver = solver ?: error("no solver")
        if (current.iteration > HARD_ITERATION_LIMIT) {
            phase = CalibrationPhase.Failed(CalibrationFailure.NOT_CONVERGING)
            return phase
        }
        phase = when (val step = activeSolver.next(current.gainDb, measuredDbfs, peakDbfs)) {
            is CalibrationStep.Adjust -> CalibrationPhase.Noise(step.gainDb, current.iteration + 1, measuredDbfs)
            is CalibrationStep.Done -> CalibrationPhase.Done(
                CalibrationOutcome(step.gainDb, speechDbfs, ambientDbfs, measuredDbfs, step.achievedSnrDb)
            )
            is CalibrationStep.Fail -> CalibrationPhase.Failed(step.reason)
        }
        return phase
    }

    /** After a failure (or to redo step 3): keep ambient and speech, start the gain search again. */
    fun restartNoise(): CalibrationPhase {
        check(!speechDbfs.isNaN()) { "speech has not been measured" }
        startNoise()
        return phase
    }

    private fun startNoise() {
        solver = solverFactory(speechDbfs, ambientDbfs)
        phase = CalibrationPhase.Noise(startGainDb, iteration = 1, lastMeasuredDbfs = null)
    }

    companion object {
        const val MIN_SPEECH_MARGIN_DB = 10.0

        /** Low enough not to clip the microphone on the first try, the solver raises it from there. */
        const val DEFAULT_START_GAIN_DB = -10.0

        /** Safety net on top of the solver's own maxIterations. */
        const val HARD_ITERATION_LIMIT = 16
    }
}
