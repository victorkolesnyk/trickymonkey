package io.github.victorkolesnyk.hamir.core

import kotlin.math.abs
import kotlin.math.log10

/** Sample rate of every PCM buffer in the project unless a parameter says otherwise. Mono, 16-bit. */
const val SAMPLE_RATE = 32000

/** Level value returned for digital silence or empty input. */
const val SILENCE_DBFS = -120.0

/** Level measurements. 0 dBFS = full-scale sine RMS is NOT used here: 0 dBFS means RMS of 32768 (full-scale square). */
object Level {
    /** RMS level in dBFS, clamped at [SILENCE_DBFS]. */
    fun rmsDbfs(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): Double {
        checkRange(samples.size, offset, length)
        if (length == 0) return SILENCE_DBFS
        var sum = 0.0
        for (i in offset until offset + length) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return powerToDbfs(sum / length / FULL_SCALE_POWER)
    }

    /** Absolute peak in dBFS, clamped at [SILENCE_DBFS]. */
    fun peakDbfs(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): Double {
        checkRange(samples.size, offset, length)
        var peak = 0
        for (i in offset until offset + length) {
            val a = abs(samples[i].toInt())
            if (a > peak) peak = a
        }
        if (peak == 0) return SILENCE_DBFS
        return (20.0 * log10(peak / FULL_SCALE)).coerceAtLeast(SILENCE_DBFS)
    }

    /**
     * Level of speech-active frames only (pauses excluded), in dBFS.
     * Used for the "speech" side of the SNR so that pauses do not lower the estimate.
     *
     * Follows ITU-T P.56 in spirit. Frames are 20 ms long. A frame is speech when its power is
     * within 15.9 dB of the mean power of all louder frames and stands clear of the noise floor of
     * the buffer (see [SpeechActivity.analyse]); pauses of up to 200 ms between speech frames count
     * as part of the speech. A steady signal is therefore fully active and the result equals its
     * RMS level; a few loud clicks (under 200 ms in total) that tower above everything else are ignored.
     * The result is the level of speech plus whatever ambient noise sounds at the same time.
     */
    fun activeSpeechDbfs(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): Double {
        require(sampleRate > 0) { "sampleRate must be positive" }
        val measurement = SpeechActivity.measure(SpeechActivity.framePowers(samples, sampleRate))
        if (measurement.frames == 0) return SILENCE_DBFS
        return powerToDbfs(measurement.power)
    }

    /** Fraction of samples at or within 1% of full scale, 0.0..1.0. */
    fun clippedFraction(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): Double {
        checkRange(samples.size, offset, length)
        if (length == 0) return 0.0
        var count = 0
        for (i in offset until offset + length) {
            if (abs(samples[i].toInt()) >= CLIP_THRESHOLD) count++
        }
        return count.toDouble() / length
    }

    internal const val FULL_SCALE = 32768.0
    internal const val FULL_SCALE_POWER = FULL_SCALE * FULL_SCALE

    /** 99% of the largest positive sample value. */
    private const val CLIP_THRESHOLD = 32440

    /** Converts a mean-square value relative to full scale into dBFS, clamped at [SILENCE_DBFS]. */
    internal fun powerToDbfs(power: Double): Double {
        if (!(power > 0.0)) return SILENCE_DBFS
        return (10.0 * log10(power)).coerceAtLeast(SILENCE_DBFS)
    }

    private fun checkRange(size: Int, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= size - length) {
            "range offset=$offset length=$length is outside an array of $size samples"
        }
    }
}
