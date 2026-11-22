package io.github.victorkolesnyk.hamir.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

object Resampler {
    /** Zero crossings of the sinc kernel on each side of its centre. */
    private const val ZERO_CROSSINGS = 16

    /** Kernel table resolution: entries per zero crossing (linear interpolation in between). */
    private const val TABLE_STEPS = 256

    /** Cutoff as a fraction of the lower Nyquist frequency. */
    private const val CUTOFF = 0.94
    private const val KAISER_BETA = 7.0

    /** Right half of a Kaiser-windowed sinc, sampled [TABLE_STEPS] times per zero crossing. */
    private val kernel: FloatArray by lazy {
        val size = ZERO_CROSSINGS * TABLE_STEPS
        val table = FloatArray(size + 2)
        val norm = besselI0(KAISER_BETA)
        for (k in 0..size) {
            val x = k.toDouble() / TABLE_STEPS
            val sinc = if (k == 0) 1.0 else sin(PI * x) / (PI * x)
            val r = k.toDouble() / size
            val window = besselI0(KAISER_BETA * sqrt(max(0.0, 1.0 - r * r))) / norm
            table[k] = (sinc * window).toFloat()
        }
        table
    }

    /** Band-limited enough for speech; returns [input] itself when rates are equal. */
    fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        require(fromRate > 0 && toRate > 0) { "sample rates must be positive: $fromRate -> $toRate" }
        if (fromRate == toRate) return input
        if (input.isEmpty()) return ShortArray(0)
        val outLength = ((input.size.toLong() * toRate + fromRate / 2) / fromRate).toInt()
        val out = ShortArray(outLength)
        val table = kernel
        // Kernel argument scale: below 1.0 when downsampling, which widens the kernel and lowers the cutoff.
        val scale = min(1.0, toRate.toDouble() / fromRate) * CUTOFF
        val halfWidth = ZERO_CROSSINGS / scale
        val tableScale = scale * TABLE_STEPS
        val last = input.size - 1
        for (i in 0 until outLength) {
            val t = i.toLong() * fromRate / toRate.toDouble()
            val first = max(0, ceil(t - halfWidth).toInt())
            val end = min(last, floor(t + halfWidth).toInt())
            var acc = 0.0
            for (n in first..end) {
                val x = abs(t - n) * tableScale
                val k = x.toInt()
                val frac = x - k
                val w = table[k] + (table[k + 1] - table[k]) * frac
                acc += input[n] * w
            }
            out[i] = (acc * scale).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Modified Bessel function of the first kind, order zero (power series). */
    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4.0
        var k = 1
        while (k < 64) {
            term *= q / (k.toDouble() * k)
            sum += term
            if (term < sum * 1e-14) break
            k++
        }
        return sum
    }
}

object Gain {
    /** Above this magnitude (as a fraction of full scale) an amplified signal is compressed smoothly. */
    internal const val KNEE = 0.75
    private const val MAX_GAIN_DB = 120.0
    private const val CEILING = 32767.0

    /**
     * Applies gain in place with a soft limiter, so the result never wraps around.
     *
     * Attenuation and unity gain are a plain multiplication. With amplification, magnitudes up to
     * [KNEE] of full scale stay linear and anything above is bent with tanh towards full scale,
     * which is continuous in value and slope at the knee. The limiter is memoryless, so buffers can
     * be processed in chunks of any size. Does not allocate.
     */
    fun apply(buffer: ShortArray, offset: Int, length: Int, gainDb: Double) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
            "range offset=$offset length=$length is outside an array of ${buffer.size} samples"
        }
        require(!gainDb.isNaN()) { "gainDb is NaN" }
        if (gainDb == 0.0 || length == 0) return
        // Capped so that an absurd request saturates instead of producing infinities.
        val gain = 10.0.pow(min(gainDb, MAX_GAIN_DB) / 20.0)
        val end = offset + length
        if (gain <= 1.0) {
            for (i in offset until end) {
                buffer[i] = (buffer[i] * gain).roundToInt().toShort()
            }
            return
        }
        val knee = KNEE * CEILING
        val room = CEILING - knee
        for (i in offset until end) {
            val x = buffer[i] * gain
            val a = abs(x)
            val y = if (a <= knee) {
                x
            } else {
                val limited = knee + room * tanh((a - knee) / room)
                if (x < 0) -limited else limited
            }
            buffer[i] = y.roundToInt().toShort()
        }
    }
}

enum class TakeQuality { OK, TOO_SHORT, TOO_QUIET, CLIPPED }

object TakeCheck {
    /** A take needs at least this much detected speech (pauses longer than 200 ms excluded). */
    const val MIN_SPEECH_SECONDS = 0.8

    /**
     * Minimum active speech level. Deliberately low: with the UNPROCESSED audio source normal
     * speech at arm's length can sit near -45 dBFS, and [VoiceBank] normalises the level anyway.
     */
    const val MIN_SPEECH_DBFS = -55.0

    /** Largest tolerated share of samples within 1% of full scale. */
    const val MAX_CLIPPED_FRACTION = 0.0005

    /**
     * Quality gate for one recorded enrollment phrase.
     *
     * Checked in this order: nothing recorded -> TOO_SHORT; more than [MAX_CLIPPED_FRACTION] of the
     * samples at full scale -> CLIPPED; active speech level below [MIN_SPEECH_DBFS] -> TOO_QUIET;
     * less than [MIN_SPEECH_SECONDS] of speech frames -> TOO_SHORT.
     */
    fun evaluate(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): TakeQuality {
        require(sampleRate > 0) { "sampleRate must be positive" }
        if (samples.isEmpty()) return TakeQuality.TOO_SHORT
        if (Level.clippedFraction(samples) > MAX_CLIPPED_FRACTION) return TakeQuality.CLIPPED
        val measurement = SpeechActivity.measure(SpeechActivity.framePowers(samples, sampleRate))
        val active = measurement.frames
        val level = if (active == 0) SILENCE_DBFS else Level.powerToDbfs(measurement.power)
        if (level < MIN_SPEECH_DBFS) return TakeQuality.TOO_QUIET
        val speechSeconds = min(active * SpeechActivity.FRAME_MS / 1000.0, samples.size.toDouble() / sampleRate)
        if (speechSeconds < MIN_SPEECH_SECONDS) return TakeQuality.TOO_SHORT
        return TakeQuality.OK
    }
}
