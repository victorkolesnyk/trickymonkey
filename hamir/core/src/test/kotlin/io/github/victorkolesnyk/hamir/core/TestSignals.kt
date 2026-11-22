package io.github.victorkolesnyk.hamir.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Deterministic test signals. There are no real recordings in the repository, so speech is synthesised. */
object TestSignals {
    fun amplitude(dbfs: Double): Double = 32768.0 * 10.0.pow(dbfs / 20.0)

    fun sine(frequency: Double, seconds: Double, peakDbfs: Double, rate: Int = SAMPLE_RATE): ShortArray {
        val a = amplitude(peakDbfs).coerceAtMost(32767.0)
        return ShortArray((seconds * rate).roundToInt()) { (a * sin(2.0 * PI * frequency * it / rate)).roundToInt().toShort() }
    }

    fun silence(seconds: Double, rate: Int = SAMPLE_RATE): ShortArray = ShortArray((seconds * rate).roundToInt())

    fun whiteNoise(seconds: Double, rmsDbfs: Double, seed: Long, rate: Int = SAMPLE_RATE): ShortArray {
        val random = Random(seed)
        val a = amplitude(rmsDbfs)
        return ShortArray((seconds * rate).roundToInt()) {
            (random.nextGaussian() * a).roundToInt().coerceIn(-32767, 32767).toShort()
        }
    }

