package io.github.victorkolesnyk.hamir

import android.app.AlertDialog
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import io.github.victorkolesnyk.hamir.CalibrationController.Problem
import io.github.victorkolesnyk.hamir.CalibrationController.Step
import io.github.victorkolesnyk.hamir.core.CalibrationFailure
import io.github.victorkolesnyk.hamir.data.Deps

/**
 * Calibration wizard (SPEC 6.3 and 7). The measuring runs in [CalibrationController];
 * this class shows the current step and forwards the two buttons.
 */
class CalibrateActivity : BaseActivity() {

    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var detailView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var levelBox: View
    private lateinit var levelBar: ProgressBar
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button

    private val stateListener: (CalibrationController.State) -> Unit = { render(it) }
    private val levelListener: (Int) -> Unit = { levelBar.progress = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibrate)
        enableUpNavigation()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        titleView = findViewById(R.id.cal_title)
        bodyView = findViewById(R.id.cal_body)
        detailView = findViewById(R.id.cal_detail)
        progressBar = findViewById(R.id.cal_progress)
        levelBox = findViewById(R.id.cal_level_box)
        levelBar = findViewById(R.id.cal_level)
        primaryButton = findViewById(R.id.cal_primary)
        secondaryButton = findViewById(R.id.cal_secondary)

