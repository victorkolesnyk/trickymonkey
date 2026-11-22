package io.github.victorkolesnyk.hamir.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResamplerTest {
    private fun zeroCrossings(samples: ShortArray, from: Int, to: Int): Int {
        var count = 0
        for (i in from + 1 until to) if ((samples[i - 1] < 0) != (samples[i] < 0)) count++
        return count
    }

    @Test
    fun equalRatesReturnTheSameArray() {
        val input = ShortArray(100) { it.toShort() }
        assertSame(input, Resampler.resample(input, 32000, 32000))
    }

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertEquals(0, Resampler.resample(ShortArray(0), 48000, 32000).size)
    }

    @Test
    fun invalidRatesAreRejected() {
        assertFailsWith<IllegalArgumentException> { Resampler.resample(ShortArray(10), 0, 32000) }
        assertFailsWith<IllegalArgumentException> { Resampler.resample(ShortArray(10), 32000, -1) }
    }

    @Test
    fun outputLengthFollowsTheRateRatio() {
        assertEquals(32000, Resampler.resample(ShortArray(48000), 48000, 32000).size)
        assertEquals(32000, Resampler.resample(ShortArray(44100), 44100, 32000).size)
        assertEquals(64000, Resampler.resample(ShortArray(16000), 8000, 32000).size)
        assertEquals(1, Resampler.resample(ShortArray(1), 44100, 32000).size)
    }

    @Test
    fun toneKeepsLevelAndFrequency() {
        for (from in intArrayOf(8000, 16000, 22050, 44100, 48000, 96000)) {
            val input = TestSignals.sine(1000.0, 1.0, peakDbfs = -6.0, rate = from)
            val output = Resampler.resample(input, from, SAMPLE_RATE)
            // Ignore the filter edges.
            val a = SAMPLE_RATE / 10
            val b = output.size - SAMPLE_RATE / 10
            val level = TestSignals.rmsDb(output, a, b)
            assertTrue(abs(level - (-9.01)) < 0.1, "$from Hz -> 32000 Hz: level $level")
            val crossings = zeroCrossings(output, a, b)
            val expected = 2 * 1000 * (b - a) / SAMPLE_RATE
            assertTrue(abs(crossings - expected) <= 2, "$from Hz -> 32000 Hz: $crossings zero crossings, expected $expected")
        }
    }

    @Test
    fun speechBandSurvivesDownsampling() {
        val input = TestSignals.sine(6000.0, 1.0, peakDbfs = -6.0, rate = 48000)
        val output = Resampler.resample(input, 48000, 16000)
        val level = TestSignals.rmsDb(output, 1600, output.size - 1600)
        assertTrue(abs(level - (-9.01)) < 0.2, "6 kHz at 16 kHz output rate: level $level")
    }

    @Test
    fun frequenciesAboveTheNewNyquistAreSuppressed() {
        // 11 kHz cannot be represented at 16 kHz; without filtering it would alias to 5 kHz at full level.
        val input = TestSignals.sine(11000.0, 1.0, peakDbfs = -6.0, rate = 48000)
        val output = Resampler.resample(input, 48000, 16000)
        val level = TestSignals.rmsDb(output, 1600, output.size - 1600)
        assertTrue(level < -9.0 - 50.0, "alias level $level dBFS")
    }

    @Test
    fun fullScaleInputDoesNotWrapAround() {
        // Square wave at full scale: the filter overshoots at the edges, and an unclamped overshoot
        // would wrap to the opposite sign.
        val halfPeriod = 480
        val input = ShortArray(48000) { if ((it / halfPeriod) % 2 == 0) 32767 else -32768 }
        val output = Resampler.resample(input, 48000, 32000)
        for (i in output.indices) {
            val t = i * 3 / 2
            val positive = (t / halfPeriod) % 2 == 0
            val phase = t % halfPeriod
            if (phase in 4..halfPeriod - 5 && t < input.size - halfPeriod) {
                assertEquals(positive, output[i] > 0, "wrong sign at output sample $i (${output[i]})")
                assertTrue(abs(output[i].toInt()) > 26000, "level at output sample $i is ${output[i]}")
            }
        }
    }
}