    /** Adds stationary white noise of the given level. */
    fun withNoise(samples: ShortArray, noiseDbfs: Double, seed: Long): ShortArray {
        val random = Random(seed)
        val a = amplitude(noiseDbfs)
        return ShortArray(samples.size) { (samples[it] + random.nextGaussian() * a).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }

    fun scaled(samples: ShortArray, gainDb: Double): ShortArray {
        val g = 10.0.pow(gainDb / 20.0)
        return ShortArray(samples.size) { (samples[it] * g).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    class Utterance(
        val samples: ShortArray,
        /** Seconds covered by syllables (excluding all gaps and pauses). */
        val voicedSeconds: Double,
        /** Seconds of silence at the start and at the end. */
        val leadSeconds: Double,
        val tailSeconds: Double,
    )

    /**
     * Speech-like signal: "syllables" (harmonic bursts with a gliding pitch, two formant-like
     * spectral peaks and a smooth envelope, sometimes preceded by a noise burst) grouped into
     * words and phrases with pauses, surrounded by silence, over a faint noise floor.
     *
     * @param speechPeakDbfs level the loudest sample is scaled to
     */
    fun utterance(
        seed: Long,
        words: Int = 9,
        rate: Int = SAMPLE_RATE,
        speechPeakDbfs: Double = -6.0,
        pitchHz: Double = 120.0,
        floorDbfs: Double = -70.0,
        /** Spread of syllable loudness; real speech has weak syllables far below the stressed ones. */
        loudnessRangeDb: Double = 8.0,
        /** Longest gap between words that is not a breath pause, in seconds. */
        maxWordGap: Double = 0.13,
    ): Utterance {
        val random = Random(seed)
        val buffer = ArrayList<FloatArray>()
        var voiced = 0.0
        fun gap(seconds: Double) = buffer.add(FloatArray((seconds * rate).roundToInt()))
        val lead = 0.3 + 0.3 * random.nextDouble()
        val tail = 0.3 + 0.3 * random.nextDouble()
        gap(lead)
        var sinceBreath = 0
        var breathAfter = 3 + random.nextInt(3)
        for (w in 0 until words) {
            val syllables = 1 + random.nextInt(4)
            for (s in 0 until syllables) {
                if (random.nextDouble() < 0.3) {
                    buffer.add(fricative(random, rate))
                }
                val seconds = 0.09 + 0.17 * random.nextDouble()
                buffer.add(syllable(random, seconds, rate, pitchHz, loudnessRangeDb))
                voiced += seconds
                if (s < syllables - 1) gap(0.01 + 0.04 * random.nextDouble())
            }
            if (w == words - 1) break
            sinceBreath++
            if (sinceBreath >= breathAfter) {
                gap(0.25 + 0.35 * random.nextDouble())
                sinceBreath = 0
                breathAfter = 3 + random.nextInt(3)
            } else {
                gap(0.05 + (maxWordGap - 0.05) * random.nextDouble())
            }
        }
        gap(tail)
        val total = buffer.sumOf { it.size }
        val mix = FloatArray(total)
        var o = 0
        var peak = 1e-9f
        for (part in buffer) {
            for (v in part) {
                mix[o++] = v
                if (v > peak) peak = v else if (-v > peak) peak = -v
            }
        }
        val scale = amplitude(speechPeakDbfs) / peak
        val floor = amplitude(floorDbfs)
        val samples = ShortArray(total) {
            (mix[it] * scale + random.nextGaussian() * floor).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return Utterance(samples, voiced, lead, tail)
    }

    private fun syllable(random: Random, seconds: Double, rate: Int, pitchHz: Double, loudnessRangeDb: Double): FloatArray {
        val n = (seconds * rate).roundToInt()
        val out = FloatArray(n)
        val f0 = pitchHz * (0.85 + 0.3 * random.nextDouble())
        val glide = 0.9 + 0.2 * random.nextDouble()
        val formant1 = 300.0 + 500.0 * random.nextDouble()
        val formant2 = 1000.0 + 1400.0 * random.nextDouble()
        val loudness = 10.0.pow(-loudnessRangeDb * random.nextDouble() / 20.0)
        val harmonics = (3600.0 / (f0 * maxOf(1.0, glide))).toInt()
        val weights = DoubleArray(harmonics + 1)
        for (h in 1..harmonics) {
            val f = h * f0
            weights[h] = (resonance(f, formant1, 120.0) + 0.5 * resonance(f, formant2, 180.0) + 0.03) / h.toDouble().pow(0.5)
        }
        val attack = (0.02 * rate).toInt().coerceAtMost(n / 3)
        val release = (0.05 * rate).toInt().coerceAtMost(n / 3)
        var phase = 2.0 * PI * random.nextDouble()
        for (i in 0 until n) {
            val f = f0 * (1.0 + (glide - 1.0) * i / n)
            phase += 2.0 * PI * f / rate
            var v = 0.0
            for (h in 1..harmonics) v += weights[h] * sin(h * phase)
            val envelope = when {
                i < attack -> 0.5 - 0.5 * cos(PI * i / attack)
                i >= n - release -> 0.5 - 0.5 * cos(PI * (n - i) / release)
                else -> 1.0
            }
            out[i] = (v * envelope * loudness).toFloat()
        }
        return out
    }

    private fun fricative(random: Random, rate: Int): FloatArray {
        val n = ((0.04 + 0.06 * random.nextDouble()) * rate).roundToInt()
        val out = FloatArray(n)
        var previous = 0.0
        for (i in 0 until n) {
            val white = random.nextGaussian()
            val highPassed = white - previous
            previous = white
            out[i] = (0.12 * highPassed * sin(PI * i / n)).toFloat()
        }
        return out
    }

    enum class SpeechModel {
        /** Syllable stream of [utterance] without breath pauses: gaps of 10..130 ms only. */
        SYLLABLES,

        /** Low-passed noise with a 3..6 Hz syllabic amplitude modulation of random depth. */
        MODULATED_NOISE,

        /** Constant-level tone. */
        FLAT_TONE,
    }

    class NoisyCase(
        val samples: ShortArray,
        /** Level of the clean speech over its known segments (their internal short gaps included). */
        val speechDbfs: Double,
        /** Ambient noise level. */
        val ambientDbfs: Double,
        /** Share of the buffer covered by speech segments. */
        val speechShare: Double,
        /** Sample ranges [from, to) of the speech segments. */
        val segments: List<IntRange>,
        /**
         * Level of the clean speech over the sounding samples only, i.e. without the 10..130 ms gaps
         * between syllables and words. Equal to [speechDbfs] for the models that have no such gaps.
         */
        val soundingDbfs: Double,
    )

    /**
     * A buffer of [seconds] with one to three speech segments covering 20..80% of it over stationary
     * white noise 10..30 dB below the speech level (or exactly [ambientBelowDb]). The segments are
     * separated by at least 0.3 s. The speech level is exact by construction: the clean speech is
     * scaled so that its mean power over the segments is [NoisyCase.speechDbfs].
     */
    fun speechInNoise(
        seed: Long,
        model: SpeechModel,
        seconds: Double = 8.0,
        ambientBelowDb: Double? = null,
        rate: Int = SAMPLE_RATE,
    ): NoisyCase {
        // java.util.Random gives nearly the same first values for neighbouring seeds: scramble them.
        var mixed = (seed + 1) * -0x61c8864680b583ebL
        mixed = (mixed xor (mixed ushr 30)) * -0x40a7b892e31b1a47L
        mixed = (mixed xor (mixed ushr 27)) * -0x6b2fb644ecceee15L
        val random = Random(mixed xor (mixed ushr 31))
        val total = (seconds * rate).roundToInt()
        val share = 0.2 + 0.6 * random.nextDouble()
        val speechDbfs = -40.0 + 25.0 * random.nextDouble()
        val below = ambientBelowDb ?: (10.0 + 20.0 * random.nextDouble())
        val speechSamples = (share * total).roundToInt()
        val minGap = (0.3 * rate).toInt()
        var count = 1 + random.nextInt(3)
        while (count > 1 && (speechSamples / count < 0.4 * rate || total - speechSamples < (count - 1) * minGap)) count--
        // Random split of the speech and of the spare silence.
        val lengths = IntArray(count)
        var left = speechSamples
        for (i in 0 until count) {
            val minimum = (0.4 * rate).toInt()
            val spare = left - minimum * (count - i)
            lengths[i] = if (i == count - 1) left else minimum + (spare * random.nextDouble()).toInt()
            left -= lengths[i]
        }
        val spareSilence = total - speechSamples - (count - 1) * minGap
        val gapWeights = DoubleArray(count + 1) { random.nextDouble() }
        val weightSum = gapWeights.sum()
        val clean = FloatArray(total)
        val segments = ArrayList<IntRange>()
        val sounding = BooleanArray(total)
        var position = 0
        for (i in 0 until count) {
            position += (spareSilence * gapWeights[i] / weightSum).toInt() + if (i > 0) minGap else 0
            val part = when (model) {
                SpeechModel.SYLLABLES -> phrase(random, lengths[i], rate, sounding, position)
                SpeechModel.MODULATED_NOISE -> modulatedNoise(random, lengths[i], rate)
                SpeechModel.FLAT_TONE -> FloatArray(lengths[i]) { sin(2.0 * PI * 220.0 * it / rate).toFloat() }
            }
            System.arraycopy(part, 0, clean, position, lengths[i])
            if (model != SpeechModel.SYLLABLES) java.util.Arrays.fill(sounding, position, position + lengths[i], true)
            segments.add(position until position + lengths[i])
            position += lengths[i]
        }
        var energy = 0.0
        for (segment in segments) for (i in segment) energy += clean[i].toDouble() * clean[i]
        val scale = amplitude(speechDbfs) / Math.sqrt(energy / speechSamples)
        val noise = amplitude(speechDbfs - below)
        val samples = ShortArray(total) {
            (clean[it] * scale + random.nextGaussian() * noise).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        val soundingSamples = sounding.count { it }
        val soundingDbfs = speechDbfs + 10.0 * log10(speechSamples.toDouble() / soundingSamples)
        return NoisyCase(samples, speechDbfs, speechDbfs - below, share, segments, soundingDbfs)
    }

    /** Exactly [length] samples of syllables and fricatives with short gaps, starting and ending on a syllable. */
    private fun phrase(random: Random, length: Int, rate: Int, sounding: BooleanArray, offset: Int): FloatArray {
        val out = FloatArray(length)
        var position = 0
        var syllablesLeftInWord = 1 + random.nextInt(4)
        while (position < length) {
            if (random.nextDouble() < 0.3) {
                val burst = fricative(random, rate)
                if (position + burst.size < length) {
                    System.arraycopy(burst, 0, out, position, burst.size)
                    java.util.Arrays.fill(sounding, offset + position, offset + position + burst.size, true)
                    position += burst.size
                }
            }
            val remaining = length - position
            var seconds = 0.09 + 0.17 * random.nextDouble()
            // Do not leave a tail too short for a syllable of its own.
            if (remaining < (seconds + 0.2) * rate) seconds = remaining.toDouble() / rate
            val syllable = syllable(random, seconds, rate, 120.0, 8.0)
            val n = minOf(syllable.size, remaining)
            System.arraycopy(syllable, 0, out, position, n)
            java.util.Arrays.fill(sounding, offset + position, offset + position + n, true)
            position += n
            if (position >= length) break
            syllablesLeftInWord--
            val gap = if (syllablesLeftInWord > 0) {
                0.01 + 0.04 * random.nextDouble()
            } else {
                syllablesLeftInWord = 1 + random.nextInt(4)
                0.05 + 0.08 * random.nextDouble()
            }
            position += (gap * rate).toInt()
        }
        return out
    }

    private fun modulatedNoise(random: Random, length: Int, rate: Int): FloatArray {
        val out = FloatArray(length)
        val modulationHz = 3.0 + 3.0 * random.nextDouble()
        val depth = 0.5 + 0.5 * random.nextDouble()
        val phase = 2.0 * PI * random.nextDouble()
        var state = 0.0
        for (i in 0 until length) {
            state += 0.15 * (random.nextGaussian() - state)
            val envelope = 1.0 - depth * (0.5 + 0.5 * cos(2.0 * PI * modulationHz * i / rate + phase))
            out[i] = (state * envelope).toFloat()
        }
        return out
    }

    private fun resonance(f: Double, centre: Double, width: Double): Double {
        val d = (f - centre) / width
        return exp(-0.5 * d * d)
    }

    /** Lazily built corpus shared by the tests: 24 takes of speaker A, 24 of speaker B (different pitch). */
    val speakerA: List<ShortArray> by lazy { List(24) { utterance(1000L + it, pitchHz = 115.0).samples } }
    val speakerB: List<ShortArray> by lazy { List(24) { utterance(2000L + it, pitchHz = 190.0).samples } }
    val bankA: VoiceBank by lazy { VoiceBank.fromUtterances(speakerA) }
    val bankB: VoiceBank by lazy { VoiceBank.fromUtterances(speakerB) }

    fun rmsDb(samples: ShortArray, from: Int = 0, to: Int = samples.size): Double {
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return 10.0 * log10(sum / (to - from) / (32768.0 * 32768.0) + 1e-30)
    }

    /** Lowest and highest level of any [windowMs] window (1 ms hop), in dBFS. */
    fun windowLevelRange(samples: ShortArray, windowMs: Int = 500, rate: Int = SAMPLE_RATE): Pair<Double, Double> {
        val window = rate * windowMs / 1000
        val prefix = DoubleArray(samples.size + 1)
        for (i in samples.indices) prefix[i + 1] = prefix[i] + samples[i].toDouble() * samples[i]
        var lowest = Double.MAX_VALUE
        var highest = 0.0
        var start = 0
        while (start + window <= samples.size) {
            val power = (prefix[start + window] - prefix[start]) / window
            if (power < lowest) lowest = power
            if (power > highest) highest = power
            start += rate / 1000
        }
        fun db(p: Double) = 10.0 * log10(p / (32768.0 * 32768.0) + 1e-30)
        return db(lowest) to db(highest)
    }

    /** Power of the signal between [lowHz] and [highHz] (relative units), by direct DFT of consecutive frames. */
    fun bandPower(samples: ShortArray, lowHz: Double, highHz: Double, rate: Int = SAMPLE_RATE): Double {
        val n = 4096
        val firstBin = (lowHz * n / rate).toInt()
        val lastBin = (highHz * n / rate).toInt() + 1
        var total = 0.0
        var frames = 0
        var start = 0
        while (start + n <= samples.size && frames < 24) {
            for (bin in firstBin..lastBin) {
                var re = 0.0
                var im = 0.0
                val w = 2.0 * PI * bin / n
                for (i in 0 until n) {
                    val hann = 0.5 - 0.5 * cos(2.0 * PI * i / n)
                    val v = samples[start + i] * hann
                    re += v * cos(w * i)
                    im -= v * sin(w * i)
                }
                total += re * re + im * im
            }
            frames++
            start += n * 4
        }
        return total / maxOf(1, frames)
    }

    fun generate(generator: BabbleGenerator, seconds: Double, chunk: Int = 640): ShortArray {
        val out = ShortArray((seconds * SAMPLE_RATE).roundToInt())
        var position = 0
        while (position < out.size) {
            val count = minOf(chunk, out.size - position)
            generator.read(out, position, count)
            position += count
        }
        return out
    }
}
