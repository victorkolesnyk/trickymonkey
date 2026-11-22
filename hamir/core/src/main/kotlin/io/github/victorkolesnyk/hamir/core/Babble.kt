package io.github.victorkolesnyk.hamir.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Preprocessed recordings of one speaker: silence trimmed, level normalised,
 * cut into speech fragments at energy minima.
 *
 * Internally the bank is one continuous PCM array (all takes back to back, pauses of
 * [PAUSE_MS] or longer removed, every remaining stretch faded at both ends) plus a sorted
 * list of cut points lying on local energy minima. A fragment is the audio between two
 * neighbouring cut points; [BabbleGenerator] starts its playback units on cut points.
 */
class VoiceBank private constructor(
    /** Normalised speech at [SAMPLE_RATE]; active speech level is [REFERENCE_DBFS]. */
    internal val data: ShortArray,
    /** Ascending sample offsets into [data]; the first is 0 and the last is data.size. */
    internal val cuts: IntArray,
    /** Mean square of [data] in squared sample units. */
    internal val meanPower: Double,
) {
    /** Number of fragments available to the generator. */
    val segmentCount: Int get() = cuts.size - 1

    /** Seconds of speech left after trimming silence. */
    val speechSeconds: Double get() = data.size.toDouble() / SAMPLE_RATE

    /** Index of the cut point closest to [position]. */
    internal fun nearestCut(position: Int): Int {
        var lo = 0
        var hi = cuts.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cuts[mid] <= position) lo = mid else hi = mid
        }
        return if (position - cuts[lo] <= cuts[hi] - position) lo else hi
    }

    /** Number of cut points at or before [position]. */
    internal fun cutsUpTo(position: Int): Int {
        var lo = 0
        var hi = cuts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cuts[mid] <= position) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** Active speech level every take is normalised to. Leaves about 25 dB of headroom for peaks. */
        internal const val REFERENCE_DBFS = -26.0

        /** Analysis frame: 10 ms. */
        private const val FRAME = SAMPLE_RATE / 100

        /** Pauses at least this long are cut out; shorter ones belong to the rhythm of speech. */
        internal const val PAUSE_MS = 150
        private const val PAUSE_FRAMES = PAUSE_MS / 10

        /** A stretch with less detected speech than this is dropped (screen taps, clicks). */
        private const val MIN_STRETCH_ACTIVE_FRAMES = 6

        /** Audio kept on each side of a speech stretch so that soft onsets and tails survive. */
        private const val PAD_FRAMES = 2

        /** Raised-cosine fade at both ends of every speech stretch: 5 ms. */
        private const val EDGE_FADE = SAMPLE_RATE / 200

        /** Fragment length limits used when a speech stretch is cut at energy minima. */
        private const val MIN_FRAGMENT_FRAMES = 12
        private const val MAX_FRAGMENT_FRAMES = 32

        /** Takes shorter than this are ignored. */
        private const val MIN_TAKE_SAMPLES = SAMPLE_RATE / 20

        /** A bank with less speech than this in total is treated as empty. */
        internal const val MIN_BANK_SAMPLES = SAMPLE_RATE / 4

        /** Takes whose speech is below this level contain nothing usable (10 dB under what [TakeCheck] accepts). */
        private const val MIN_USABLE_DBFS = TakeCheck.MIN_SPEECH_DBFS - 10.0

        /** Normalisation never pushes the peak of a take above this fraction of full scale. */
        private const val MAX_PEAK = 0.9 * 32767.0

        /** Corner frequency of the DC / rumble blocker. */
        private const val HIGH_PASS_HZ = 50.0

        /** @param utterances raw takes at [sampleRate]; they are resampled to [SAMPLE_RATE] when needed. */
        fun fromUtterances(utterances: List<ShortArray>, sampleRate: Int = SAMPLE_RATE): VoiceBank {
            require(sampleRate > 0) { "sampleRate must be positive" }
            val pieces = ArrayList<ShortArray>()
            val pieceCuts = ArrayList<IntArray>()
            var total = 0L
            for (utterance in utterances) {
                val take = Resampler.resample(utterance, sampleRate, SAMPLE_RATE)
                val cuts = IntList()
                val piece = processTake(take, cuts) ?: continue
                if (total + piece.size > Int.MAX_VALUE - 16) break
                pieces.add(piece)
                pieceCuts.add(cuts.toArray())
                total += piece.size
            }
            if (total < MIN_BANK_SAMPLES) return VoiceBank(ShortArray(0), intArrayOf(0), 0.0)

            val data = ShortArray(total.toInt())
            val cuts = IntList()
            cuts.add(0)
            var offset = 0
            var energy = 0.0
            for (p in pieces.indices) {
                val piece = pieces[p]
                System.arraycopy(piece, 0, data, offset, piece.size)
                for (s in piece) energy += s.toDouble() * s
                for (c in pieceCuts[p]) cuts.add(offset + c)
                offset += piece.size
            }
            return VoiceBank(data, cuts.toArray(), energy / data.size)
        }

        /**
         * Cleans one take. Returns the kept audio, or null when the take has no usable speech.
         * Appends to [cutsOut] the end offset of every fragment (relative to the returned array, ascending,
         * the last one equal to its size).
         */
        private fun processTake(take: ShortArray, cutsOut: IntList): ShortArray? {
            val n = take.size
            if (n < MIN_TAKE_SAMPLES) return null

            // 1. Remove DC and rumble: they waste headroom and distort the energy analysis.
            val x = FloatArray(n)
            val r = (1.0 - 2.0 * PI * HIGH_PASS_HZ / SAMPLE_RATE).toFloat()
            var prevIn = take[0].toFloat()
            var prevOut = 0f
            for (i in 0 until n) {
                val input = take[i].toFloat()
                val y = input - prevIn + r * prevOut
                prevIn = input
                prevOut = y
                x[i] = y
            }

            // 2. Frame powers and a lightly smoothed energy contour.
            val frames = n / FRAME
            val power = DoubleArray(frames)
            for (k in 0 until frames) {
                var sum = 0.0
                val from = k * FRAME
                for (i in from until from + FRAME) sum += x[i].toDouble() * x[i]
                power[k] = sum / FRAME
            }
            val contour = DoubleArray(frames)
            for (k in 0 until frames) {
                val before = power[max(0, k - 1)]
                val after = power[min(frames - 1, k + 1)]
                contour[k] = (before + 2.0 * power[k] + after) / 4.0
            }

            // 3. Speech stretches: active frames, bridged over pauses shorter than PAUSE_FRAMES.
            val activity = SpeechActivity.analyse(contour, 1000 * FRAME / SAMPLE_RATE)
            val stretchStart = IntList()
            val stretchEnd = IntList()
            var activePower = 0.0
            var activeFrames = 0
            var k = 0
            while (k < frames) {
                if (!activity.isActive(contour[k])) {
                    k++
                    continue
                }
                val start = k
                var end = k + 1
                var count = 0
                var sum = 0.0
                var gap = 0
                while (k < frames && gap < PAUSE_FRAMES) {
                    if (activity.isActive(contour[k])) {
                        gap = 0
                        end = k + 1
                        count++
                        sum += power[k]
                    } else {
                        gap++
                    }
                    k++
                }
                if (count >= MIN_STRETCH_ACTIVE_FRAMES) {
                    stretchStart.add(start)
                    stretchEnd.add(end)
                    activePower += sum
                    activeFrames += count
                }
            }
            if (activeFrames == 0) return null
            val activeLevel = activePower / activeFrames
            if (activeLevel <= Level.FULL_SCALE_POWER * 10.0.pow(MIN_USABLE_DBFS / 10.0)) return null

            // 4. Per-take normalisation of the active speech level, guarded against clipping.
            var peak = 0f
            var kept = 0
            for (s in 0 until stretchStart.size) {
                val from = max(0, stretchStart[s] - PAD_FRAMES) * FRAME
                val to = min(frames, stretchEnd[s] + PAD_FRAMES) * FRAME
                for (i in from until to) {
                    val a = abs(x[i])
                    if (a > peak) peak = a
                }
                kept += to - from
            }
            if (peak <= 0f) return null
            val reference = Level.FULL_SCALE * 10.0.pow(REFERENCE_DBFS / 20.0)
            var gain = reference / sqrt(activeLevel)
            if (peak * gain > MAX_PEAK) gain = MAX_PEAK / peak

            // 5. Copy the stretches back to back, fade their edges, cut each at energy minima.
            val out = ShortArray(kept)
            var o = 0
            for (s in 0 until stretchStart.size) {
                val fromFrame = max(0, stretchStart[s] - PAD_FRAMES)
                val toFrame = min(frames, stretchEnd[s] + PAD_FRAMES)
                val from = fromFrame * FRAME
                val length = (toFrame - fromFrame) * FRAME
                val fade = min(EDGE_FADE, length / 2)
                for (i in 0 until length) {
                    var v = x[from + i] * gain
                    if (i < fade) {
                        v *= 0.5 - 0.5 * cos(PI * (i + 0.5) / fade)
                    } else if (i >= length - fade) {
                        v *= 0.5 - 0.5 * cos(PI * (length - i - 0.5) / fade)
                    }
                    out[o + i] = v.roundToInt().coerceIn(-32767, 32767).toShort()
                }
                // Greedy segmentation: the next cut is the quietest frame boundary that keeps the
                // fragment between MIN_FRAGMENT_FRAMES and MAX_FRAGMENT_FRAMES long.
                var position = fromFrame
                while (toFrame - position > MAX_FRAGMENT_FRAMES) {
                    val lo = position + MIN_FRAGMENT_FRAMES
                    val hi = min(position + MAX_FRAGMENT_FRAMES, toFrame - MIN_FRAGMENT_FRAMES)
                    if (lo > hi) break
                    var best = lo
                    var bestEnergy = Double.MAX_VALUE
                    for (b in lo..hi) {
                        val e = power[b - 1] + power[min(frames - 1, b)]
                        if (e < bestEnergy) {
                            bestEnergy = e
                            best = b
                        }
                    }
                    cutsOut.add(o + (best - fromFrame) * FRAME)
                    position = best
                }
                o += length
                cutsOut.add(o)
            }
            return out
        }
    }
}

