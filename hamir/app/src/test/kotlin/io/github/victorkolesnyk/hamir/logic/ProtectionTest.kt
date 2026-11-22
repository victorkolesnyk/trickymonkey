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

    private fun now(volume: Int, route: OutputRoute? = OutputRoute.WIRED) = PlaybackConditions(volume, route = route)

    @Test
    fun matchingPlaybackRaisesNoWarnings() {
        val plan = ready(AppSettings(calibration = cal))
        assertEquals(emptySet(), MismatchDetector.check(plan, cal, now(10)))
    }

    @Test
    fun volumeChangeIsDetected() {
        val plan = ready(AppSettings(calibration = cal))
        val warnings = MismatchDetector.check(plan, cal, now(9))
        assertEquals(setOf(ProtectionWarning.VOLUME_CHANGED), warnings)
        assertTrue(MismatchDetector.hasMismatch(warnings))
        assertFalse(MismatchDetector.isSilent(warnings))
    }

    @Test
    fun routeTypeIsJudgedOnlyOnceTheRealRouteIsKnown() {
        val plan = ready(AppSettings(calibration = cal))
        assertEquals(setOf(ProtectionWarning.ROUTE_CHANGED), MismatchDetector.check(plan, cal, now(10, OutputRoute.SPEAKER)))
        assertEquals(emptySet(), MismatchDetector.check(plan, cal, now(10, null)))
    }

    @Test
    fun routeUnknownForTooLongIsAWarningInEveryMode() {
        val unknown = PlaybackConditions(10, route = null, routeUnknownTooLong = true)
        val calibrated = ready(AppSettings(calibration = cal))
        assertEquals(setOf(ProtectionWarning.ROUTE_UNKNOWN), MismatchDetector.check(calibrated, cal, unknown))
        val manual = ready(AppSettings())
        assertEquals(
            setOf(ProtectionWarning.ROUTE_UNKNOWN, ProtectionWarning.UNCALIBRATED),
            MismatchDetector.check(manual, null, unknown),
        )
        // Once a device is known the flag is moot.
        assertEquals(emptySet(), MismatchDetector.check(calibrated, cal, unknown.copy(route = OutputRoute.WIRED)))
    }

    @Test
    fun zeroVolumeIsMutedInEveryModeAndNotReportedAsAMereVolumeChange() {
        val calibrated = ready(AppSettings(calibration = cal))
        val warnings = MismatchDetector.check(calibrated, cal, now(0))
        assertEquals(setOf(ProtectionWarning.MUTED), warnings)
        assertTrue(MismatchDetector.isSilent(warnings))
        val manual = ready(AppSettings())
        assertEquals(
            setOf(ProtectionWarning.MUTED, ProtectionWarning.UNCALIBRATED),
            MismatchDetector.check(manual, null, now(0, OutputRoute.SPEAKER)),
        )
    }

    @Test
    fun mutedStreamIsSilentAlthoughTheIndexMatches() {
        val plan = ready(AppSettings(calibration = cal))
        val warnings = MismatchDetector.check(plan, cal, PlaybackConditions(10, streamMuted = true, route = OutputRoute.WIRED))
        assertEquals(setOf(ProtectionWarning.MUTED), warnings)
        assertTrue(MismatchDetector.isSilent(warnings))
    }

    @Test
    fun doNotDisturbTotalSilenceIsSilentInEveryMode() {
        val dnd = PlaybackConditions(10, totalSilence = true, route = OutputRoute.WIRED)
        val calibrated = MismatchDetector.check(ready(AppSettings(calibration = cal)), cal, dnd)
        assertEquals(setOf(ProtectionWarning.DND_SILENCE), calibrated)
        assertTrue(MismatchDetector.isSilent(calibrated))
        val manual = MismatchDetector.check(ready(AppSettings()), null, dnd)
        assertTrue(ProtectionWarning.DND_SILENCE in manual && MismatchDetector.isSilent(manual))
    }

    @Test
    fun manualModeAlwaysWarnsThatSnrIsNotVerified() {
        val plan = ready(AppSettings())
        val warnings = MismatchDetector.check(plan, null, now(7, OutputRoute.BLUETOOTH))
        assertEquals(setOf(ProtectionWarning.UNCALIBRATED), warnings)
        assertFalse(MismatchDetector.hasMismatch(warnings))
        assertFalse(MismatchDetector.isSilent(warnings))
    }

    @Test
    fun derivedAndClampedGainAreReported() {
        val settings = AppSettings(targetSnrDb = -5, calibration = cal.copy(targetSnrDb = 0, gainDb = 10.0))
        val plan = ready(settings)
        val warnings = MismatchDetector.check(plan, settings.calibration, now(10))
        assertEquals(setOf(ProtectionWarning.DERIVED_GAIN, ProtectionWarning.GAIN_CLAMPED), warnings)
        assertFalse(MismatchDetector.hasMismatch(warnings))
    }

    // ---------------------------------------------------------------- start gate

    @Test
    fun protectionStartsOnlyWithEnoughVoiceAndWithoutTotalSilence() {
        assertEquals(null, StartGate.protection(voiceReady = true, totalSilence = false))
        assertEquals(StartBlock.DND_TOTAL_SILENCE, StartGate.protection(voiceReady = true, totalSilence = true))
        assertEquals(StartBlock.NOT_ENOUGH_VOICE, StartGate.protection(voiceReady = false, totalSilence = false))
        // Without voice data there is nothing to start, whatever the mode: report that first.
        assertEquals(StartBlock.NOT_ENOUGH_VOICE, StartGate.protection(voiceReady = false, totalSilence = true))
    }

    @Test
    fun calibrationNoiseIsRefusedInTotalSilence() {
        assertEquals(null, StartGate.calibrationNoise(totalSilence = false))
        assertEquals(StartBlock.DND_TOTAL_SILENCE, StartGate.calibrationNoise(totalSilence = true))
    }

    // ---------------------------------------------------------------- headline

    private fun running(mode: GainMode, vararg warnings: ProtectionWarning) =
        ProtectionState(ProtectionStatus.RUNNING, warnings = warnings.toSet(), mode = mode)

    @Test
    fun onlyCalibratedMatchingAudiblePlaybackIsPresentedAsGood() {
        assertEquals(Headline.PLAYING_CALIBRATED, running(GainMode.CALIBRATED).headline)
        assertFalse(Headline.PLAYING_CALIBRATED.alarming)
        // Any mode other than CALIBRATED, and any warning on top of CALIBRATED except "route unknown"
        // (which has its own line in the warning list), must change the headline.
        for (mode in GainMode.values()) {
            if (mode != GainMode.CALIBRATED) assertTrue(running(mode).headline != Headline.PLAYING_CALIBRATED, mode.name)
        }
        for (warning in ProtectionWarning.values()) {
            if (warning == ProtectionWarning.ROUTE_UNKNOWN) continue
            assertTrue(running(GainMode.CALIBRATED, warning).headline != Headline.PLAYING_CALIBRATED, warning.name)
        }
    }

    @Test
    fun manualModeIsNeverShownAsVerified() {
        assertEquals(Headline.PLAYING_UNVERIFIED, running(GainMode.MANUAL, ProtectionWarning.UNCALIBRATED).headline)
        assertEquals(Headline.PLAYING_UNVERIFIED, running(GainMode.MANUAL).headline)
        assertFalse(Headline.PLAYING_UNVERIFIED.alarming)
    }

    @Test
    fun clampedAndDerivedGainHaveTheirOwnHeadlines() {
        assertEquals(
            Headline.PLAYING_LEVEL_NOT_REACHED,
            running(GainMode.DERIVED, ProtectionWarning.DERIVED_GAIN, ProtectionWarning.GAIN_CLAMPED).headline,
        )
        assertTrue(Headline.PLAYING_LEVEL_NOT_REACHED.alarming)
        assertEquals(Headline.PLAYING_DERIVED, running(GainMode.DERIVED, ProtectionWarning.DERIVED_GAIN).headline)
    }

    @Test
    fun mismatchOutranksDerivedAndClamped() {
        val state = running(GainMode.DERIVED, ProtectionWarning.VOLUME_CHANGED, ProtectionWarning.GAIN_CLAMPED)
        assertEquals(Headline.PLAYING_MISMATCH, state.headline)
        assertTrue(state.headline.alarming)
    }

    @Test
    fun silenceOutranksEverythingInEveryMode() {
        for (mode in GainMode.values()) {
            assertEquals(Headline.SILENT, running(mode, ProtectionWarning.MUTED, ProtectionWarning.UNCALIBRATED).headline)
            assertEquals(Headline.SILENT, running(mode, ProtectionWarning.DND_SILENCE, ProtectionWarning.VOLUME_CHANGED).headline)
        }
        assertTrue(Headline.SILENT.alarming)
    }

    @Test
    fun stoppedStatesDistinguishUserStopFromEverythingElse() {
        assertEquals(Headline.OFF, ProtectionState().headline)
        assertEquals(Headline.LOADING, ProtectionState(ProtectionStatus.LOADING).headline)
        assertEquals(Headline.OFF, ProtectionState(ProtectionStatus.STOPPED, stopReason = StopReason.USER).headline)
        for (reason in StopReason.values()) {
            if (reason == StopReason.USER) continue
            val headline = ProtectionState(ProtectionStatus.STOPPED, stopReason = reason).headline
            assertEquals(Headline.STOPPED_ALERT, headline, reason.name)
            assertTrue(headline.alarming)
        }
    }

    // ---------------------------------------------------------------- route guard

    @Test
    fun routeIsNotJudgedBeforeTheFirstRealDevice() {
        val guard = RouteGuard()
        assertEquals(RouteGuard.Verdict.PENDING, guard.observe(null, 0))
        assertEquals(RouteGuard.Verdict.PENDING, guard.observe(null, 4999))
        assertFalse(guard.baselineKnown)
        assertEquals(RouteGuard.Verdict.PENDING_TOO_LONG, guard.observe(null, 5000))
        assertEquals(RouteGuard.Verdict.STABLE, guard.observe(17, 6000))
        assertTrue(guard.baselineKnown)
        assertEquals(17, guard.baselineDeviceId)
    }

    @Test
    fun sameDeviceStaysStable() {
        val guard = RouteGuard()
        for (t in 0..100) assertEquals(RouteGuard.Verdict.STABLE, guard.observe(3, t * 1000L))
    }

    @Test
    fun anyOtherDeviceIsALossAndStaysOne() {
        // Wired earbud (id 3) on the target microphone, then Bluetooth earphones (id 9) take the media route.
        val guard = RouteGuard()
        guard.observe(3, 0)
        assertEquals(RouteGuard.Verdict.LOST, guard.observe(9, 1000))
        // Even if the route comes back by itself: the user was unprotected and must be told.
        assertEquals(RouteGuard.Verdict.LOST, guard.observe(3, 2000))
    }

    @Test
    fun vanishedOutputIsALossAfterAShortGrace() {
        val guard = RouteGuard(nullGraceMs = 2500)
        guard.observe(3, 0)
        assertEquals(RouteGuard.Verdict.STABLE, guard.observe(null, 1000))
        assertEquals(RouteGuard.Verdict.STABLE, guard.observe(null, 3400))
        assertEquals(RouteGuard.Verdict.LOST, guard.observe(null, 3500))
    }

    @Test
    fun shortGapWhileTheTrackIsRestoredIsTolerated() {
        val guard = RouteGuard(nullGraceMs = 2500)
        guard.observe(3, 0)
        assertEquals(RouteGuard.Verdict.STABLE, guard.observe(null, 1000))
        assertEquals(RouteGuard.Verdict.STABLE, guard.observe(3, 2000))
        // The grace period starts over with the next gap.
        assertEquals(RouteGuard.Verdict.STABLE, guard.observe(null, 10_000))
        assertEquals(RouteGuard.Verdict.STABLE, guard.observe(null, 12_000))
        assertEquals(RouteGuard.Verdict.LOST, guard.observe(null, 12_500))
    }

    @Test
    fun gapFollowedByAnotherDeviceIsALoss() {
        val guard = RouteGuard()
        guard.observe(3, 0)
        guard.observe(null, 500)
        assertEquals(RouteGuard.Verdict.LOST, guard.observe(4, 900))
    }

    // ---------------------------------------------------------------- silence alarm

    @Test
    fun silenceAlarmSoundsAtOnceAndThenEveryTenSeconds() {
        val alarm = SilenceAlarm(repeatMs = 10_000)
        assertFalse(alarm.onObservation(false, 0))
        assertFalse(alarm.silentNow)
        val fired = (1..25).filter { alarm.onObservation(true, it * 1000L) }
        assertEquals(listOf(1, 11, 21), fired)
        assertTrue(alarm.silentNow)
    }

    @Test
    fun silenceAlarmRestartsAfterSoundCameBack() {
        val alarm = SilenceAlarm(repeatMs = 10_000)
        assertTrue(alarm.onObservation(true, 1000))
        assertFalse(alarm.onObservation(true, 2000))
        assertFalse(alarm.onObservation(false, 3000))
        assertFalse(alarm.silentNow)
        assertTrue(alarm.onObservation(true, 4000), "a new silence must alarm immediately")
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
