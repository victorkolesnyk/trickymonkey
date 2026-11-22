package io.github.victorkolesnyk.hamir.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import io.github.victorkolesnyk.hamir.core.BabbleGenerator
import io.github.victorkolesnyk.hamir.core.Gain
import io.github.victorkolesnyk.hamir.core.SAMPLE_RATE
import io.github.victorkolesnyk.hamir.logic.FadeEnvelope
import io.github.victorkolesnyk.hamir.logic.PcmMath

/**
 * Plays the babble through a streaming AudioTrack (32 kHz mono PCM16, USAGE_MEDIA) on its own thread.
 * Starts and stops with a 150 ms fade. The AudioTrack is created and released on the playback thread,
 * release happens in finally.
 *
 * One generator may back several players only one after another: [join] the old one first.
 */
class BabblePlayer(
    private val generator: BabbleGenerator,
    initialGainDb: Double,
    /** Called on the playback thread when playback dies for any reason other than [stop] or [kill]. */
    private val onError: (Throwable) -> Unit,
) {
    /** Target gain; the playback thread approaches it by at most [MAX_GAIN_STEP_DB] per 20 ms chunk. */
    @Volatile
    var gainDb: Double = initialGainDb

    /** Size of the AudioTrack buffer in frames, 0 until playback has started. */
    @Volatile
    var bufferFrames: Int = 0
        private set

    @Volatile
    private var stopRequested = false

    @Volatile
    private var killed = false

    @Volatile
    private var track: AudioTrack? = null

    private var thread: Thread? = null

    @Synchronized
    fun start() {
        check(thread == null) { "already started" }
        thread = Thread({ run() }, "hamir-babble").also { it.start() }
    }

    /** Fades out and stops. Returns immediately. */
    fun stop() {
        stopRequested = true
    }

    /** Silences the output at once, without a fade: used when the headphones are gone. */
    fun kill() {
        killed = true
        stopRequested = true
        try {
            track?.let {
                it.pause()
                it.flush()
            }
        } catch (e: IllegalStateException) {
            // The playback thread released the track in the meantime.
        }
    }

    fun join(timeoutMs: Long) {
        val current = synchronized(this) { thread } ?: return
        try {
            current.join(timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** AudioDeviceInfo type of the device the track is routed to, or null when unknown. */
    fun routedDeviceType(): Int? = try {
        track?.routedDevice?.type
    } catch (e: RuntimeException) {
        null
    }

    private fun run() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (e: RuntimeException) {
            // Not allowed on this device: default priority is still far faster than real time.
        }
        var audioTrack: AudioTrack? = null
        try {
            audioTrack = openTrack()
            val chunk = ShortArray(CHUNK_SAMPLES)
            val envelope = FadeEnvelope(FADE_SAMPLES)
            var appliedGainDb = gainDb
            var restarts = 0
            var chunksSinceRestart = 0
            while (!killed) {
                if (stopRequested) envelope.beginFadeOut()
                generator.read(chunk, 0, chunk.size)
                appliedGainDb = PcmMath.slew(appliedGainDb, gainDb, MAX_GAIN_STEP_DB)
                Gain.apply(chunk, 0, chunk.size, appliedGainDb)
                envelope.process(chunk, 0, chunk.size)
                if (!writeFully(audioTrack!!, chunk)) {
                    // The audio server dropped the track (it restarted, or the output went away).
                    // Recreate it instead of giving up; a track that keeps dying is a real error.
                    if (++restarts > MAX_TRACK_RESTARTS) throw IllegalStateException("AudioTrack keeps dying")
                    track = null
                    val dead = audioTrack
                    audioTrack = null
                    dead.release()
                    audioTrack = openTrack()
                    chunksSinceRestart = 0
                    continue
                }
                if (++chunksSinceRestart >= STABLE_CHUNKS) restarts = 0
                if (envelope.finished) break
            }
            val current: AudioTrack = audioTrack
            if (!killed) {
                // Push the faded tail out of the track buffer with silence, so release() cuts nothing audible.
                java.util.Arrays.fill(chunk, 0.toShort())
                var padded = 0
                while (padded < bufferFrames && !killed) {
                    if (!writeFully(current, chunk)) break
                    padded += chunk.size
                }
                current.stop()
            }
        } catch (e: Throwable) {
            if (!killed && !stopRequested) onError(e)
        } finally {
            track = null
            try {
                audioTrack?.release()
            } catch (e: RuntimeException) {
                // Nothing left to clean up.
            }
        }
    }

    private fun openTrack(): AudioTrack {
        val audioTrack = createTrack()
        try {
            bufferFrames = audioTrack.bufferSizeInFrames
            audioTrack.play()
        } catch (e: RuntimeException) {
            audioTrack.release()
            throw e
        }
        track = audioTrack
        return audioTrack
    }

    /** Writes the whole chunk. Returns false when the track died and has to be recreated. */
    private fun writeFully(audioTrack: AudioTrack, data: ShortArray): Boolean {
        var offset = 0
        var stalled = 0
        while (offset < data.size && !killed) {
            val written = audioTrack.write(data, offset, data.size - offset)
            if (written == AudioTrack.ERROR_DEAD_OBJECT) return false
            if (written < 0) throw IllegalStateException("AudioTrack.write returned $written")
            if (written == 0) {
                // A playing track in blocking mode always takes data; one that does not is not audible.
                if (++stalled > MAX_STALLED_WRITES) throw IllegalStateException("AudioTrack accepts no data")
                Thread.sleep(5)
            } else {
                stalled = 0
            }
            offset += written
        }
        return true
    }

    private fun createTrack(): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) throw IllegalStateException("AudioTrack.getMinBufferSize returned $minBytes")
        // About 200 ms: enough to ride out scheduling hiccups, short enough for a quick stop.
        val bufferBytes = maxOf(minBytes * 2, SAMPLE_RATE * 2 / 5)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            audioTrack.release()
            throw IllegalStateException("AudioTrack not initialized")
        }
        return audioTrack
    }

    companion object {
        const val FADE_MS = 150
        const val FADE_SAMPLES = SAMPLE_RATE * FADE_MS / 1000
        const val CHUNK_MS = 20
        const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000
        const val MAX_GAIN_STEP_DB = 3.0
        private const val MAX_TRACK_RESTARTS = 3

        /** About two seconds without progress. */
        private const val MAX_STALLED_WRITES = 400

        /** Five seconds of uninterrupted playback: after that an earlier restart is forgotten. */
        private const val STABLE_CHUNKS = 5000 / CHUNK_MS

        /** Time until a gain change of [deltaDb] is fully applied and has left the track buffer. */
        fun settleMs(deltaDb: Double, bufferFrames: Int): Int {
            val slewChunks = Math.ceil(Math.abs(deltaDb) / MAX_GAIN_STEP_DB).toInt()
            return slewChunks * CHUNK_MS + bufferFrames * 1000 / SAMPLE_RATE
        }
    }
}
