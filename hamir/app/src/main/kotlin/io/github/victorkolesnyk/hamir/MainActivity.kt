package io.github.victorkolesnyk.hamir

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.victorkolesnyk.hamir.audio.AudioRoutes
import io.github.victorkolesnyk.hamir.data.Deps
import io.github.victorkolesnyk.hamir.logic.AppSettings
import io.github.victorkolesnyk.hamir.logic.CalibrationRecord
import io.github.victorkolesnyk.hamir.logic.Headline
import io.github.victorkolesnyk.hamir.logic.OutputRoute
import io.github.victorkolesnyk.hamir.logic.Profile
import io.github.victorkolesnyk.hamir.logic.ProfileStats
import io.github.victorkolesnyk.hamir.logic.ProtectionPlanner
import io.github.victorkolesnyk.hamir.logic.ProtectionState
import io.github.victorkolesnyk.hamir.logic.ProtectionStatus
import io.github.victorkolesnyk.hamir.logic.ProtectionWarning
import io.github.victorkolesnyk.hamir.logic.StartBlock
import io.github.victorkolesnyk.hamir.logic.StartGate
import io.github.victorkolesnyk.hamir.logic.StopReason
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Main screen (SPEC 6.1): profile and calibration status, the big on/off button, target and
 * speaker settings, manual level, navigation. Settings and statistics are read on [Deps.io];
 * the protection state comes from the service through [Deps.protectionState].
 */
class MainActivity : BaseActivity() {

    private class Snapshot(val settings: AppSettings, val statsA: ProfileStats, val statsB: ProfileStats)

    private lateinit var statusView: TextView
    private lateinit var statusDetailView: TextView
    private lateinit var warningsView: TextView
    private lateinit var toggleButton: Button
    private lateinit var manualBox: View
    private lateinit var manualValueView: TextView
    private lateinit var manualSeek: SeekBar
    private lateinit var profileAView: TextView
    private lateinit var profileBView: TextView
    private lateinit var calibrationView: TextView
    private lateinit var calibrationNoteView: TextView
    private lateinit var resetCalibrationButton: Button
    private lateinit var targetGroup: RadioGroup
    private lateinit var targetMinus5: RadioButton
    private lateinit var targetZero: RadioButton
    private lateinit var twoSpeakersSwitch: Switch
    private lateinit var lockedHintView: TextView

    private var snapshot: Snapshot? = null
    private var refreshToken = 0

    /** True while [render] sets widget values, so change listeners ignore those updates. */
    private var rendering = false

    /** The process died while protection was on; shown until the next start. */
    private var interrupted = false

    /** Slider value not yet written to the settings. */
    private var pendingManualGain: Double? = null

    private val stateListener: (ProtectionState) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        interrupted = savedInstanceState?.getBoolean(KEY_INTERRUPTED) ?: false

        statusView = findViewById(R.id.main_status)
        statusDetailView = findViewById(R.id.main_status_detail)
        warningsView = findViewById(R.id.main_warnings)
        toggleButton = findViewById(R.id.main_toggle)
        manualBox = findViewById(R.id.main_manual_box)
        manualValueView = findViewById(R.id.main_manual_value)
        manualSeek = findViewById(R.id.main_manual_seek)
        profileAView = findViewById(R.id.main_profile_a)
        profileBView = findViewById(R.id.main_profile_b)
        calibrationView = findViewById(R.id.main_calibration)
        calibrationNoteView = findViewById(R.id.main_calibration_note)
        resetCalibrationButton = findViewById(R.id.main_reset_calibration)
        targetGroup = findViewById(R.id.main_target_group)
        targetMinus5 = findViewById(R.id.main_target_minus5)
        targetZero = findViewById(R.id.main_target_zero)
        twoSpeakersSwitch = findViewById(R.id.main_two_speakers)
        lockedHintView = findViewById(R.id.main_locked_hint)

