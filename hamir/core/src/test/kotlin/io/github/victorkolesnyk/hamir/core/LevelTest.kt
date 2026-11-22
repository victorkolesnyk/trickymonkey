package io.github.victorkolesnyk.hamir.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LevelTest {
    private fun assertNear(expected: Double, actual: Double, tolerance: Double, message: String = "") {
        assertTrue(abs(expected - actual) <= tolerance, "$message expected $expected +-$tolerance but was $actual")
    }

    @Test
    fun rmsOfFullScaleSquareIsZeroDbfs() {
        assertEquals(0.0, Level.rmsDbfs(ShortArray(1000) { Short.MIN_VALUE }), 1e-9)
        assertNear(0.0, Level.rmsDbfs(ShortArray(1000) { if (it % 2 == 0) 32767 else -32767 }), 0.001)
    }

    @Test
    fun rmsOfSineIsThreeDbBelowItsPeak() {
        val sine = TestSignals.sine(1000.0, 1.0, peakDbfs = -10.0)
        assertNear(-13.01, Level.rmsDbfs(sine), 0.02)
        assertNear(-10.0, Level.peakDbfs(sine), 0.02)
    }

    @Test
    fun silenceAndEmptyInputGiveSilenceLevel() {
        assertEquals(SILENCE_DBFS, Level.rmsDbfs(ShortArray(0)))
        assertEquals(SILENCE_DBFS, Level.rmsDbfs(ShortArray(3200)))
        assertEquals(SILENCE_DBFS, Level.peakDbfs(ShortArray(0)))
        assertEquals(SILENCE_DBFS, Level.peakDbfs(ShortArray(3200)))
        assertEquals(SILENCE_DBFS, Level.activeSpeechDbfs(ShortArray(0)))
        assertEquals(SILENCE_DBFS, Level.activeSpeechDbfs(ShortArray(32000)))
        assertEquals(0.0, Level.clippedFraction(ShortArray(0)))
        assertEquals(0.0, Level.clippedFraction(ShortArray(100)))
    }

    @Test
    fun rmsAndPeakHonourOffsetAndLength() {
        val samples = ShortArray(300)
        for (i in 100 until 200) samples[i] = 16384
        samples[250] = -32768
        assertNear(-6.02, Level.rmsDbfs(samples, 100, 100), 0.01)
        assertNear(-6.02, Level.peakDbfs(samples, 100, 100), 0.01)
        assertEquals(SILENCE_DBFS, Level.rmsDbfs(samples, 0, 100))
        assertEquals(0.0, Level.peakDbfs(samples, 200), 1e-9)
        assertEquals(SILENCE_DBFS, Level.rmsDbfs(samples, 300))
        assertEquals(1.0 / 50, Level.clippedFraction(samples, 225, 50), 1e-12)
    }

    @Test
    fun invalidRangesAreRejected() {
        val samples = ShortArray(10)
        assertFailsWith<IllegalArgumentException> { Level.rmsDbfs(samples, 5, 6) }
        assertFailsWith<IllegalArgumentException> { Level.rmsDbfs(samples, -1, 2) }
        assertFailsWith<IllegalArgumentException> { Level.peakDbfs(samples, 11) }
        assertFailsWith<IllegalArgumentException> { Level.clippedFraction(samples, 0, -1) }
        assertFailsWith<IllegalArgumentException> { Level.activeSpeechDbfs(samples, 0) }
    }

    @Test
    fun peakOfSingleSample() {
        assertEquals(0.0, Level.peakDbfs(shortArrayOf(0, Short.MIN_VALUE, 0)), 1e-9)
        assertNear(-90.31, Level.peakDbfs(shortArrayOf(0, 1, 0)), 0.01)
    }

    @Test
    fun clippedFractionCountsSamplesWithinOnePercentOfFullScale() {
        val samples = shortArrayOf(0, 32767, -32768, 32440, -32440, 32439, -32439, 1000, -1000, 0)
        assertEquals(0.4, Level.clippedFraction(samples), 1e-12)
        assertEquals(1.0, Level.clippedFraction(ShortArray(64) { 32767 }), 1e-12)
    }

    @Test
    fun activeSpeechIgnoresPauses() {
        // 1 s of tone, 3 s of silence: plain RMS is 6 dB lower than the level while "speaking".
        val tone = TestSignals.sine(300.0, 1.0, peakDbfs = -17.0)
        val signal = TestSignals.concat(TestSignals.silence(1.5), tone, TestSignals.silence(1.5))
        assertNear(-20.0, Level.activeSpeechDbfs(signal), 0.3)
        assertNear(-26.0, Level.rmsDbfs(signal), 0.3)
    }

    @Test
    fun activeSpeechIgnoresPausesFilledWithRoomNoise() {
        val rate = SAMPLE_RATE
        val noise = TestSignals.whiteNoise(6.0, rmsDbfs = -55.0, seed = 3)
        val signal = noise.copyOf()
        val tone = TestSignals.sine(250.0, 2.0, peakDbfs = -27.0)
        for (i in tone.indices) signal[2 * rate + i] = (signal[2 * rate + i] + tone[i]).toShort()
        assertNear(-30.0, Level.activeSpeechDbfs(signal), 0.3)
        assertTrue(Level.rmsDbfs(signal) < -34.0)
    }

    @Test
    fun activeSpeechOfSteadySignalEqualsItsRms() {
        val noise = TestSignals.whiteNoise(3.0, rmsDbfs = -35.0, seed = 5)
        assertNear(Level.rmsDbfs(noise), Level.activeSpeechDbfs(noise), 0.1)
        val tone = TestSignals.sine(440.0, 2.0, peakDbfs = -12.0)
        assertNear(Level.rmsDbfs(tone), Level.activeSpeechDbfs(tone), 0.1)
    }

    @Test
    fun activeSpeechOfSyntheticSpeechIsAboveRmsAndIndependentOfPauseLength() {
        val utterance = TestSignals.utterance(11).samples
        val active = Level.activeSpeechDbfs(utterance)
        assertTrue(active > Level.rmsDbfs(utterance) + 1.0, "active $active should exceed rms ${Level.rmsDbfs(utterance)}")
        val padded = TestSignals.concat(TestSignals.silence(10.0), utterance, TestSignals.silence(10.0))
        assertNear(active, Level.activeSpeechDbfs(padded), 0.3)
        assertTrue(Level.rmsDbfs(padded) < Level.rmsDbfs(utterance) - 3.0)
    }

    @Test
    fun activeSpeechIgnoresIsolatedLoudClicks() {
        // A few taps on the case, 25 dB above the speech, must not be mistaken for the speech level.
        val utterance = TestSignals.utterance(12, speechPeakDbfs = -30.0).samples
        val expected = Level.activeSpeechDbfs(utterance)
        val tapped = utterance.copyOf()
        for (tap in 0 until 3) {
            val at = (tap + 1) * tapped.size / 4
            for (i in 0 until 160) tapped[at + i] = if (i % 2 == 0) 20000 else -20000
        }
        assertTrue(Level.peakDbfs(tapped) > -5.0)
        assertNear(expected, Level.activeSpeechDbfs(tapped), 1.0)
    }

    @Test
    fun activeSpeechFollowsSparseSpeech() {
        // Half a second of speech-level tone in 20 s of room noise is still measured at its own level.
        val signal = TestSignals.whiteNoise(20.0, rmsDbfs = -60.0, seed = 8)
        val tone = TestSignals.sine(250.0, 0.5, peakDbfs = -27.0)
        for (i in tone.indices) signal[7 * SAMPLE_RATE + i] = (signal[7 * SAMPLE_RATE + i] + tone[i]).toShort()
        assertNear(-30.0, Level.activeSpeechDbfs(signal), 0.3)
    }

    private fun describe(errors: List<Double>): String {
        val sorted = errors.sorted()
        fun q(x: Double) = sorted[((sorted.size - 1) * x).toInt()]
        return "n=%d min %+.2f, 5%% %+.2f, median %+.2f, 95%% %+.2f, max %+.2f dB".format(
            sorted.size, sorted.first(), q(0.05), q(0.5), q(0.95), sorted.last(),
        )
    }

    @Test
    fun activeSpeechMatchesTheKnownSpeechLevelOverAmbientNoise() {
        // Regression for an under-read of up to 6 dB: with ambient noise 10..16 dB below the speech
        // the ambient frames used to be taken for speech. 300 randomised 8 s buffers, speech in
        // 20..80% of each, stationary noise 10..30 dB below the speech level.
        for (model in TestSignals.SpeechModel.values()) {
            val errors = ArrayList<Double>()
            val errorsAgainstSoundingOnly = ArrayList<Double>()
            var lowestShare = 1.0
            var highestShare = 0.0
            var closestAmbient = 100.0
            for (seed in 0L until 100L) {
                val case = TestSignals.speechInNoise(seed, model)
                val measured = Level.activeSpeechDbfs(case.samples)
                val below = case.speechDbfs - case.ambientDbfs
                val what = "$model seed $seed: speech ${case.speechDbfs} dBFS in ${case.speechShare} of the buffer, ambient $below dB below, measured $measured"
                assertTrue(abs(measured - case.speechDbfs) <= 1.5, what)
                assertTrue(abs(measured - case.soundingDbfs) <= 1.5, "$what, sounding-only level ${case.soundingDbfs}")
                errors.add(measured - case.speechDbfs)
                errorsAgainstSoundingOnly.add(measured - case.soundingDbfs)
                lowestShare = minOf(lowestShare, case.speechShare)
                highestShare = maxOf(highestShare, case.speechShare)
                closestAmbient = minOf(closestAmbient, below)
            }
            // The cases really cover the range.
            assertTrue(lowestShare < 0.25 && highestShare > 0.75 && closestAmbient < 10.5)
            println("activeSpeechDbfs error, $model: ${describe(errors)}")
            if (model == TestSignals.SpeechModel.SYLLABLES) {
                println("activeSpeechDbfs error, $model, against the level of the syllables without the gaps between them: ${describe(errorsAgainstSoundingOnly)}")
            }
        }
    }

    @Test
    fun activeSpeechIsNotDilutedByAmbientJustBelowTheSpeech() {
        // The worst region of the old defect: ambient exactly 10, 12, 14 and 16 dB below the speech.
        for (below in doubleArrayOf(10.0, 12.0, 14.0, 16.0)) {
            val errors = ArrayList<Double>()
            for (seed in 1000L until 1050L) {
                val model = if (seed % 2 == 0L) TestSignals.SpeechModel.MODULATED_NOISE else TestSignals.SpeechModel.FLAT_TONE
                val case = TestSignals.speechInNoise(seed, model, ambientBelowDb = below)
                val measured = Level.activeSpeechDbfs(case.samples)
                // Speech and noise sound together, so reading up to 0.41 dB high at 10 dB is physical.
                assertTrue(measured - case.speechDbfs in -0.5..1.0, "ambient $below dB below, seed $seed: error ${measured - case.speechDbfs}")
                // What the application derives from it: speech over ambient, for its 10 dB gate.
                val ambient = Level.rmsDbfs(TestSignals.whiteNoise(2.0, case.ambientDbfs, seed))
                assertTrue(abs((measured - ambient) - below) <= 1.0, "speech over ambient ${measured - ambient}, true $below")
                errors.add(measured - case.speechDbfs)
            }
            println("activeSpeechDbfs error with ambient $below dB below speech: ${describe(errors)}")
        }
    }

    @Test
    fun activeSpeechCountsShortPausesButNotLongOnes() {
        // 100 ms gaps between bursts belong to the speech (P.56 hangover of 200 ms), 400 ms gaps do not.
        val burst = TestSignals.sine(300.0, 0.2, peakDbfs = -17.0)
        fun train(gapSeconds: Double) = TestSignals.concat(
            TestSignals.silence(1.0),
            *Array(19) { if (it % 2 == 0) burst else TestSignals.silence(gapSeconds) },
            TestSignals.silence(1.0),
        )
        // 10 bursts of 0.2 s and 9 gaps of 0.1 s: duty cycle 2.0 / 2.9.
        assertNear(-20.0 + 10 * kotlin.math.log10(2.0 / 2.9), Level.activeSpeechDbfs(train(0.1)), 0.2)
        assertNear(-20.0, Level.activeSpeechDbfs(train(0.4)), 0.2)
    }

    @Test
    fun activeSpeechHonoursSampleRateAndShortInput() {
        val rate = 16000
        val tone = TestSignals.sine(300.0, 1.0, peakDbfs = -17.0, rate = rate)
        val signal = TestSignals.concat(TestSignals.silence(2.0, rate), tone, TestSignals.silence(1.0, rate))
        assertNear(-20.0, Level.activeSpeechDbfs(signal, rate), 0.3)
        // Shorter than one 20 ms frame: measured as a single frame.
        val blip = ShortArray(100) { 8192 }
        assertNear(-12.04, Level.activeSpeechDbfs(blip), 0.05)
    }
}