class GainTest {
    @Test
    fun unityGainLeavesSamplesUntouched() {
        val buffer = shortArrayOf(0, 1, -1, 32767, -32768, 12345, 30000, -30000)
        val expected = buffer.copyOf()
        Gain.apply(buffer, 0, buffer.size, 0.0)
        assertContentEquals(expected, buffer)
    }

    @Test
    fun attenuationIsLinear() {
        val buffer = shortArrayOf(0, 1000, -1000, 32767, -32768)
        Gain.apply(buffer, 0, buffer.size, -6.0206)
        assertContentEquals(shortArrayOf(0, 500, -500, 16383, -16384), buffer)
        val silent = shortArrayOf(32767, -32768)
        Gain.apply(silent, 0, 2, Double.NEGATIVE_INFINITY)
        assertContentEquals(shortArrayOf(0, 0), silent)
    }

    @Test
    fun amplificationIsLinearBelowTheKnee() {
        val buffer = shortArrayOf(0, 1000, -1000, 6000, -6000, 12000)
        Gain.apply(buffer, 0, buffer.size, 6.0206)
        assertContentEquals(shortArrayOf(0, 2000, -2000, 12000, -12000, 24000), buffer)
    }

    @Test
    fun onlyTheRequestedRangeIsChanged() {
        val buffer = ShortArray(10) { 1000 }
        Gain.apply(buffer, 3, 4, 6.0206)
        assertContentEquals(shortArrayOf(1000, 1000, 1000, 2000, 2000, 2000, 2000, 1000, 1000, 1000), buffer)
        Gain.apply(buffer, 10, 0, 20.0)
        assertFailsWith<IllegalArgumentException> { Gain.apply(buffer, 8, 3, 1.0) }
        assertFailsWith<IllegalArgumentException> { Gain.apply(buffer, -1, 3, 1.0) }
        assertFailsWith<IllegalArgumentException> { Gain.apply(buffer, 0, 3, Double.NaN) }
    }

    @Test
    fun heavyAmplificationNeverWrapsAndStaysMonotonic() {
        for (gainDb in doubleArrayOf(0.5, 3.0, 12.0, 40.0, 200.0, Double.POSITIVE_INFINITY)) {
            val buffer = ShortArray(65536) { (it - 32768).toShort() }
            Gain.apply(buffer, 0, buffer.size, gainDb)
            for (i in buffer.indices) {
                val input = i - 32768
                val output = buffer[i].toInt()
                if (input != 0) assertEquals(input > 0, output > 0, "sign flipped for $input at $gainDb dB")
                if (i > 0) assertTrue(output >= buffer[i - 1], "not monotonic at $input for $gainDb dB")
                assertTrue(abs(output) >= abs(input) || abs(output) >= 24000, "$input was attenuated to $output at $gainDb dB")
            }
        }
    }

    @Test
    fun limiterIsSmoothAtTheKnee() {
        // Amplify a slow ramp by 12 dB: the output slope must fall gradually, with no step.
        val buffer = ShortArray(8000) { it.toShort() }
        Gain.apply(buffer, 0, buffer.size, 12.0)
        var previousSlope = buffer[1] - buffer[0]
        for (i in 2 until buffer.size) {
            val slope = buffer[i] - buffer[i - 1]
            assertTrue(slope in 0..5, "slope $slope at $i")
            assertTrue(abs(slope - previousSlope) <= 2, "slope jumps from $previousSlope to $slope at $i")
            previousSlope = slope
        }
        assertTrue(buffer.last() in 24577..32767)
    }

