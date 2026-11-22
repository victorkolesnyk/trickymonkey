package io.github.victorkolesnyk.hamir.logic

/** Growable PCM16 buffer with a hard capacity, used while recording a take. */
class PcmBuffer(private val maxSamples: Int, initialCapacity: Int = 16384) {
    private var data = ShortArray(initialCapacity.coerceIn(1, maxOf(1, maxSamples)))

    var size: Int = 0
        private set

    val isFull: Boolean get() = size >= maxSamples

    /** Appends as much as fits and returns the number of samples taken. */
    fun append(source: ShortArray, offset: Int, length: Int): Int {
        val count = minOf(length, maxSamples - size)
        if (count <= 0) return 0
        if (size + count > data.size) {
            var capacity = data.size
            while (capacity < size + count) capacity = minOf(maxSamples.toLong(), capacity.toLong() * 2).toInt()
            data = data.copyOf(capacity)
        }
        System.arraycopy(source, offset, data, size, count)
        size += count
        return count
    }

    fun toArray(): ShortArray = data.copyOf(size)
}

object PcmMath {
    /**
     * Multiplies samples by a gain that moves linearly from [fromGain] to [toGain] across the range.
     * Gains are linear factors in 0..1; used for the click-free start and stop.
     */
    fun ramp(buffer: ShortArray, offset: Int, length: Int, fromGain: Float, toGain: Float) {
        if (length <= 0) return
        val step = (toGain - fromGain) / length
        var gain = fromGain
        for (i in offset until offset + length) {
            val value = buffer[i] * gain
            buffer[i] = value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            gain += step
        }
    }

    /** Moves [current] towards [target] by at most [maxStep] (dB per audio chunk). */
    fun slew(current: Double, target: Double, maxStep: Double): Double = when {
        target > current + maxStep -> current + maxStep
        target < current - maxStep -> current - maxStep
        else -> target
    }

    /** Meter floor for sources with voice-recognition tuning (about -22 dBFS at 90 dB SPL). */
    const val METER_FLOOR_TUNED_DBFS = -60.0

    /** Meter floor for the UNPROCESSED source (about -36 dBFS at 94 dB SPL): speech sits near -60 dBFS. */
    const val METER_FLOOR_RAW_DBFS = -80.0

    /** Maps a level in dBFS to a 0..100 meter position; [floorDbfs] and below is 0. */
    fun meterPercent(levelDbfs: Double, floorDbfs: Double = METER_FLOOR_TUNED_DBFS): Int {
        if (levelDbfs.isNaN()) return 0
        val fraction = (levelDbfs - floorDbfs) / -floorDbfs
        return (fraction * 100.0).toInt().coerceIn(0, 100)
    }
}

/** Envelope of the playback thread: fade in after start, fade out before stop. */
class FadeEnvelope(private val fadeSamples: Int) {
    private var position = 0
    private var fadingOut = false

    /** True once a fade-out has run to silence; everything after it is muted. */
    var finished: Boolean = false
        private set

    fun beginFadeOut() {
        if (fadingOut) return
        fadingOut = true
        // Start the fade-out from the gain reached so far, so an interrupted fade-in does not jump.
        position = fadeSamples - minOf(position, fadeSamples)
    }

    fun process(buffer: ShortArray, offset: Int, length: Int) {
        var i = offset
        val end = offset + length
        while (i < end) {
            if (finished) {
                java.util.Arrays.fill(buffer, i, end, 0.toShort())
                return
            }
            if (!fadingOut && position >= fadeSamples) return
            val count = minOf(end - i, fadeSamples - position)
            if (count <= 0) {
                finished = fadingOut
                if (!fadingOut) return
                continue
            }
            val from = gainAt(position)
            val to = gainAt(position + count)
            PcmMath.ramp(buffer, i, count, from, to)
            position += count
            i += count
            if (fadingOut && position >= fadeSamples) finished = true
        }
    }

    private fun gainAt(pos: Int): Float {
        if (fadeSamples <= 0) return if (fadingOut) 0f else 1f
        val fraction = pos.toFloat() / fadeSamples
        return if (fadingOut) 1f - fraction else fraction
    }
}
