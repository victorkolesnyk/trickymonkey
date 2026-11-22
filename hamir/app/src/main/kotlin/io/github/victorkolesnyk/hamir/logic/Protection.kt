package io.github.victorkolesnyk.hamir.logic

/** How the playback gain was obtained. */
enum class GainMode {
    /** Solved by calibration for the selected target. */
    CALIBRATED,

    /** Calibrated for the other target and shifted arithmetically; not measured. */
    DERIVED,

    /** No calibration: the user sets the level by ear, SNR unknown. */
    MANUAL,
}

data class ProtectionPlan(
    val mode: GainMode,
    val gainDb: Double,
    /** The derived gain hit the allowed range, so the target is not reached. */
    val gainClamped: Boolean,
    val profiles: List<Profile>,
)

sealed class PlanResult {
    data class Ready(val plan: ProtectionPlan) : PlanResult()

    /** Profile "A" has fewer than [EnrollmentRules.MIN_TAKES] takes. */
    object NotEnoughVoice : PlanResult()
}

object ProtectionPlanner {
    /** Two speakers are possible only when both profiles have enough takes. */
    fun twoSpeakersAvailable(takesA: Int, takesB: Int): Boolean =
        EnrollmentRules.isReady(takesA) && EnrollmentRules.isReady(takesB)

    fun plan(settings: AppSettings, takesA: Int, takesB: Int): PlanResult {
        if (!EnrollmentRules.isReady(takesA)) return PlanResult.NotEnoughVoice
        val profiles = if (settings.twoSpeakers && twoSpeakersAvailable(takesA, takesB)) {
            listOf(Profile.A, Profile.B)
        } else {
            listOf(Profile.A)
        }
        val cal = settings.calibration
        if (cal == null) {
            return PlanResult.Ready(
                ProtectionPlan(GainMode.MANUAL, AppSettings.clampGain(settings.manualGainDb), false, profiles)
            )
        }
        if (cal.targetSnrDb == settings.targetSnrDb) {
            return PlanResult.Ready(ProtectionPlan(GainMode.CALIBRATED, cal.gainDb, false, profiles))
        }
        // SNR = speech - noise: a lower target SNR needs proportionally louder noise.
        val wanted = cal.gainDb + (cal.targetSnrDb - settings.targetSnrDb)
        val clamped = AppSettings.clampGain(wanted)
        return PlanResult.Ready(ProtectionPlan(GainMode.DERIVED, clamped, clamped != wanted, profiles))
    }
}

enum class ProtectionWarning {
    /** Manual mode: SNR has not been verified. */
    UNCALIBRATED,

    /** STREAM_MUSIC volume differs from the calibrated index. */
    VOLUME_CHANGED,

    /** Output type differs from the calibrated one (already at start; a change while playing stops playback). */
    ROUTE_CHANGED,

    /** Media volume is zero or the stream is muted: nothing is audible. */
    MUTED,

    /** Do Not Disturb in "total silence" mode mutes media players although the volume index is unchanged. */
    DND_SILENCE,

    /** The system has not told for several seconds which device the sound goes to. */
    ROUTE_UNKNOWN,

    /** Gain recomputed for a target other than the calibrated one. */
    DERIVED_GAIN,

    /** Recomputed gain does not fit the allowed range. */
    GAIN_CLAMPED,
}

/** What the service observes once per second while the noise plays. */
data class PlaybackConditions(
    val volumeIndex: Int,
    val streamMuted: Boolean = false,
    /** NotificationManager.INTERRUPTION_FILTER_NONE. */
    val totalSilence: Boolean = false,
    /** Type of the device the track is really routed to; null until the system has reported one. */
    val route: OutputRoute? = null,
    val routeUnknownTooLong: Boolean = false,
)

object MismatchDetector {
    /** Warnings that mean "the noise plays, but not as calibrated". */
    val MISMATCH: Set<ProtectionWarning> =
        setOf(ProtectionWarning.VOLUME_CHANGED, ProtectionWarning.ROUTE_CHANGED)

