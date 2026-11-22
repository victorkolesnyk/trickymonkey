package io.github.victorkolesnyk.hamir.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder

class MicUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * An open, recording AudioRecord: mono PCM16, built-in microphone, no gain control effects.
 * Preferred rate is 32000 Hz; [sampleRate] tells what the device actually gave.
 * Use from a worker thread and always [close] in finally.
 */
class MicSession private constructor(private val record: AudioRecord, val sampleRate: Int) {
    private var emptyReads = 0

    /** Blocks until samples arrive. Returns the number read (may be 0 on a spurious wake-up). */
    @Throws(MicUnavailableException::class)
    fun read(buffer: ShortArray, offset: Int, length: Int): Int {
        val n = record.read(buffer, offset, length)
        if (n < 0) throw MicUnavailableException("AudioRecord.read returned $n")
        if (n == 0) {
            if (++emptyReads > MAX_EMPTY_READS) throw MicUnavailableException("AudioRecord delivers no data")
            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw MicUnavailableException("interrupted", e)
            }
        } else {
            emptyReads = 0
        }
        return n
    }

    fun close() {
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        } catch (e: IllegalStateException) {
            // Already stopped or never started: nothing to do.
        } finally {
            record.release()
        }
    }

    companion object {
        private const val MAX_EMPTY_READS = 100
        private val RATES = intArrayOf(32000, 44100, 48000, 16000)

        /**
         * UNPROCESSED when the device supports it (PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED, API 24),
         * otherwise VOICE_RECOGNITION; both run without automatic gain control.
         */
        fun preferredSource(context: Context): Int {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val supported = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            return if (supported == "true") {
                MediaRecorder.AudioSource.UNPROCESSED
            } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
        }

        @Throws(MicUnavailableException::class)
        fun open(context: Context): MicSession {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw MicUnavailableException("RECORD_AUDIO not granted")
            }
            val preferred = preferredSource(context)
            val sources = if (preferred == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                intArrayOf(preferred)
            } else {
                intArrayOf(preferred, MediaRecorder.AudioSource.VOICE_RECOGNITION)
            }
            for (source in sources) {
                for (rate in RATES) {
                    val record = create(source, rate) ?: continue
                    var started = false
                    try {
                        preferBuiltInMic(context, record)
                        record.startRecording()
                        started = record.recordingState == AudioRecord.RECORDSTATE_RECORDING
                    } catch (e: RuntimeException) {
                        started = false
                    } finally {
                        if (!started) record.release()
                    }
                    if (started) return MicSession(record, rate)
                }
            }
            throw MicUnavailableException("no usable AudioRecord configuration")
        }

        private fun create(source: Int, rate: Int): AudioRecord? {
            val minBytes = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBytes <= 0) return null
            // At least half a second, so a briefly delayed reader does not lose samples.
            val bufferBytes = maxOf(minBytes * 2, rate)
            val record = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            } catch (e: RuntimeException) {
                // UnsupportedOperationException, IllegalArgumentException or SecurityException.
                return null
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            return record
        }

        /**
         * With a wired or USB headset plugged in Android records from the headset microphone by default.
         * Calibration needs the phone's own microphone, the one the earbud is pressed against.
         */
        private fun preferBuiltInMic(context: Context, record: AudioRecord) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val builtIn = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (builtIn != null) record.setPreferredDevice(builtIn)
        }
    }
}