    @Test
    fun processingInChunksGivesTheSameResult() {
        val whole = TestSignals.whiteNoise(0.5, rmsDbfs = -12.0, seed = 9)
        val chunked = whole.copyOf()
        Gain.apply(whole, 0, whole.size, 9.0)
        var position = 0
        var chunk = 1
        while (position < chunked.size) {
            val count = minOf(chunk, chunked.size - position)
            Gain.apply(chunked, position, count, 9.0)
            position += count
            chunk = chunk * 3 % 251 + 1
        }
        assertContentEquals(whole, chunked)
    }
}

class TakeCheckTest {
    @Test
    fun normalTakeIsAccepted() {
        for (seed in 1L..5L) {
            assertEquals(TakeQuality.OK, TakeCheck.evaluate(TestSignals.utterance(seed).samples), "seed $seed")
        }
    }

    @Test
    fun quietButUsableTakeIsAccepted() {
        // Typical level of the UNPROCESSED audio source: speech around -45 dBFS.
        val take = TestSignals.utterance(3, speechPeakDbfs = -30.0, floorDbfs = -85.0).samples
        assertTrue(Level.activeSpeechDbfs(take) < -40.0)
        assertEquals(TakeQuality.OK, TakeCheck.evaluate(take))
    }

    @Test
    fun emptyAndVeryShortTakesAreTooShort() {
        assertEquals(TakeQuality.TOO_SHORT, TakeCheck.evaluate(ShortArray(0)))
        assertEquals(TakeQuality.TOO_SHORT, TakeCheck.evaluate(TestSignals.sine(300.0, 0.3, peakDbfs = -10.0)))
        // A long recording with only a short word in it is too short as well.
        val word = TestSignals.concat(
            TestSignals.whiteNoise(2.0, -75.0, 1), TestSignals.sine(300.0, 0.4, peakDbfs = -10.0), TestSignals.whiteNoise(2.0, -75.0, 2),
        )
        assertEquals(TakeQuality.TOO_SHORT, TakeCheck.evaluate(word))
    }

    @Test
    fun silentAndFaintTakesAreTooQuiet() {
        assertEquals(TakeQuality.TOO_QUIET, TakeCheck.evaluate(ShortArray(5 * SAMPLE_RATE)))
        assertEquals(TakeQuality.TOO_QUIET, TakeCheck.evaluate(TestSignals.whiteNoise(4.0, rmsDbfs = -70.0, seed = 4)))
        val faint = TestSignals.utterance(2, speechPeakDbfs = -45.0, floorDbfs = -90.0).samples
        assertTrue(Level.activeSpeechDbfs(faint) < TakeCheck.MIN_SPEECH_DBFS)
        assertEquals(TakeQuality.TOO_QUIET, TakeCheck.evaluate(faint))
    }

    @Test
    fun overdrivenTakeIsClipped() {
        val clean = TestSignals.utterance(4).samples
        val overdriven = TestSignals.scaled(clean, 20.0)
        assertEquals(TakeQuality.CLIPPED, TakeCheck.evaluate(overdriven))
        // A single full-scale sample in a long take is tolerated.
        val oneSpike = clean.copyOf()
        oneSpike[oneSpike.size / 2] = 32767
        assertEquals(TakeQuality.OK, TakeCheck.evaluate(oneSpike))
    }

    @Test
    fun sampleRateIsHonoured() {
        val rate = 16000
        val take = TestSignals.utterance(6, rate = rate).samples
        assertEquals(TakeQuality.OK, TakeCheck.evaluate(take, rate))
        // 1 s of tone at 8 kHz would look like 0.25 s if it were taken for 32 kHz audio.
        val tone = TestSignals.sine(300.0, 1.0, peakDbfs = -10.0, rate = 8000)
        assertEquals(TakeQuality.OK, TakeCheck.evaluate(tone, 8000))
        assertEquals(TakeQuality.TOO_SHORT, TakeCheck.evaluate(tone, SAMPLE_RATE))
        assertFailsWith<IllegalArgumentException> { TakeCheck.evaluate(tone, 0) }
    }
}
