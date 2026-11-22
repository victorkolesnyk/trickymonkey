package io.github.victorkolesnyk.hamir.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VoiceBankTest {
    private fun assertEmpty(bank: VoiceBank, what: String) {
        assertEquals(0, bank.segmentCount, what)
        assertEquals(0.0, bank.speechSeconds, what)
    }

    @Test
    fun emptyShortAndSilentInputGiveAnEmptyBank() {
        assertEmpty(VoiceBank.fromUtterances(emptyList()), "no takes")
        assertEmpty(VoiceBank.fromUtterances(listOf(ShortArray(0))), "empty take")
        assertEmpty(VoiceBank.fromUtterances(listOf(ShortArray(0), ShortArray(1), ShortArray(7))), "tiny takes")
        assertEmpty(VoiceBank.fromUtterances(listOf(ShortArray(5 * SAMPLE_RATE))), "digital silence")
        assertEmpty(VoiceBank.fromUtterances(listOf(TestSignals.sine(300.0, 0.05, -10.0))), "50 ms take")
        assertEmpty(VoiceBank.fromUtterances(listOf(TestSignals.sine(300.0, 0.1, -10.0))), "100 ms take")
        assertEmpty(VoiceBank.fromUtterances(listOf(ShortArray(3), ShortArray(0)), 8000), "tiny takes at 8 kHz")
        // A lone click in silence is not speech.
        val click = ShortArray(2 * SAMPLE_RATE)
        for (i in 0 until 64) click[SAMPLE_RATE + i] = 20000
        assertEmpty(VoiceBank.fromUtterances(listOf(click)), "click")
    }

    @Test
    fun invalidSampleRateIsRejected() {
        assertFailsWith<IllegalArgumentException> { VoiceBank.fromUtterances(emptyList(), 0) }
    }

    @Test
    fun edgeSilenceAndLongPausesAreRemoved() {
        val utterances = List(6) { TestSignals.utterance(300L + it) }
        val bank = VoiceBank.fromUtterances(utterances.map { it.samples })
        val total = utterances.sumOf { it.samples.size } / SAMPLE_RATE.toDouble()
        val voiced = utterances.sumOf { it.voicedSeconds }
        val edges = utterances.sumOf { it.leadSeconds + it.tailSeconds }
        assertTrue(bank.segmentCount > 0)
        // Everything that was voiced is kept, the lead-in and lead-out silence is gone.
        assertTrue(bank.speechSeconds >= voiced, "kept ${bank.speechSeconds} s of $voiced s voiced")
        assertTrue(bank.speechSeconds <= total - 0.9 * edges, "kept ${bank.speechSeconds} s of $total s with $edges s of edge silence")
        // No stretch of silence as long as a pause survives inside the bank.
        val frame = SAMPLE_RATE / 100
        var quietRun = 0
        var longestQuietRun = 0
        val threshold = TestSignals.amplitude(VoiceBank.REFERENCE_DBFS - 35.0)
        for (f in 0 until bank.data.size / frame) {
            var sum = 0.0
            for (i in f * frame until (f + 1) * frame) sum += bank.data[i].toDouble() * bank.data[i]
            if (Math.sqrt(sum / frame) < threshold) quietRun++ else quietRun = 0
            if (quietRun > longestQuietRun) longestQuietRun = quietRun
        }
        assertTrue(longestQuietRun * 10 < VoiceBank.PAUSE_MS + 60, "a quiet stretch of ${longestQuietRun * 10} ms survived")
    }

    @Test
    fun explicitLongPauseIsCutOut() {
        val first = TestSignals.sine(220.0, 1.0, peakDbfs = -12.0)
        val second = TestSignals.sine(330.0, 1.0, peakDbfs = -12.0)
        val take = TestSignals.concat(TestSignals.silence(0.7), first, TestSignals.silence(1.5), second, TestSignals.silence(0.4))
        val bank = VoiceBank.fromUtterances(listOf(take))
        // 2 s of tone plus a few 10 ms frames of padding around each of the two stretches.
        assertTrue(bank.speechSeconds in 2.0..2.16, "speech seconds ${bank.speechSeconds}")
        assertTrue(bank.segmentCount >= 6, "segments ${bank.segmentCount}")
        // The splice between the two tones is a cut point.
        val splice = bank.data.size / 2
        assertTrue(bank.cuts.any { abs(it - splice) <= SAMPLE_RATE / 50 }, "no cut near the removed pause")
    }

    @Test
    fun everyTakeIsNormalisedToTheSameLevel() {
        val takes = List(8) { TestSignals.utterance(400L + it).samples }
        val reference = VoiceBank.fromUtterances(takes)
        // The same takes recorded 18 dB quieter, and with levels all over the place.
        val quiet = VoiceBank.fromUtterances(takes.map { TestSignals.scaled(it, -18.0) })
        val uneven = VoiceBank.fromUtterances(takes.mapIndexed { i, t -> TestSignals.scaled(t, -3.0 * i) })
        val level = Level.rmsDbfs(reference.data)
        assertTrue(abs(Level.rmsDbfs(quiet.data) - level) < 0.3, "quiet bank ${Level.rmsDbfs(quiet.data)} vs $level")
        assertTrue(abs(Level.rmsDbfs(uneven.data) - level) < 0.3, "uneven bank ${Level.rmsDbfs(uneven.data)} vs $level")
        assertTrue(abs(quiet.speechSeconds - reference.speechSeconds) < 0.5)
        val active = Level.activeSpeechDbfs(reference.data)
        assertTrue(abs(active - VoiceBank.REFERENCE_DBFS) < 1.0, "active level $active")
        assertEquals(0.0, Level.clippedFraction(reference.data))

        // Per take, not only on average: the loudest and the quietest second of the uneven bank are close.
        val second = SAMPLE_RATE
        var lowest = Double.MAX_VALUE
        var highest = -Double.MAX_VALUE
        var start = 0
        while (start + 2 * second <= uneven.data.size) {
            val l = Level.rmsDbfs(uneven.data, start, 2 * second)
            lowest = minOf(lowest, l)
            highest = maxOf(highest, l)
            start += second
        }
        assertTrue(highest - lowest < 6.0, "2 s levels range from $lowest to $highest dBFS")
    }

    @Test
    fun hotTakeIsNotClippedByNormalisation() {
        // Very peaky signal: one loud spike in an otherwise quiet tone. Normalising the active level
        // to the reference would push the spikes over full scale.
        val take = TestSignals.sine(200.0, 2.0, peakDbfs = -50.0)
        for (k in 0 until 20) take[1000 + k * 3000] = 30000
        val bank = VoiceBank.fromUtterances(listOf(take))
        assertTrue(bank.segmentCount > 0)
        assertEquals(0.0, Level.clippedFraction(bank.data))
        assertTrue(Level.peakDbfs(bank.data) <= -0.9)
    }

    @Test
    fun dcOffsetIsRemoved() {
        val take = TestSignals.utterance(77).samples
        val shifted = ShortArray(take.size) { (take[it] + 3000).toShort() }
        val bank = VoiceBank.fromUtterances(listOf(shifted))
        assertTrue(bank.segmentCount > 0)
        val mean = bank.data.sumOf { it.toInt() }.toDouble() / bank.data.size
        assertTrue(abs(mean) < 20.0, "mean $mean")
        val clean = VoiceBank.fromUtterances(listOf(take))
        assertTrue(abs(bank.speechSeconds - clean.speechSeconds) < 0.2)
    }

    @Test
    fun cutPointsAreOrderedAndFragmentsHaveSpeechLikeLength() {
        val bank = TestSignals.bankA
        val cuts = bank.cuts
        assertEquals(0, cuts.first())
        assertEquals(bank.data.size, cuts.last())
        assertEquals(cuts.size - 1, bank.segmentCount)
        var longest = 0
        for (i in 1 until cuts.size) {
            val length = cuts[i] - cuts[i - 1]
            assertTrue(length > 0, "cut points must ascend")
            longest = maxOf(longest, length)
        }
        assertTrue(longest <= SAMPLE_RATE * 320 / 1000, "longest fragment ${longest / 32} ms")
        val average = bank.data.size / bank.segmentCount / 32
        assertTrue(average in 120..320, "average fragment $average ms")
    }

    @Test
    fun cutPointsLieOnEnergyMinima() {
        val bank = TestSignals.bankA
        val data = bank.data
        val half = SAMPLE_RATE / 100
        fun power(centre: Int): Double {
            val from = maxOf(0, centre - half)
            val to = minOf(data.size, centre + half)
            var sum = 0.0
            for (i in from until to) sum += data[i].toDouble() * data[i]
            return sum / (to - from)
        }
        var atCuts = 0.0
        for (i in 1 until bank.cuts.size - 1) atCuts += power(bank.cuts[i])
        atCuts /= bank.cuts.size - 2
        val overall = data.sumOf { it.toDouble() * it } / data.size
        val difference = 10.0 * log10(overall / atCuts)
        println("VoiceBank: mean power in 20 ms around cut points is %.1f dB below the bank average".format(difference))
        assertTrue(difference > 6.0, "cut points are only $difference dB below average")
    }

    @Test
    fun otherSampleRatesAreResampled() {
        val rate = 16000
        val takes = List(4) { TestSignals.utterance(500L + it, rate = rate).samples }
        val bank = VoiceBank.fromUtterances(takes, rate)
        val same = VoiceBank.fromUtterances(takes.map { Resampler.resample(it, rate, SAMPLE_RATE) })
        assertTrue(bank.segmentCount > 0)
        assertEquals(same.segmentCount, bank.segmentCount)
        assertEquals(same.speechSeconds, bank.speechSeconds)
        // Reading 16 kHz audio as if it were 32 kHz would halve the duration.
        val wrong = VoiceBank.fromUtterances(takes)
        assertTrue(wrong.speechSeconds < 0.6 * bank.speechSeconds)
    }

    @Test
    fun unusableTakesAmongGoodOnesAreSkipped() {
        val good = List(3) { TestSignals.utterance(600L + it).samples }
        val bank = VoiceBank.fromUtterances(good)
        val mixed = VoiceBank.fromUtterances(listOf(ShortArray(0), good[0], ShortArray(SAMPLE_RATE), good[1], ShortArray(5), good[2]))
        assertEquals(bank.segmentCount, mixed.segmentCount)
        assertEquals(bank.speechSeconds, mixed.speechSeconds)
    }

    @Test
    fun nearestCutAndCutsUpToAgreeWithLinearSearch() {
        val bank = TestSignals.bankA
        val cuts = bank.cuts
        for (position in intArrayOf(0, 1, cuts[1] - 1, cuts[1], cuts[1] + 1, cuts[5] + 17, bank.data.size - 1, bank.data.size)) {
            val expectedNearest = cuts.indices.minByOrNull { abs(cuts[it] - position) }!!
            assertEquals(abs(cuts[expectedNearest] - position), abs(cuts[bank.nearestCut(position)] - position), "nearestCut($position)")
            assertEquals(cuts.count { it <= position }, bank.cutsUpTo(position), "cutsUpTo($position)")
        }
    }
}