    /** Warnings that mean "nothing is audible at all": protection is effectively off. */
    val SILENT: Set<ProtectionWarning> = setOf(ProtectionWarning.MUTED, ProtectionWarning.DND_SILENCE)

    fun check(
        plan: ProtectionPlan,
        calibration: CalibrationRecord?,
        now: PlaybackConditions,
    ): Set<ProtectionWarning> {
        val out = LinkedHashSet<ProtectionWarning>()
        val muted = now.volumeIndex <= 0 || now.streamMuted
        if (muted) out.add(ProtectionWarning.MUTED)
        if (now.totalSilence) out.add(ProtectionWarning.DND_SILENCE)
        if (now.routeUnknownTooLong && now.route == null) out.add(ProtectionWarning.ROUTE_UNKNOWN)
        if (plan.mode == GainMode.MANUAL || calibration == null) {
            out.add(ProtectionWarning.UNCALIBRATED)
            return out
        }
        // A muted stream is reported as such; "volume changed" would only repeat it.
        if (!muted && now.volumeIndex != calibration.musicVolumeIndex) out.add(ProtectionWarning.VOLUME_CHANGED)
        if (now.route != null && now.route != calibration.route) out.add(ProtectionWarning.ROUTE_CHANGED)
        if (plan.mode == GainMode.DERIVED) out.add(ProtectionWarning.DERIVED_GAIN)
        if (plan.gainClamped) out.add(ProtectionWarning.GAIN_CLAMPED)
        return out
    }

    fun hasMismatch(warnings: Set<ProtectionWarning>): Boolean = warnings.any { it in MISMATCH }

    fun isSilent(warnings: Set<ProtectionWarning>): Boolean = warnings.any { it in SILENT }
}

/**
 * Watches the device an AudioTrack is routed to. The first device the system reports becomes the
 * baseline (the earbud on the target microphone). Any other device later on, in every mode, means the
 * noise no longer comes out of that earbud: e.g. Bluetooth earphones connected and took over the media
 * route, which does not trigger ACTION_AUDIO_BECOMING_NOISY. Not thread-safe.
 */
class RouteGuard(
    /** A track may be unrouted for a moment while the audio server restores it. */
    private val nullGraceMs: Long = 2500,
    private val unknownWarnMs: Long = 5000,
) {
    enum class Verdict {
        /** No device reported yet: the route must not be judged. */
        PENDING,

        /** Still no device after [unknownWarnMs]. */
        PENDING_TOO_LONG,
        STABLE,

        /** The device changed or vanished. Final. */
        LOST,
    }

    var baselineDeviceId: Int? = null
        private set

    private var startedAt: Long? = null
    private var nullSince: Long? = null
    private var lost = false

    val baselineKnown: Boolean get() = baselineDeviceId != null

    /** @param deviceId AudioDeviceInfo.getId() of the routed device, null when the track reports none */
    fun observe(deviceId: Int?, nowMs: Long): Verdict {
        if (lost) return Verdict.LOST
        val baseline = baselineDeviceId
        if (baseline == null) {
            if (deviceId != null) {
                baselineDeviceId = deviceId
                return Verdict.STABLE
            }
            val since = startedAt ?: nowMs.also { startedAt = it }
            return if (nowMs - since >= unknownWarnMs) Verdict.PENDING_TOO_LONG else Verdict.PENDING
        }
        if (deviceId == baseline) {
            nullSince = null
            return Verdict.STABLE
        }
        if (deviceId == null) {
            val since = nullSince ?: nowMs.also { nullSince = it }
            if (nowMs - since < nullGraceMs) return Verdict.STABLE
        }
        lost = true
        return Verdict.LOST
    }
}

/** Decides when to repeat the "no sound" alarm: at once when silence begins, then every [repeatMs]. */
class SilenceAlarm(private val repeatMs: Long = 10_000) {
    private var lastAlertAt: Long? = null

    val silentNow: Boolean get() = lastAlertAt != null

    /** @return true when the alarm should sound now */
    fun onObservation(silent: Boolean, nowMs: Long): Boolean {
        if (!silent) {
            lastAlertAt = null
            return false
        }
        val last = lastAlertAt
        if (last == null || nowMs - last >= repeatMs) {
            lastAlertAt = nowMs
            return true
        }
        return false
    }
}