/** Minimal growable int array (keeps boxing out of bank construction). */
internal class IntList {
    private var items = IntArray(16)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    operator fun get(index: Int): Int = items[index]

    fun toArray(): IntArray = items.copyOf(size)
}

data class BabbleConfig(
    /** Total simultaneous voices. The paper's optimum is 8. */
    val voices: Int = 8,
    val minSegmentMs: Int = 400,
    val maxSegmentMs: Int = 2000,
    /** Per-fragment playback speed is drawn from 1 +- speedJitter. */
    val speedJitter: Double = 0.06,
    val crossfadeMs: Int = 20,
    /** Long-term RMS of the mix before [Gain]. */
    val targetRmsDbfs: Double = -20.0,
)

/**
 * Endless personalised babble: [BabbleConfig.voices] overlapping streams of shuffled
 * speech fragments. With several banks the voices are split evenly between them
 * (two speakers -> 4 + 4). Not thread-safe.
 *
 * Each voice plays "units": a unit starts on a random cut point of its bank, lasts
 * minSegmentMs..maxSegmentMs of output time, is resampled by linear interpolation to a random
 * speed of 1 +- speedJitter and overlaps its neighbours by an equal-power crossfade.
 *
 * Level control: the summed voices pass through a slow look-ahead gain stage that holds the
 * power of a sliding [WINDOW_BLOCKS] x 10 ms window at the target, then through a soft limiter.
 * Without it a babble of few voices dips whenever several of them hit a quiet syllable at once,
 * and those dips are exactly where the protected speech would show through.
 *
 * [read] does not allocate. Bank audio is shared, not copied.
 */
