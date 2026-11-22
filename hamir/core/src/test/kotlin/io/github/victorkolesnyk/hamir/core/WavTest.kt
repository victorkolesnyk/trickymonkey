package io.github.victorkolesnyk.hamir.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WavTest {
    private fun pcm(size: Int): ShortArray {
        val random = Random(size.toLong())
        return ShortArray(size) { random.nextInt(65536).toShort() }
    }

    /** Builds a WAV file by hand so that the reader is tested against something other than the writer. */
    private fun wav(
        formatTag: Int = 1,
        channels: Int = 1,
        rate: Int = 32000,
        bits: Int = 16,
        frames: ShortArray = shortArrayOf(),
        extraChunks: ByteArray = ByteArray(0),
        fmtExtra: ByteArray = ByteArray(0),
        declaredDataSize: Int? = null,
    ): ByteArray {
        val fmtSize = 16 + fmtExtra.size
        val dataSize = frames.size * 2
        val buffer = ByteBuffer.allocate(12 + 8 + fmtSize + extraChunks.size + 8 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(buffer.capacity() - 8).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(fmtSize)
        buffer.putShort(formatTag.toShort()).putShort(channels.toShort()).putInt(rate)
        buffer.putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        buffer.put(fmtExtra)
        buffer.put(extraChunks)
        buffer.put("data".toByteArray()).putInt(declaredDataSize ?: dataSize)
        for (s in frames) buffer.putShort(s)
        return buffer.array()
    }

    @Test
    fun encodeDecodeRoundTrip() {
        for (size in intArrayOf(0, 1, 2, 4095, 8192, 8193, 50000)) {
            val samples = pcm(size)
            val decoded = Wav.decode(Wav.encode(samples, 44100))
            assertEquals(44100, decoded.sampleRate)
            assertContentEquals(samples, decoded.samples, "size $size")
        }
    }

    @Test
    fun defaultSampleRateIsTheProjectRate() {
        assertEquals(SAMPLE_RATE, Wav.decode(Wav.encode(pcm(10))).sampleRate)
    }

    @Test
    fun writerProducesACanonicalHeader() {
        val samples = shortArrayOf(1, -2, 0x0304)
        val bytes = Wav.encode(samples, 32000)
        assertContentEquals(wav(frames = samples), bytes)
        assertEquals(44 + 6, bytes.size)
    }

    @Test
    fun fileRoundTrip() {
        val dir = Files.createTempDirectory("hamir-wav").toFile()
        try {
            val file = File(dir, "take.wav")
            val samples = pcm(12345)
            Wav.write(file, samples, 16000)
            assertEquals(44L + 2 * samples.size, file.length())
            val read = Wav.read(file)
            assertEquals(16000, read.sampleRate)
            assertContentEquals(samples, read.samples)
            val missing = assertFailsWith<IOException> { Wav.read(File(dir, "missing.wav")) }
            assertTrue(missing.message!!.contains("missing.wav"))
            File(dir, "text.wav").writeText("this is not audio at all, just text")
            val bad = assertFailsWith<IOException> { Wav.read(File(dir, "text.wav")) }
            assertTrue(bad.message!!.contains("text.wav"), bad.message)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun streamRoundTrip() {
        val samples = pcm(999)
        val out = ByteArrayOutputStream()
        Wav.write(out, samples, 8000)
        val read = Wav.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(8000, read.sampleRate)
        assertContentEquals(samples, read.samples)
    }

    @Test
    fun stereoIsDownmixedToMono() {
        val frames = shortArrayOf(1000, 3000, -1000, -3000, 32767, 32767, -32768, -32768, 100, -100, 1, 2, -1, -2)
        val decoded = Wav.decode(wav(channels = 2, rate = 48000, frames = frames))
        assertEquals(48000, decoded.sampleRate)
        assertContentEquals(shortArrayOf(2000, -2000, 32767, -32768, 0, 2, -1), decoded.samples)
    }

    @Test
    fun moreThanTwoChannelsAreAveraged() {
        val decoded = Wav.decode(wav(channels = 4, frames = shortArrayOf(400, 800, 1200, 1600, -4, -4, -4, -4, 9, 9, 9)))
        assertContentEquals(shortArrayOf(1000, -4), decoded.samples)
    }

    @Test
    fun extensibleFormatWithPcmSubtypeIsAccepted() {
        // cbSize = 22, valid bits, channel mask, sub-format GUID starting with the PCM tag.
        val extension = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(22).putShort(16).putInt(3).putShort(1).array()
        val frames = shortArrayOf(10, 20, 30, 40)
        val decoded = Wav.decode(wav(formatTag = 0xFFFE, channels = 2, frames = frames, fmtExtra = extension))
        assertContentEquals(shortArrayOf(15, 35), decoded.samples)
        // Extensible float is not PCM16.
        val float = extension.copyOf().also { it[8] = 3 }
        assertFailsWith<IOException> { Wav.decode(wav(formatTag = 0xFFFE, channels = 2, frames = frames, fmtExtra = float)) }
    }

    @Test
    fun unknownChunksAreSkippedIncludingOddSizedOnes() {
        val list = ByteBuffer.allocate(8 + 5 + 1 + 8 + 4).order(ByteOrder.LITTLE_ENDIAN)
            .put("LIST".toByteArray()).putInt(5).put(byteArrayOf(1, 2, 3, 4, 5)).put(0)
            .put("fact".toByteArray()).putInt(4).putInt(1234).array()
        val samples = pcm(100)
        assertContentEquals(samples, Wav.decode(wav(frames = samples, extraChunks = list)).samples)
    }

    @Test
    fun streamedAndTruncatedDataChunksAreReadToTheEnd() {
        val samples = pcm(100)
        assertContentEquals(samples, Wav.decode(wav(frames = samples, declaredDataSize = 0)).samples)
        assertContentEquals(samples, Wav.decode(wav(frames = samples, declaredDataSize = -1)).samples)
        assertContentEquals(samples, Wav.decode(wav(frames = samples, declaredDataSize = 1_000_000)).samples)
        // File cut in the middle of a sample: the incomplete sample is dropped.
        val whole = wav(frames = samples)
        assertContentEquals(samples.copyOf(49), Wav.decode(whole.copyOf(44 + 99)).samples)
        // Trailing bytes after the data chunk are ignored.
        assertContentEquals(samples, Wav.decode(whole + byteArrayOf(1, 2, 3)).samples)
    }

    @Test
    fun unsupportedOrBrokenFilesAreRejected() {
        val samples = pcm(16)
        assertFailsWith<IOException> { Wav.decode(ByteArray(0)) }
        assertFailsWith<IOException> { Wav.decode("RIFF".toByteArray()) }
        assertFailsWith<IOException> { Wav.decode(ByteArray(100)) }
        assertFailsWith<IOException> { Wav.decode(wav(bits = 8, frames = samples)) }
        assertFailsWith<IOException> { Wav.decode(wav(bits = 24, frames = samples)) }
        assertFailsWith<IOException> { Wav.decode(wav(formatTag = 3, bits = 32, frames = samples)) }
        assertFailsWith<IOException> { Wav.decode(wav(formatTag = 85, frames = samples)) }
        assertFailsWith<IOException> { Wav.decode(wav(channels = 0, frames = samples)) }
        assertFailsWith<IOException> { Wav.decode(wav(rate = 0, frames = samples)) }
        // RIFF but not WAVE.
        val avi = wav(frames = samples).also { "AVI ".toByteArray().copyInto(it, 8) }
        assertFailsWith<IOException> { Wav.decode(avi) }
        // No data chunk, and data before fmt.
        assertFailsWith<IOException> { Wav.decode(wav(frames = samples).copyOf(36)) }
        val noFmt = wav(frames = samples).also { "junk".toByteArray().copyInto(it, 12) }
        assertFailsWith<IOException> { Wav.decode(noFmt) }
        assertFailsWith<IllegalArgumentException> { Wav.encode(samples, 0) }
    }
}