        manualSeek.max = SEEK_MAX
        toggleButton.setOnClickListener { onToggle() }
        resetCalibrationButton.setOnClickListener { confirmResetCalibration() }
        findViewById<Button>(R.id.main_btn_enroll).setOnClickListener {
            startActivity(Intent(this, EnrollActivity::class.java))
        }
        findViewById<Button>(R.id.main_btn_calibrate).setOnClickListener {
            startActivity(Intent(this, CalibrateActivity::class.java))
        }
        findViewById<Button>(R.id.main_btn_guide).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }

        targetGroup.setOnCheckedChangeListener { _, checkedId ->
            if (rendering) return@setOnCheckedChangeListener
            val target = if (checkedId == R.id.main_target_zero) AppSettings.TARGET_EQUAL else AppSettings.TARGET_RECOMMENDED
            updateSettings { it.copy(targetSnrDb = target) }
        }
        twoSpeakersSwitch.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            updateSettings { it.copy(twoSpeakers = checked) }
        }
        manualSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                // Touch, keyboard and accessibility changes all arrive here with fromUser = true.
                if (!fromUser || rendering) return
                onManualGainChosen(seekToGain(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = flushManualGain()
        })
    }

    /** New slider value: shown and applied to the playing noise at once, stored after a short pause. */
    private fun onManualGainChosen(gain: Double) {
        val current = snapshot ?: return
        snapshot = Snapshot(current.settings.copy(manualGainDb = gain), current.statsA, current.statsB)
        manualValueView.text = getString(R.string.manual_gain_label, formatDb(gain, signed = true))
        // The slider is only visible without a calibration, i.e. in manual mode.
        if (Deps.protectionState.value.active) ProtectionService.setManualGain(this, gain)
        pendingManualGain = gain
        Deps.main.removeCallbacks(persistManualGain)
        Deps.main.postDelayed(persistManualGain, MANUAL_GAIN_DEBOUNCE_MS)
    }

    private val persistManualGain = Runnable { flushManualGain() }

    private fun flushManualGain() {
        Deps.main.removeCallbacks(persistManualGain)
        val gain = pendingManualGain ?: return
        pendingManualGain = null
        val app = applicationContext
        Deps.io.execute {
            try {
                Deps.settings(app).update { it.copy(manualGainDb = gain) }
            } catch (e: Exception) {
                // The next refresh shows what is really stored.
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_INTERRUPTED, interrupted)
    }

    override fun onStart() {
        super.onStart()
        Deps.protectionState.addListener(stateListener)
        render()
        refresh()
    }

    override fun onStop() {
        flushManualGain()
        Deps.protectionState.removeListener(stateListener)
        super.onStop()
    }

    // ---------------------------------------------------------------- data

    /** Reads settings and bank statistics off the UI thread. */
    private fun refresh() {
        val app = applicationContext
        val token = ++refreshToken
        Deps.io.execute {
            var loaded: Snapshot? = null
            var wasInterrupted = false
            try {
                val repository = Deps.settings(app)
                var settings = repository.load()
                if (settings.protectionActive && Deps.protectionState.value.status == ProtectionStatus.IDLE) {
                    // The flag survived although no service is running in this process: it was killed.
                    wasInterrupted = true
                    settings = repository.update { it.copy(protectionActive = false) }
                }
                val bank = Deps.bank(app)
                loaded = Snapshot(settings, bank.stats(Profile.A), bank.stats(Profile.B))
            } catch (e: Exception) {
                // Leave the previous snapshot; the screen stays usable.
            }
            val result = loaded
            val killed = wasInterrupted
            Deps.main.post {
                if (token != refreshToken || isDestroyed) return@post
                if (result != null) snapshot = result
                if (killed) interrupted = true
                render()
            }
        }
    }

    /** Applies a change to the visible snapshot at once and to the stored settings on [Deps.io]. */
    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val current = snapshot ?: return
        snapshot = Snapshot(transform(current.settings), current.statsA, current.statsB)
        render()
        val app = applicationContext
        Deps.io.execute {
            try {
                Deps.settings(app).update(transform)
            } catch (e: Exception) {
                // The next refresh shows what is really stored.
            }
        }
    }

    // ---------------------------------------------------------------- actions

    private fun onToggle() {
        if (Deps.protectionState.value.active) {
            ProtectionService.stop(this)
            return
        }
        val current = snapshot ?: return
        when (StartGate.protection(current.statsA.ready, AudioRoutes.totalSilence(this))) {
            StartBlock.NOT_ENOUGH_VOICE -> {
                Toast.makeText(this, R.string.main_need_voice, Toast.LENGTH_LONG).show()
                return
            }
            StartBlock.DND_TOTAL_SILENCE -> {
                showTracked(
                    AlertDialog.Builder(this)
                        .setTitle(R.string.start_blocked_dnd_title)
                        .setMessage(R.string.start_blocked_dnd)
                        .setPositiveButton(R.string.action_ok, null)
                )
                return
            }
            null -> Unit
        }
        // POST_NOTIFICATIONS is a runtime permission since API 33; older versions need no request.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            showTracked(
                AlertDialog.Builder(this)
                    .setTitle(R.string.perm_notif_title)
                    .setMessage(R.string.perm_notif_message)
                    .setPositiveButton(R.string.perm_notif_allow) { _, _ ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
                        }
                    }
                    .setNegativeButton(R.string.perm_notif_skip) { _, _ -> startProtection() }
            )
            return
        }
        startProtection()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        // An empty result means the request was interrupted: the user did not decide, so do not start.
        if (grantResults.isEmpty()) return
        if (grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.perm_notif_denied, Toast.LENGTH_LONG).show()
        }
        // Protection works without notifications too; a refusal must not block it.
        startProtection()
    }

    private fun startProtection() {
        if (Deps.protectionState.value.active) return
        interrupted = false
        if (!ProtectionService.start(this)) {
            Toast.makeText(this, R.string.main_start_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmResetCalibration() {
        showTracked(
            AlertDialog.Builder(this)
                .setTitle(R.string.confirm_reset_cal_title)
                .setMessage(R.string.confirm_reset_cal_message)
                .setPositiveButton(R.string.action_reset) { _, _ ->
                    if (!Deps.protectionState.value.active) updateSettings { it.copy(calibration = null) }
                }
                .setNegativeButton(R.string.action_cancel, null)
        )
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        rendering = true
        try {
            val state = Deps.protectionState.value
            renderStatus(state)
            renderSettings(state, snapshot)
        } finally {
            rendering = false
        }
    }

    private fun renderStatus(state: ProtectionState) {
        // The process died while the noise played: show it like any other unwanted stop.
        val showInterrupted = interrupted && !state.active && state.headline == Headline.OFF
        val headline = if (showInterrupted) Headline.STOPPED_ALERT else state.headline

        statusView.text = if (headline == Headline.LOADING && state.loadTotal > 0) {
            getString(R.string.status_loading_progress, state.loadDone, state.loadTotal)
        } else {
            getString(ProtectionService.headlineText(headline))
        }
        // Green is reserved for the one state that was actually measured.
        statusView.setTextColor(
            getColor(
                when {
                    headline.alarming -> R.color.warning
                    headline == Headline.PLAYING_CALIBRATED -> R.color.ok
                    else -> R.color.accent
                }
            )
        )

        val detail: String? = when {
            state.status == ProtectionStatus.RUNNING -> {
                val gain = getString(R.string.mode_gain, formatDb(state.gainDb, signed = true))
                // Lets the user see at a glance when the noise goes somewhere unexpected.
                val route = state.route
                if (route == null) gain else gain + " " + getString(R.string.mode_route, getString(routeText(route)))
            }
            showInterrupted -> getString(R.string.stop_interrupted)
            headline == Headline.STOPPED_ALERT ->
                getString(ProtectionService.stopReasonText(state.stopReason ?: StopReason.INTERRUPTED))
            else -> null
        }
        statusDetailView.text = detail ?: ""
        statusDetailView.visibility = if (detail == null) View.GONE else View.VISIBLE

        val lines = ArrayList<String>()
        if (state.status == ProtectionStatus.RUNNING) {
            for (warning in state.warnings) lines.add(getString(warningText(warning)))
        }
        warningsView.text = lines.joinToString("\n")
        warningsView.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE

        toggleButton.setText(if (state.active) R.string.main_toggle_stop else R.string.main_toggle_start)
    }

    private fun renderSettings(state: ProtectionState, current: Snapshot?) {
        toggleButton.isEnabled = state.active || current != null
        val editable = current != null && !state.active
        targetMinus5.isEnabled = editable
        targetZero.isEnabled = editable
        lockedHintView.visibility = if (state.active) View.VISIBLE else View.GONE
        if (current == null) {
            twoSpeakersSwitch.isEnabled = false
            resetCalibrationButton.visibility = View.GONE
            return
        }
        val settings = current.settings
        profileAView.text = statsText(R.string.profile_a, current.statsA)
        profileBView.text = statsText(R.string.profile_b, current.statsB)

        targetGroup.check(
            if (settings.targetSnrDb == AppSettings.TARGET_EQUAL) R.id.main_target_zero else R.id.main_target_minus5
        )
        val twoAvailable = ProtectionPlanner.twoSpeakersAvailable(current.statsA.takeCount, current.statsB.takeCount)
        twoSpeakersSwitch.isEnabled = editable && twoAvailable
        twoSpeakersSwitch.isChecked = settings.twoSpeakers && twoAvailable

        val calibration = settings.calibration
        if (calibration == null) {
            calibrationView.setText(R.string.cal_none)
            calibrationNoteView.visibility = View.GONE
            resetCalibrationButton.visibility = View.GONE
            manualBox.visibility = View.VISIBLE
            if (!manualSeek.isPressed && manualSeek.progress != gainToSeek(settings.manualGainDb)) {
                manualSeek.progress = gainToSeek(settings.manualGainDb)
            }
            if (!manualSeek.isPressed) {
                manualValueView.text =
                    getString(R.string.manual_gain_label, formatDb(settings.manualGainDb, signed = true))
            }
        } else {
            calibrationView.text = calibrationText(calibration)
            calibrationNoteView.visibility =
                if (calibration.targetSnrDb != settings.targetSnrDb) View.VISIBLE else View.GONE
            resetCalibrationButton.visibility = View.VISIBLE
            resetCalibrationButton.isEnabled = !state.active
            manualBox.visibility = View.GONE
        }
    }

    private fun statsText(nameRes: Int, stats: ProfileStats): String =
        getString(R.string.profile_stats, getString(nameRes), stats.takeCount, stats.recordedSeconds.roundToInt())

    private fun calibrationText(record: CalibrationRecord): String {
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, UKRAINIAN)
            .format(Date(record.timeMillis))
        return getString(
            R.string.cal_summary,
            time,
            formatDb(record.achievedSnrDb),
            formatDb(record.targetSnrDb),
            record.musicVolumeIndex,
            getString(routeText(record.route)),
        )
    }

    companion object {
        private const val KEY_INTERRUPTED = "interrupted"
        private const val MANUAL_GAIN_DEBOUNCE_MS = 400L
        private val SEEK_MAX = (AppSettings.MAX_GAIN_DB - AppSettings.MIN_GAIN_DB).toInt()

        fun seekToGain(progress: Int): Double = AppSettings.clampGain(AppSettings.MIN_GAIN_DB + progress)

        fun gainToSeek(gainDb: Double): Int =
            (AppSettings.clampGain(gainDb) - AppSettings.MIN_GAIN_DB).roundToInt().coerceIn(0, SEEK_MAX)

        fun routeText(route: OutputRoute): Int = when (route) {
            OutputRoute.WIRED -> R.string.route_wired
            OutputRoute.BLUETOOTH -> R.string.route_bluetooth
            OutputRoute.SPEAKER -> R.string.route_speaker
            OutputRoute.OTHER -> R.string.route_other
        }

        fun warningText(warning: ProtectionWarning): Int = when (warning) {
            ProtectionWarning.UNCALIBRATED -> R.string.warn_uncalibrated
            ProtectionWarning.VOLUME_CHANGED -> R.string.warn_volume_changed
            ProtectionWarning.ROUTE_CHANGED -> R.string.warn_route_changed
            ProtectionWarning.MUTED -> R.string.warn_muted
            ProtectionWarning.DND_SILENCE -> R.string.warn_dnd
            ProtectionWarning.ROUTE_UNKNOWN -> R.string.warn_route_unknown
            ProtectionWarning.DERIVED_GAIN -> R.string.warn_derived_gain
            ProtectionWarning.GAIN_CLAMPED -> R.string.warn_gain_clamped
        }
    }
}
