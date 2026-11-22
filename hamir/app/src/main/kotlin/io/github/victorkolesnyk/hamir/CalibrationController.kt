package io.github.victorkolesnyk.hamir

import android.content.Context
import android.media.AudioManager
import io.github.victorkolesnyk.hamir.audio.AudioRoutes
import io.github.victorkolesnyk.hamir.audio.BabblePlayer
import io.github.victorkolesnyk.hamir.audio.MicSession
import io.github.victorkolesnyk.hamir.audio.MicUnavailableException
import io.github.victorkolesnyk.hamir.core.BabbleGenerator
import io.github.victorkolesnyk.hamir.core.CalibrationFailure
import io.github.victorkolesnyk.hamir.core.Level
import io.github.victorkolesnyk.hamir.data.Deps
import io.github.victorkolesnyk.hamir.logic.BankLoadResult
import io.github.victorkolesnyk.hamir.logic.BankLoader
import io.github.victorkolesnyk.hamir.logic.CalibrationPhase
import io.github.victorkolesnyk.hamir.logic.CalibrationRecord
import io.github.victorkolesnyk.hamir.logic.CalibrationSession
import io.github.victorkolesnyk.hamir.logic.Observable
import io.github.victorkolesnyk.hamir.logic.OutputRoute
import io.github.victorkolesnyk.hamir.logic.PcmBuffer
import io.github.victorkolesnyk.hamir.logic.PcmMath
import io.github.victorkolesnyk.hamir.logic.PlanResult
import io.github.victorkolesnyk.hamir.logic.Profile
import io.github.victorkolesnyk.hamir.logic.ProtectionPlanner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the calibration wizard (SPEC section 7) outside the Activity, so rotation does not restart it.
 * Public methods and changes of [state] happen on the main thread; measuring runs on one worker thread.
 */
object CalibrationController {
    enum class Step { INTRO, LOADING, AMBIENT, SPEECH_READY, SPEECH, NOISE_READY, NOISE, DONE, FAILED, ERROR }

    enum class Problem { NOT_ENOUGH_VOICE, VOICE_UNREADABLE, MIC, AUDIO, PROTECTION_RUNNING }

    data class State(
        val step: Step = Step.INTRO,
        /** Progress of the running measurement, 0..100. */
        val progress: Int = 0,
        val loadDone: Int = 0,
        val loadTotal: Int = 0,
        val targetSnrDb: Int = -5,
        /** When the previous speech attempt was too quiet: how far above ambient it was. */
        val rejectedMarginDb: Double? = null,
        val iteration: Int = 0,
        val gainDb: Double = 0.0,
        val lastMeasuredDbfs: Double? = null,
        val targetNoiseDbfs: Double? = null,
        val failure: CalibrationFailure? = null,
        val problem: Problem? = null,
        val record: CalibrationRecord? = null,
    ) {
        val measuring: Boolean
            get() = step == Step.LOADING || step == Step.AMBIENT || step == Step.SPEECH || step == Step.NOISE
    }

    const val AMBIENT_MS = 2000
    const val SPEECH_MS = 8000
    const val NOISE_MEASURE_MS = 2000

    /** SPEC: the first 0.5 s after a gain change are thrown away. */
    const val NOISE_DISCARD_MS = 500

    /** Microphone start-up transient that must not count as ambient or speech. */
    private const val MIC_WARMUP_MS = 300

    val state = Observable(State())

