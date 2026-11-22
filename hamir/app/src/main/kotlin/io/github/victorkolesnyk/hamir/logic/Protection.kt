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

    /** Output type differs from the calibrated one. */
    ROUTE_CHANGED,

    /** Media volume is zero: nothing is audible. */
    MUTED,

    /** Gain recomputed for a target other than the calibrated one. */
    DERIVED_GAIN,

    /** Recomputed gain does not fit the allowed range. */
    GAIN_CLAMPED,
}

object MismatchDetector {
    /** Warnings that mean "playback no longer matches what was calibrated". */
    val MISMATCH: Set<ProtectionWarning> =
        setOf(ProtectionWarning.VOLUME_CHANGED, ProtectionWarning.ROUTE_CHANGED, ProtectionWarning.MUTED)

    /**
     * @param currentRoute null when the route cannot be determined; then no route warning is raised
     */
    fun check(
        plan: ProtectionPlan,
        calibration: CalibrationRecord?,
        currentVolumeIndex: Int,
        currentRoute: OutputRoute?,
    ): Set<ProtectionWarning> {
        val out = LinkedHashSet<ProtectionWarning>()
        if (currentVolumeIndex <= 0) out.add(ProtectionWarning.MUTED)
        if (plan.mode == GainMode.MANUAL || calibration == null) {
            out.add(ProtectionWarning.UNCALIBRATED)
            return out
        }
        if (currentVolumeIndex != calibration.musicVolumeIndex) out.add(ProtectionWarning.VOLUME_CHANGED)
        if (currentRoute != null && currentRoute != calibration.route) out.add(ProtectionWarning.ROUTE_CHANGED)
        if (plan.mode == GainMode.DERIVED) out.add(ProtectionWarning.DERIVED_GAIN)
        if (plan.gainClamped) out.add(ProtectionWarning.GAIN_CLAMPED)
        return out
    }

    fun hasMismatch(warnings: Set<ProtectionWarning>): Boolean = warnings.any { it in MISMATCH }
}

enum class ProtectionStatus { IDLE, LOADING, RUNNING, STOPPED }

enum class StopReason {
    USER,

    /** ACTION_AUDIO_BECOMING_NOISY: headphones unplugged or Bluetooth lost. */
    HEADSET_DISCONNECTED,
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
) {
    val active: Boolean get() = status == ProtectionStatus.LOADING || status == ProtectionStatus.RUNNING
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
