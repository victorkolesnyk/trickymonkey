package io.github.victorkolesnyk.hamir.logic

import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory replacement for the envelope key provider. */
class MemoryKeyProvider : BankKeyProvider {
    var key: SecretKey? = null
    var created = 0
    var deleted = 0

    /** Simulates a stored key that can no longer be opened. */
    var broken = false

    override fun existingKey(): SecretKey? {
        if (broken) throw BankKeyLostException("broken")
        return key
    }

    override fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        created++
        return generator.generateKey().also { key = it }
    }

    override fun deleteKey() {
        key = null
        broken = false
        deleted++
    }
}

/**
 * Stand-in for BankCodec with the same size overhead (32 bytes), so layout tests do not depend
 * on the core implementation. "Authentication" is a key fingerprint in the header.
 */
object FakeCodec : TakeCodec {
    override fun encrypt(pcm: ShortArray, key: SecretKey): ByteArray {
        val out = ByteArray(BankStore.FILE_OVERHEAD_BYTES + pcm.size * 2)
        val mark = key.encoded
        for (i in 0 until BankStore.FILE_OVERHEAD_BYTES) out[i] = mark[i % mark.size]
        for (i in pcm.indices) {
            out[BankStore.FILE_OVERHEAD_BYTES + 2 * i] = (pcm[i].toInt() and 0xff).toByte()
            out[BankStore.FILE_OVERHEAD_BYTES + 2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        return out
    }

    override fun decrypt(data: ByteArray, key: SecretKey): ShortArray {
        require(data.size >= BankStore.FILE_OVERHEAD_BYTES) { "short" }
        val mark = key.encoded
        for (i in 0 until BankStore.FILE_OVERHEAD_BYTES) {
            if (data[i] != mark[i % mark.size]) throw GeneralSecurityException("wrong key")
        }
        val n = (data.size - BankStore.FILE_OVERHEAD_BYTES) / 2
        return ShortArray(n) { i ->
            val lo = data[BankStore.FILE_OVERHEAD_BYTES + 2 * i].toInt() and 0xff
            val hi = data[BankStore.FILE_OVERHEAD_BYTES + 2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort()
        }
    }
}

class BankStoreTest {
    private lateinit var dir: File
    private lateinit var keys: MemoryKeyProvider
    private lateinit var store: BankStore

    private fun take(length: Int, seed: Int = 1) = ShortArray(length) { ((it * 31 + seed * 977) % 20000 - 10000).toShort() }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("hamir-bank").toFile()
        keys = MemoryKeyProvider()
        store = BankStore(File(dir, "bank"), keys, FakeCodec)
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun layoutIsProfileDirAndThreeDigitNumber() {
        store.saveTake(Profile.A, 7, take(100))
        store.saveTake(Profile.B, 100, take(100))
        assertTrue(File(dir, "bank/A/007.hmr").isFile)
        assertTrue(File(dir, "bank/B/100.hmr").isFile)
        assertEquals("007.hmr", store.takeFile(Profile.A, 7).name)
    }

    @Test
    fun emptyStoreHasNoTakes() {
        assertEquals(emptyList(), store.takeNumbers(Profile.A))
        assertEquals(ProfileStats(0, 0.0), store.stats(Profile.A))
        assertFalse(store.hasTake(Profile.A, 1))
        val loaded = store.loadAll(Profile.A)
        assertEquals(0, loaded.takes.size)
        assertEquals(0, loaded.failed)
        assertEquals(0, keys.created, "reading must not create a key")
        assertFailsWith<java.io.IOException> { store.loadTake(Profile.A, 1) }
    }

    @Test
    fun saveAndLoadRoundTrip() {
        val pcm = take(3200)
        store.saveTake(Profile.A, 12, pcm)
        assertTrue(store.hasTake(Profile.A, 12))
        assertContentEquals(pcm, store.loadTake(Profile.A, 12))
    }

    @Test
    fun takeNumbersAreSortedAndPerProfile() {
        for (n in listOf(30, 2, 100, 17)) store.saveTake(Profile.A, n, take(10, n))
        store.saveTake(Profile.B, 5, take(10))
        assertEquals(listOf(2, 17, 30, 100), store.takeNumbers(Profile.A))
        assertEquals(listOf(5), store.takeNumbers(Profile.B))
    }

    @Test
    fun overwriteReplacesTakeAndLeavesNoTempFile() {
        store.saveTake(Profile.A, 3, take(100, 1))
        val second = take(250, 2)
        store.saveTake(Profile.A, 3, second)
        assertContentEquals(second, store.loadTake(Profile.A, 3))
        assertEquals(listOf("003.hmr"), store.profileDir(Profile.A).list()!!.toList())
    }

    @Test
    fun statsCountTakesAndSecondsFromFileSizes() {
        store.saveTake(Profile.A, 1, take(32000))
        store.saveTake(Profile.A, 2, take(16000))
        val stats = store.stats(Profile.A)
        assertEquals(2, stats.takeCount)
        assertEquals(1.5, stats.recordedSeconds, 1e-9)
        assertFalse(stats.ready)
    }

    @Test
    fun foreignFilesAreIgnored() {
        store.saveTake(Profile.A, 1, take(10))
        val profileDir = store.profileDir(Profile.A)
        File(profileDir, "notes.txt").writeText("x")
        File(profileDir, "1.hmr").writeText("x")
        File(profileDir, "0001.hmr").writeText("x")
        File(profileDir, "000.hmr").writeText("x")
        File(profileDir, "abc.hmr").writeText("x")
        File(profileDir, "002.hmr.tmp").writeText("x")
        File(profileDir, "005.hmr").mkdir()
        assertEquals(listOf(1), store.takeNumbers(Profile.A))
        assertEquals(1, store.stats(Profile.A).takeCount)
    }

    @Test
    fun parseNumberAcceptsOnlyCanonicalNames() {
        assertEquals(1, BankStore.parseNumber("001.hmr"))
        assertEquals(999, BankStore.parseNumber("999.hmr"))
        assertNull(BankStore.parseNumber("000.hmr"))
        assertNull(BankStore.parseNumber("01.hmr"))
        assertNull(BankStore.parseNumber("0010.hmr"))
        assertNull(BankStore.parseNumber("001.HMR"))
        assertNull(BankStore.parseNumber("-01.hmr"))
        assertNull(BankStore.parseNumber("١٢٣.hmr"))
    }

    @Test
    fun numberOutOfRangeIsRejected() {
        assertFailsWith<IllegalArgumentException> { store.saveTake(Profile.A, 0, take(10)) }
        assertFailsWith<IllegalArgumentException> { store.takeFile(Profile.A, 1000) }
    }

    @Test
    fun loadAllSkipsDamagedFilesAndReportsProgress() {
        store.saveTake(Profile.A, 1, take(100, 1))
        store.saveTake(Profile.A, 2, take(100, 2))
        store.saveTake(Profile.A, 3, take(100, 3))
        store.takeFile(Profile.A, 2).writeBytes(ByteArray(200) { 0x55 })
        val progress = ArrayList<Pair<Int, Int>>()
        val loaded = store.loadAll(Profile.A, onProgress = { done, total -> progress.add(done to total) })
        assertEquals(2, loaded.takes.size)
        assertEquals(1, loaded.failed)
        assertContentEquals(take(100, 1), loaded.takes[0])
        assertContentEquals(take(100, 3), loaded.takes[1])
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test
    fun loadAllStopsWhenCancelled() {
        for (n in 1..5) store.saveTake(Profile.A, n, take(10, n))
        var polls = 0
        val loaded = store.loadAll(Profile.A, isCancelled = { ++polls > 2 })
        assertEquals(2, loaded.takes.size)
    }

    @Test
    fun firstSaveCreatesTheKeyAndLaterSavesReuseIt() {
        store.saveTake(Profile.A, 1, take(100))
        store.saveTake(Profile.B, 1, take(100))
        store.saveTake(Profile.A, 2, take(100))
        assertEquals(1, keys.created)
    }

    @Test
    fun lostKeyMakesTakesUnreadableAndIsReported() {
        store.saveTake(Profile.A, 1, take(100))
        keys.key = null
        val loaded = store.loadAll(Profile.A)
        assertEquals(0, loaded.takes.size)
        assertEquals(1, loaded.failed)
        assertTrue(loaded.keyLost)
        assertFailsWith<BankKeyLostException> { store.loadTake(Profile.A, 1) }
    }

    @Test
    fun noNewKeyIsCreatedOverExistingTakes() {
        store.saveTake(Profile.A, 1, take(100))
        keys.key = null
        // Neither into the same profile nor into the other one.
        assertFailsWith<BankKeyLostException> { store.saveTake(Profile.A, 2, take(100)) }
        assertFailsWith<BankKeyLostException> { store.saveTake(Profile.B, 1, take(100)) }
        assertEquals(1, keys.created, "a second key must not be generated silently")
        assertEquals(listOf(1), store.takeNumbers(Profile.A))
        assertEquals(emptyList(), store.takeNumbers(Profile.B))
    }

    @Test
    fun keyThatCannotBeOpenedIsTreatedLikeALostKey() {
        store.saveTake(Profile.A, 1, take(100))
        keys.broken = true
        assertTrue(store.loadAll(Profile.A).keyLost)
        assertFailsWith<BankKeyLostException> { store.saveTake(Profile.A, 2, take(100)) }
        assertFailsWith<BankKeyLostException> { store.loadTake(Profile.A, 1) }
    }

    @Test
    fun deletingEverythingAllowsAFreshStartAfterKeyLoss() {
        store.saveTake(Profile.A, 1, take(100))
        keys.key = null
        assertTrue(store.deleteAll())
        store.saveTake(Profile.A, 1, take(100))
        assertEquals(2, keys.created)
        assertContentEquals(take(100), store.loadTake(Profile.A, 1))
    }

    @Test
    fun hasAnyTakeLooksAtBothProfiles() {
        assertFalse(store.hasAnyTake())
        store.saveTake(Profile.B, 7, take(10))
        assertTrue(store.hasAnyTake())
        store.deleteProfile(Profile.B)
        assertFalse(store.hasAnyTake())
    }

    @Test
    fun loadingAMissingTakeThrowsIOException() {
        store.saveTake(Profile.A, 1, take(10))
        assertFailsWith<java.io.IOException> { store.loadTake(Profile.A, 9) }
    }

    @Test
    fun deleteTakeRemovesOnlyThatTake() {
        store.saveTake(Profile.A, 1, take(10))
        store.saveTake(Profile.A, 2, take(10))
        assertTrue(store.deleteTake(Profile.A, 1))
        assertFalse(store.deleteTake(Profile.A, 1))
        assertEquals(listOf(2), store.takeNumbers(Profile.A))
    }

    @Test
    fun deleteProfileKeepsTheOtherProfileAndTheKey() {
        store.saveTake(Profile.A, 1, take(10))
        store.saveTake(Profile.B, 1, take(10))
        assertTrue(store.deleteProfile(Profile.A))
        assertEquals(emptyList(), store.takeNumbers(Profile.A))
        assertEquals(listOf(1), store.takeNumbers(Profile.B))
        assertEquals(0, keys.deleted)
        assertFalse(store.profileDir(Profile.A).exists())
    }

    @Test
    fun deleteAllRemovesFilesAndDestroysTheKey() {
        store.saveTake(Profile.A, 1, take(10))
        store.saveTake(Profile.B, 2, take(10))
        assertTrue(store.deleteAll())
        assertFalse(File(dir, "bank").exists())
        assertEquals(1, keys.deleted)
        assertNull(keys.key)
        // The store stays usable afterwards with a fresh key.
        store.saveTake(Profile.A, 1, take(10))
        assertEquals(listOf(1), store.takeNumbers(Profile.A))
    }

    @Test
    fun deleteAllOnEmptyStoreSucceeds() {
        assertTrue(store.deleteAll())
    }

    @Test
    fun realCodecRoundTripAndTamperDetection() {
        val real = BankStore(File(dir, "real"), keys)
        val pcm = take(5000)
        real.saveTake(Profile.A, 1, pcm)
        val file = real.takeFile(Profile.A, 1)
        assertEquals(
            (BankStore.FILE_OVERHEAD_BYTES + pcm.size * 2).toLong(), file.length(),
            "file size must match the documented HMR1 layout: stats depend on it",
        )
        assertContentEquals(pcm, real.loadTake(Profile.A, 1))
        assertEquals(5000.0 / 32000.0, real.stats(Profile.A).recordedSeconds, 1e-9)

        val bytes = file.readBytes()
        bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertFailsWith<GeneralSecurityException> { real.loadTake(Profile.A, 1) }
        assertEquals(1, real.loadAll(Profile.A).failed)
    }

    @Test
    fun realCodecDoesNotStorePlainPcm() {
        val real = BankStore(File(dir, "real"), keys)
        val pcm = ShortArray(4000) { 0x1234 }
        real.saveTake(Profile.A, 1, pcm)
        real.saveTake(Profile.A, 2, pcm)
        val first = real.takeFile(Profile.A, 1).readBytes()
        val second = real.takeFile(Profile.A, 2).readBytes()
        var plainPairs = 0
        for (i in BankStore.FILE_OVERHEAD_BYTES - 16 until first.size - 1) {
            if (first[i] == 0x34.toByte() && first[i + 1] == 0x12.toByte()) plainPairs++
        }
        assertTrue(plainPairs < 40, "ciphertext looks like plain PCM")
        assertNotEquals(first.toList(), second.toList(), "same take must encrypt differently (fresh IV)")
    }
}