class BabbleGenerator(
    banks: List<VoiceBank>,
    config: BabbleConfig = BabbleConfig(),
    random: Random = java.security.SecureRandom(),
) {
    private class Player {
        @JvmField var data: ShortArray = EMPTY
        @JvmField var bank = -1
        @JvmField var base = 0
        @JvmField var sourceLength = 0
        @JvmField var position = 0.0
        @JvmField var step = 1.0
        @JvmField var played = 0
        @JvmField var length = 0
        @JvmField var gain = 0f
    }

    private val random: Random = random
    private val banks: Array<VoiceBank>
    private val voices: Int
    private val jitter: Double
    private val minOut: Int
    private val maxOut: Int
    private val fade: Int
    private val fadeIn: FloatArray
    private val fadeOut: FloatArray

    /** Per bank: how many leading cut points may start a unit (0 = bank shorter than one unit, play it whole). */
    private val validStarts: IntArray
    private val voiceBank: IntArray
    private val voiceGain: FloatArray
    private val current: Array<Player>
    private val previous: Array<Player>

    private val targetPower: Double
    private val ring = FloatArray(WINDOW_BLOCKS * BLOCK)
    private val blockPower = DoubleArray(WINDOW_BLOCKS)
    private var blocksMixed = 0L
    private var blocksEmitted = 0L
    private var lastGain = -1.0
    private val staged = ShortArray(BLOCK)
    private var stagedPosition = BLOCK

    /** Test hook: when false the look-ahead gain stage is bypassed (the limiter stays). */
    internal var levelControl = true

    /** Test hook, called for every unit started after construction: (voice, source offset, output samples, speed). */
    internal var onUnitStart: ((voice: Int, start: Int, length: Int, speed: Double) -> Unit)? = null

    /** How many voices draw from each non-empty bank, in the order the banks were given. */
    internal val voicesPerBank: IntArray

    init {
        require(config.voices >= 1) { "voices must be at least 1" }
        require(config.minSegmentMs >= 1) { "minSegmentMs must be positive" }
        require(config.maxSegmentMs >= config.minSegmentMs) { "maxSegmentMs must not be below minSegmentMs" }
        require(config.maxSegmentMs <= MAX_SEGMENT_MS) { "maxSegmentMs must not exceed $MAX_SEGMENT_MS" }
        require(config.speedJitter >= 0.0 && config.speedJitter <= MAX_SPEED_JITTER) {
            "speedJitter must be within 0..$MAX_SPEED_JITTER"
        }
        require(config.crossfadeMs >= 0) { "crossfadeMs must not be negative" }
        require(config.targetRmsDbfs.isFinite()) { "targetRmsDbfs must be finite" }
        val usable = banks.filter { it.segmentCount > 0 }
        require(usable.isNotEmpty()) { "all voice banks are empty" }

        this.banks = usable.toTypedArray()
        voices = config.voices
        jitter = config.speedJitter
        minOut = max(4, (config.minSegmentMs.toLong() * SAMPLE_RATE / 1000).toInt())
        maxOut = max(minOut, (config.maxSegmentMs.toLong() * SAMPLE_RATE / 1000).toInt())

        val worstMinSource = ceil(minOut * (1.0 + jitter)).toInt() + SOURCE_MARGIN
        validStarts = IntArray(usable.size)
        var shortestUnit = minOut
        for (b in usable.indices) {
            val size = usable[b].data.size
            if (size > worstMinSource) {
                validStarts[b] = usable[b].cutsUpTo(size - worstMinSource)
            } else {
                shortestUnit = min(shortestUnit, ((size - SOURCE_MARGIN) / (1.0 + jitter)).toInt())
            }
        }
        // A unit must be at least two fades long, otherwise three units of one voice could overlap.
        fade = min(config.crossfadeMs.toLong() * SAMPLE_RATE / 1000, (shortestUnit / 2).toLong()).toInt()
        fadeIn = FloatArray(fade)
        fadeOut = FloatArray(fade)
        for (i in 0 until fade) {
            val phase = 0.5 * PI * (i + 0.5) / fade
            fadeIn[i] = sin(phase).toFloat()
            fadeOut[i] = cos(phase).toFloat()
        }

        targetPower = (Level.FULL_SCALE * 10.0.pow(config.targetRmsDbfs / 20.0)).pow(2)
        voiceBank = IntArray(voices) { it % usable.size }
        voicesPerBank = IntArray(usable.size)
        for (b in voiceBank) voicesPerBank[b]++
        // Every voice contributes the same power, whichever bank it reads.
        voiceGain = FloatArray(voices) { v ->
            val bankPower = usable[voiceBank[v]].meanPower
            if (bankPower > 0.0) sqrt(targetPower / (voices * bankPower)).toFloat() else 0f
        }

        current = Array(voices) { Player() }
        previous = Array(voices) { Player() }
        for (v in 0 until voices) {
            // Staggered start: every voice begins somewhere inside the body of its first unit.
            val player = current[v]
            startUnit(v, player)
            val body = player.length - 2 * fade
            val skip = fade + if (body > 0) random.nextInt(body) else 0
            player.played = skip
            player.position = skip * player.step
        }
        // Fill the look-ahead half of the level window.
        for (i in 0 until LOOKAHEAD_BLOCKS) mixNextBlock()
    }

    /** Fills out[offset until offset+length] completely with the next samples at [SAMPLE_RATE]. */
    fun read(out: ShortArray, offset: Int = 0, length: Int = out.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= out.size - length) {
            "range offset=$offset length=$length is outside an array of ${out.size} samples"
        }
        var o = offset
        val end = offset + length
        while (o < end) {
            if (stagedPosition == BLOCK) {
                emitBlock()
                stagedPosition = 0
            }
            val count = min(end - o, BLOCK - stagedPosition)
            System.arraycopy(staged, stagedPosition, out, o, count)
            stagedPosition += count
            o += count
        }
    }

    /** Samples until each voice reaches its next unit boundary, counted from the mixing position. */
    internal fun samplesToNextBoundary(): IntArray =
        IntArray(voices) { current[it].length - fade - current[it].played }

    /** Chooses the next unit of voice [v] and loads it into [player]. */
    private fun startUnit(v: Int, player: Player) {
        val b = voiceBank[v]
        val bank = banks[b]
        val size = bank.data.size
        val speed = 1.0 + jitter * (2.0 * random.nextDouble() - 1.0)
        var start = 0
        var sourceLength = size
        if (validStarts[b] > 0) {
            val wantOut = minOut + if (maxOut > minOut) random.nextInt(maxOut - minOut + 1) else 0
            val wantSource = ceil(wantOut * speed).toInt() + SOURCE_MARGIN
            // Prefer material no voice of this bank is playing right now (and not what this voice just played).
            var tries = 0
            do {
                start = bank.cuts[random.nextInt(validStarts[b])]
                tries++
            } while (tries < MAX_START_TRIES && isBeingPlayed(b, start, start + wantSource))
            sourceLength = min(wantSource, size - start)
            // End on an energy minimum too when one is close and the length stays within limits.
            val snapped = bank.cuts[bank.nearestCut(start + sourceLength)] - start
            val minSource = ceil(minOut * speed).toInt() + SOURCE_MARGIN
            val maxSource = (maxOut * speed).toInt() + SOURCE_MARGIN
            if (abs(snapped - sourceLength) <= SNAP_SAMPLES && snapped >= minSource && snapped <= maxSource) {
                sourceLength = snapped
            }
        }
        player.data = bank.data
        player.bank = b
        player.base = start
        player.sourceLength = sourceLength
        player.position = 0.0
        player.step = speed
        player.played = 0
        // The interpolator reads one sample ahead; the margin also absorbs rounding of position.
        player.length = max(2 * fade, ((sourceLength - SOURCE_MARGIN) / speed).toInt())
        player.gain = voiceGain[v]
        onUnitStart?.invoke(v, start, player.length, speed)
    }

    private fun isBeingPlayed(bank: Int, from: Int, to: Int): Boolean {
        for (u in 0 until voices) {
            val c = current[u]
            if (c.bank == bank && c.sourceLength > 0 && from < c.base + c.sourceLength && c.base < to) return true
            val p = previous[u]
            if (p.bank == bank && p.sourceLength > 0 && from < p.base + p.sourceLength && p.base < to) return true
        }
        return false
    }

    /** Mixes the next 10 ms of all voices into the ring and records its power. */
    private fun mixNextBlock() {
        val slot = (blocksMixed % WINDOW_BLOCKS).toInt()
        val offset = slot * BLOCK
        val mix = ring
        java.util.Arrays.fill(mix, offset, offset + BLOCK, 0f)
        for (v in 0 until voices) {
            var o = 0
            while (o < BLOCK) {
                val cur = current[v]
                val untilCrossfade = cur.length - fade - cur.played
                if (untilCrossfade <= 0) {
                    // The current unit starts fading out: it becomes "previous" and a new one fades in.
                    val finished = previous[v]
                    previous[v] = cur
                    current[v] = finished
                    startUnit(v, finished)
                    continue
                }
                val count = min(BLOCK - o, untilCrossfade)
                render(cur, mix, offset + o, count)
                val prev = previous[v]
                val tail = min(count, prev.length - prev.played)
                if (tail > 0) render(prev, mix, offset + o, tail)
                o += count
            }
        }
        if (blocksMixed == 0L) {
            // The voices start mid-unit; ramp the very first block so the stream starts from zero.
            for (i in 0 until BLOCK) mix[offset + i] *= (i + 0.5f) / BLOCK
        }
        var sum = 0.0
        for (i in offset until offset + BLOCK) sum += mix[i].toDouble() * mix[i]
        blockPower[slot] = sum / BLOCK
        blocksMixed++
    }

    /** Adds the next [count] output samples of unit [p] to mix[offset...]. */
    private fun render(p: Player, mix: FloatArray, offset: Int, count: Int) {
        val data = p.data
        val base = p.base
        val gain = p.gain
        val step = p.step
        val fadeOutStart = p.length - fade
        var position = p.position
        var played = p.played
        var o = offset
        val end = offset + count
        while (o < end) {
            // Split the run into fade-in, body and fade-out so that the body loop carries no envelope logic.
            val runEnd: Int
            val table: FloatArray?
            val tableOffset: Int
            if (played < fade) {
                runEnd = min(end, o + (fade - played))
                table = fadeIn
                tableOffset = played - o
            } else if (played < fadeOutStart) {
                runEnd = min(end, o + (fadeOutStart - played))
                table = null
                tableOffset = 0
            } else {
                runEnd = end
                table = fadeOut
                tableOffset = played - fadeOutStart - o
            }
            played += runEnd - o
            if (table == null) {
                while (o < runEnd) {
                    val index = position.toInt()
                    val a = data[base + index].toFloat()
                    mix[o] += (a + (data[base + index + 1] - a) * (position - index).toFloat()) * gain
                    position += step
                    o++
                }
            } else {
                while (o < runEnd) {
                    val index = position.toInt()
                    val a = data[base + index].toFloat()
                    mix[o] += (a + (data[base + index + 1] - a) * (position - index).toFloat()) * gain * table[tableOffset + o]
                    position += step
                    o++
                }
            }
        }
        p.position = position
        p.played = played
    }

    /** Produces the next 10 ms of output into [staged]. */
    private fun emitBlock() {
        mixNextBlock()
        var gain = 1.0
        if (levelControl) {
            val filled = min(blocksMixed, WINDOW_BLOCKS.toLong()).toInt()
            var sum = 0.0
            for (i in 0 until filled) sum += blockPower[i]
            val windowPower = sum / filled
            gain = if (windowPower > 0.0) {
                sqrt(targetPower / windowPower).coerceIn(MIN_CONTROL_GAIN, MAX_CONTROL_GAIN)
            } else {
                MAX_CONTROL_GAIN
            }
        }
        if (lastGain < 0.0) lastGain = gain
        val offset = (blocksEmitted % WINDOW_BLOCKS).toInt() * BLOCK
        val gainStep = (gain - lastGain) / BLOCK
        var g = lastGain
        for (i in 0 until BLOCK) {
            g += gainStep
            val x = ring[offset + i] * g
            val a = abs(x)
            val y = if (a <= LIMITER_KNEE) {
                x
            } else {
                val limited = LIMITER_KNEE + LIMITER_ROOM * tanh((a - LIMITER_KNEE) / LIMITER_ROOM)
                if (x < 0) -limited else limited
            }
            staged[i] = y.roundToInt().toShort()
        }
        lastGain = gain
        blocksEmitted++
    }

    internal companion object {
        private val EMPTY = ShortArray(0)

        /** Internal processing block: 10 ms. */
        const val BLOCK = SAMPLE_RATE / 100

        /** The level window is centred on the block being emitted, so half of it is look-ahead. */
        const val LOOKAHEAD_BLOCKS = 12
        const val WINDOW_BLOCKS = 2 * LOOKAHEAD_BLOCKS + 1

        /** Range of the level-control gain: +-12 dB. */
        private const val MAX_CONTROL_GAIN = 3.981071705534973
        private const val MIN_CONTROL_GAIN = 1.0 / MAX_CONTROL_GAIN

        /** Output limiter: linear up to -6 dBFS, then bent towards a ceiling of 0.95 of full scale. */
        private const val LIMITER_KNEE = 0.5 * 32768.0
        private const val LIMITER_CEILING = 0.95 * 32768.0
        private const val LIMITER_ROOM = LIMITER_CEILING - LIMITER_KNEE

        /** Source samples reserved past the last output sample of a unit. */
        private const val SOURCE_MARGIN = 2

        /** A unit end is moved to the nearest cut point when that is at most 150 ms away. */
        private const val SNAP_SAMPLES = SAMPLE_RATE * 150 / 1000

        private const val MAX_START_TRIES = 4
        private const val MAX_SEGMENT_MS = 60_000
        private const val MAX_SPEED_JITTER = 0.5
    }
}
