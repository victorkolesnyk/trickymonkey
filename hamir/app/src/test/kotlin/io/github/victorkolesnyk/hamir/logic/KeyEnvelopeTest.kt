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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** In-memory stand-in for the AndroidKeyStore wrapping key. */
class MemoryWrappingKey : WrappingKeySource {
    var key: SecretKey? = null
    var created = 0
    var deleted = 0
    var uses = 0

    override fun existing(): SecretKey? {
        uses++
        return key
    }

    override fun getOrCreate(): SecretKey {
        uses++
        key?.let { return it }
        created++
        return newAesKey().also { key = it }
    }

    override fun delete() {
        key = null
        deleted++
    }

    companion object {
        fun newAesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}

class KeyEnvelopeTest {
    private lateinit var dir: File
    private lateinit var keyFile: File
    private lateinit var wrapping: MemoryWrappingKey

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("hamir-key").toFile()
        keyFile = File(dir, "bank/key.bin")
        wrapping = MemoryWrappingKey()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun dataKey(seed: Int = 1) = ByteArray(32) { (it * 7 + seed).toByte() }

    // ---------------------------------------------------------------- format

    @Test
    fun blobHasTheDocumentedLayout() {
        val blob = KeyEnvelope.wrap(dataKey(), MemoryWrappingKey.newAesKey())
        assertEquals(4 + 12 + 32 + 16, blob.size)
        assertEquals(KeyEnvelope.BLOB_BYTES, blob.size)
        assertEquals("HMK1", String(blob, 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun wrapAndUnwrapRoundTrip() {
        val key = MemoryWrappingKey.newAesKey()
        assertContentEquals(dataKey(5), KeyEnvelope.unwrap(KeyEnvelope.wrap(dataKey(5), key), key))
    }

    @Test
    fun everyWrapUsesAFreshIvAndHidesTheKey() {
        val key = MemoryWrappingKey.newAesKey()
        val first = KeyEnvelope.wrap(dataKey(), key)
        val second = KeyEnvelope.wrap(dataKey(), key)
        assertNotEquals(first.toList(), second.toList())
        assertNotEquals(first.copyOfRange(4, 16).toList(), second.copyOfRange(4, 16).toList(), "IV repeated")
        assertNotEquals(dataKey().toList(), first.copyOfRange(16, 48).toList(), "data key stored in the clear")
    }

    @Test
    fun wrongWrappingKeyIsRejected() {
        val blob = KeyEnvelope.wrap(dataKey(), MemoryWrappingKey.newAesKey())
        assertFailsWith<GeneralSecurityException> { KeyEnvelope.unwrap(blob, MemoryWrappingKey.newAesKey()) }
    }

    @Test
    fun anyFlippedBitIsRejected() {
        val key = MemoryWrappingKey.newAesKey()
        val blob = KeyEnvelope.wrap(dataKey(), key)
        for (index in listOf(4, 15, 16, 40, blob.size - 1)) {
            val damaged = blob.copyOf()
            damaged[index] = (damaged[index].toInt() xor 0x10).toByte()
            assertFailsWith<GeneralSecurityException>("byte $index") { KeyEnvelope.unwrap(damaged, key) }
        }
    }

    @Test
    fun badHeaderOrLengthIsRejected() {
        val key = MemoryWrappingKey.newAesKey()
        val blob = KeyEnvelope.wrap(dataKey(), key)
        val wrongMagic = blob.copyOf().also { it[3] = '2'.code.toByte() }
        assertFailsWith<IllegalArgumentException> { KeyEnvelope.unwrap(wrongMagic, key) }
        assertFailsWith<IllegalArgumentException> { KeyEnvelope.unwrap(blob.copyOf(blob.size - 1), key) }
        assertFailsWith<IllegalArgumentException> { KeyEnvelope.unwrap(blob + 0, key) }
        assertFailsWith<IllegalArgumentException> { KeyEnvelope.unwrap(ByteArray(0), key) }
        assertFailsWith<IllegalArgumentException> { KeyEnvelope.wrap(ByteArray(16), key) }
    }

    // ---------------------------------------------------------------- provider

    @Test
    fun noKeyExistsBeforeTheFirstCreate() {
        val provider = EnvelopeKeyProvider(keyFile, wrapping)
        assertNull(provider.existingKey())
        assertFalse(keyFile.exists())
        assertEquals(0, wrapping.created, "asking must not create the keystore key")
    }

    @Test
    fun createStoresTheWrappedKeyAndCachesTheDataKey() {
        val provider = EnvelopeKeyProvider(keyFile, wrapping)
        val key = provider.createKey()
        assertEquals("AES", key.algorithm)
        assertEquals(32, key.encoded.size)
        assertEquals(KeyEnvelope.BLOB_BYTES.toLong(), keyFile.length())
        assertEquals(listOf("key.bin"), keyFile.parentFile.list()!!.toList(), "no temp file may stay behind")
        val usesAfterCreate = wrapping.uses
        assertSame(key, provider.existingKey())
        assertSame(key, provider.existingKey())
        assertEquals(usesAfterCreate, wrapping.uses, "the keystore key must not be touched again in this process")
    }

    @Test
    fun aNewProcessGetsTheSameDataKeyBackFromTheFile() {
        val first = EnvelopeKeyProvider(keyFile, wrapping).createKey()
        val reopened = EnvelopeKeyProvider(keyFile, wrapping).existingKey()
        assertNotNull(reopened)
        assertContentEquals(first.encoded, reopened.encoded)
    }

    @Test
    fun differentBanksGetDifferentDataKeys() {
        val a = EnvelopeKeyProvider(File(dir, "a/key.bin"), wrapping).createKey()
        val b = EnvelopeKeyProvider(File(dir, "b/key.bin"), wrapping).createKey()
        assertNotEquals(a.encoded.toList(), b.encoded.toList())
    }

    @Test
    fun missingWrappingKeyMeansTheBankKeyIsLost() {
        EnvelopeKeyProvider(keyFile, wrapping).createKey()
        wrapping.key = null
        assertFailsWith<BankKeyLostException> { EnvelopeKeyProvider(keyFile, wrapping).existingKey() }
        assertEquals(1, wrapping.created, "a lost keystore key must not be regenerated behind the user's back")
    }

    @Test
    fun replacedWrappingKeyOrDamagedFileMeansTheBankKeyIsLost() {
        EnvelopeKeyProvider(keyFile, wrapping).createKey()
        val original = keyFile.readBytes()

        wrapping.key = MemoryWrappingKey.newAesKey()
        assertFailsWith<BankKeyLostException> { EnvelopeKeyProvider(keyFile, wrapping).existingKey() }

        keyFile.writeBytes(original.copyOf(10))
        assertFailsWith<BankKeyLostException> { EnvelopeKeyProvider(keyFile, wrapping).existingKey() }

        keyFile.writeBytes(ByteArray(original.size))
        assertFailsWith<BankKeyLostException> { EnvelopeKeyProvider(keyFile, wrapping).existingKey() }
    }

    @Test
    fun keystoreFaultsOfAnyKindAreReportedAsKeyUnavailable() {
        EnvelopeKeyProvider(keyFile, wrapping).createKey()
        val faulty = object : WrappingKeySource {
            override fun existing(): SecretKey? = throw java.security.ProviderException("keystore daemon failed")
            override fun getOrCreate(): SecretKey = throw java.security.ProviderException("keystore daemon failed")
            override fun delete() = Unit
        }
        assertFailsWith<BankKeyLostException> { EnvelopeKeyProvider(keyFile, faulty).existingKey() }
        // The fault may pass: the key file must still be there for the next attempt.
        assertTrue(keyFile.isFile)
        assertNotNull(EnvelopeKeyProvider(keyFile, wrapping).existingKey())
    }

    @Test
    fun damagedKeyFileIsReplacedOnlyWhileTheBankIsEmpty() {
        val root = keyFile.parentFile
        val first = BankStore(root, EnvelopeKeyProvider(keyFile, wrapping))
        first.saveTake(Profile.A, 1, take(1000))
        first.deleteTake(Profile.A, 1)
        keyFile.writeBytes(ByteArray(KeyEnvelope.BLOB_BYTES))

        val store = BankStore(root, EnvelopeKeyProvider(keyFile, wrapping))
        store.saveTake(Profile.A, 1, take(700))
        assertContentEquals(take(700), BankStore(root, EnvelopeKeyProvider(keyFile, wrapping)).loadTake(Profile.A, 1))

        // With a take on disk the same damage must not be papered over.
        keyFile.writeBytes(ByteArray(KeyEnvelope.BLOB_BYTES))
        val later = BankStore(root, EnvelopeKeyProvider(keyFile, wrapping))
        assertFailsWith<BankKeyLostException> { later.saveTake(Profile.A, 2, take(700)) }
        assertEquals(KeyEnvelope.BLOB_BYTES.toLong(), keyFile.length())
    }

    @Test
    fun deleteRemovesFileCacheAndWrappingKey() {
        val provider = EnvelopeKeyProvider(keyFile, wrapping)
        val old = provider.createKey()
        provider.deleteKey()
        assertFalse(keyFile.exists())
        assertEquals(1, wrapping.deleted)
        assertNull(wrapping.key)
        assertNull(provider.existingKey(), "the cached key must be forgotten")
        val fresh = provider.createKey()
        assertNotEquals(old.encoded.toList(), fresh.encoded.toList())
        provider.deleteKey()
        provider.deleteKey()
    }

    // ---------------------------------------------------------------- together with the store and the real codec

    private fun take(n: Int) = ShortArray(n) { (it * 13 % 9000 - 4500).toShort() }

    @Test
    fun storeWithEnvelopeKeyRoundTripsAcrossProcesses() {
        val root = File(dir, "bank")
        val store = BankStore(root, EnvelopeKeyProvider(File(root, "key.bin"), wrapping))
        store.saveTake(Profile.A, 1, take(4000))
        store.saveTake(Profile.B, 2, take(3000))
        assertTrue(File(root, "key.bin").isFile)
        assertEquals(listOf(1), store.takeNumbers(Profile.A), "key.bin must not be mistaken for a take")

        val reopened = BankStore(root, EnvelopeKeyProvider(File(root, "key.bin"), wrapping))
        assertContentEquals(take(4000), reopened.loadTake(Profile.A, 1))
        assertContentEquals(take(3000), reopened.loadTake(Profile.B, 2))
        assertEquals(0, reopened.loadAll(Profile.A).failed)
    }

    @Test
    fun deletedKeyFileIsReportedAndNeverReplacedOverExistingTakes() {
        val root = File(dir, "bank")
        BankStore(root, EnvelopeKeyProvider(File(root, "key.bin"), wrapping)).saveTake(Profile.A, 1, take(4000))
        assertTrue(File(root, "key.bin").delete())

        val store = BankStore(root, EnvelopeKeyProvider(File(root, "key.bin"), wrapping))
        val loaded = store.loadAll(Profile.A)
        assertTrue(loaded.keyLost)
        assertEquals(1, loaded.failed)
        assertFailsWith<BankKeyLostException> { store.saveTake(Profile.A, 2, take(100)) }
        assertFalse(File(root, "key.bin").exists(), "no new key may appear while old takes exist")
        assertIs<BankLoadResult.Unreadable>(BankLoader.load(store, listOf(Profile.A)))
    }

    @Test
    fun deleteAllRemovesTakesKeyFileAndWrappingKey() {
        val root = File(dir, "bank")
        val store = BankStore(root, EnvelopeKeyProvider(File(root, "key.bin"), wrapping))
        store.saveTake(Profile.A, 1, take(4000))
        assertTrue(store.deleteAll())
        assertFalse(root.exists())
        assertNull(wrapping.key)
        assertEquals(1, wrapping.deleted)
        // A fresh bank starts with fresh keys.
        store.saveTake(Profile.A, 1, take(500))
        assertEquals(2, wrapping.created)
        assertContentEquals(take(500), store.loadTake(Profile.A, 1))
    }

    private inline fun <reified T> assertIs(value: Any?) {
        assertTrue(value is T, "expected ${T::class.simpleName}, got $value")
    }
}
