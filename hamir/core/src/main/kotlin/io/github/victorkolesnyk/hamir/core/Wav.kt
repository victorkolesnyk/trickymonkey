package io.github.victorkolesnyk.hamir.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Decoded WAV file: mono PCM16 samples and their sample rate. */
class WavData(val samples: ShortArray, val sampleRate: Int)

/**
 * Minimal RIFF/WAVE support for the CLI and tests: 16-bit integer PCM only.
 * Files with several channels are downmixed to mono on read; writing is always mono.
 */
object Wav {
    private const val FORMAT_PCM = 1
    private const val FORMAT_EXTENSIBLE = 0xFFFE
    private const val HEADER_BYTES = 44

    /** @throws IOException when the file is not a readable PCM16 WAV. */
    @Throws(IOException::class)
    fun read(file: File): WavData = try {
        decode(file.readBytes())
    } catch (e: IOException) {
        throw IOException("${file.path}: ${e.message}", e)
    }

    /** Reads [input] to its end; the stream is not closed. */
    @Throws(IOException::class)
    fun read(input: InputStream): WavData = decode(input.readBytes())

    /** @throws IOException when [bytes] are not a PCM16 WAV. */
    @Throws(IOException::class)
    fun decode(bytes: ByteArray): WavData {
        if (bytes.size < 12 || !tagAt(bytes, 0, "RIFF") || !tagAt(bytes, 8, "WAVE")) {
            throw IOException("not a RIFF/WAVE file")
        }
        var channels = 0
        var sampleRate = 0
        var formatSeen = false
        var position = 12
        while (position + 8 <= bytes.size) {
            val declared = u32(bytes, position + 4)
            val body = position + 8
            // Streamed files may declare 0 or 0xFFFFFFFF, truncated ones more than there is: take what exists.
            val available = (bytes.size - body).toLong()
            if (tagAt(bytes, position, "fmt ")) {
                if (declared < 16 || available < 16) throw IOException("fmt chunk is too short")
                var format = u16(bytes, body)
                channels = u16(bytes, body + 2)
                sampleRate = u32(bytes, body + 4).toInt()
                val bits = u16(bytes, body + 14)
                if (format == FORMAT_EXTENSIBLE && declared >= 26 && available >= 26) format = u16(bytes, body + 24)
                if (format != FORMAT_PCM || bits != 16) {
                    throw IOException("only 16-bit PCM is supported (format tag $format, $bits bits)")
                }
                if (channels < 1 || sampleRate < 1) throw IOException("invalid channel count or sample rate")
                formatSeen = true
            } else if (tagAt(bytes, position, "data")) {
                if (!formatSeen) throw IOException("data chunk precedes fmt chunk")
                val size = if (declared == 0L || declared > available) available else declared
                return WavData(downmix(bytes, body, (size / (2L * channels)).toInt(), channels), sampleRate)
            }
            val advance = declared + (declared and 1L)
            if (advance > available) break
            position = body + advance.toInt()
        }
        throw IOException(if (formatSeen) "no data chunk" else "no fmt chunk")
    }

    @Throws(IOException::class)
    fun write(file: File, samples: ShortArray, sampleRate: Int = SAMPLE_RATE) {
        file.outputStream().buffered().use { write(it, samples, sampleRate) }
    }

    /** Writes a complete mono PCM16 file to [output]; the stream is not closed. */
    @Throws(IOException::class)
    fun write(output: OutputStream, samples: ShortArray, sampleRate: Int = SAMPLE_RATE) {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(samples.size <= (Int.MAX_VALUE - HEADER_BYTES) / 2) { "too many samples for a WAV file" }
        val dataBytes = samples.size * 2
        val header = ByteArray(HEADER_BYTES)
        putTag(header, 0, "RIFF")
        putU32(header, 4, 36 + dataBytes)
        putTag(header, 8, "WAVE")
        putTag(header, 12, "fmt ")
        putU32(header, 16, 16)
        putU16(header, 20, FORMAT_PCM)
        putU16(header, 22, 1)
        putU32(header, 24, sampleRate)
        putU32(header, 28, sampleRate * 2)
        putU16(header, 32, 2)
        putU16(header, 34, 16)
        putTag(header, 36, "data")
        putU32(header, 40, dataBytes)
        output.write(header)
        val chunk = ByteArray(8192)
        var i = 0
        while (i < samples.size) {
            var n = 0
            while (n < chunk.size && i < samples.size) {
                val s = samples[i++].toInt()
                chunk[n++] = s.toByte()
                chunk[n++] = (s shr 8).toByte()
            }
            output.write(chunk, 0, n)
        }
        output.flush()
    }

    fun encode(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val buffer = ByteArrayOutputStream(HEADER_BYTES + samples.size * 2)
        write(buffer, samples, sampleRate)
        return buffer.toByteArray()
    }

    private fun downmix(bytes: ByteArray, start: Int, frames: Int, channels: Int): ShortArray {
        val out = ShortArray(frames)
        var p = start
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                sum += (bytes[p].toInt() and 0xFF) or (bytes[p + 1].toInt() shl 8)
                p += 2
            }
            out[i] = Math.floorDiv(sum + channels / 2, channels).toShort()
        }
        return out
    }

    private fun tagAt(bytes: ByteArray, offset: Int, tag: String): Boolean {
        for (i in 0 until 4) if (bytes[offset + i] != tag[i].code.toByte()) return false
        return true
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)

    private fun putTag(bytes: ByteArray, offset: Int, tag: String) {
        for (i in 0 until 4) bytes[offset + i] = tag[i].code.toByte()
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        putU16(bytes, offset, value and 0xFFFF)
        putU16(bytes, offset + 2, value ushr 16)
    }
}