enum class ProtectionStatus { IDLE, LOADING, RUNNING, STOPPED }

enum class StopReason {
    USER,

    /** ACTION_AUDIO_BECOMING_NOISY: headphones unplugged or Bluetooth lost. */
    HEADSET_DISCONNECTED,

    /** The sound moved to another output device, or the output vanished ([RouteGuard]). */
    OUTPUT_CHANGED,
    NOT_ENOUGH_VOICE,

    /** Takes exist but could not be decrypted or contain no usable speech. */
    VOICE_UNREADABLE,
    AUDIO_ERROR,

    /** The system refused to (re)start the foreground service or killed the process. */
    INTERRUPTED,
}

/** Snapshot of the protection service for the main screen. */
data class ProtectionState(
    val status: ProtectionStatus = ProtectionStatus.IDLE,
    val warnings: Set<ProtectionWarning> = emptySet(),
    val stopReason: StopReason? = null,
    val mode: GainMode? = null,
    val gainDb: Double = 0.0,
    val loadDone: Int = 0,
    val loadTotal: Int = 0,
    /** Type of the device the noise really comes out of; null until the system has reported it. */
    val route: OutputRoute? = null,
) {
    val active: Boolean get() = status == ProtectionStatus.LOADING || status == ProtectionStatus.RUNNING

    /**
     * The one-line status for the main screen and the notification title. Honesty rule: only a
     * calibrated, matching, audible playback may be presented as verified; everything else says
     * plainly what is not verified or not working.
     */
    val headline: Headline
        get() = when (status) {
            ProtectionStatus.IDLE -> Headline.OFF
            ProtectionStatus.LOADING -> Headline.LOADING
            ProtectionStatus.STOPPED ->
                if (stopReason == null || stopReason == StopReason.USER) Headline.OFF else Headline.STOPPED_ALERT
            ProtectionStatus.RUNNING -> when {
                MismatchDetector.isSilent(warnings) -> Headline.SILENT
                mode == GainMode.MANUAL || ProtectionWarning.UNCALIBRATED in warnings -> Headline.PLAYING_UNVERIFIED
                MismatchDetector.hasMismatch(warnings) -> Headline.PLAYING_MISMATCH
                ProtectionWarning.GAIN_CLAMPED in warnings -> Headline.PLAYING_LEVEL_NOT_REACHED
                mode == GainMode.DERIVED || ProtectionWarning.DERIVED_GAIN in warnings -> Headline.PLAYING_DERIVED
                mode == GainMode.CALIBRATED -> Headline.PLAYING_CALIBRATED
                else -> Headline.PLAYING_UNVERIFIED
            }
        }
}

enum class Headline {
    OFF,
    LOADING,

    /** Stopped for a reason the user did not ask for. */
    STOPPED_ALERT,

    /** Playing, but nothing is audible. */
    SILENT,

    /** Playing at a level nobody measured (manual mode). */
    PLAYING_UNVERIFIED,

    /** Playing, but volume or output differ from the calibration. */
    PLAYING_MISMATCH,

    /** Playing, but the gain range does not reach the target. */
    PLAYING_LEVEL_NOT_REACHED,

    /** Playing at a level recomputed for another target. */
    PLAYING_DERIVED,

    /** Playing as calibrated: the only state shown as good. */
    PLAYING_CALIBRATED;

    /** States that must alarm the user. */
    val alarming: Boolean
        get() = this == STOPPED_ALERT || this == SILENT || this == PLAYING_MISMATCH || this == PLAYING_LEVEL_NOT_REACHED
}

/** Thread-safe observable value. Listeners are called on the thread that sets the value. */
class Observable<T>(initial: T) {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(T) -> Unit>()

    @Volatile
    var value: T = initial
        set(newValue) {
            field = newValue
            for (listener in listeners) listener(newValue)
        }

    fun addListener(listener: (T) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (T) -> Unit) {
        listeners.remove(listener)
    }
}
