package io.github.victorkolesnyk.hamir.logic

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The bank key is missing or cannot be opened. The cause may be permanent (key file or keystore key
 * gone) or a passing keystore fault; the two are deliberately not told apart, because mistaking a
 * passing fault for a permanent one would make the user delete recordings for nothing. The advice is
 * therefore always: restart the phone first, delete and re-record only if that does not help.
 */
class BankKeyLostException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/**
 * Format of the wrapped data key in `bank/key.bin`:
 * "HMK1" | 12-byte IV | AES-256-GCM(32-byte data key + 16-byte tag). The magic is also the associated data.
 * The IV comes from the cipher itself, as AndroidKeyStore keys require.
 */
object KeyEnvelope {
    const val DATA_KEY_BYTES = 32
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val MAGIC = byteArrayOf('H'.code.toByte(), 'M'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())
    private val HEADER_BYTES = MAGIC.size + IV_BYTES

    /** Size of a valid blob: header, key and tag. */
    val BLOB_BYTES: Int = HEADER_BYTES + DATA_KEY_BYTES + TAG_BITS / 8

    @Throws(GeneralSecurityException::class)
    fun wrap(dataKey: ByteArray, wrappingKey: SecretKey): ByteArray {
        require(dataKey.size == DATA_KEY_BYTES) { "data key must be $DATA_KEY_BYTES bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES) { "cipher produced an IV of ${iv?.size} bytes, expected $IV_BYTES" }
        cipher.updateAAD(MAGIC)
        val encrypted = cipher.doFinal(dataKey)
        val out = ByteArray(HEADER_BYTES + encrypted.size)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        System.arraycopy(iv, 0, out, MAGIC.size, IV_BYTES)
        System.arraycopy(encrypted, 0, out, HEADER_BYTES, encrypted.size)
        return out
    }

    /** @throws GeneralSecurityException on a wrong key or tampered blob, IllegalArgumentException on a bad header. */
    @Throws(GeneralSecurityException::class)
    fun unwrap(blob: ByteArray, wrappingKey: SecretKey): ByteArray {
        require(blob.size == BLOB_BYTES) { "not a HMK1 blob: ${blob.size} bytes" }
        for (i in MAGIC.indices) require(blob[i] == MAGIC[i]) { "not a HMK1 blob: bad magic" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(TAG_BITS, blob, MAGIC.size, IV_BYTES))
        cipher.updateAAD(MAGIC)
        val key = cipher.doFinal(blob, HEADER_BYTES, blob.size - HEADER_BYTES)
        if (key.size != DATA_KEY_BYTES) {
            key.fill(0)
            throw GeneralSecurityException("unwrapped key has ${key.size} bytes")
        }
        return key
    }
}

/** The hardware-backed key that wraps the data key. The Android implementation lives in AndroidKeyStore. */
interface WrappingKeySource {
    /** The wrapping key, or null when it does not exist. */
    fun existing(): SecretKey?
    fun getOrCreate(): SecretKey
    fun delete()
}

/**
 * Envelope encryption of the voice bank: a random 256-bit data key encrypts the takes in software
 * (fast), and only that key is wrapped by the slow keystore key. The unwrapped key is cached for
 * the lifetime of the process. Thread-safe; does disk and crypto work, so call off the UI thread.
 */
class EnvelopeKeyProvider(
    private val keyFile: File,
    private val wrapping: WrappingKeySource,
    private val random: SecureRandom = SecureRandom(),
) : BankKeyProvider {
    private var cached: SecretKey? = null

    @Synchronized
    override fun existingKey(): SecretKey? {
        cached?.let { return it }
        if (!keyFile.isFile) return null
        val raw = try {
            val wrappingKey = wrapping.existing()
                ?: throw BankKeyLostException("key file exists but the wrapping key is gone")
            KeyEnvelope.unwrap(keyFile.readBytes(), wrappingKey)
        } catch (e: BankKeyLostException) {
            throw e
        } catch (e: IOException) {
            throw BankKeyLostException("cannot read the key file", e)
        } catch (e: GeneralSecurityException) {
            throw BankKeyLostException("cannot unwrap the data key", e)
        } catch (e: RuntimeException) {
            // IllegalArgumentException from a damaged file, or a provider failure inside the keystore.
            throw BankKeyLostException("cannot open the data key", e)
        }
        return keep(raw)
    }

    @Synchronized
    override fun createKey(): SecretKey {
        val raw = ByteArray(KeyEnvelope.DATA_KEY_BYTES)
        random.nextBytes(raw)
        val blob = KeyEnvelope.wrap(raw, wrapping.getOrCreate())
        val dir = keyFile.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("cannot create $dir")
        }
        val tmp = File(dir, keyFile.name + ".tmp")
        try {
            tmp.outputStream().use { out ->
                out.write(blob)
                out.fd.sync()
            }
            if (!tmp.renameTo(keyFile)) {
                keyFile.delete()
                if (!tmp.renameTo(keyFile)) throw IOException("cannot move $tmp to $keyFile")
            }
        } finally {
            tmp.delete()
        }
        return keep(raw)
    }

    @Synchronized
    override fun deleteKey() {
        cached = null
        val fileGone = !keyFile.exists() || keyFile.delete()
        wrapping.delete()
        if (!fileGone) throw IOException("cannot delete $keyFile")
    }

    private fun keep(raw: ByteArray): SecretKey {
        // SecretKeySpec copies the bytes, so the local copy can be wiped.
        val key = SecretKeySpec(raw, "AES")
        raw.fill(0)
        cached = key
        return key
    }
}