        primaryButton.setOnClickListener { onPrimary() }
        secondaryButton.setOnClickListener { CalibrationController.reset() }
    }

    override fun onStart() {
        super.onStart()
        CalibrationController.state.addListener(stateListener)
        CalibrationController.level.addListener(levelListener)
        render(CalibrationController.state.value)
        levelBar.progress = CalibrationController.level.value
        updateMicPermissionBox()
    }

    override fun onResume() {
        super.onResume()
        updateMicPermissionBox()
        render(CalibrationController.state.value)
    }

    override fun onStop() {
        CalibrationController.state.removeListener(stateListener)
        CalibrationController.level.removeListener(levelListener)
        // Rotation keeps a measurement going; leaving the screen aborts it.
        if (!isChangingConfigurations) CalibrationController.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        // Leaving the wizard for good frees the decrypted voice bank.
        if (isFinishing) CalibrationController.reset()
        super.onDestroy()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // The step before the noise shows the media volume: keep it current.
            Deps.main.post { if (!isDestroyed) render(CalibrationController.state.value) }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun onPrimary() {
        when (CalibrationController.state.value.step) {
            Step.INTRO -> if (ensureMicPermission()) CalibrationController.begin(this)
            Step.AMBIENT_READY -> if (ensureMicPermission()) CalibrationController.startAmbient(this)
            Step.SPEECH_READY -> if (ensureMicPermission()) CalibrationController.startSpeech(this)
            Step.NOISE_READY -> if (ensureMicPermission() && CalibrationController.startNoise(this) != null) {
                // The only refusal that leaves the step open is Do Not Disturb "total silence".
                showTracked(
                    AlertDialog.Builder(this)
                        .setTitle(R.string.start_blocked_dnd_title)
                        .setMessage(R.string.start_blocked_dnd)
                        .setPositiveButton(R.string.action_ok, null)
                )
            }
            Step.FAILED -> CalibrationController.retryNoise()
            Step.DONE -> finish()
            Step.ERROR -> CalibrationController.reset()
            Step.LOADING, Step.AMBIENT, Step.SPEECH, Step.NOISE -> Unit
        }
    }

    private fun render(state: CalibrationController.State) {
        var detail: String? = null
        var primary: Int? = null
        var secondary: Int? = null
        var progress: Int? = null
        var showLevel = false
        val title: Int
        val body: String

        when (state.step) {
            Step.INTRO -> {
                title = R.string.cal_title_intro
                body = getString(R.string.cal_intro_body)
                primary = R.string.cal_btn_begin
            }
            Step.LOADING -> {
                title = R.string.cal_title_loading
                body = getString(R.string.cal_loading_body)
                if (state.loadTotal > 0) {
                    detail = getString(R.string.cal_loading_progress, state.loadDone, state.loadTotal)
                    progress = state.loadDone * 100 / state.loadTotal
                } else {
                    progress = 0
                }
                secondary = R.string.action_cancel
            }
            Step.AMBIENT_READY -> {
                title = R.string.cal_title_ambient
                body = getString(R.string.cal_ambient_ready_body)
                primary = R.string.cal_btn_ambient
                secondary = R.string.cal_btn_restart
            }
            Step.AMBIENT -> {
                title = R.string.cal_title_ambient
                body = getString(R.string.cal_ambient_body)
                progress = state.progress
                showLevel = true
                secondary = R.string.action_cancel
            }
            Step.SPEECH_READY -> {
                title = R.string.cal_title_speech
                body = getString(R.string.cal_speech_ready_body) + "\n\n" +
                    getString(R.string.cal_target, formatDb(state.targetSnrDb))
                state.rejectedMarginDb?.let { margin ->
                    detail = getString(R.string.cal_speech_retry, formatDb(maxOf(0.0, margin)))
                }
                primary = R.string.cal_btn_speech
                secondary = R.string.cal_btn_restart
            }
            Step.SPEECH -> {
                title = R.string.cal_title_speech
                body = getString(R.string.cal_speech_body)
                progress = state.progress
                showLevel = true
                secondary = R.string.action_cancel
            }
            Step.NOISE_READY -> {
                title = R.string.cal_title_noise
                body = getString(R.string.cal_noise_ready_body)
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                detail = getString(
                    R.string.cal_volume_now,
                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                )
                primary = R.string.cal_btn_noise
                secondary = R.string.cal_btn_restart
            }
            Step.NOISE -> {
                title = R.string.cal_title_noise
                body = getString(R.string.cal_noise_body, state.iteration, formatDb(state.gainDb, signed = true))
                val measured = state.lastMeasuredDbfs
                val wanted = state.targetNoiseDbfs
                if (measured != null && wanted != null) {
                    detail = getString(R.string.cal_noise_measured, formatDb(measured), formatDb(wanted))
                }
                progress = state.progress
                showLevel = true
                secondary = R.string.action_cancel
            }
            Step.DONE -> {
                title = R.string.cal_title_done
                val record = state.record
                body = if (record == null) "" else getString(
                    R.string.cal_done_body,
                    formatDb(record.achievedSnrDb),
                    formatDb(record.targetSnrDb),
                    formatDb(record.gainDb, signed = true),
                    record.musicVolumeIndex,
                    getString(MainActivity.routeText(record.route)),
                )
                primary = R.string.cal_btn_done
                secondary = R.string.cal_btn_again
            }
            Step.FAILED -> {
                title = R.string.cal_title_failed
                body = getString(
                    when (state.failure) {
                        CalibrationFailure.NEED_MORE_VOLUME -> R.string.cal_fail_need_more_volume
                        CalibrationFailure.NEED_LESS_VOLUME -> R.string.cal_fail_need_less_volume
                        CalibrationFailure.MIC_CLIPPING -> R.string.cal_fail_mic_clipping
                        CalibrationFailure.NOISE_NOT_AUDIBLE -> R.string.cal_fail_not_audible
                        CalibrationFailure.NOT_CONVERGING, null -> R.string.cal_fail_not_converging
                    }
                )
                val measured = state.lastMeasuredDbfs
                val wanted = state.targetNoiseDbfs
                if (measured != null && wanted != null) {
                    detail = getString(R.string.cal_noise_measured, formatDb(measured), formatDb(wanted))
                }
                primary = R.string.cal_btn_retry_noise
                secondary = R.string.cal_btn_restart
            }
            Step.ERROR -> {
                title = R.string.cal_title_error
                body = getString(
                    when (state.problem) {
                        Problem.NOT_ENOUGH_VOICE -> R.string.cal_err_not_enough_voice
                        Problem.VOICE_UNREADABLE -> R.string.cal_err_voice_unreadable
                        Problem.MIC -> R.string.cal_err_mic
                        Problem.PROTECTION_RUNNING -> R.string.cal_err_protection_running
                        Problem.OUTPUT_CHANGED -> R.string.cal_err_output_changed
                        Problem.AUDIO, null -> R.string.cal_err_audio
                    }
                )
                primary = R.string.cal_btn_restart
            }
        }

        titleView.setText(title)
        bodyView.text = body
        detailView.text = detail ?: ""
        detailView.visibility = if (detail == null) View.GONE else View.VISIBLE
        progressBar.visibility = if (progress == null) View.GONE else View.VISIBLE
        if (progress != null) progressBar.progress = progress.coerceIn(0, 100)
        levelBox.visibility = if (showLevel) View.VISIBLE else View.GONE
        if (primary != null) primaryButton.setText(primary)
        primaryButton.visibility = if (primary == null) View.GONE else View.VISIBLE
        if (secondary != null) secondaryButton.setText(secondary)
        secondaryButton.visibility = if (secondary == null) View.GONE else View.VISIBLE
    }
}
