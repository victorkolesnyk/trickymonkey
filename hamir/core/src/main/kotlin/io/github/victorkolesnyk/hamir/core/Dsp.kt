package io.github.victorkolesnyk.hamir.core

object Resampler {
    /** Band-limited enough for speech; returns [input] itself when rates are equal. */
    fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray = TODO()
}

object Gain {
    /** Applies gain in place with a soft limiter, so the result never wraps around. */
    fun apply(buffer: ShortArray, offset: Int, length: Int, gainDb: Double): Unit = TODO()
}

enum class TakeQuality { OK, TOO_SHORT, TOO_QUIET, CLIPPED }

object TakeCheck {
    /** Quality gate for one recorded enrollment phrase. */
    fun evaluate(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): TakeQuality = TODO()
}
