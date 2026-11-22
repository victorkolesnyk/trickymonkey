package io.github.victorkolesnyk.hamir

import android.content.Context
import io.github.victorkolesnyk.hamir.audio.MicSession
import io.github.victorkolesnyk.hamir.audio.MicUnavailableException
import io.github.victorkolesnyk.hamir.audio.TakePlayer
import io.github.victorkolesnyk.hamir.core.Level
import io.github.victorkolesnyk.hamir.core.Resampler
import io.github.victorkolesnyk.hamir.core.SAMPLE_RATE
import io.github.victorkolesnyk.hamir.core.TakeCheck
import io.github.victorkolesnyk.hamir.core.TakeQuality
import io.github.victorkolesnyk.hamir.data.Deps
import io.github.victorkolesnyk.hamir.logic.EnrollmentRules
import io.github.victorkolesnyk.hamir.logic.Observable
import io.github.victorkolesnyk.hamir.logic.PcmBuffer
import io.github.victorkolesnyk.hamir.logic.PcmMath
import io.github.victorkolesnyk.hamir.logic.PhraseCursor
import io.github.victorkolesnyk.hamir.logic.Profile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recording session of the enrollment screen. Lives in the process, not in the Activity,
 * so a rotation neither interrupts a recording nor loses the position.
 *
 * Every public method and every change of [state] happens on the main thread;
 * audio, crypto and disk work run on worker threads.
 */
object EnrollController {
    enum class Busy { IDLE, RECORDING, SAVING, PLAYING, DELETING }

    enum class Notice {
        NONE, ACCEPTED, TOO_SHORT, TOO_QUIET, CLIPPED, MIC_ERROR, SAVE_ERROR, PLAY_ERROR,
        PROFILE_DELETED, ALL_DELETED, DELETE_ERROR, PROTECTION_RUNNING,
    }

    data class State(
        val profile: Profile = Profile.A,
        /** 1-based phrase number. */
        val phrase: Int = 1,
        val recorded: Set<Int> = emptySet(),
        /** False until [recorded] has been read from disk for [profile]. */
        val loaded: Boolean = false,
        val busy: Busy = Busy.IDLE,
        val notice: Notice = Notice.NONE,
        val elapsedMs: Int = 0,
    )

    val state = Observable(State())

    /** Input level 0..100 while recording. */
    val level = Observable(0)

    private var loadToken = 0
    private var stopFlag: AtomicBoolean? = null
    private var discardFlag: AtomicBoolean? = null

    /** Last accepted take, kept so "listen" right after recording needs no decryption. */
    private var lastTake: Triple<Profile, Int, ShortArray>? = null

    /** Position to restore after process death; ignored when a session already exists. */
    fun restore(profile: Profile, phrase: Int) {
        val current = state.value
        if (current.loaded || current.busy != Busy.IDLE) return
        pendingPhrase = PhraseCursor.clamp(phrase, EnrollmentRules.PHRASE_COUNT)
        state.value = current.copy(profile = profile)
    }

    private var pendingPhrase: Int? = null

    fun open(context: Context) {
        if (!state.value.loaded) reload(context)
    }

    fun selectProfile(context: Context, profile: Profile) {
        val current = state.value
        if (current.busy != Busy.IDLE || current.profile == profile) return
        state.value = State(profile = profile)
        reload(context)
    }

    private fun reload(context: Context) {
        val app = context.applicationContext
        val token = ++loadToken
        val profile = state.value.profile
        Deps.io.execute {
            val numbers = try {
                Deps.bank(app).takeNumbers(profile).toSet()
            } catch (e: Exception) {
                emptySet()
            }
            Deps.main.post {
                if (token != loadToken) return@post
                val phrase = pendingPhrase ?: PhraseCursor.firstUnrecorded(numbers, EnrollmentRules.PHRASE_COUNT)
                pendingPhrase = null
                state.value = state.value.copy(recorded = numbers, loaded = true, phrase = phrase)
            }
        }
    }

    fun next() = move { PhraseCursor.next(it, EnrollmentRules.PHRASE_COUNT) }

    fun previous() = move { PhraseCursor.previous(it, EnrollmentRules.PHRASE_COUNT) }

    private fun move(step: (Int) -> Int) {
        val current = state.value
        if (current.busy != Busy.IDLE || !current.loaded) return
        level.value = 0
        state.value = current.copy(phrase = step(current.phrase), notice = Notice.NONE, elapsedMs = 0)
    }

    fun startRecording(context: Context) {
        val current = state.value
        if (current.busy != Busy.IDLE || !current.loaded) return
        if (Deps.protectionState.value.active) {
            // The noise would end up in the take.
            state.value = current.copy(notice = Notice.PROTECTION_RUNNING)
            return
        }
        val app = context.applicationContext
        val profile = current.profile
        val phrase = current.phrase
        val stop = AtomicBoolean(false)
        val discard = AtomicBoolean(false)
        stopFlag = stop
        discardFlag = discard
        state.value = current.copy(busy = Busy.RECORDING, notice = Notice.NONE, elapsedMs = 0)
        Thread({ recordTake(app, profile, phrase, stop, discard) }, "hamir-enroll-rec").start()
    }

    fun stopRecording() {
        stopFlag?.set(true)
    }

