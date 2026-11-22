package io.github.victorkolesnyk.hamir.core

import kotlin.math.max
import kotlin.math.min

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

    /** When the buffer has a clear noise floor, a speech frame must also be 6 dB above it ... */
    private const val ABOVE_FLOOR = 4.0

    /** ... but that rule never rejects frames within 10 dB of the active level, so steady signals stay active. */
    private const val FLOOR_RULE_LIMIT = 0.1
    private const val FLOOR_PERCENTILE = 0.10

    /**
     * Loud material shorter than this that towers above everything else by more than the margin
     * is an outlier (a tap on the case, a door), not speech.
     */
    private const val MIN_ACTIVE_MS = 200

    /** Result of [analyse]: a frame is speech when threshold <= power < ceiling. */
    class Activity(val threshold: Double, val ceiling: Double) {
        fun isActive(power: Double): Boolean = power > 0.0 && power >= threshold && power < ceiling
    }

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
     * Frames are visited from the loudest down while each one is within [MARGIN] of the mean of
     * those already accepted; the first frame that falls out marks the threshold. This follows the
     * actual level of the speech whether it fills the whole buffer or only a small part of it.
     */
    fun analyse(powers: DoubleArray, frameMs: Int = FRAME_MS): Activity {
        if (powers.isEmpty()) return Activity(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        val sorted = powers.copyOf()
        sorted.sort()
        val minFrames = max(1, MIN_ACTIVE_MS / frameMs)
        var top = sorted.size - 1
        var ceiling = Double.POSITIVE_INFINITY
        var threshold: Double
        var level: Double
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
            level = sum / accepted
            break
        }
        val floor = sorted[(top * FLOOR_PERCENTILE).toInt()]
        threshold = max(threshold, min(floor * ABOVE_FLOOR, level * FLOOR_RULE_LIMIT))
        return Activity(threshold, ceiling)
    }
}
