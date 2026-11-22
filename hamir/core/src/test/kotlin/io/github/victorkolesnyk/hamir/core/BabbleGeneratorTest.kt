package io.github.victorkolesnyk.hamir.core

import java.util.Random
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BabbleGeneratorTest {
    private val bankA get() = TestSignals.bankA
    private val bankB get() = TestSignals.bankB
    private val emptyBank = VoiceBank.fromUtterances(emptyList())

    /** Checks the level requirements of SPEC section 5 and returns a one-line summary. */
    private fun assertLevels(out: ShortArray, target: Double, what: String, dipLimitDb: Double = 6.0): String {
        val rms = Level.rmsDbfs(out)
        val (lowest, highest) = TestSignals.windowLevelRange(out, 500)
        val summary = "%s: long-term RMS %.2f dBFS (target %.1f), 500 ms windows %.2f..%+.2f dB around it, peak %.2f dBFS".format(
            what, rms, target, lowest - rms, highest - rms, Level.peakDbfs(out),
        )
        println(summary)
        assertTrue(abs(rms - target) <= 1.0, summary)
        assertTrue(lowest >= rms - dipLimitDb, summary)
        assertEquals(0.0, Level.clippedFraction(out), summary)
        assertTrue(Level.peakDbfs(out) < -0.3, summary)
        return summary
    }

    @Test
    fun defaultMixMeetsLevelRequirements() {
        for (seed in longArrayOf(1, 2, 3)) {
            val out = TestSignals.generate(BabbleGenerator(listOf(bankA), BabbleConfig(), Random(seed)), 120.0)
            assertLevels(out, -20.0, "8 voices, one bank, seed $seed")
        }
    }

    @Test
    fun twoSpeakerMixMeetsLevelRequirements() {
        val out = TestSignals.generate(BabbleGenerator(listOf(bankA, bankB), BabbleConfig(), Random(4)), 120.0)
        assertLevels(out, -20.0, "8 voices, two banks")
    }

    @Test
    fun levelRequirementsHoldForSpeechWithWideDynamics() {
        // Harsher material: syllables spread over 25 dB and word gaps just short of the pause limit,
        // which therefore stay in the bank.
        val takes = List(20) { TestSignals.utterance(3000L + it, loudnessRangeDb = 25.0, maxWordGap = 0.145).samples }
        val bank = VoiceBank.fromUtterances(takes)
        val raw = BabbleGenerator(listOf(bank), BabbleConfig(), Random(31)).also { it.levelControl = false }
        val rawOut = TestSignals.generate(raw, 180.0)
        println("wide dynamics, no level control: deepest 500 ms dip %.2f dB".format(Level.rmsDbfs(rawOut) - TestSignals.windowLevelRange(rawOut).first))
        val out = TestSignals.generate(BabbleGenerator(listOf(bank), BabbleConfig(), Random(31)), 180.0)
        assertLevels(out, -20.0, "wide dynamics, 8 voices")
        val four = TestSignals.generate(BabbleGenerator(listOf(bank), BabbleConfig(voices = 4), Random(31)), 180.0)
        assertLevels(four, -20.0, "wide dynamics, 4 voices")
    }

    @Test
    fun otherTargetLevelsAreHonoured() {
        for (target in doubleArrayOf(-30.0, -26.0, -23.0)) {
            val config = BabbleConfig(targetRmsDbfs = target)
            val out = TestSignals.generate(BabbleGenerator(listOf(bankA), config, Random(5)), 60.0)
            assertLevels(out, target, "target $target dBFS")
        }
    }

    @Test
    fun levelRequirementsHoldForOtherVoiceCounts() {
        for (voices in intArrayOf(2, 4, 16)) {
            val out = TestSignals.generate(BabbleGenerator(listOf(bankA, bankB), BabbleConfig(voices = voices), Random(6)), 60.0)
            assertLevels(out, -20.0, "$voices voices")
        }
    }

    @Test
    fun levelControlIsWhatKeepsDipsShallow() {
        // With few voices the raw sum of fragments dips much deeper than the controlled output.
        val config = BabbleConfig(voices = 2)
        val raw = BabbleGenerator(listOf(bankA), config, Random(8)).also { it.levelControl = false }
        val controlled = BabbleGenerator(listOf(bankA), config, Random(8))
        val rawOut = TestSignals.generate(raw, 120.0)
        val controlledOut = TestSignals.generate(controlled, 120.0)
        val rawDip = Level.rmsDbfs(rawOut) - TestSignals.windowLevelRange(rawOut).first
        val controlledDip = Level.rmsDbfs(controlledOut) - TestSignals.windowLevelRange(controlledOut).first
        println("2 voices: deepest 500 ms dip %.2f dB without level control, %.2f dB with it".format(rawDip, controlledDip))
        assertTrue(controlledDip < rawDip - 1.0, "raw dip $rawDip dB, controlled dip $controlledDip dB")
        // The raw mix is already close to the target: the per-voice gains are computed from the bank level.
        assertTrue(abs(Level.rmsDbfs(rawOut) - (-20.0)) < 1.0, "raw level ${Level.rmsDbfs(rawOut)}")
    }

    @Test
    fun streamStartsFromSilenceAtFullLevelWithinHalfASecond() {
        val out = TestSignals.generate(BabbleGenerator(listOf(bankA), BabbleConfig(), Random(9)), 2.0)
        assertTrue(abs(out[0].toInt()) < 100, "first sample ${out[0]}")
        assertTrue(Level.rmsDbfs(out, 0, SAMPLE_RATE / 2) > -21.5, "first 500 ms at ${Level.rmsDbfs(out, 0, SAMPLE_RATE / 2)} dBFS")
    }

    @Test
    fun voicesAreSplitEvenlyBetweenBanks() {
        assertContentEquals(intArrayOf(4, 4), BabbleGenerator(listOf(bankA, bankB), BabbleConfig(), Random(1)).voicesPerBank)
        assertContentEquals(intArrayOf(8), BabbleGenerator(listOf(bankA), BabbleConfig(), Random(1)).voicesPerBank)
        assertContentEquals(intArrayOf(3, 3, 2), BabbleGenerator(listOf(bankA, bankB, bankA), BabbleConfig(), Random(1)).voicesPerBank)
        assertContentEquals(intArrayOf(3, 3), BabbleGenerator(listOf(bankA, bankB), BabbleConfig(voices = 6), Random(1)).voicesPerBank)
        assertContentEquals(intArrayOf(1, 0), BabbleGenerator(listOf(bankA, bankB), BabbleConfig(voices = 1), Random(1)).voicesPerBank)
    }

    @Test
    fun emptyBanksAreIgnored() {
        val generator = BabbleGenerator(listOf(emptyBank, bankA, emptyBank), BabbleConfig(), Random(3))
        assertContentEquals(intArrayOf(8), generator.voicesPerBank)
        assertLevels(TestSignals.generate(generator, 30.0), -20.0, "one bank of three non-empty")
        // Same stream as if the empty banks had not been passed at all.
        val alone = TestSignals.generate(BabbleGenerator(listOf(bankA), BabbleConfig(), Random(3)), 2.0)
        val withEmpty = TestSignals.generate(BabbleGenerator(listOf(bankA, emptyBank), BabbleConfig(), Random(3)), 2.0)
        assertContentEquals(alone, withEmpty)
    }

    @Test
    fun allBanksEmptyIsRejected() {
        assertFailsWith<IllegalArgumentException> { BabbleGenerator(emptyList()) }
        assertFailsWith<IllegalArgumentException> { BabbleGenerator(listOf(emptyBank)) }
        assertFailsWith<IllegalArgumentException> { BabbleGenerator(listOf(emptyBank, emptyBank), BabbleConfig(), Random(1)) }
    }

    @Test
    fun invalidConfigIsRejected() {
        val bad = listOf(
            BabbleConfig(voices = 0),
            BabbleConfig(minSegmentMs = 0),
            BabbleConfig(minSegmentMs = 500, maxSegmentMs = 400),
            BabbleConfig(speedJitter = -0.1),
            BabbleConfig(speedJitter = 0.9),
            BabbleConfig(crossfadeMs = -1),
            BabbleConfig(targetRmsDbfs = Double.NaN),
        )
        for (config in bad) {
            assertFailsWith<IllegalArgumentException>(config.toString()) { BabbleGenerator(listOf(bankA), config, Random(1)) }
        }
    }

    @Test
    fun bothSpeakersContributeEqually() {
        // Speaker A is a 300 Hz tone, speaker B a 1000 Hz tone, recorded at very different levels.
        val low = VoiceBank.fromUtterances(List(4) { TestSignals.sine(300.0, 3.0, peakDbfs = -6.0) })
        val high = VoiceBank.fromUtterances(List(4) { TestSignals.sine(1000.0, 3.0, peakDbfs = -30.0) })
        val out = TestSignals.generate(BabbleGenerator(listOf(low, high), BabbleConfig(), Random(11)), 20.0)
        val lowPower = TestSignals.bandPower(out, 270.0, 330.0)
        val highPower = TestSignals.bandPower(out, 900.0, 1100.0)
        val elsewhere = TestSignals.bandPower(out, 450.0, 700.0)
        val balance = 10.0 * log10(lowPower / highPower)
        println("two tone banks: band power ratio %.2f dB, off-band %.1f dB below".format(balance, 10.0 * log10(lowPower / elsewhere)))
        assertTrue(abs(balance) < 2.0, "speakers differ by $balance dB")
        assertTrue(elsewhere < lowPower / 100.0)
    }

    @Test
    fun sameSeedGivesTheSameStream() {
        val first = TestSignals.generate(BabbleGenerator(listOf(bankA, bankB), BabbleConfig(), Random(42)), 10.0)
        val second = TestSignals.generate(BabbleGenerator(listOf(bankA, bankB), BabbleConfig(), Random(42)), 10.0)
        assertContentEquals(first, second)
        val other = TestSignals.generate(BabbleGenerator(listOf(bankA, bankB), BabbleConfig(), Random(43)), 10.0)
        assertFalse(first.contentEquals(other))
    }

    @Test
    fun streamDoesNotDependOnReadChunkSize() {
        val reference = TestSignals.generate(BabbleGenerator(listOf(bankA), BabbleConfig(), Random(42)), 5.0, chunk = 640)
        for (chunk in intArrayOf(1, 7, 319, 320, 321, 1000, 48000)) {
            val out = TestSignals.generate(BabbleGenerator(listOf(bankA), BabbleConfig(), Random(42)), 5.0, chunk = chunk)
            assertContentEquals(reference, out, "chunk $chunk")
        }
    }

    @Test
    fun secureRandomStreamsDoNotRepeat() {
        val first = TestSignals.generate(BabbleGenerator(listOf(bankA)), 5.0)
        val second = TestSignals.generate(BabbleGenerator(listOf(bankA)), 5.0)
        assertFalse(first.contentEquals(second))
        // Not just shifted or scaled copies: the two streams are uncorrelated.
        var cross = 0.0
        var energy = 0.0
        for (i in first.indices) {
            cross += first[i].toDouble() * second[i]
            energy += first[i].toDouble() * first[i]
        }
        assertTrue(abs(cross / energy) < 0.2, "correlation ${cross / energy}")
        assertLevels(first, -20.0, "SecureRandom stream", dipLimitDb = 6.0)
    }

    @Test
    fun streamDoesNotRepeatItselfOverTime() {
        val out = TestSignals.generate(BabbleGenerator(listOf(bankA), BabbleConfig(), Random(12)), 60.0)
        val block = SAMPLE_RATE
        val seen = HashSet<List<Short>>()
        for (start in 0 until out.size - block step block) {
            assertTrue(seen.add(out.asList().subList(start, start + 2000).toList()), "second $start repeats an earlier one")
        }
    }

    @Test
    fun readHonoursOffsetAndLength() {
        val generator = BabbleGenerator(listOf(bankA), BabbleConfig(), Random(42))
        val buffer = ShortArray(3000) { 12345 }
        generator.read(buffer, 1000, 1500)
        for (i in 0 until 1000) assertEquals(12345, buffer[i].toInt())
        for (i in 2500 until 3000) assertEquals(12345, buffer[i].toInt())
        val reference = TestSignals.generate(BabbleGenerator(listOf(bankA), BabbleConfig(), Random(42)), 1.0)
        assertContentEquals(reference.copyOfRange(0, 1500), buffer.copyOfRange(1000, 2500))
        generator.read(buffer, 3000, 0)
        generator.read(buffer, 0, 0)
        // The default arguments fill the rest of the array.
        val tail = ShortArray(500)
        generator.read(tail, 100)
        assertContentEquals(reference.copyOfRange(1500, 1900), tail.copyOfRange(100, 500))
        assertFailsWith<IllegalArgumentException> { generator.read(buffer, 2000, 1001) }
        assertFailsWith<IllegalArgumentException> { generator.read(buffer, -1, 10) }
        assertFailsWith<IllegalArgumentException> { generator.read(buffer, 0, -1) }
    }

    @Test
    fun unitsFollowTheConfiguredLengthAndSpeed() {
        val config = BabbleConfig(minSegmentMs = 300, maxSegmentMs = 900, speedJitter = 0.1)
        val generator = BabbleGenerator(listOf(bankA), config, Random(13))
        val lengths = ArrayList<Int>()
        val speeds = ArrayList<Double>()
        val starts = HashSet<Int>()
        val perVoice = IntArray(8)
        generator.onUnitStart = { voice, start, length, speed ->
            lengths.add(length)
            speeds.add(speed)
            starts.add(start)
            perVoice[voice]++
        }
        TestSignals.generate(generator, 120.0)
        assertTrue(lengths.size > 1000, "${lengths.size} units")
        val minOut = 300 * 32
        val maxOut = 900 * 32
        assertTrue(lengths.all { it in minOut - 1..maxOut + 1 }, "lengths ${lengths.min()}..${lengths.max()}")
        assertTrue(lengths.min() < minOut + 3200 && lengths.max() > maxOut - 3200, "lengths ${lengths.min()}..${lengths.max()}")
        assertTrue(speeds.all { it in 0.9..1.1 })
        assertTrue(speeds.min() < 0.92 && speeds.max() > 1.08, "speeds ${speeds.min()}..${speeds.max()}")
        assertTrue(perVoice.all { it > 100 }, perVoice.contentToString())
        // Units start on cut points and use most of the bank.
        val cuts = bankA.cuts.toHashSet()
        assertTrue(starts.all { it in cuts })
        assertTrue(starts.size > bankA.segmentCount / 2, "${starts.size} distinct starts of ${bankA.segmentCount} fragments")
    }

    @Test
    fun zeroJitterPlaysAtOriginalSpeed() {
        val generator = BabbleGenerator(listOf(bankA), BabbleConfig(speedJitter = 0.0), Random(14))
        val speeds = HashSet<Double>()
        generator.onUnitStart = { _, _, _, speed -> speeds.add(speed) }
        assertLevels(TestSignals.generate(generator, 30.0), -20.0, "no speed jitter")
        assertEquals(setOf(1.0), speeds)
    }

    @Test
    fun fixedSegmentLengthIsAccepted() {
        val generator = BabbleGenerator(listOf(bankA), BabbleConfig(minSegmentMs = 500, maxSegmentMs = 500), Random(15))
        val lengths = HashSet<Int>()
        generator.onUnitStart = { _, _, length, _ -> lengths.add(length) }
        assertLevels(TestSignals.generate(generator, 30.0), -20.0, "fixed 500 ms units")
        assertTrue(lengths.all { abs(it - 16000) <= 1 }, lengths.toString())
    }

    @Test
    fun voicesStartStaggered() {
        val generator = BabbleGenerator(listOf(bankA), BabbleConfig(), Random(16))
        val boundaries = generator.samplesToNextBoundary()
        assertEquals(8, boundaries.size)
        assertTrue(boundaries.all { it > 0 })
        val sorted = boundaries.sorted()
        // No two voices reach their first join within the same crossfade.
        for (i in 1 until sorted.size) {
            assertTrue(sorted[i] - sorted[i - 1] > 640, "boundaries $sorted")
        }
        assertTrue(sorted.last() - sorted.first() > SAMPLE_RATE / 4, "boundaries $sorted")
    }

    @Test
    fun unitBoundariesOfDifferentVoicesRarelyCoincide() {
        val generator = BabbleGenerator(listOf(bankA), BabbleConfig(), Random(17))
        var samples = 0L
        val joins = ArrayList<Long>()
        generator.onUnitStart = { _, _, _, _ -> joins.add(samples) }
        val buffer = ShortArray(320)
        repeat(100 * 120) {
            generator.read(buffer)
            samples += buffer.size
        }
        // Joins are recorded with 10 ms resolution; if all voices switched together there would be
        // eight joins per instant instead of one.
        val distinct = joins.toSet().size
        assertTrue(joins.size > 500, "${joins.size} joins")
        assertTrue(distinct > joins.size * 0.9, "$distinct distinct instants for ${joins.size} joins")
    }

    private fun largestStep(out: ShortArray): Int {
        var largest = 0
        for (i in 1 until out.size) largest = maxOf(largest, abs(out[i] - out[i - 1]))
        return largest
    }

    @Test
    fun joinsAreClickFree() {
        // One voice built from a pure tone: the waveform is smooth everywhere, so any click at a
        // join shows up as a sample-to-sample step far above the slope of the tone itself.
        val tone = VoiceBank.fromUtterances(List(3) { TestSignals.sine(300.0, 4.0, peakDbfs = -10.0) })
        val config = BabbleConfig(voices = 1, minSegmentMs = 200, maxSegmentMs = 600)
        val generator = BabbleGenerator(listOf(tone), config, Random(18))
        var joins = 0
        generator.onUnitStart = { _, _, _, _ -> joins++ }
        val out = TestSignals.generate(generator, 60.0)
        val peak = out.maxOf { abs(it.toInt()) }
        val step = largestStep(out)
        // A 300 Hz tone sped up by 6% moves at most 6.3% of its amplitude per sample.
        println("click test: %d joins, peak %d, largest step %d (%.1f%% of peak)".format(joins, peak, step, 100.0 * step / peak))
        assertTrue(joins > 100)
        assertTrue(step < 0.12 * peak, "largest step $step with peak $peak")

        // The same measurement does detect clicks when the crossfade is switched off.
        val hard = TestSignals.generate(BabbleGenerator(listOf(tone), config.copy(crossfadeMs = 0), Random(18)), 60.0)
        val hardStep = largestStep(hard)
        println("click test without crossfade: largest step %d (%.1f%% of peak)".format(hardStep, 100.0 * hardStep / peak))
        assertTrue(hardStep > 0.5 * peak, "hard cuts gave a largest step of only $hardStep")
    }

    @Test
    fun tinyBankStillProducesBabble() {
        // One short take: far less material than a single unit needs.
        val take = TestSignals.concat(TestSignals.silence(0.3), TestSignals.sine(250.0, 0.5, peakDbfs = -12.0), TestSignals.silence(0.3))
        val bank = VoiceBank.fromUtterances(listOf(take))
        assertTrue(bank.segmentCount > 0)
        assertTrue(bank.speechSeconds < 0.6)
        val out = TestSignals.generate(BabbleGenerator(listOf(bank), BabbleConfig(), Random(19)), 30.0)
        assertLevels(out, -20.0, "0.5 s bank")
        // And together with a normal bank.
        val mixed = TestSignals.generate(BabbleGenerator(listOf(bank, bankA), BabbleConfig(), Random(19)), 30.0)
        assertLevels(mixed, -20.0, "0.5 s bank + normal bank")
    }

    @Test
    fun singleTakeBankWorks() {
        val bank = VoiceBank.fromUtterances(listOf(TestSignals.utterance(700).samples))
        assertTrue(bank.segmentCount > 0)
        val out = TestSignals.generate(BabbleGenerator(listOf(bank), BabbleConfig(), Random(20)), 60.0)
        assertLevels(out, -20.0, "single take bank")
    }

    @Test
    fun extremeConfigsDoNotBreakTheStream() {
        val configs = listOf(
            BabbleConfig(voices = 1),
            BabbleConfig(voices = 32),
            BabbleConfig(minSegmentMs = 1, maxSegmentMs = 5),
            BabbleConfig(minSegmentMs = 20, maxSegmentMs = 60, crossfadeMs = 100),
            BabbleConfig(minSegmentMs = 5000, maxSegmentMs = 20000),
            BabbleConfig(speedJitter = 0.5),
            BabbleConfig(crossfadeMs = 0),
            BabbleConfig(targetRmsDbfs = -3.0),
            BabbleConfig(targetRmsDbfs = 20.0),
            BabbleConfig(targetRmsDbfs = -100.0),
        )
        for (config in configs) {
            val out = TestSignals.generate(BabbleGenerator(listOf(bankA), config, Random(21)), 10.0)
            assertEquals(0.0, Level.clippedFraction(out), config.toString())
            assertTrue(Level.peakDbfs(out) < -0.3, "$config peak ${Level.peakDbfs(out)}")
            // Units of a few milliseconds are excluded: they all start on energy minima of the
            // bank, so their mix is quieter than the level control can make up for.
            if (config.targetRmsDbfs in -40.0..-10.0 && config.minSegmentMs >= 20) {
                assertTrue(abs(Level.rmsDbfs(out) - config.targetRmsDbfs) < 1.5, "$config level ${Level.rmsDbfs(out)}")
            }
        }
    }

    @Test
    fun arbitraryInputAndConfigNeverBreakBankOrGenerator() {
        // The generator runs on an audio thread: whatever was recorded, it must neither throw nor wrap.
        val random = Random(2024)
        var generators = 0
        var emptyBanks = 0
        repeat(400) { round ->
            val takes = List(random.nextInt(5)) {
                val length = when (random.nextInt(4)) {
                    0 -> random.nextInt(400)
                    1 -> random.nextInt(9000)
                    else -> random.nextInt(60000)
                }
                val amplitude = when (random.nextInt(4)) {
                    0 -> 0.0
                    1 -> 3.0
                    2 -> 1000.0
                    else -> 40000.0
                }
                val burst = 1 + random.nextInt(8000)
                val kind = random.nextInt(3)
                ShortArray(length) { i ->
                    val v = when (kind) {
                        0 -> random.nextGaussian() * amplitude
                        1 -> if ((i / burst) % 2 == 0) amplitude * Math.sin(i * 0.05) else 0.0
                        else -> amplitude
                    }
                    v.toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            val rate = intArrayOf(8000, 16000, 32000, 44100, 48000)[random.nextInt(5)]
            val bank = VoiceBank.fromUtterances(takes, rate)
            assertTrue(bank.segmentCount >= 0 && bank.speechSeconds >= 0.0)
            assertEquals(bank.segmentCount == 0, bank.speechSeconds == 0.0, "round $round")
            if (bank.segmentCount == 0) {
                emptyBanks++
                return@repeat
            }
            assertEquals(0.0, Level.clippedFraction(bank.data), "round $round")
            val minSegment = 1 + random.nextInt(600)
            val config = BabbleConfig(
                voices = 1 + random.nextInt(10),
                minSegmentMs = minSegment,
                maxSegmentMs = minSegment + random.nextInt(2500),
                speedJitter = random.nextDouble() * 0.5,
                crossfadeMs = random.nextInt(100),
                targetRmsDbfs = -40.0 + 30.0 * random.nextDouble(),
            )
            val generator = BabbleGenerator(listOf(bank), config, Random(round.toLong()))
            val out = TestSignals.generate(generator, 1.5, chunk = 1 + random.nextInt(2000))
            assertEquals(0.0, Level.clippedFraction(out), "round $round $config")
            assertTrue(Level.peakDbfs(out) < -0.3, "round $round $config peak ${Level.peakDbfs(out)}")
            generators++
        }
        println("fuzz: $generators generators run, $emptyBanks empty banks")
        assertTrue(generators > 100 && emptyBanks > 20, "$generators generators, $emptyBanks empty banks")
    }

    @Test
    fun generationIsMuchFasterThanRealTime() {
        val generator = BabbleGenerator(listOf(bankA, bankB), BabbleConfig(), Random(22))
        val buffer = ShortArray(640)
        repeat(50 * 60) { generator.read(buffer) }
        val seconds = 300
        val started = System.nanoTime()
        repeat(50 * seconds) { generator.read(buffer) }
        val elapsed = (System.nanoTime() - started) / 1e9
        val factor = seconds / elapsed
        println("performance: %d s of 8-voice babble in %.3f s = %.0fx real time".format(seconds, elapsed, factor))
        assertTrue(factor >= 20.0, "only ${factor}x real time")
    }

    @Test
    fun readDoesNotAllocate() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        val generator = BabbleGenerator(listOf(bankA, bankB), BabbleConfig(), Random(23))
        val buffer = ShortArray(640)
        repeat(50 * 30) { generator.read(buffer) }
        @Suppress("DEPRECATION")
        val thread = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(50 * 60) { generator.read(buffer) }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        println("allocation: $allocated bytes for 60 s of audio (${60 * SAMPLE_RATE} samples)")
        assertTrue(allocated < 16 * 1024, "$allocated bytes allocated while generating 60 s")
    }
}
