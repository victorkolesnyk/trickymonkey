package io.github.victorkolesnyk.hamir.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.victorkolesnyk.hamir.core.SAMPLE_RATE
import java.util.concurrent.atomic.AtomicBoolean

/** Plays one recorded take ([SAMPLE_RATE], mono PCM16) and blocks until it ends or [stop] is set. */
object TakePlayer {
    @Throws(IllegalStateException::class)
    fun play(pcm: ShortArray, stop: AtomicBoolean) {
        if (pcm.isEmpty()) return
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBytes <= 0) throw IllegalStateException("AudioTrack.getMinBufferSize returned $minBytes")
        val bufferBytes = maxOf(minBytes * 2, SAMPLE_RATE * 2 / 5)
        val bufferMillis = bufferBytes / 2 * 1000L / SAMPLE_RATE
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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
            if (track.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("AudioTrack not initialized")
            track.play()
            val chunk = SAMPLE_RATE / 20
            var offset = 0
            while (offset < pcm.size && !stop.get()) {
                val written = track.write(pcm, offset, minOf(chunk, pcm.size - offset))
                if (written < 0) throw IllegalStateException("AudioTrack.write returned $written")
                if (written == 0) Thread.sleep(5)
                offset += written
            }
            if (stop.get()) {
                track.pause()
                track.flush()
                return
            }
            // Let the buffered tail play out. The head position must be read while the track still plays:
            // after stop() it reads 0. The deadline covers a head that never advances.
            val deadline = System.currentTimeMillis() + bufferMillis + 500
            while (!stop.get() && System.currentTimeMillis() < deadline) {
                // Mask: the head position is an unsigned 32-bit frame counter.
                val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (head >= pcm.size) break
                Thread.sleep(20)
            }
            if (stop.get()) {
                track.pause()
                track.flush()
            } else {
                track.stop()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            try {
                track?.release()
            } catch (e: RuntimeException) {
                // Nothing left to clean up.
            }
        }
    }
}
