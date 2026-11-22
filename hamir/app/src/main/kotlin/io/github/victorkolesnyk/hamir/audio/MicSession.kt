package io.github.victorkolesnyk.hamir.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.victorkolesnyk.hamir.logic.PcmMath

class MicUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * An open, recording AudioRecord: mono PCM16, built-in microphone, no gain control effects enabled.
 * Preferred rate is 32000 Hz; [sampleRate] tells what the device actually gave.
 * Use from a worker thread and always [close] in finally.
 */
class MicSession private constructor(
    private val record: AudioRecord,
    val sampleRate: Int,
    /** MediaRecorder.AudioSource the session was really opened with. */
    val source: Int,
) {
    /** What the recording is for; decides the audio source. */
    enum class Purpose {
        /**
         * Enrollment phrases: VOICE_RECOGNITION. The Android compatibility definition tunes it to roughly
         * -22 dBFS at 90 dB SPL with gain control and noise suppression off, so normal speech passes TakeCheck.
         * UNPROCESSED is about 30 dB less sensitive and would put speech at the "too quiet" threshold.
         */
        ENROLLMENT,

        /** Relative level measurements: UNPROCESSED when the device supports it, otherwise VOICE_RECOGNITION. */
        CALIBRATION,
    }

    /** Level that should read as zero on a meter fed by this session. */
    val meterFloorDbfs: Double
        get() = if (source == MediaRecorder.AudioSource.UNPROCESSED) {
            PcmMath.METER_FLOOR_RAW_DBFS
        } else {
            PcmMath.METER_FLOOR_TUNED_DBFS
        }

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

        /** PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED (API 24) says whether the raw source exists. */
        private fun supportsUnprocessed(context: Context): Boolean {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        }

        /** Sources to try in order. Neither enables automatic gain control. */
        private fun sourcesFor(context: Context, purpose: Purpose): IntArray =
            if (purpose == Purpose.CALIBRATION && supportsUnprocessed(context)) {
                intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION)
            } else {
                intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            }

        @Throws(MicUnavailableException::class)
        fun open(context: Context, purpose: Purpose): MicSession {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw MicUnavailableException("RECORD_AUDIO not granted")
            }
            for (source in sourcesFor(context, purpose)) {
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
                    if (started) return MicSession(record, rate, source)
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
