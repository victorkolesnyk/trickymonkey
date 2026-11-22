package io.github.victorkolesnyk.hamir.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CalibrationSolverTest {
    private fun solver(
        targetSnrDb: Double = -5.0,
        speechDbfs: Double = -30.0,
        ambientDbfs: Double = -60.0,
        maxIterations: Int = 8,
    ) = CalibrationSolver(targetSnrDb, speechDbfs, ambientDbfs, maxIterations = maxIterations)

    private fun fail(reason: CalibrationFailure) = CalibrationStep.Fail(reason)

    /** Microphone model: the noise arrives at gain + pathDb and adds to the ambient power. */
    private fun measure(gainDb: Double, pathDb: Double, ambientDbfs: Double): Double =
        10.0 * log10(10.0.pow((gainDb + pathDb) / 10.0) + 10.0.pow(ambientDbfs / 10.0))

    private fun run(solver: CalibrationSolver, startGainDb: Double, pathDb: Double, peakOverRmsDb: Double = 10.0): Pair<CalibrationStep, Int> {
        var gain = startGainDb
        var calls = 0
        while (true) {
            val measured = measure(gain, pathDb, solver.ambientDbfs)
            val step = solver.next(gain, measured, measured + peakOverRmsDb)
            calls++
            if (step is CalibrationStep.Adjust) gain = step.gainDb else return step to calls
            check(calls < 100) { "solver does not terminate" }
        }
    }

    @Test
    fun targetNoiseIsSpeechMinusSnr() {
        assertEquals(-25.0, solver(targetSnrDb = -5.0, speechDbfs = -30.0).targetNoiseDbfs)
        assertEquals(-30.0, solver(targetSnrDb = 0.0, speechDbfs = -30.0).targetNoiseDbfs)
        assertEquals(-42.5, solver(targetSnrDb = 2.5, speechDbfs = -40.0).targetNoiseDbfs)
    }

    @Test
    fun defaultsMatchTheSpecification() {
        val s = CalibrationSolver(-5.0, -30.0, -60.0)
        assertEquals(-40.0, s.minGainDb)
        assertEquals(12.0, s.maxGainDb)
        assertEquals(1.0, s.toleranceDb)
        assertEquals(8, s.maxIterations)
    }

    @Test
    fun adjustMovesGainByTheMeasuredError() {
        // target -25, measured -37 at gain -20: 12 dB short.
        assertEquals(CalibrationStep.Adjust(-8.0), solver().next(-20.0, -37.0, -25.0))
        // measured -15: 10 dB too loud.
        assertEquals(CalibrationStep.Adjust(-30.0), solver().next(-20.0, -15.0, -4.0))
    }

    @Test
    fun adjustIsLimitedToTheGainRange() {
        // Target -50 dBFS, measured -20 at gain -20: -50 dB would be needed, the minimum is -40.
        assertEquals(CalibrationStep.Adjust(-40.0), solver(speechDbfs = -55.0, ambientDbfs = -80.0).next(-20.0, -20.0, -8.0))
        // 0.6 dB beyond the maximum but within tolerance of it: try the maximum.
        assertEquals(CalibrationStep.Adjust(12.0), solver().next(5.0, -32.6, -20.0))
    }

    @Test
    fun doneWhenWithinTolerance() {
        val s = solver()
        assertEquals(CalibrationStep.Done(-3.0, -5.0), s.next(-3.0, -25.0, -12.0))
        assertEquals(CalibrationStep.Done(-3.0, -6.0), solver().next(-3.0, -24.0, -12.0))
        assertEquals(CalibrationStep.Done(-3.0, -4.0), solver().next(-3.0, -26.0, -12.0))
        assertIs<CalibrationStep.Adjust>(solver().next(-3.0, -26.01, -12.0))
        assertIs<CalibrationStep.Adjust>(solver().next(-3.0, -23.99, -12.0))
        // Done also at the limits of the gain range.
        assertEquals(CalibrationStep.Done(12.0, -4.5), solver().next(12.0, -25.5, -12.0))
        assertEquals(CalibrationStep.Done(-40.0, -5.5), solver().next(-40.0, -24.5, -12.0))
    }

    @Test
    fun convergesOnALinearPlaybackChain() {
        for (target in doubleArrayOf(-5.0, 0.0)) {
            for (path in doubleArrayOf(-40.0, -25.0, -12.0)) {
                for (start in doubleArrayOf(-40.0, -20.0, 0.0, 12.0)) {
                    val s = solver(targetSnrDb = target, speechDbfs = -35.0, ambientDbfs = -65.0)
                    val (step, calls) = run(s, start, path)
                    val wantedGain = s.targetNoiseDbfs - path
                    if (measure(start, path, s.ambientDbfs) + 10.0 > CalibrationSolver.MIC_CLIP_PEAK_DBFS) {
                        // Starting this loud overloads the microphone on the very first measurement.
                        assertEquals(fail(CalibrationFailure.MIC_CLIPPING), step, "target $target path $path start $start")
                        assertEquals(1, calls)
                        continue
                    }
                    if (wantedGain > s.maxGainDb + s.toleranceDb) {
                        assertEquals(fail(CalibrationFailure.NEED_MORE_VOLUME), step, "target $target path $path start $start")
                        continue
                    }
                    assertIs<CalibrationStep.Done>(step, "target $target path $path start $start")
                    assertTrue(abs(step.achievedSnrDb - target) <= s.toleranceDb, "achieved ${step.achievedSnrDb}")
                    assertTrue(abs(step.gainDb - wantedGain) <= s.toleranceDb + 0.1, "gain ${step.gainDb}, wanted $wantedGain")
                    assertTrue(step.gainDb in s.minGainDb..s.maxGainDb)
                    assertTrue(calls <= 3, "$calls measurements")
                    assertEquals(calls, s.iterations)
                }
            }
        }
    }

    @Test
    fun convergesWhenThePlaybackChainCompresses() {
        // Every dB of gain yields only 0.6 dB at the microphone.
        val s = solver(speechDbfs = -30.0, ambientDbfs = -70.0)
        var gain = -30.0
        var step: CalibrationStep
        var calls = 0
        do {
            val measured = -22.0 + 0.6 * gain
            step = s.next(gain, measured, measured + 10.0)
            calls++
            if (step is CalibrationStep.Adjust) gain = step.gainDb
        } while (step is CalibrationStep.Adjust)
        assertIs<CalibrationStep.Done>(step)
        assertTrue(abs(step.achievedSnrDb - (-5.0)) <= 1.0)
        assertTrue(calls <= 8)
    }

    @Test
    fun micClippingFailsFirst() {
        assertEquals(fail(CalibrationFailure.MIC_CLIPPING), solver().next(0.0, -10.0, -0.5))
        // Even when the level happens to be on target.
        assertEquals(fail(CalibrationFailure.MIC_CLIPPING), solver().next(0.0, -25.0, 0.0))
        // Exactly -1 dBFS is still accepted.
        assertEquals(CalibrationStep.Done(0.0, -5.0), solver().next(0.0, -25.0, -1.0))
        assertEquals(fail(CalibrationFailure.MIC_CLIPPING), solver().next(0.0, -25.0, -0.99))
    }

    @Test
    fun needMoreVolumeWhenRequiredGainExceedsMaximum() {
        // Clearly audible (30 dB above ambient) but 15 dB short at gain 0: would need +15 dB.
        assertEquals(fail(CalibrationFailure.NEED_MORE_VOLUME), solver().next(0.0, -40.0, -28.0))
        // Already at maximum gain, audible, still 5 dB short.
        assertEquals(fail(CalibrationFailure.NEED_MORE_VOLUME), solver().next(12.0, -30.0, -18.0))
        // Whole run: the path is so weak that even +12 dB is not enough.
        val (step, calls) = run(solver(speechDbfs = -30.0, ambientDbfs = -60.0), -10.0, pathDb = -45.0)
        assertEquals(fail(CalibrationFailure.NEED_MORE_VOLUME), step)
        assertTrue(calls <= 2)
    }

    @Test
    fun noiseNotAudibleWhenMaximumGainDoesNotRiseAboveAmbient() {
        // Headphones unplugged: the microphone hears only the ambient level whatever the gain.
        val s = solver(ambientDbfs = -60.0)
        val first = s.next(-20.0, -59.5, -45.0)
        assertEquals(CalibrationStep.Adjust(12.0), first, "the solver first tries the maximum gain")
        assertEquals(fail(CalibrationFailure.NOISE_NOT_AUDIBLE), s.next(12.0, -59.0, -45.0))
        // Started at maximum gain: fails straight away.
        assertEquals(fail(CalibrationFailure.NOISE_NOT_AUDIBLE), solver().next(12.0, -57.01, -45.0))
        // 3 dB above ambient counts as audible, so the diagnosis changes.
        assertEquals(fail(CalibrationFailure.NEED_MORE_VOLUME), solver().next(12.0, -57.0, -45.0))
        // Digital silence from the microphone.
        val silent = solver()
        assertEquals(CalibrationStep.Adjust(12.0), silent.next(-10.0, SILENCE_DBFS, SILENCE_DBFS))
        assertEquals(fail(CalibrationFailure.NOISE_NOT_AUDIBLE), silent.next(12.0, SILENCE_DBFS, SILENCE_DBFS))
    }

    @Test
    fun notConvergingAfterMaxIterations() {
        // An erratic chain: the reading alternates between 6 dB too loud and 6 dB too quiet.
        val s = solver(maxIterations = 8)
        var gain = -10.0
        var calls = 0
        var step: CalibrationStep
        do {
            val measured = if (calls % 2 == 0) -19.0 else -31.0
            step = s.next(gain, measured, -10.0)
            calls++
            if (step is CalibrationStep.Adjust) gain = step.gainDb
        } while (step is CalibrationStep.Adjust)
        assertEquals(fail(CalibrationFailure.NOT_CONVERGING), step)
        assertEquals(8, calls)
        assertEquals(8, s.iterations)
        // Asking again does not restart the search.
        assertEquals(fail(CalibrationFailure.NOT_CONVERGING), s.next(gain, -19.0, -10.0))
    }

    @Test
    fun maxIterationsOfOneAllowsASingleMeasurement() {
        assertEquals(fail(CalibrationFailure.NOT_CONVERGING), solver(maxIterations = 1).next(-10.0, -19.0, -10.0))
        assertEquals(CalibrationStep.Done(-10.0, -5.0), solver(maxIterations = 1).next(-10.0, -25.0, -10.0))
    }

    @Test
    fun lastAllowedMeasurementCanStillSucceed() {
        val s = solver(maxIterations = 3)
        assertIs<CalibrationStep.Adjust>(s.next(-10.0, -19.0, -10.0))
        assertIs<CalibrationStep.Adjust>(s.next(-16.0, -31.0, -10.0))
        val last = s.next(-10.0, -24.8, -10.0)
        assertIs<CalibrationStep.Done>(last)
        assertEquals(-10.0, last.gainDb)
        assertEquals(-5.2, last.achievedSnrDb, 1e-9)
    }

    @Test
    fun tooLoudAtMinimumGainNeedsLessVolume() {
        // Media volume far too high: even -40 dB of digital gain leaves the noise 10 dB too loud.
        val s = solver()
        assertEquals(CalibrationStep.Adjust(-40.0), s.next(-35.0, -10.0, -3.0))
        assertEquals(fail(CalibrationFailure.NEED_LESS_VOLUME), s.next(-40.0, -15.0, -5.0))
        // Started at the minimum: fails on the first measurement.
        assertEquals(fail(CalibrationFailure.NEED_LESS_VOLUME), solver().next(-40.0, -23.9, -12.0))
        // Within tolerance at the minimum is a success, not a failure.
        assertEquals(CalibrationStep.Done(-40.0, -6.0), solver().next(-40.0, -24.0, -12.0))
        // Too quiet at the minimum is an ordinary adjustment.
        assertEquals(CalibrationStep.Adjust(-30.0), solver().next(-40.0, -35.0, -22.0))
        // Whole run on a chain that is 15 dB too hot for a quiet talker (target -45 dBFS).
        val hot = solver(speechDbfs = -50.0, ambientDbfs = -80.0)
        var gain = -20.0
        var result: CalibrationStep
        var count = 0
        do {
            result = hot.next(gain, gain + 10.0, gain + 18.0)
            count++
            if (result is CalibrationStep.Adjust) gain = result.gainDb
        } while (result is CalibrationStep.Adjust)
        assertEquals(fail(CalibrationFailure.NEED_LESS_VOLUME), result)
        assertEquals(2, count)
    }

    @Test
    fun needLessVolumeIsAppendedToTheEnum() {
        // The application stores and switches on these constants: the first four keep their positions.
        assertEquals(
            listOf("NEED_MORE_VOLUME", "MIC_CLIPPING", "NOISE_NOT_AUDIBLE", "NOT_CONVERGING", "NEED_LESS_VOLUME"),
            CalibrationFailure.values().map { it.name },
        )
    }

    @Test
    fun gainOutsideTheRangeIsNeverAccepted() {
        // On target, but the caller played at a gain the solver would never hand out.
        assertEquals(CalibrationStep.Adjust(12.0), solver().next(20.0, -25.0, -12.0))
        assertEquals(CalibrationStep.Adjust(-40.0), solver().next(-55.0, -25.0, -12.0))
        // Off target as well: still pulled back into the range first, whatever the measurement says.
        assertEquals(CalibrationStep.Adjust(12.0), solver().next(12.5, -40.0, -30.0))
        assertEquals(CalibrationStep.Adjust(-40.0), solver().next(-41.0, -10.0, -0.5))
        // It counts as a measurement, so a caller that ignores the answer cannot loop for ever.
        val s = solver(maxIterations = 3)
        assertEquals(CalibrationStep.Adjust(12.0), s.next(30.0, -25.0, -12.0))
        assertEquals(CalibrationStep.Adjust(12.0), s.next(30.0, -25.0, -12.0))
        assertEquals(fail(CalibrationFailure.NOT_CONVERGING), s.next(30.0, -25.0, -12.0))
        // Random probing: a Done always carries a gain inside the range.
        val random = java.util.Random(5)
        var done = 0
        repeat(20000) {
            val solver = CalibrationSolver(
                targetSnrDb = -5.0 + 10.0 * random.nextDouble(),
                speechDbfs = -60.0 + 40.0 * random.nextDouble(),
                ambientDbfs = -90.0 + 40.0 * random.nextDouble(),
            )
            val gain = -60.0 + 90.0 * random.nextDouble()
            val measured = solver.targetNoiseDbfs + 4.0 * random.nextGaussian()
            val step = solver.next(gain, measured, measured + 10.0)
            if (step is CalibrationStep.Done) {
                done++
                assertTrue(step.gainDb in solver.minGainDb..solver.maxGainDb, "Done with gain ${step.gainDb}")
                assertEquals(gain, step.gainDb)
                assertTrue(measured >= solver.ambientDbfs + CalibrationSolver.AUDIBLE_MARGIN_DB, "Done on an inaudible noise")
                assertTrue(abs(measured - solver.targetNoiseDbfs) <= solver.toleranceDb)
            }
        }
        assertTrue(done > 1000, "$done successful probes")
    }

    @Test
    fun ambientAloneIsNeverAcceptedAsTheNoise() {
        // Reviewer's case: target -48 dBFS, ambient -50. The reading of -48.2 is within tolerance of
        // the target, but it is only 1.8 dB above ambient: nothing shows that the noise is playing.
        val s = CalibrationSolver(0.0, -48.0, -50.0)
        val step = s.next(-40.0, -48.2, -30.0)
        assertIs<CalibrationStep.Adjust>(step)
        // The solver now aims at the lowest level that is both audible and within tolerance: -47.
        assertEquals(-38.8, step.gainDb, 1e-9)
        val done = s.next(-38.8, -47.0, -30.0)
        assertIs<CalibrationStep.Done>(done)
        assertEquals(-1.0, done.achievedSnrDb, 1e-9)

        // Target so close to ambient that it cannot be verified at all.
        assertEquals(fail(CalibrationFailure.NOISE_NOT_AUDIBLE), CalibrationSolver(0.0, -49.0, -50.0).next(-40.0, -48.8, -30.0))
        assertEquals(fail(CalibrationFailure.NOISE_NOT_AUDIBLE), CalibrationSolver(5.0, -45.0, -50.0).next(0.0, -30.0, -20.0))
        // Exactly at the audibility margin and on target: accepted.
        assertEquals(CalibrationStep.Done(-10.0, 0.0), CalibrationSolver(0.0, -47.0, -50.0).next(-10.0, -47.0, -30.0))
        // With the gate the application uses (speech 10 dB above ambient) nothing changes.
        assertEquals(CalibrationStep.Done(-10.0, 0.0), CalibrationSolver(0.0, -40.0, -50.0).next(-10.0, -40.0, -30.0))
    }

    @Test
    fun resetStartsANewRun() {
        val s = solver(maxIterations = 2)
        assertIs<CalibrationStep.Adjust>(s.next(-10.0, -19.0, -10.0))
        assertEquals(fail(CalibrationFailure.NOT_CONVERGING), s.next(-16.0, -31.0, -10.0))
        s.reset()
        assertEquals(0, s.iterations)
        assertIs<CalibrationStep.Adjust>(s.next(-10.0, -19.0, -10.0))
    }

    @Test
    fun invalidArgumentsAreRejected() {
        assertFailsWith<IllegalArgumentException> { CalibrationSolver(Double.NaN, -30.0, -60.0) }
        assertFailsWith<IllegalArgumentException> { CalibrationSolver(-5.0, -30.0, -60.0, minGainDb = 5.0, maxGainDb = 0.0) }
        assertFailsWith<IllegalArgumentException> { CalibrationSolver(-5.0, -30.0, -60.0, toleranceDb = -1.0) }
        assertFailsWith<IllegalArgumentException> { solver().next(0.0, Double.NaN, -10.0) }
    }
}
