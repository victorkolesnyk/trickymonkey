package io.github.victorkolesnyk.hamir.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryStore : KeyValueStore {
    val map = HashMap<String, String>()
    override fun read(): Map<String, String> = HashMap(map)
    override fun write(changes: Map<String, String?>) {
        for ((key, value) in changes) if (value == null) map.remove(key) else map[key] = value
    }
}

class SettingsTest {
    private val record = CalibrationRecord(
        gainDb = 3.25, targetSnrDb = -5, musicVolumeIndex = 11, route = OutputRoute.WIRED,
        speechDbfs = -31.5, noiseDbfs = -26.4, achievedSnrDb = -5.1, timeMillis = 1_790_000_000_000,
    )

    @Test
    fun emptyStoreGivesDefaults() {
        val settings = SettingsRepository(MemoryStore()).load()
        assertEquals(AppSettings(), settings)
        assertEquals(-5, settings.targetSnrDb)
        assertFalse(settings.twoSpeakers)
        assertNull(settings.calibration)
        assertFalse(settings.protectionActive)
    }

    @Test
    fun everythingSurvivesARoundTrip() {
        val store = MemoryStore()
        val saved = AppSettings(0, true, -17.5, record, true)
        SettingsRepository(store).update { saved }
        assertEquals(saved, SettingsRepository(store).load())
    }

    @Test
    fun updateTransformsTheStoredValue() {
        val repo = SettingsRepository(MemoryStore())
        repo.update { it.copy(twoSpeakers = true) }
        val result = repo.update { it.copy(targetSnrDb = 0) }
        assertTrue(result.twoSpeakers)
        assertEquals(0, repo.load().targetSnrDb)
    }

    @Test
    fun clearingCalibrationRemovesItsKeys() {
        val store = MemoryStore()
        val repo = SettingsRepository(store)
        repo.update { it.copy(calibration = record) }
        assertTrue(store.map.keys.any { it.startsWith("cal_") })
        repo.update { it.copy(calibration = null) }
        assertFalse(store.map.keys.any { it.startsWith("cal_") })
        assertNull(repo.load().calibration)
    }

    @Test
    fun damagedValuesFallBackToDefaults() {
        val store = MemoryStore()
        store.map["target_snr_db"] = "seven"
        store.map["manual_gain_db"] = "NaN"
        store.map["two_speakers"] = "yes"
        val settings = SettingsRepository(store).load()
        assertEquals(-5, settings.targetSnrDb)
        assertEquals(AppSettings.DEFAULT_MANUAL_GAIN_DB, settings.manualGainDb)
        assertFalse(settings.twoSpeakers)
    }

    @Test
    fun unsupportedTargetAndGainAreSanitized() {
        val store = MemoryStore()
        store.map["target_snr_db"] = "-12"
        store.map["manual_gain_db"] = "80"
        val settings = SettingsRepository(store).load()
        assertEquals(-5, settings.targetSnrDb)
        assertEquals(12.0, settings.manualGainDb)
    }

    @Test
    fun partialOrDamagedCalibrationIsTreatedAsAbsent() {
        val store = MemoryStore()
        val repo = SettingsRepository(store)
        repo.update { it.copy(calibration = record) }
        store.map.remove("cal_route")
        assertNull(repo.load().calibration)

        repo.update { it.copy(calibration = record) }
        store.map["cal_gain_db"] = "Infinity"
        assertNull(repo.load().calibration)

        repo.update { it.copy(calibration = record) }
        store.map["cal_route"] = "TELEPATHY"
        assertNull(repo.load().calibration)
    }
}
