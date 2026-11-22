package io.github.victorkolesnyk.hamir.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.InvalidAlgorithmParameterException
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import java.util.Random
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BankCodecTest {
    private fun newKey(bits: Int = 256): SecretKey = KeyGenerator.getInstance("AES").apply { init(bits) }.generateKey()

    private fun pcm(size: Int, seed: Long = 1): ShortArray {
        val random = Random(seed)
        return ShortArray(size) { random.nextInt(65536).toShort() }
    }

    private val magic = "HMR1".toByteArray(Charsets.US_ASCII)

    @Test
    fun roundTrip() {
        val key = newKey()
        for (size in intArrayOf(0, 1, 2, 7, 1000, 5 * SAMPLE_RATE)) {
            val samples = pcm(size, size.toLong())
            assertContentEquals(samples, BankCodec.decrypt(BankCodec.encrypt(samples, key), key), "size $size")
        }
        val extremes = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE, 0x1234, 0x7F80.toShort(), (-0x7F80).toShort())
        assertContentEquals(extremes, BankCodec.decrypt(BankCodec.encrypt(extremes, key), key))
    }

    @Test
    fun roundTripWithShorterAesKeys() {
        val key = newKey(128)
        val samples = pcm(321)
        assertContentEquals(samples, BankCodec.decrypt(BankCodec.encrypt(samples, key), key))
    }

    @Test
    fun layoutIsMagicIvCiphertextTag() {
        val samples = pcm(100)
        val data = BankCodec.encrypt(samples, newKey())
        assertEquals(4 + 12 + 2 * samples.size + 16, data.size)
        assertContentEquals(magic, data.copyOfRange(0, 4))
        assertEquals(4 + 12 + 16, BankCodec.encrypt(ShortArray(0), newKey()).size)
    }

    @Test
    fun formatIsPlainAesGcmWithMagicAsAssociatedData() {
        // Decrypt with the JDK directly, following the documented layout.
        val key = newKey()
        val samples = shortArrayOf(1, -2, 300, -30000, 0x0102)
        val data = BankCodec.encrypt(samples, key)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 4, 12))
        cipher.updateAAD(magic)
        val plain = cipher.doFinal(data, 16, data.size - 16)
        val decoded = ShortArray(samples.size)
        ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(decoded)
        assertContentEquals(samples, decoded)
        // Little-endian: 0x0102 is stored as 02 01.
        assertEquals(0x02, plain[8].toInt())
        assertEquals(0x01, plain[9].toInt())

        // Without the associated data the tag does not verify.
        val noAad = Cipher.getInstance("AES/GCM/NoPadding")
        noAad.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 4, 12))
        assertFailsWith<AEADBadTagException> { noAad.doFinal(data, 16, data.size - 16) }
    }

    @Test
    fun filesWrittenByAnotherImplementationOfTheFormatAreReadable() {
        val key = newKey()
        val samples = pcm(777)
        val plain = ByteArray(samples.size * 2)
        ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
        val iv = ByteArray(12) { (it * 7 + 1).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(magic)
        val file = magic + iv + cipher.doFinal(plain)
        assertContentEquals(samples, BankCodec.decrypt(file, key))

        // The same payload sealed without associated data is rejected.
        val bare = Cipher.getInstance("AES/GCM/NoPadding")
        bare.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12) { (it + 100).toByte() }))
        val wrong = magic + ByteArray(12) { (it + 100).toByte() } + bare.doFinal(plain)
        assertFailsWith<GeneralSecurityException> { BankCodec.decrypt(wrong, key) }
    }

    @Test
    fun everyEncryptionUsesAFreshIv() {
        val key = newKey()
        val samples = pcm(200)
        val ivs = HashSet<List<Byte>>()
        val bodies = HashSet<List<Byte>>()
        repeat(50) {
            val data = BankCodec.encrypt(samples, key)
            ivs.add(data.copyOfRange(4, 16).toList())
            bodies.add(data.copyOfRange(16, data.size).toList())
        }
        assertEquals(50, ivs.size)
        assertEquals(50, bodies.size)
    }

    @Test
    fun ciphertextDoesNotContainThePlainAudio() {
        val samples = ShortArray(4000) { 0x4142 }
        val data = BankCodec.encrypt(samples, newKey())
        var run = 0
        var longest = 0
        for (i in 16 until data.size) {
            run = if (data[i] == 0x41.toByte() || data[i] == 0x42.toByte()) run + 1 else 0
            longest = maxOf(longest, run)
        }
        assertTrue(longest < 8, "found $longest plaintext-looking bytes in a row")
    }

    @Test
    fun tamperingAnywhereAfterTheMagicIsDetected() {
        val key = newKey()
        val data = BankCodec.encrypt(pcm(64), key)
        // Every byte of the IV, the ciphertext and the tag, one bit at a time.
        for (index in 4 until data.size) {
            val tampered = data.copyOf()
            tampered[index] = (tampered[index].toInt() xor (1 shl (index % 8))).toByte()
            assertFailsWith<GeneralSecurityException>("byte $index") { BankCodec.decrypt(tampered, key) }
        }
    }

    @Test
    fun truncatedOrExtendedDataIsRejected() {
        val key = newKey()
        val data = BankCodec.encrypt(pcm(64), key)
        assertFailsWith<GeneralSecurityException> { BankCodec.decrypt(data.copyOf(data.size - 1), key) }
        assertFailsWith<GeneralSecurityException> { BankCodec.decrypt(data.copyOf(data.size - 2), key) }
        assertFailsWith<GeneralSecurityException> { BankCodec.decrypt(data + byteArrayOf(0, 0), key) }
        assertFailsWith<GeneralSecurityException> { BankCodec.decrypt(data.copyOf(4 + 12 + 16), key) }
    }

    @Test
    fun badHeaderIsRejectedWithIllegalArgument() {
        val key = newKey()
        val data = BankCodec.encrypt(pcm(64), key)
        for (index in 0 until 4) {
            val tampered = data.copyOf()
            tampered[index] = (tampered[index] + 1).toByte()
            assertFailsWith<IllegalArgumentException>("magic byte $index") { BankCodec.decrypt(tampered, key) }
        }
        assertFailsWith<IllegalArgumentException> { BankCodec.decrypt(ByteArray(0), key) }
        assertFailsWith<IllegalArgumentException> { BankCodec.decrypt(magic, key) }
        assertFailsWith<IllegalArgumentException> { BankCodec.decrypt(data.copyOf(4 + 12 + 15), key) }
        assertFailsWith<IllegalArgumentException> { BankCodec.decrypt(Wav.encode(pcm(64)), key) }
    }

    @Test
    fun wrongKeyIsRejected() {
        val data = BankCodec.encrypt(pcm(64), newKey())
        assertFailsWith<GeneralSecurityException> { BankCodec.decrypt(data, newKey()) }
        assertFailsWith<GeneralSecurityException> { BankCodec.decrypt(data, newKey(128)) }
        val exception = assertFailsWith<Exception> { BankCodec.decrypt(data, SecretKeySpec(ByteArray(32), "AES")) }
        assertTrue(exception is AEADBadTagException, "expected a tag failure, got $exception")
    }

    /**
     * Stand-in for the Android Keystore: an AES/GCM cipher that refuses caller-supplied IVs when
     * encrypting, exactly like Keystore keys with randomized encryption required (the default).
     */
    class StrictGcmSpi : CipherSpi() {
        private val delegate = Cipher.getInstance("AES/GCM/NoPadding", "SunJCE")

        override fun engineSetMode(mode: String) = Unit
        override fun engineSetPadding(padding: String) = Unit
        override fun engineGetBlockSize(): Int = delegate.blockSize
        override fun engineGetOutputSize(inputLen: Int): Int = delegate.getOutputSize(inputLen)
        override fun engineGetIV(): ByteArray? = delegate.iv
        override fun engineGetParameters(): AlgorithmParameters? = delegate.parameters

        override fun engineInit(opmode: Int, key: Key, random: SecureRandom?) {
            initCalls.add("init(mode=$opmode, key)")
            delegate.init(opmode, key)
        }

        override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameterSpec?, random: SecureRandom?) {
            if (opmode == Cipher.ENCRYPT_MODE && params != null) {
                throw InvalidAlgorithmParameterException("Caller-provided IV not permitted")
            }
            initCalls.add("init(mode=$opmode, key, spec)")
            delegate.init(opmode, key, params)
        }

        override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameters?, random: SecureRandom?) {
            if (opmode == Cipher.ENCRYPT_MODE && params != null) {
                throw InvalidAlgorithmParameterException("Caller-provided IV not permitted")
            }
            delegate.init(opmode, key, params)
        }

        override fun engineUpdateAAD(src: ByteArray, offset: Int, len: Int) = delegate.updateAAD(src, offset, len)
        override fun engineUpdate(input: ByteArray, inputOffset: Int, inputLen: Int): ByteArray? =
            delegate.update(input, inputOffset, inputLen)

        override fun engineUpdate(input: ByteArray, inputOffset: Int, inputLen: Int, output: ByteArray, outputOffset: Int): Int =
            delegate.update(input, inputOffset, inputLen, output, outputOffset)

        override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray =
            if (input == null) delegate.doFinal() else delegate.doFinal(input, inputOffset, inputLen)

        override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray, outputOffset: Int): Int =
            if (input == null) delegate.doFinal(output, outputOffset) else delegate.doFinal(input, inputOffset, inputLen, output, outputOffset)

        companion object {
            val initCalls: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        }
    }

    private class StrictProvider : Provider("HamirStrictGcm", "1.0", "AES/GCM that rejects caller-supplied IVs") {
        init {
            put("Cipher.AES/GCM/NoPadding", StrictGcmSpi::class.java.name)
        }
    }

    @Test
    fun worksWithKeysThatRejectCallerSuppliedIvs() {
        val provider = StrictProvider()
        assertEquals(1, Security.insertProviderAt(provider, 1), "test provider was not installed first")
        try {
            StrictGcmSpi.initCalls.clear()
            // The stand-in really is what Cipher.getInstance returns now, and it really refuses IVs.
            val probe = Cipher.getInstance("AES/GCM/NoPadding")
            assertEquals("HamirStrictGcm", probe.provider.name)
            assertFailsWith<InvalidAlgorithmParameterException> {
                probe.init(Cipher.ENCRYPT_MODE, newKey(), GCMParameterSpec(128, ByteArray(12)))
            }

            val key = newKey()
            val samples = pcm(500)
            val data = BankCodec.encrypt(samples, key)
            assertContentEquals(samples, BankCodec.decrypt(data, key))
            assertEquals(listOf("init(mode=1, key)", "init(mode=2, key, spec)"), StrictGcmSpi.initCalls.toList())
        } finally {
            Security.removeProvider(provider.name)
        }
        // The file is readable by the stock provider as well.
        val key = newKey()
        assertContentEquals(pcm(9), BankCodec.decrypt(BankCodec.encrypt(pcm(9), key), key))
    }
}
