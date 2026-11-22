package io.github.victorkolesnyk.hamir.logic

/** Where the noise comes out. Stored with the calibration because loudness depends on it. */
enum class OutputRoute { WIRED, BLUETOOTH, SPEAKER, OTHER }

/** Result of a successful calibration (SPEC section 7, step 4). */
data class CalibrationRecord(
    val gainDb: Double,
    /** Target the gain was solved for: -5 or 0. */
    val targetSnrDb: Int,
    val musicVolumeIndex: Int,
    val route: OutputRoute,
    val speechDbfs: Double,
    val noiseDbfs: Double,
    val achievedSnrDb: Double,
    val timeMillis: Long,
)

data class AppSettings(
    val targetSnrDb: Int = TARGET_RECOMMENDED,
    val twoSpeakers: Boolean = false,
    val manualGainDb: Double = DEFAULT_MANUAL_GAIN_DB,
    val calibration: CalibrationRecord? = null,
    /** True between a successful start and an orderly stop; lets the UI detect a killed process. */
    val protectionActive: Boolean = false,
) {
    companion object {
        const val TARGET_RECOMMENDED = -5
        const val TARGET_EQUAL = 0
        const val MIN_GAIN_DB = -40.0
        const val MAX_GAIN_DB = 12.0
        const val DEFAULT_MANUAL_GAIN_DB = -6.0

        fun sanitizeTarget(value: Int): Int = if (value == TARGET_EQUAL) TARGET_EQUAL else TARGET_RECOMMENDED

        fun clampGain(value: Double): Double =
            if (value.isNaN()) DEFAULT_MANUAL_GAIN_DB else value.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    }
}

/** Minimal persistent string map. The Android implementation wraps SharedPreferences. */
interface KeyValueStore {
    fun read(): Map<String, String>

    /** Replaces the given keys; a null value removes the key. */
    fun write(changes: Map<String, String?>)
}

/**
 * Typed settings on top of a [KeyValueStore]. Unknown or damaged values fall back to defaults,
 * a damaged calibration is treated as absent. Thread-safe; call off the UI thread.
 */
class SettingsRepository(private val store: KeyValueStore) {

    @Synchronized
    fun load(): AppSettings = decode(store.read())

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val updated = transform(decode(store.read()))
        store.write(encode(updated))
        return updated
    }

    companion object {
        private const val K_TARGET = "target_snr_db"
        private const val K_TWO = "two_speakers"
        private const val K_MANUAL_GAIN = "manual_gain_db"
        private const val K_ACTIVE = "protection_active"
        private const val K_CAL_GAIN = "cal_gain_db"
        private const val K_CAL_TARGET = "cal_target_snr_db"
        private const val K_CAL_VOLUME = "cal_music_volume_index"
        private const val K_CAL_ROUTE = "cal_route"
        private const val K_CAL_SPEECH = "cal_speech_dbfs"
        private const val K_CAL_NOISE = "cal_noise_dbfs"
        private const val K_CAL_SNR = "cal_achieved_snr_db"
        private const val K_CAL_TIME = "cal_time_millis"

        private val CAL_KEYS = listOf(
            K_CAL_GAIN, K_CAL_TARGET, K_CAL_VOLUME, K_CAL_ROUTE, K_CAL_SPEECH, K_CAL_NOISE, K_CAL_SNR, K_CAL_TIME,
        )

        fun decode(map: Map<String, String>): AppSettings {
            val defaults = AppSettings()
            return AppSettings(
                targetSnrDb = AppSettings.sanitizeTarget(map[K_TARGET]?.toIntOrNull() ?: defaults.targetSnrDb),
                twoSpeakers = map[K_TWO] == "true",
                manualGainDb = AppSettings.clampGain(finite(map[K_MANUAL_GAIN]) ?: defaults.manualGainDb),
                calibration = decodeCalibration(map),
                protectionActive = map[K_ACTIVE] == "true",
            )
        }

        fun encode(settings: AppSettings): Map<String, String?> {
            val out = LinkedHashMap<String, String?>()
            out[K_TARGET] = settings.targetSnrDb.toString()
            out[K_TWO] = settings.twoSpeakers.toString()
            out[K_MANUAL_GAIN] = settings.manualGainDb.toString()
            out[K_ACTIVE] = settings.protectionActive.toString()
            val cal = settings.calibration
            if (cal == null) {
                for (key in CAL_KEYS) out[key] = null
            } else {
                out[K_CAL_GAIN] = cal.gainDb.toString()
                out[K_CAL_TARGET] = cal.targetSnrDb.toString()
                out[K_CAL_VOLUME] = cal.musicVolumeIndex.toString()
                out[K_CAL_ROUTE] = cal.route.name
                out[K_CAL_SPEECH] = cal.speechDbfs.toString()
                out[K_CAL_NOISE] = cal.noiseDbfs.toString()
                out[K_CAL_SNR] = cal.achievedSnrDb.toString()
                out[K_CAL_TIME] = cal.timeMillis.toString()
            }
            return out
        }

        private fun decodeCalibration(map: Map<String, String>): CalibrationRecord? {
            val route = map[K_CAL_ROUTE]?.let { name -> OutputRoute.values().firstOrNull { it.name == name } }
            return CalibrationRecord(
                gainDb = finite(map[K_CAL_GAIN]) ?: return null,
                targetSnrDb = map[K_CAL_TARGET]?.toIntOrNull() ?: return null,
                musicVolumeIndex = map[K_CAL_VOLUME]?.toIntOrNull() ?: return null,
                route = route ?: return null,
                speechDbfs = finite(map[K_CAL_SPEECH]) ?: return null,
                noiseDbfs = finite(map[K_CAL_NOISE]) ?: return null,
                achievedSnrDb = finite(map[K_CAL_SNR]) ?: return null,
                timeMillis = map[K_CAL_TIME]?.toLongOrNull() ?: return null,
            )
        }

        private fun finite(text: String?): Double? {
            val value = text?.toDoubleOrNull() ?: return null
            return if (value.isNaN() || value.isInfinite()) null else value
        }
    }
}
