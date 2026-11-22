package io.github.victorkolesnyk.hamir.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Energy-based speech activity detection shared by [Level.activeSpeechDbfs], [TakeCheck] and [VoiceBank].
 * All powers are mean squares; only their ratios matter, so any unit works.
 */
internal object SpeechActivity {
    const val FRAME_MS = 20

    /**
     * A frame is speech when its power is at most this far (15.9 dB, the margin of ITU-T P.56)
     * below the mean power of all frames louder than itself.
     */
    private const val MARGIN = 38.9

    /**
     * Noise-floor rule: a speech frame must also stand above the quiet frames of the buffer (their
     * 10th percentile) by the smaller of 6 dB and half the distance, in dB, between that floor and
     * the loud frames (the mean of the loudest [LOUD_SHARE]).
     *
     * Both reference points come straight from the sorted frame powers. They must not be derived
     * from the level of the frames accepted so far: with ambient noise 10..16 dB below the speech
     * the P.56 margin alone accepts the ambient frames too, that level is then diluted by them,
     * and a rule tied to it switches itself off exactly when it is needed.
     */
    private const val MAX_ABOVE_FLOOR = 4.0
    private const val FLOOR_PERCENTILE = 0.10
    private const val LOUD_SHARE = 0.15

    /**
     * Loud material shorter than this that towers above everything else by more than the margin
     * is an outlier (a tap on the case, a door), not speech.
     */
    private const val MIN_ACTIVE_MS = 200

    /**
     * Pauses up to this long between speech frames belong to the speech (the hangover time of
     * ITU-T P.56): weak syllables and stop closures inside a phrase count towards its level.
     */
    const val HANGOVER_MS = 200

    /** Result of [analyse]: a frame is speech when threshold <= power < ceiling. */
    class Activity(val threshold: Double, val ceiling: Double) {
        fun isActive(power: Double): Boolean = power > 0.0 && power >= threshold && power < ceiling
    }

    /** Result of [measure]: mean power of the speech frames and how many there are. */
    class Measurement(val power: Double, val frames: Int)

    /**
     * Mean-square power (relative to full scale) of consecutive [frameMs] frames. A trailing partial
     * frame is kept when it covers at least half a frame, or when it is the only one.
     */
    fun framePowers(samples: ShortArray, sampleRate: Int, frameMs: Int = FRAME_MS): DoubleArray {
        val frame = max(1, sampleRate * frameMs / 1000)
        val n = samples.size
        if (n == 0) return DoubleArray(0)
        val full = n / frame
        val rest = n - full * frame
        val count = full + if (rest * 2 >= frame || full == 0) 1 else 0
        val out = DoubleArray(count)
        for (f in 0 until count) {
            val from = f * frame
            val to = min(n, from + frame)
            var sum = 0.0
            for (i in from until to) {
                val s = samples[i].toDouble()
                sum += s * s
            }
            out[f] = sum / (to - from) / Level.FULL_SCALE_POWER
        }
        return out
    }

    /**
     * Finds the speech/pause threshold for the given frame powers.
     *
     * Two rules, the stricter one wins. (1) P.56 margin: frames are visited from the loudest down
     * while each one is within [MARGIN] of the mean of those already accepted; the first frame that
     * falls out marks the threshold. This follows the level of the speech whether it fills the
     * whole buffer or a small part of it, and decides when the pauses are (nearly) silent.
     * (2) Noise floor, see [MAX_ABOVE_FLOOR]: decides when the pauses hold ambient noise that is
     * less than the margin below the speech.
     */
    fun analyse(powers: DoubleArray, frameMs: Int = FRAME_MS): Activity {
        if (powers.isEmpty()) return Activity(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        val sorted = powers.copyOf()
        sorted.sort()
        val minFrames = max(1, MIN_ACTIVE_MS / frameMs)
        var top = sorted.size - 1
        var ceiling = Double.POSITIVE_INFINITY
        var threshold: Double
        while (true) {
            var sum = 0.0
            var i = top
            while (i >= 0) {
                val accepted = top - i
                if (accepted > 0 && sorted[i] * MARGIN < sum / accepted) break
                sum += sorted[i]
                i--
            }
            val accepted = top - i
            if (i >= 0 && accepted < minFrames) {
                // Too little material to be speech: drop it as an outlier and look below it.
                ceiling = sorted[i + 1]
                top = i
                continue
            }
            threshold = if (i < 0) 0.0 else sorted[i + 1]
            break
        }
        val floor = sorted[(top * FLOOR_PERCENTILE).toInt()]
        if (floor > 0.0) {
            val loudFrames = max(1, ((top + 1) * LOUD_SHARE).toInt())
            var loud = 0.0
            for (i in top - loudFrames + 1..top) loud += sorted[i]
            loud /= loudFrames
            threshold = max(threshold, floor * min(MAX_ABOVE_FLOOR, sqrt(loud / floor)))
        }
        return Activity(threshold, ceiling)
    }

    /**
     * Active speech level of a sequence of frame powers: the mean over all speech frames plus the
     * pauses of at most [hangoverMs] between them. Outlier frames (see [MIN_ACTIVE_MS]) are left out.
     */
    fun measure(powers: DoubleArray, frameMs: Int = FRAME_MS, hangoverMs: Int = HANGOVER_MS): Measurement {
        val activity = analyse(powers, frameMs)
        val hangover = hangoverMs / frameMs
        var sum = 0.0
        var frames = 0
        var gapSum = 0.0
        var gapFrames = 0
        var gapLength = 0
        var inSpeech = false
        for (p in powers) {
            if (activity.isActive(p)) {
                if (inSpeech && gapLength <= hangover) {
                    sum += gapSum
                    frames += gapFrames
                }
                sum += p
                frames++
                gapSum = 0.0
                gapFrames = 0
                gapLength = 0
                inSpeech = true
            } else if (inSpeech) {
                gapLength++
                if (p < activity.ceiling) {
                    gapSum += p
                    gapFrames++
                }
            }
        }
        return Measurement(if (frames > 0) sum / frames else 0.0, frames)
    }
}