    /** Microphone level 0..100 while measuring. */
    val level = Observable(0)

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hamir-calibration").apply { isDaemon = true }
    }

    /** Incremented by every reset; results of older jobs are dropped. Main thread only. */
    private var generation = 0
    private var cancelFlag = AtomicBoolean(false)

    // Owned by the worker thread.
    private var session: CalibrationSession? = null
    private var generator: BabbleGenerator? = null

    private class Cancelled : Exception()

    /** Step 1: load the voice bank, then measure silence. */
    fun begin(context: Context) {
        if (state.value.measuring) return
        if (Deps.protectionState.value.active) {
            state.value = State(step = Step.ERROR, problem = Problem.PROTECTION_RUNNING)
            return
        }
        val app = context.applicationContext
        val job = newJob()
        state.value = State(step = Step.LOADING)
        worker.execute {
            runGuarded(job) {
                session = null
                val settings = Deps.settings(app).load()
                val bank = Deps.bank(app)
                val plan = ProtectionPlanner.plan(
                    settings, bank.stats(Profile.A).takeCount, bank.stats(Profile.B).takeCount,
                )
                if (plan !is PlanResult.Ready) {
                    job.post { State(step = Step.ERROR, problem = Problem.NOT_ENOUGH_VOICE) }
                    return@runGuarded
                }
                val loaded = BankLoader.load(
                    bank, plan.plan.profiles,
                    onProgress = { done, total -> job.update { it.copy(loadDone = done, loadTotal = total) } },
                    isCancelled = { job.cancel.get() },
                )
                when (loaded) {
                    is BankLoadResult.Cancelled -> throw Cancelled()
                    is BankLoadResult.Unreadable -> {
                        job.post { State(step = Step.ERROR, problem = Problem.VOICE_UNREADABLE) }
                        return@runGuarded
                    }
                    is BankLoadResult.Ready -> generator = loaded.generator
                }
                val target = settings.targetSnrDb
                val newSession = CalibrationSession(target.toDouble())
                job.post { State(step = Step.AMBIENT, targetSnrDb = target) }
                val samples = recordFixed(app, job, AMBIENT_MS)
                newSession.submitAmbient(Level.rmsDbfs(samples.pcm))
                session = newSession
                job.post { State(step = Step.SPEECH_READY, targetSnrDb = target) }
            }
        }
    }

    /** Step 2: eight seconds of the user's speech. */
    fun startSpeech(context: Context) {
        val current = state.value
        if (current.step != Step.SPEECH_READY) return
        val app = context.applicationContext
        val job = newJob()
        state.value = current.copy(step = Step.SPEECH, progress = 0)
        worker.execute {
            runGuarded(job) {
                val activeSession = session ?: throw IllegalStateException("no session")
                val samples = recordFixed(app, job, SPEECH_MS)
                val speech = Level.activeSpeechDbfs(samples.pcm, samples.sampleRate)
                when (val phase = activeSession.submitSpeech(speech)) {
                    is CalibrationPhase.Speech -> job.update {
                        it.copy(step = Step.SPEECH_READY, progress = 0, rejectedMarginDb = phase.rejectedMarginDb)
                    }
                    else -> job.update {
                        it.copy(
                            step = Step.NOISE_READY, progress = 0, rejectedMarginDb = null,
                            targetNoiseDbfs = activeSession.targetNoiseDbfs,
                        )
                    }
                }
            }
        }
    }

    /** Step 3: play the noise and search for the gain. */
    fun startNoise(context: Context) {
        val current = state.value
        if (current.step != Step.NOISE_READY) return
        if (Deps.protectionState.value.active) {
            state.value = State(step = Step.ERROR, problem = Problem.PROTECTION_RUNNING)
            return
        }
        val app = context.applicationContext
        val job = newJob()
        state.value = current.copy(
            step = Step.NOISE, progress = 0, iteration = 1, lastMeasuredDbfs = null, failure = null,
        )
        worker.execute { runGuarded(job) { runNoise(app, job) } }
    }

    /** After a failed search: keep silence and speech, go back to the start of step 3. */
    fun retryNoise() {
        val current = state.value
        if (current.step != Step.FAILED) return
        val job = newJob()
        worker.execute {
            runGuarded(job) {
                val activeSession = session ?: throw IllegalStateException("no session")
                activeSession.restartNoise()
                job.update { it.copy(step = Step.NOISE_READY, failure = null, progress = 0, lastMeasuredDbfs = null) }
            }
        }
    }

    /** Aborts whatever is running and returns to the introduction. */
    fun reset() {
        generation++
        cancelFlag.set(true)
        level.value = 0
        state.value = State()
        worker.execute {
            session = null
            generator = null
        }
    }

    /** The wizard went to the background: a running measurement is worthless, waiting steps are kept. */
    fun onBackground() {
        if (state.value.measuring) reset()
    }

    // ---------------------------------------------------------------- worker side

    private class Job(val id: Int, val cancel: AtomicBoolean) {
        fun post(make: () -> State) = update { make() }

        fun update(transform: (State) -> State) {
            Deps.main.post {
                if (id == generation) state.value = transform(state.value)
            }
        }

        fun level(percent: Int) {
            Deps.main.post {
                if (id == generation) level.value = percent
            }
        }

        fun checkCancelled() {
            if (cancel.get()) throw Cancelled()
        }
    }

    private fun newJob(): Job {
        generation++
        cancelFlag.set(true)
        val flag = AtomicBoolean(false)
        cancelFlag = flag
        return Job(generation, flag)
    }

    private fun runGuarded(job: Job, block: () -> Unit) {
        try {
            if (job.cancel.get()) return
            block()
        } catch (e: Cancelled) {
            // reset() already put the wizard back to the introduction.
        } catch (e: MicUnavailableException) {
            job.post { State(step = Step.ERROR, problem = Problem.MIC) }
        } catch (e: Throwable) {
            job.post { State(step = Step.ERROR, problem = Problem.AUDIO) }
        } finally {
            job.level(0)
        }
    }

    private class Recording(val pcm: ShortArray, val sampleRate: Int)

    /** Opens the microphone, skips the warm-up and records exactly [durationMs]. */
    private fun recordFixed(app: Context, job: Job, durationMs: Int): Recording {
        var mic: MicSession? = null
        try {
            mic = MicSession.open(app)
            skip(mic, job, MIC_WARMUP_MS)
            val pcm = collect(mic, job, durationMs) { percent -> job.update { it.copy(progress = percent) } }
            return Recording(pcm, mic.sampleRate)
        } finally {
            mic?.close()
        }
    }

    private fun skip(mic: MicSession, job: Job, durationMs: Int) {
        var remaining = (mic.sampleRate.toLong() * durationMs / 1000).toInt()
        val chunk = ShortArray(mic.sampleRate / 20)
        while (remaining > 0) {
            job.checkCancelled()
            val n = mic.read(chunk, 0, minOf(chunk.size, remaining))
            remaining -= n
        }
    }

    private fun collect(mic: MicSession, job: Job, durationMs: Int, onProgress: (Int) -> Unit): ShortArray {
        val total = (mic.sampleRate.toLong() * durationMs / 1000).toInt()
        val buffer = PcmBuffer(total, total)
        val chunk = ShortArray(mic.sampleRate / 20)
        var lastPercent = -1
        while (!buffer.isFull) {
            job.checkCancelled()
            val n = mic.read(chunk, 0, minOf(chunk.size, total - buffer.size))
            if (n <= 0) continue
            buffer.append(chunk, 0, n)
            job.level(PcmMath.meterPercent(Level.peakDbfs(chunk, 0, n)))
            val percent = (buffer.size * 100L / total).toInt()
            if (percent / 5 != lastPercent / 5) {
                lastPercent = percent
                onProgress(percent)
            }
        }
        return buffer.toArray()
    }

    private fun runNoise(app: Context, job: Job) {
        val activeSession = session ?: throw IllegalStateException("no session")
        val activeGenerator = generator ?: throw IllegalStateException("no generator")
        var phase = activeSession.phase as? CalibrationPhase.Noise ?: activeSession.restartNoise() as CalibrationPhase.Noise
        val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val playerError = AtomicBoolean(false)
        val player = BabblePlayer(activeGenerator, phase.gainDb) { playerError.set(true) }
        var mic: MicSession? = null
        try {
            mic = MicSession.open(app)
            player.start()
            var previousGain = phase.gainDb
            // Start-up counts as a full change of level: fade-in plus an empty track buffer.
            var firstRound = true
            while (true) {
                job.checkCancelled()
                val current = phase
                player.gainDb = current.gainDb
                job.update {
                    it.copy(
                        step = Step.NOISE, iteration = current.iteration, gainDb = current.gainDb,
                        lastMeasuredDbfs = current.lastMeasuredDbfs, progress = 0,
                    )
                }
                val bufferFrames = if (player.bufferFrames > 0) player.bufferFrames else BabblePlayer.FADE_SAMPLES * 2
                var settle = NOISE_DISCARD_MS + BabblePlayer.settleMs(current.gainDb - previousGain, bufferFrames)
                if (firstRound) settle += BabblePlayer.FADE_MS + MIC_WARMUP_MS
                firstRound = false
                previousGain = current.gainDb
                skip(mic, job, settle)
                val pcm = collect(mic, job, NOISE_MEASURE_MS) { percent -> job.update { it.copy(progress = percent) } }
                if (playerError.get()) throw IllegalStateException("playback failed")

                val measured = Level.rmsDbfs(pcm)
                val peak = Level.peakDbfs(pcm)
                when (val next = activeSession.submitNoise(measured, peak)) {
                    is CalibrationPhase.Noise -> phase = next
                    is CalibrationPhase.Done -> {
                        val route = AudioRoutes.current(audioManager, player.routedDeviceType()) ?: OutputRoute.OTHER
                        val record = CalibrationRecord(
                            gainDb = next.outcome.gainDb,
                            targetSnrDb = activeSession.targetSnrDb.toInt(),
                            musicVolumeIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                            route = route,
                            speechDbfs = next.outcome.speechDbfs,
                            noiseDbfs = next.outcome.noiseDbfs,
                            achievedSnrDb = next.outcome.achievedSnrDb,
                            timeMillis = System.currentTimeMillis(),
                        )
                        job.checkCancelled()
                        Deps.settings(app).update { it.copy(calibration = record) }
                        job.update {
                            it.copy(step = Step.DONE, record = record, lastMeasuredDbfs = measured, progress = 100)
                        }
                        return
                    }
                    is CalibrationPhase.Failed -> {
                        job.update {
                            it.copy(step = Step.FAILED, failure = next.reason, lastMeasuredDbfs = measured, progress = 0)
                        }
                        return
                    }
                    else -> throw IllegalStateException("unexpected phase $next")
                }
            }
        } finally {
            player.stop()
            mic?.close()
            // The generator is not thread-safe: the old playback thread must be gone before a retry.
            player.join(2000)
        }
    }
}
