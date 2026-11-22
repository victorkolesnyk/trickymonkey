package io.github.victorkolesnyk.hamir

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.victorkolesnyk.hamir.audio.AudioRoutes
import io.github.victorkolesnyk.hamir.audio.BabblePlayer
import io.github.victorkolesnyk.hamir.core.BabbleGenerator
import io.github.victorkolesnyk.hamir.data.Deps
import io.github.victorkolesnyk.hamir.logic.AppSettings
import io.github.victorkolesnyk.hamir.logic.BankLoadResult
import io.github.victorkolesnyk.hamir.logic.BankLoader
import io.github.victorkolesnyk.hamir.logic.CalibrationRecord
import io.github.victorkolesnyk.hamir.logic.GainMode
import io.github.victorkolesnyk.hamir.logic.Headline
import io.github.victorkolesnyk.hamir.logic.MismatchDetector
import io.github.victorkolesnyk.hamir.logic.PlanResult
import io.github.victorkolesnyk.hamir.logic.PlaybackConditions
import io.github.victorkolesnyk.hamir.logic.Profile
import io.github.victorkolesnyk.hamir.logic.ProtectionPlan
import io.github.victorkolesnyk.hamir.logic.ProtectionPlanner
import io.github.victorkolesnyk.hamir.logic.ProtectionState
import io.github.victorkolesnyk.hamir.logic.ProtectionStatus
import io.github.victorkolesnyk.hamir.logic.ProtectionWarning
import io.github.victorkolesnyk.hamir.logic.RouteGuard
import io.github.victorkolesnyk.hamir.logic.SilenceAlarm
import io.github.victorkolesnyk.hamir.logic.StartGate
import io.github.victorkolesnyk.hamir.logic.StopReason
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service (type mediaPlayback) that plays the babble. It never opens the microphone.
 *
 * Lifecycle: ACTION_START -> LOADING (bank decrypted on a worker thread) -> RUNNING -> STOPPED.
 * All fields are touched on the main thread only; the worker threads talk back through [Deps.main].
 * The state for the main screen is published in [Deps.protectionState].
 */
class ProtectionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var player: BabblePlayer? = null
    private var plan: ProtectionPlan? = null
    private var calibration: CalibrationRecord? = null
    private var receiverRegistered = false

    /** True from the start request until [finish]. */
    private var active = false

    /** Manual level chosen while the bank was still loading; applied when playback starts. */
    private var pendingManualGainDb: Double? = null

    /** Cancels the bank loading of the current start; replaced on every start. */
    private var loadCancel = AtomicBoolean(false)

    /** Both are replaced on every start of playback. */
    private var routeGuard = RouteGuard()
    private var silenceAlarm = SilenceAlarm()
    private var silentNotified = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                finish(StopReason.HEADSET_DISCONNECTED)
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!active || player == null) return
            checkPlayback()
            Deps.main.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (active) finish(StopReason.USER) else stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_MANUAL_GAIN -> {
                if (!active) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val gain = AppSettings.clampGain(intent.getDoubleExtra(EXTRA_GAIN_DB, AppSettings.DEFAULT_MANUAL_GAIN_DB))
                applyManualGain(gain)
                return START_STICKY
            }
        }
        // ACTION_START, or a null intent: the system recreated the service after killing the process.
        val restartedBySystem = intent == null
        return if (begin(restartedBySystem)) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        // Destroyed while active means nobody asked for it: do not lose protection silently.
        if (active) finish(StopReason.INTERRUPTED, fromDestroy = true)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start

    private fun begin(restartedBySystem: Boolean): Boolean {
        // startForegroundService() obliges us to call startForeground() every time, even when already running.
        if (!enterForeground(buildOngoingNotification(Deps.protectionState.value.takeIf { active }))) {
            if (active) {
                finish(StopReason.INTERRUPTED)
            } else {
                Deps.protectionState.value = ProtectionState(ProtectionStatus.STOPPED, stopReason = StopReason.INTERRUPTED)
                if (restartedBySystem) alertStopped(StopReason.INTERRUPTED)
                writeActiveFlag(false)
                stopSelf()
            }
            return false
        }
        if (active) return true

        active = true
        pendingManualGainDb = null
        // Alerts of an earlier run would contradict the new one.
        val manager = notificationManager()
        manager.cancel(NOTIFICATION_ALERT)
        manager.cancel(NOTIFICATION_SILENT)
        manager.cancel(NOTIFICATION_GAP)
        // Set already while loading: a process killed now must be noticed too. On a system restart
        // the stored flag is what tells a real interruption from a stale restart, so leave it alone.
        if (!restartedBySystem) writeActiveFlag(true)
        acquireWakeLock()
        registerNoisyReceiver()
        val cancel = AtomicBoolean(false)
        loadCancel = cancel
        publish(ProtectionState(ProtectionStatus.LOADING))

        val app = applicationContext
        Thread({ load(app, cancel, restartedBySystem) }, "hamir-bank-load").start()
        return true
    }

    /** Worker thread: settings, plan, decryption, generator. */
    private fun load(app: Context, cancel: AtomicBoolean, restartedBySystem: Boolean) {
        var failure: StopReason? = null
        var generator: BabbleGenerator? = null
        var loadedPlan: ProtectionPlan? = null
        var loadedCalibration: CalibrationRecord? = null
        try {
            val repository = Deps.settings(app)
            val settings = repository.load()
            if (restartedBySystem && !settings.protectionActive) {
                // Stale restart after an orderly stop: nothing to resume.
                Deps.main.post { if (loadCancel === cancel && active) finish(StopReason.USER) }
                return
            }
            val bank = Deps.bank(app)
            val planResult = ProtectionPlanner.plan(
                settings, bank.stats(Profile.A).takeCount, bank.stats(Profile.B).takeCount,
            )
            if (planResult is PlanResult.Ready) {
                loadedPlan = planResult.plan
                loadedCalibration = settings.calibration
                val result = BankLoader.load(
                    bank, planResult.plan.profiles,
                    onProgress = { done, total ->
                        if (done % 5 == 0 || done == total) {
                            Deps.main.post {
                                if (loadCancel === cancel && Deps.protectionState.value.status == ProtectionStatus.LOADING) {
                                    Deps.protectionState.value =
                                        Deps.protectionState.value.copy(loadDone = done, loadTotal = total)
                                }
                            }
                        }
                    },
                    isCancelled = { cancel.get() },
                )
                when (result) {
                    is BankLoadResult.Ready -> generator = result.generator
                    is BankLoadResult.Unreadable -> failure = StopReason.VOICE_UNREADABLE
                    is BankLoadResult.Cancelled -> return
                }
            } else {
                failure = StopReason.NOT_ENOUGH_VOICE
            }
        } catch (e: Throwable) {
            failure = StopReason.VOICE_UNREADABLE
            generator = null
        }
        val readyGenerator = generator
        val readyPlan = loadedPlan
        val finalFailure = failure
        Deps.main.post {
            if (loadCancel !== cancel || !active) return@post
            if (finalFailure != null || readyGenerator == null || readyPlan == null) {
                finish(finalFailure ?: StopReason.VOICE_UNREADABLE)
            } else if (StartGate.protection(voiceReady = true, AudioRoutes.totalSilence(this)) != null) {
                // The main screen refuses this too; here it covers a system restart and a mode switched
                // on while the bank was loading. Starting would play into a muted output with no way to warn.
                finish(StopReason.DND_SILENCE)
            } else {
                startPlayback(readyGenerator, readyPlan, loadedCalibration)
                // The process was killed while the noise played and the system brought the service back:
                // the noise plays again, but the user must learn about the gap.
                if (restartedBySystem && active) alertGap()
            }
        }
    }

    private fun startPlayback(generator: BabbleGenerator, loadedPlan: ProtectionPlan, record: CalibrationRecord?) {
        val pending = pendingManualGainDb
        pendingManualGainDb = null
        val newPlan = if (pending != null && loadedPlan.mode == GainMode.MANUAL) {
            loadedPlan.copy(gainDb = pending)
        } else {
            loadedPlan
        }
        plan = newPlan
        calibration = record
        routeGuard = RouteGuard()
        silenceAlarm = SilenceAlarm()
        silentNotified = false
        val newPlayer = BabblePlayer(
            generator, newPlan.gainDb,
            onError = {
                // Playback thread: AudioTrack could not be created or died.
                Deps.main.post { if (active) finish(StopReason.AUDIO_ERROR) }
            },
            // Main thread: react to a re-route at once instead of waiting for the next poll.
            onRoutingChanged = { if (active && player != null) checkPlayback() },
        )
        player = newPlayer
        newPlayer.start()
        publish(ProtectionState(status = ProtectionStatus.RUNNING, mode = newPlan.mode, gainDb = newPlan.gainDb))
        checkPlayback()
        Deps.main.removeCallbacks(poll)
        Deps.main.postDelayed(poll, POLL_MS)
    }

    // ---------------------------------------------------------------- running

    /**
     * Once per second and on every routing callback: is the noise still coming out of the same
     * device, is it audible, does it match the calibration?
     */
    private fun checkPlayback() {
        val activePlan = plan ?: return
        val activePlayer = player ?: return
        val old = Deps.protectionState.value
        if (old.status != ProtectionStatus.RUNNING) return
        val now = SystemClock.elapsedRealtime()

        val routed = activePlayer.routedDevice()
        val verdict = routeGuard.observe(routed?.id, now)
        if (verdict == RouteGuard.Verdict.LOST) {
            // In every mode: the sound left the earbud that sits on the target microphone.
            finish(StopReason.OUTPUT_CHANGED)
            return
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val conditions = PlaybackConditions(
            volumeIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            // A muted stream keeps its index.
            streamMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC),
            // "Total silence" mutes media players without touching the stream volume.
            totalSilence = AudioRoutes.totalSilence(this),
            // No guessing: the route type is judged only once the system has reported a real device.
            route = if (routeGuard.baselineKnown && routed != null) AudioRoutes.classify(routed.type) else null,
            routeUnknownTooLong = verdict == RouteGuard.Verdict.PENDING_TOO_LONG,
        )
        val warnings = MismatchDetector.check(activePlan, calibration, conditions)
        val route = conditions.route ?: old.route
        if (warnings != old.warnings || route != old.route) {
            val newMismatch = warnings.any { it in MismatchDetector.MISMATCH && it !in old.warnings }
            publish(old.copy(warnings = warnings, route = route))
            if (newMismatch) vibrate(longArrayOf(0, 200))
        }

        val silent = MismatchDetector.isSilent(warnings)
        if (silenceAlarm.onObservation(silent, now)) {
            alertSilent(warnings)
        } else if (!silent && silentNotified) {
            silentNotified = false
            notificationManager().cancel(NOTIFICATION_SILENT)
        }
    }

    private fun applyManualGain(gainDb: Double) {
        val activePlan = plan
        if (activePlan == null) {
            // Still loading: remember the value, the plan decides later whether it applies.
            pendingManualGainDb = gainDb
            return
        }
        if (activePlan.mode != GainMode.MANUAL) return
        plan = activePlan.copy(gainDb = gainDb)
        player?.gainDb = gainDb
        val old = Deps.protectionState.value
        if (old.status == ProtectionStatus.RUNNING) Deps.protectionState.value = old.copy(gainDb = gainDb)
    }

    /** Publishes the state and mirrors it in the ongoing notification. */
    private fun publish(state: ProtectionState) {
        Deps.protectionState.value = state
        if (active) notificationManager().notify(NOTIFICATION_ONGOING, buildOngoingNotification(state))
    }

    // ---------------------------------------------------------------- stop

    /**
     * Single exit point. Idempotent. A stop that the user did not ask for raises the
     * "PROTECTION STOPPED" notification with vibration.
     */
    private fun finish(reason: StopReason, fromDestroy: Boolean = false) {
        if (!active) return
        active = false
        loadCancel.set(true)
        Deps.main.removeCallbacks(poll)
        unregisterNoisyReceiver()

        val stoppedPlayer = player
        player = null
        if (stoppedPlayer != null) {
            // Headphones gone: the sound would continue from the phone speaker, so cut it at once.
            val outputGone = reason == StopReason.HEADSET_DISCONNECTED || reason == StopReason.OUTPUT_CHANGED
            if (outputGone) stoppedPlayer.kill() else stoppedPlayer.stop()
        }
        plan = null
        calibration = null

        Deps.protectionState.value = ProtectionState(ProtectionStatus.STOPPED, stopReason = reason)
        writeActiveFlag(false)
        silentNotified = false
        notificationManager().cancel(NOTIFICATION_SILENT)
        if (reason != StopReason.USER) alertStopped(reason)

        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        if (!fromDestroy) stopSelf()
    }

    /**
     * Persists "protection is on" so that a killed process can be noticed later.
     * Always written through the serial [Deps.io] executor, so set and clear cannot overtake each other.
     */
    private fun writeActiveFlag(value: Boolean) {
        val app = applicationContext
        Deps.io.execute {
            try {
                Deps.settings(app).update { it.copy(protectionActive = value) }
            } catch (e: Exception) {
                // Only the "interrupted" hint on the main screen depends on this flag.
            }
        }
    }

    // ---------------------------------------------------------------- system plumbing

    private fun enterForeground(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ONGOING, notification)
        }
        true
    } catch (e: RuntimeException) {
        // API 31+: ForegroundServiceStartNotAllowedException (an IllegalStateException) when the app is
        // in the background; SecurityException if the type permission is missing.
        false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hamir:protection")
        lock.setReferenceCounted(false)
        lock.acquire()
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: RuntimeException) {
            // Already released.
        }
        wakeLock = null
    }

    private fun registerNoisyReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // System broadcasts are still delivered to a non-exported receiver.
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        try {
            unregisterReceiver(noisyReceiver)
        } catch (e: IllegalArgumentException) {
            // Was not registered after all.
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        val manager = notificationManager()
        val ongoing = NotificationChannel(
            CHANNEL_ONGOING, getString(R.string.channel_ongoing_name), NotificationManager.IMPORTANCE_LOW,
        )
        ongoing.description = getString(R.string.channel_ongoing_description)
        ongoing.setShowBadge(false)
        manager.createNotificationChannel(ongoing)

        val alerts = NotificationChannel(
            CHANNEL_ALERT, getString(R.string.channel_alert_name), NotificationManager.IMPORTANCE_HIGH,
        )
        alerts.description = getString(R.string.channel_alert_description)
        alerts.enableVibration(true)
        alerts.vibrationPattern = ALERT_PATTERN
        manager.createNotificationChannel(alerts)
    }

    private fun mainScreenIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildOngoingNotification(state: ProtectionState?): Notification {
        val running = state != null && state.status == ProtectionStatus.RUNNING
        val warnings = state?.warnings ?: emptySet()
        val title = if (state != null && state.status == ProtectionStatus.RUNNING) {
            getString(headlineText(state.headline))
        } else {
            getString(R.string.notif_loading_title)
        }
        val text = when {
            !running -> getString(R.string.notif_loading_text)
            ProtectionWarning.MUTED in warnings -> getString(R.string.warn_muted)
            ProtectionWarning.DND_SILENCE in warnings -> getString(R.string.warn_dnd)
            ProtectionWarning.UNCALIBRATED in warnings -> getString(R.string.warn_uncalibrated)
            ProtectionWarning.VOLUME_CHANGED in warnings -> getString(R.string.warn_volume_changed)
            ProtectionWarning.ROUTE_CHANGED in warnings -> getString(R.string.warn_route_changed)
            ProtectionWarning.GAIN_CLAMPED in warnings -> getString(R.string.warn_gain_clamped)
            ProtectionWarning.DERIVED_GAIN in warnings -> getString(R.string.warn_derived_gain)
            ProtectionWarning.ROUTE_UNKNOWN in warnings -> getString(R.string.warn_route_unknown)
            else -> getString(R.string.notif_running_text)
        }
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ProtectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stat_stop), getString(R.string.action_stop), stopIntent,
        ).build()
        val builder = Notification.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_hamir)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(mainScreenIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(stopAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Without this Android 12+ may delay showing the notification by ten seconds.
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** "PROTECTION STOPPED": separate notification that survives the service, plus vibration. */
    private fun alertStopped(reason: StopReason) {
        val text = getString(stopReasonText(reason))
        val notification = Notification.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_hamir)
            .setContentTitle(getString(R.string.notif_stopped_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(mainScreenIntent())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        try {
            notificationManager().notify(NOTIFICATION_ALERT, notification)
        } catch (e: RuntimeException) {
            // The vibration below still signals the stop.
        }
        vibrate(ALERT_PATTERN)
    }

    /**
     * "NO SOUND": the noise plays into a muted stream. Posted again every ten seconds while that
     * lasts, each time with the long vibration; removed as soon as the sound is back.
     */
    private fun alertSilent(warnings: Set<ProtectionWarning>) {
        val text = getString(if (ProtectionWarning.MUTED in warnings) R.string.warn_muted else R.string.warn_dnd)
        val notification = Notification.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_hamir)
            .setContentTitle(getString(R.string.notif_silent_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(mainScreenIntent())
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        try {
            notificationManager().notify(NOTIFICATION_SILENT, notification)
            silentNotified = true
        } catch (e: RuntimeException) {
            // The vibration below still signals the problem.
        }
        vibrate(ALERT_PATTERN)
    }

    /** One-off notice after the system restarted the service: there was a gap without noise. */
    private fun alertGap() {
        val text = getString(R.string.notif_gap_text)
        val notification = Notification.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_hamir)
            .setContentTitle(getString(R.string.notif_gap_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(mainScreenIntent())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        try {
            notificationManager().notify(NOTIFICATION_GAP, notification)
        } catch (e: RuntimeException) {
            // The vibration below still signals the gap.
        }
        vibrate(ALERT_PATTERN)
    }

    private fun vibrate(pattern: LongArray) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            // Alarm usage keeps the vibration when touch feedback is disabled in the system settings.
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            @Suppress("DEPRECATION")
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1), attributes)
        } catch (e: RuntimeException) {
            // No vibrator or not allowed: the notification remains.
        }
    }

    companion object {
        const val ACTION_START = "io.github.victorkolesnyk.hamir.action.START"
        const val ACTION_STOP = "io.github.victorkolesnyk.hamir.action.STOP"
        const val ACTION_SET_MANUAL_GAIN = "io.github.victorkolesnyk.hamir.action.SET_MANUAL_GAIN"
        const val EXTRA_GAIN_DB = "gain_db"

        private const val CHANNEL_ONGOING = "protection"
        private const val CHANNEL_ALERT = "protection_alert"
        private const val NOTIFICATION_ONGOING = 1
        const val NOTIFICATION_ALERT = 2
        private const val NOTIFICATION_SILENT = 3
        private const val NOTIFICATION_GAP = 4
        private const val POLL_MS = 1000L
        private val ALERT_PATTERN = longArrayOf(0, 600, 250, 600, 250, 600)

        fun stopReasonText(reason: StopReason): Int = when (reason) {
            StopReason.USER -> R.string.stop_user
            StopReason.HEADSET_DISCONNECTED -> R.string.stop_headset
            StopReason.OUTPUT_CHANGED -> R.string.stop_output_changed
            StopReason.DND_SILENCE -> R.string.start_blocked_dnd
            StopReason.NOT_ENOUGH_VOICE -> R.string.stop_not_enough_voice
            StopReason.VOICE_UNREADABLE -> R.string.stop_voice_unreadable
            StopReason.AUDIO_ERROR -> R.string.stop_audio_error
            StopReason.INTERRUPTED -> R.string.stop_interrupted
        }

        /** Text of the one-line status, shared by the main screen and the notification title. */
        fun headlineText(headline: Headline): Int = when (headline) {
            Headline.OFF -> R.string.status_idle
            Headline.LOADING -> R.string.status_loading
            Headline.STOPPED_ALERT -> R.string.status_stopped_alert
            Headline.SILENT -> R.string.status_silent
            Headline.PLAYING_UNVERIFIED -> R.string.status_playing_unverified
            Headline.PLAYING_MISMATCH -> R.string.status_playing_mismatch
            Headline.PLAYING_LEVEL_NOT_REACHED -> R.string.status_playing_level_not_reached
            Headline.PLAYING_DERIVED -> R.string.status_playing_derived
            Headline.PLAYING_CALIBRATED -> R.string.status_playing_calibrated
        }

        /** Must be called while the activity is in the foreground (Android 12+ background-start rules). */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, ProtectionService::class.java).setAction(ACTION_START))
            true
        } catch (e: RuntimeException) {
            false
        }

        fun stop(context: Context) {
            sendCommand(context, Intent(context, ProtectionService::class.java).setAction(ACTION_STOP))
        }

        fun setManualGain(context: Context, gainDb: Double) {
            sendCommand(
                context,
                Intent(context, ProtectionService::class.java)
                    .setAction(ACTION_SET_MANUAL_GAIN)
                    .putExtra(EXTRA_GAIN_DB, gainDb),
            )
        }

        private fun sendCommand(context: Context, intent: Intent) {
            try {
                context.startService(intent)
            } catch (e: RuntimeException) {
                // App in the background and service not running: nothing to command.
            }
        }
    }
}