    private fun recordTake(app: Context, profile: Profile, phrase: Int, stop: AtomicBoolean, discard: AtomicBoolean) {
        var mic: MicSession? = null
        var notice = Notice.NONE
        var accepted: ShortArray? = null
        try {
            mic = MicSession.open(app)
            val rate = mic.sampleRate
            val buffer = PcmBuffer(EnrollmentRules.MAX_TAKE_SECONDS * rate)
            val chunk = ShortArray(rate / 20)
            var lastTick = 0
            // SPEC 6.2: the take stops by itself after 15 seconds.
            while (!stop.get() && !buffer.isFull) {
                val n = mic.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                buffer.append(chunk, 0, n)
                val percent = PcmMath.meterPercent(Level.peakDbfs(chunk, 0, n))
                val elapsed = (buffer.size * 1000L / rate).toInt()
                val tick = elapsed / 250
                val publishElapsed = tick != lastTick
                lastTick = tick
                Deps.main.post {
                    if (state.value.busy == Busy.RECORDING) {
                        level.value = percent
                        if (publishElapsed) state.value = state.value.copy(elapsedMs = elapsed)
                    }
                }
            }
            // Free the microphone before the slower work.
            mic.close()
            mic = null
            if (!discard.get()) {
                Deps.main.post {
                    level.value = 0
                    if (state.value.busy == Busy.RECORDING) state.value = state.value.copy(busy = Busy.SAVING)
                }
                val pcm = Resampler.resample(buffer.toArray(), rate, SAMPLE_RATE)
                notice = when (TakeCheck.evaluate(pcm, SAMPLE_RATE)) {
                    TakeQuality.OK -> {
                        Deps.bank(app).saveTake(profile, phrase, pcm)
                        accepted = pcm
                        Notice.ACCEPTED
                    }
                    TakeQuality.TOO_SHORT -> Notice.TOO_SHORT
                    TakeQuality.TOO_QUIET -> Notice.TOO_QUIET
                    TakeQuality.CLIPPED -> Notice.CLIPPED
                }
            }
        } catch (e: MicUnavailableException) {
            notice = Notice.MIC_ERROR
        } catch (e: Throwable) {
            // Disk, keystore or memory trouble: the take is not counted.
            notice = Notice.SAVE_ERROR
            accepted = null
        } finally {
            mic?.close()
        }
        val finalNotice = notice
        val finalTake = accepted
        Deps.main.post {
            if (stopFlag === stop) {
                stopFlag = null
                discardFlag = null
            }
            level.value = 0
            val current = state.value
            if (finalTake != null) lastTake = Triple(profile, phrase, finalTake)
            val recorded = if (finalTake != null && current.profile == profile) current.recorded + phrase else current.recorded
            state.value = current.copy(busy = Busy.IDLE, notice = finalNotice, recorded = recorded, elapsedMs = 0)
        }
    }

    fun play(context: Context) {
        val current = state.value
        if (current.busy != Busy.IDLE || current.phrase !in current.recorded) return
        val app = context.applicationContext
        val profile = current.profile
        val phrase = current.phrase
        val stop = AtomicBoolean(false)
        stopFlag = stop
        val cached = lastTake?.takeIf { it.first == profile && it.second == phrase }?.third
        state.value = current.copy(busy = Busy.PLAYING, notice = Notice.NONE)
        Thread({
            var failed = false
            try {
                val pcm = cached ?: Deps.bank(app).loadTake(profile, phrase)
                TakePlayer.play(pcm, stop)
            } catch (e: Throwable) {
                failed = true
            }
            Deps.main.post {
                if (stopFlag === stop) stopFlag = null
                val now = state.value
                state.value = now.copy(busy = Busy.IDLE, notice = if (failed) Notice.PLAY_ERROR else now.notice)
            }
        }, "hamir-enroll-play").start()
    }

    fun stopPlayback() {
        if (state.value.busy == Busy.PLAYING) stopFlag?.set(true)
    }

    /** The screen went to the background: a half-recorded phrase is dropped, playback stops. */
    fun onBackground() {
        when (state.value.busy) {
            Busy.RECORDING -> {
                discardFlag?.set(true)
                stopFlag?.set(true)
            }
            Busy.PLAYING -> stopFlag?.set(true)
            else -> Unit
        }
    }

    fun deleteProfile(context: Context) = delete(context, all = false)

    fun deleteAll(context: Context) = delete(context, all = true)

    private fun delete(context: Context, all: Boolean) {
        val current = state.value
        if (current.busy != Busy.IDLE) return
        val app = context.applicationContext
        val profile = current.profile
        lastTake = null
        state.value = current.copy(busy = Busy.DELETING, notice = Notice.NONE)
        Deps.io.execute {
            val ok = try {
                if (all) Deps.bank(app).deleteAll() else Deps.bank(app).deleteProfile(profile)
            } catch (e: Exception) {
                false
            }
            val remaining = try {
                Deps.bank(app).takeNumbers(profile).toSet()
            } catch (e: Exception) {
                emptySet()
            }
            Deps.main.post {
                val notice = when {
                    !ok -> Notice.DELETE_ERROR
                    all -> Notice.ALL_DELETED
                    else -> Notice.PROFILE_DELETED
                }
                state.value = state.value.copy(
                    busy = Busy.IDLE,
                    notice = notice,
                    recorded = remaining,
                    phrase = PhraseCursor.firstUnrecorded(remaining, EnrollmentRules.PHRASE_COUNT),
                )
            }
        }
    }
}
