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
import io.github.victorkolesnyk.hamir.logic.MismatchDetector
import io.github.victorkolesnyk.hamir.logic.PlanResult
import io.github.victorkolesnyk.hamir.logic.Profile
import io.github.victorkolesnyk.hamir.logic.ProtectionPlan
import io.github.victorkolesnyk.hamir.logic.ProtectionPlanner
import io.github.victorkolesnyk.hamir.logic.ProtectionState
import io.github.victorkolesnyk.hamir.logic.ProtectionStatus
import io.github.victorkolesnyk.hamir.logic.ProtectionWarning
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

    /** Cancels the bank loading of the current start; replaced on every start. */
    private var loadCancel = AtomicBoolean(false)

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
                clearActiveFlag()
                stopSelf()
            }
            return false
        }
        if (active) return true

        active = true
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
            if (failure == null) repository.update { it.copy(protectionActive = true) }
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
            } else {
                startPlayback(readyGenerator, readyPlan, loadedCalibration)
            }
        }
    }

    private fun startPlayback(generator: BabbleGenerator, newPlan: ProtectionPlan, record: CalibrationRecord?) {
        plan = newPlan
        calibration = record
        val newPlayer = BabblePlayer(generator, newPlan.gainDb) {
            // Playback thread: AudioTrack could not be created or died.
            Deps.main.post { if (active) finish(StopReason.AUDIO_ERROR) }
        }
        player = newPlayer
        newPlayer.start()
        val state = ProtectionState(
            status = ProtectionStatus.RUNNING,
            warnings = currentWarnings(newPlan, record, newPlayer),
            mode = newPlan.mode,
            gainDb = newPlan.gainDb,
        )
        publish(state)
        Deps.main.removeCallbacks(poll)
        Deps.main.postDelayed(poll, POLL_MS)
    }

    // ---------------------------------------------------------------- running

    private fun currentWarnings(
        activePlan: ProtectionPlan, record: CalibrationRecord?, activePlayer: BabblePlayer,
    ): Set<ProtectionWarning> {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val route = AudioRoutes.current(audioManager, activePlayer.routedDeviceType())
        return MismatchDetector.check(activePlan, record, volume, route)
    }

    /** Once per second: does playback still match what was calibrated? */
    private fun checkPlayback() {
        val activePlan = plan ?: return
        val activePlayer = player ?: return
        val old = Deps.protectionState.value
        if (old.status != ProtectionStatus.RUNNING) return
        val warnings = currentWarnings(activePlan, calibration, activePlayer)
        if (warnings == old.warnings) return
        val newMismatch = warnings.any { it in MismatchDetector.MISMATCH && it !in old.warnings }
        publish(old.copy(warnings = warnings))
        if (newMismatch) vibrate(longArrayOf(0, 200))
    }

    private fun applyManualGain(gainDb: Double) {
        val activePlan = plan
        if (activePlan == null || activePlan.mode != GainMode.MANUAL) return
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
            if (reason == StopReason.HEADSET_DISCONNECTED) stoppedPlayer.kill() else stoppedPlayer.stop()
        }
        plan = null
        calibration = null

        Deps.protectionState.value = ProtectionState(ProtectionStatus.STOPPED, stopReason = reason)
        clearActiveFlag()
        if (reason != StopReason.USER) alertStopped(reason)

        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        if (!fromDestroy) stopSelf()
    }

    private fun clearActiveFlag() {
        val app = applicationContext
        Deps.io.execute {
            try {
                Deps.settings(app).update { it.copy(protectionActive = false) }
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
        val running = state?.status == ProtectionStatus.RUNNING
        val warnings = state?.warnings ?: emptySet()
        val title = getString(if (running) R.string.notif_running_title else R.string.notif_loading_title)
        val text = when {
            !running -> getString(R.string.notif_loading_text)
            ProtectionWarning.MUTED in warnings -> getString(R.string.warn_muted)
            MismatchDetector.hasMismatch(warnings) -> getString(R.string.warn_mismatch)
            ProtectionWarning.UNCALIBRATED in warnings -> getString(R.string.notif_manual_text)
            ProtectionWarning.GAIN_CLAMPED in warnings -> getString(R.string.warn_gain_clamped)
            ProtectionWarning.DERIVED_GAIN in warnings -> getString(R.string.warn_derived_gain)
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
        private const val POLL_MS = 1000L
        private val ALERT_PATTERN = longArrayOf(0, 600, 250, 600, 250, 600)

        fun stopReasonText(reason: StopReason): Int = when (reason) {
            StopReason.USER -> R.string.stop_user
            StopReason.HEADSET_DISCONNECTED -> R.string.stop_headset
            StopReason.NOT_ENOUGH_VOICE -> R.string.stop_not_enough_voice
            StopReason.VOICE_UNREADABLE -> R.string.stop_voice_unreadable
            StopReason.AUDIO_ERROR -> R.string.stop_audio_error
            StopReason.INTERRUPTED -> R.string.stop_interrupted
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
