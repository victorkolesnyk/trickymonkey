package io.github.victorkolesnyk.hamir.logic

import io.github.victorkolesnyk.hamir.core.CalibrationFailure
import io.github.victorkolesnyk.hamir.core.CalibrationStep
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationSessionTest {
    /** Scripted solver: returns the queued steps and records what it was asked. */
    private class Script(vararg steps: CalibrationStep) : NoiseSolver {
        val queue = ArrayDeque(steps.toList())
        val calls = ArrayList<Triple<Double, Double, Double>>()
        override fun next(currentGainDb: Double, measuredNoiseDbfs: Double, measuredPeakDbfs: Double): CalibrationStep {
            calls.add(Triple(currentGainDb, measuredNoiseDbfs, measuredPeakDbfs))
            return queue.removeFirst()
        }
    }

    private fun session(script: Script, created: MutableList<Pair<Double, Double>> = ArrayList()) =
        CalibrationSession(-5.0, startGainDb = -10.0) { speech, ambient ->
            created.add(speech to ambient)
            script
        }

    @Test
    fun startsWithAmbientThenSpeech() {
        val s = session(Script())
        assertIs<CalibrationPhase.Ambient>(s.phase)
        val phase = assertIs<CalibrationPhase.Speech>(s.submitAmbient(-62.0))
        assertEquals(1, phase.attempt)
        assertNull(phase.rejectedMarginDb)
        assertEquals(-62.0, s.ambientDbfs)
    }

    @Test
    fun speechCloserThanTenDbToAmbientRepeatsTheStep() {
        val s = session(Script())
        s.submitAmbient(-50.0)
        val retry = assertIs<CalibrationPhase.Speech>(s.submitSpeech(-41.0))
        assertEquals(2, retry.attempt)
        assertEquals(9.0, retry.rejectedMarginDb!!, 1e-9)
        val again = assertIs<CalibrationPhase.Speech>(s.submitSpeech(-120.0))
        assertEquals(3, again.attempt)
        assertIs<CalibrationPhase.Noise>(s.submitSpeech(-40.0))
    }

    @Test
    fun acceptedSpeechStartsNoiseAtStartGainWithFreshSolver() {
        val created = ArrayList<Pair<Double, Double>>()
        val s = session(Script(), created)
        s.submitAmbient(-60.0)
        val noise = assertIs<CalibrationPhase.Noise>(s.submitSpeech(-30.0))
        assertEquals(-10.0, noise.gainDb)
        assertEquals(1, noise.iteration)
        assertNull(noise.lastMeasuredDbfs)
        assertEquals(listOf(-30.0 to -60.0), created)
        assertEquals(-25.0, s.targetNoiseDbfs)
    }

    @Test
    fun adjustStepsLeadToDone() {
        val script = Script(
            CalibrationStep.Adjust(-2.0),
            CalibrationStep.Adjust(-1.2),
            CalibrationStep.Done(-1.2, -4.7),
        )
        val s = session(script)
        s.submitAmbient(-60.0)
        s.submitSpeech(-30.0)
        val second = assertIs<CalibrationPhase.Noise>(s.submitNoise(-33.0, -20.0))
        assertEquals(-2.0, second.gainDb)
        assertEquals(2, second.iteration)
        assertEquals(-33.0, second.lastMeasuredDbfs)
        val third = assertIs<CalibrationPhase.Noise>(s.submitNoise(-25.8, -12.0))
        assertEquals(3, third.iteration)
        val done = assertIs<CalibrationPhase.Done>(s.submitNoise(-25.3, -11.0))
        assertEquals(CalibrationOutcome(-1.2, -30.0, -60.0, -25.3, -4.7), done.outcome)
        assertEquals(
            listOf(Triple(-10.0, -33.0, -20.0), Triple(-2.0, -25.8, -12.0), Triple(-1.2, -25.3, -11.0)),
            script.calls,
        )
    }

    @Test
    fun failureIsReportedAndNoiseCanBeRestarted() {
        val created = ArrayList<Pair<Double, Double>>()
        val script = Script(CalibrationStep.Fail(CalibrationFailure.MIC_CLIPPING), CalibrationStep.Done(-10.0, -5.0))
        val s = session(script, created)
        s.submitAmbient(-60.0)
        s.submitSpeech(-30.0)
        val failed = assertIs<CalibrationPhase.Failed>(s.submitNoise(-3.0, -0.2))
        assertEquals(CalibrationFailure.MIC_CLIPPING, failed.reason)
        val restarted = assertIs<CalibrationPhase.Noise>(s.restartNoise())
        assertEquals(1, restarted.iteration)
        assertEquals(-10.0, restarted.gainDb)
        assertEquals(2, created.size, "restart must create a new solver so its iteration count starts over")
        assertIs<CalibrationPhase.Done>(s.submitNoise(-25.0, -12.0))
    }

    @Test
    fun endlessAdjustIsCutOffByTheHardLimit() {
        val steps = Array<CalibrationStep>(100) { CalibrationStep.Adjust(0.0) }
        val s = session(Script(*steps))
        s.submitAmbient(-60.0)
        s.submitSpeech(-30.0)
        var phase: CalibrationPhase = s.phase
        var measurements = 0
        while (phase is CalibrationPhase.Noise) {
            phase = s.submitNoise(-40.0, -30.0)
            measurements++
            assertTrue(measurements < 50)
        }
        assertEquals(CalibrationFailure.NOT_CONVERGING, assertIs<CalibrationPhase.Failed>(phase).reason)
        assertEquals(CalibrationSession.HARD_ITERATION_LIMIT + 1, measurements)
    }

    @Test
    fun callsOutOfOrderAreRejected() {
        val s = session(Script())
        assertFailsWith<IllegalStateException> { s.submitSpeech(-30.0) }
        assertFailsWith<IllegalStateException> { s.submitNoise(-30.0, -20.0) }
        assertFailsWith<IllegalStateException> { s.restartNoise() }
        s.submitAmbient(-60.0)
        assertFailsWith<IllegalStateException> { s.submitAmbient(-60.0) }
    }

    /** Simulated acoustic path with the real core solver: measured = gain + pathGain, plus ambient floor. */
    private fun simulate(pathDb: Double, ambient: Double, speech: Double, target: Double): CalibrationPhase {
        val s = CalibrationSession(target)
        s.submitAmbient(ambient)
        var phase = s.submitSpeech(speech)
        var guard = 0
        while (phase is CalibrationPhase.Noise) {
            val noise = -20.0 + phase.gainDb + pathDb
            val measured = 10.0 * Math.log10(Math.pow(10.0, noise / 10.0) + Math.pow(10.0, ambient / 10.0))
            phase = s.submitNoise(measured, measured + 12.0)
            assertTrue(++guard < 40)
        }
        return phase
    }

    @Test
    fun realSolverConvergesOnALinearPath() {
        val done = assertIs<CalibrationPhase.Done>(simulate(pathDb = -8.0, ambient = -65.0, speech = -35.0, target = -5.0))
        // Needed: noise at -30 dBFS = -20 + gain - 8  ->  gain = -2.
        assertTrue(abs(done.outcome.gainDb - (-2.0)) <= 1.01, "gain ${done.outcome.gainDb}")
        assertTrue(abs(done.outcome.achievedSnrDb - (-5.0)) <= 1.01, "snr ${done.outcome.achievedSnrDb}")
        assertEquals(-35.0, done.outcome.speechDbfs)
    }

    @Test
    fun realSolverAsksForMoreVolumeWhenTheOutputIsTooWeak() {
        val failed = assertIs<CalibrationPhase.Failed>(simulate(pathDb = -40.0, ambient = -70.0, speech = -30.0, target = -5.0))
        assertTrue(
            failed.reason == CalibrationFailure.NEED_MORE_VOLUME || failed.reason == CalibrationFailure.NOISE_NOT_AUDIBLE,
            "reason ${failed.reason}",
        )
    }

    @Test
    fun realSolverReportsMicClipping() {
        val s = CalibrationSession(-5.0)
        s.submitAmbient(-60.0)
        s.submitSpeech(-20.0)
        val failed = assertIs<CalibrationPhase.Failed>(s.submitNoise(-6.0, -0.3))
        assertEquals(CalibrationFailure.MIC_CLIPPING, failed.reason)
    }
}
