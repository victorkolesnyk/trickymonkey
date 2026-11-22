package io.github.victorkolesnyk.hamir.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtectionTest {
    private val cal = CalibrationRecord(
        gainDb = 2.0, targetSnrDb = -5, musicVolumeIndex = 10, route = OutputRoute.WIRED,
        speechDbfs = -30.0, noiseDbfs = -25.0, achievedSnrDb = -5.0, timeMillis = 1L,
    )

    private fun ready(settings: AppSettings, a: Int = 60, b: Int = 0): ProtectionPlan =
        assertIs<PlanResult.Ready>(ProtectionPlanner.plan(settings, a, b)).plan

    @Test
    fun refusesToStartBelowTwentyTakes() {
        assertIs<PlanResult.NotEnoughVoice>(ProtectionPlanner.plan(AppSettings(), 19, 100))
        assertIs<PlanResult.Ready>(ProtectionPlanner.plan(AppSettings(), 20, 0))
    }

    @Test
    fun manualModeWithoutCalibration() {
        val plan = ready(AppSettings(manualGainDb = -12.0))
        assertEquals(GainMode.MANUAL, plan.mode)
        assertEquals(-12.0, plan.gainDb)
        assertEquals(listOf(Profile.A), plan.profiles)
    }

    @Test
    fun calibratedGainIsUsedForTheCalibratedTarget() {
        val plan = ready(AppSettings(targetSnrDb = -5, calibration = cal, manualGainDb = -30.0))
        assertEquals(GainMode.CALIBRATED, plan.mode)
        assertEquals(2.0, plan.gainDb)
        assertFalse(plan.gainClamped)
    }

    @Test
    fun otherTargetShiftsGainByTheDifference() {
        // Calibrated for -5 dB, now 0 dB wanted: noise may be 5 dB quieter.
        val quieter = ready(AppSettings(targetSnrDb = 0, calibration = cal))
        assertEquals(GainMode.DERIVED, quieter.mode)
        assertEquals(-3.0, quieter.gainDb)
        // Calibrated for 0 dB, now -5 dB wanted: noise must be 5 dB louder.
        val louder = ready(AppSettings(targetSnrDb = -5, calibration = cal.copy(targetSnrDb = 0)))
        assertEquals(7.0, louder.gainDb)
        assertFalse(louder.gainClamped)
    }

    @Test
    fun derivedGainIsClampedAndFlagged() {
        val plan = ready(AppSettings(targetSnrDb = -5, calibration = cal.copy(targetSnrDb = 0, gainDb = 10.0)))
        assertEquals(12.0, plan.gainDb)
        assertTrue(plan.gainClamped)
    }

    @Test
    fun twoSpeakersNeedBothProfilesReady() {
        val two = AppSettings(twoSpeakers = true)
        assertEquals(listOf(Profile.A, Profile.B), ready(two, 20, 20).profiles)
        assertEquals(listOf(Profile.A), ready(two, 20, 19).profiles)
        assertEquals(listOf(Profile.A), ready(AppSettings(twoSpeakers = false), 100, 100).profiles)
        assertTrue(ProtectionPlanner.twoSpeakersAvailable(20, 20))
        assertFalse(ProtectionPlanner.twoSpeakersAvailable(100, 19))
    }

    @Test
    fun matchingPlaybackRaisesNoWarnings() {
        val plan = ready(AppSettings(calibration = cal))
        assertEquals(emptySet(), MismatchDetector.check(plan, cal, 10, OutputRoute.WIRED))
    }

    @Test
    fun volumeChangeIsDetected() {
        val plan = ready(AppSettings(calibration = cal))
        val warnings = MismatchDetector.check(plan, cal, 9, OutputRoute.WIRED)
        assertEquals(setOf(ProtectionWarning.VOLUME_CHANGED), warnings)
        assertTrue(MismatchDetector.hasMismatch(warnings))
    }

    @Test
    fun routeChangeIsDetectedButUnknownRouteIsNot() {
        val plan = ready(AppSettings(calibration = cal))
        assertEquals(
            setOf(ProtectionWarning.ROUTE_CHANGED),
            MismatchDetector.check(plan, cal, 10, OutputRoute.SPEAKER),
        )
        assertEquals(emptySet(), MismatchDetector.check(plan, cal, 10, null))
    }

    @Test
    fun zeroVolumeIsMutedInEveryMode() {
        val calibrated = ready(AppSettings(calibration = cal))
        assertEquals(
            setOf(ProtectionWarning.MUTED, ProtectionWarning.VOLUME_CHANGED),
            MismatchDetector.check(calibrated, cal, 0, OutputRoute.WIRED),
        )
        val manual = ready(AppSettings())
        assertEquals(
            setOf(ProtectionWarning.MUTED, ProtectionWarning.UNCALIBRATED),
            MismatchDetector.check(manual, null, 0, OutputRoute.SPEAKER),
        )
    }

    @Test
    fun manualModeAlwaysWarnsThatSnrIsNotVerified() {
        val plan = ready(AppSettings())
        val warnings = MismatchDetector.check(plan, null, 7, OutputRoute.BLUETOOTH)
        assertEquals(setOf(ProtectionWarning.UNCALIBRATED), warnings)
        assertFalse(MismatchDetector.hasMismatch(warnings))
    }

    @Test
    fun derivedAndClampedGainAreReported() {
        val settings = AppSettings(targetSnrDb = -5, calibration = cal.copy(targetSnrDb = 0, gainDb = 10.0))
        val plan = ready(settings)
        val warnings = MismatchDetector.check(plan, settings.calibration, 10, OutputRoute.WIRED)
        assertEquals(setOf(ProtectionWarning.DERIVED_GAIN, ProtectionWarning.GAIN_CLAMPED), warnings)
        assertFalse(MismatchDetector.hasMismatch(warnings))
    }

    @Test
    fun stateActiveOnlyWhileLoadingOrRunning() {
        assertFalse(ProtectionState().active)
        assertTrue(ProtectionState(ProtectionStatus.LOADING).active)
        assertTrue(ProtectionState(ProtectionStatus.RUNNING).active)
        assertFalse(ProtectionState(ProtectionStatus.STOPPED, stopReason = StopReason.USER).active)
    }

    @Test
    fun observableNotifiesAndUnsubscribes() {
        val observable = Observable(0)
        val seen = ArrayList<Int>()
        val listener: (Int) -> Unit = { seen.add(it) }
        observable.addListener(listener)
        observable.addListener(listener)
        observable.value = 1
        observable.value = 2
        observable.removeListener(listener)
        observable.value = 3
        assertEquals(listOf(1, 2), seen)
        assertEquals(3, observable.value)
    }
}
