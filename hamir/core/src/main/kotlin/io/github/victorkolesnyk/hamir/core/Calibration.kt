package io.github.victorkolesnyk.hamir.core

enum class CalibrationFailure { NEED_MORE_VOLUME, MIC_CLIPPING, NOISE_NOT_AUDIBLE, NOT_CONVERGING }

sealed class CalibrationStep {
    /** Play again with [gainDb] and measure once more. */
    data class Adjust(val gainDb: Double) : CalibrationStep()
    data class Done(val gainDb: Double, val achievedSnrDb: Double) : CalibrationStep()
    data class Fail(val reason: CalibrationFailure) : CalibrationStep()
}

/**
 * Iterative search for the digital gain that puts the noise [targetSnrDb] relative to speech
 * at the microphone. SNR = speech - noise, so targetSnrDb = -5 means noise 5 dB above speech.
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
    val targetNoiseDbfs: Double get() = speechDbfs - targetSnrDb

    /** Feed one measurement taken while the noise played at [currentGainDb]. */
    fun next(currentGainDb: Double, measuredNoiseDbfs: Double, measuredPeakDbfs: Double): CalibrationStep = TODO()
}
