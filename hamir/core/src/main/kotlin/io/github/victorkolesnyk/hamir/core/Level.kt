package io.github.victorkolesnyk.hamir.core

/** Sample rate of every PCM buffer in the project unless a parameter says otherwise. Mono, 16-bit. */
const val SAMPLE_RATE = 32000

/** Level value returned for digital silence or empty input. */
const val SILENCE_DBFS = -120.0

/** Level measurements. 0 dBFS = full-scale sine RMS is NOT used here: 0 dBFS means RMS of 32768 (full-scale square). */
object Level {
    /** RMS level in dBFS, clamped at [SILENCE_DBFS]. */
    fun rmsDbfs(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): Double = TODO()

    /** Absolute peak in dBFS, clamped at [SILENCE_DBFS]. */
    fun peakDbfs(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): Double = TODO()

    /**
     * Level of speech-active frames only (pauses excluded), in dBFS.
     * Used for the "speech" side of the SNR so that pauses do not lower the estimate.
     */
    fun activeSpeechDbfs(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): Double = TODO()

    /** Fraction of samples at or within 1% of full scale, 0.0..1.0. */
    fun clippedFraction(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): Double = TODO()
}
