package io.github.victorkolesnyk.hamir

import android.app.AlertDialog
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import io.github.victorkolesnyk.hamir.EnrollController.Busy
import io.github.victorkolesnyk.hamir.EnrollController.Notice
import io.github.victorkolesnyk.hamir.logic.EnrollmentRules
import io.github.victorkolesnyk.hamir.logic.Profile

/**
 * Voice enrollment (SPEC 6.2): one phrase at a time, record / listen / re-record / next,
 * level meter, counter, profile switch, deletion with confirmation.
 * The session itself lives in [EnrollController]; this class only renders it.
 */
class EnrollActivity : BaseActivity() {

    private lateinit var phrases: Array<String>
    private lateinit var profileGroup: RadioGroup
    private lateinit var profileA: RadioButton
    private lateinit var profileB: RadioButton
    private lateinit var counterView: TextView
    private lateinit var phraseNumberView: TextView
    private lateinit var phraseView: TextView
    private lateinit var phraseStatusView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var noticeView: TextView
    private lateinit var recordButton: Button
    private lateinit var playButton: Button
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var deleteProfileButton: Button
    private lateinit var deleteAllButton: Button

    private var rendering = false

    private val stateListener: (EnrollController.State) -> Unit = { render(it) }
    private val levelListener: (Int) -> Unit = { levelBar.progress = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enroll)
        enableUpNavigation()
        // Reading a phrase takes longer than the screen timeout of many phones.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        phrases = resources.getStringArray(R.array.enroll_phrases)
        profileGroup = findViewById(R.id.enroll_profile_group)
        profileA = findViewById(R.id.enroll_profile_a)
        profileB = findViewById(R.id.enroll_profile_b)
        counterView = findViewById(R.id.enroll_counter)
        phraseNumberView = findViewById(R.id.enroll_phrase_number)
        phraseView = findViewById(R.id.enroll_phrase)
        phraseStatusView = findViewById(R.id.enroll_phrase_status)
        levelBar = findViewById(R.id.enroll_level)
        noticeView = findViewById(R.id.enroll_notice)
        recordButton = findViewById(R.id.enroll_record)
        playButton = findViewById(R.id.enroll_play)
        previousButton = findViewById(R.id.enroll_prev)
        nextButton = findViewById(R.id.enroll_next)
        deleteProfileButton = findViewById(R.id.enroll_delete_profile)
        deleteAllButton = findViewById(R.id.enroll_delete_all)

        if (savedInstanceState != null) {
            // After process death the controller is new: put it back where the user was.
            val savedProfile = if (savedInstanceState.getString(KEY_PROFILE) == Profile.B.name) Profile.B else Profile.A
            EnrollController.restore(savedProfile, savedInstanceState.getInt(KEY_PHRASE, 1))
        }

        profileGroup.setOnCheckedChangeListener { _, checkedId ->
            if (rendering) return@setOnCheckedChangeListener
            val profile = if (checkedId == R.id.enroll_profile_b) Profile.B else Profile.A
            EnrollController.selectProfile(this, profile)
        }
        recordButton.setOnClickListener {
            if (EnrollController.state.value.busy == Busy.RECORDING) {
                EnrollController.stopRecording()
            } else if (ensureMicPermission()) {
                EnrollController.startRecording(this)
            }
        }
        playButton.setOnClickListener {
            if (EnrollController.state.value.busy == Busy.PLAYING) {
                EnrollController.stopPlayback()
            } else {
                EnrollController.play(this)
            }
        }
        previousButton.setOnClickListener { EnrollController.previous() }
        nextButton.setOnClickListener { EnrollController.next() }
        deleteProfileButton.setOnClickListener { confirmDeleteProfile() }
        deleteAllButton.setOnClickListener { confirmDeleteAll() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val state = EnrollController.state.value
        outState.putString(KEY_PROFILE, state.profile.name)
        outState.putInt(KEY_PHRASE, state.phrase)
    }

    override fun onStart() {
        super.onStart()
        EnrollController.state.addListener(stateListener)
        EnrollController.level.addListener(levelListener)
        EnrollController.open(this)
        render(EnrollController.state.value)
        levelBar.progress = EnrollController.level.value
        updateMicPermissionBox()
    }

    override fun onResume() {
        super.onResume()
        // The user may come back from the system settings with the permission granted.
        updateMicPermissionBox()
    }

    override fun onStop() {
        EnrollController.state.removeListener(stateListener)
        EnrollController.level.removeListener(levelListener)
        // Rotation keeps the recording going; leaving the screen ends it.
        if (!isChangingConfigurations) EnrollController.onBackground()
        super.onStop()
    }

    private fun confirmDeleteProfile() {
        val state = EnrollController.state.value
        if (state.busy != Busy.IDLE) return
        val name = getString(if (state.profile == Profile.A) R.string.profile_a else R.string.profile_b)
        showTracked(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_delete_profile_title, name))
                .setMessage(R.string.confirm_delete_profile_message)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    // The dialog is not modal across rotation: make sure it still targets the same profile.
                    if (EnrollController.state.value.profile == state.profile) EnrollController.deleteProfile(this)
                }
                .setNegativeButton(R.string.action_cancel, null)
        )
    }

    private fun confirmDeleteAll() {
        if (EnrollController.state.value.busy != Busy.IDLE) return
        showTracked(
            AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete_all_title)
                .setMessage(R.string.confirm_delete_all_message)
                .setPositiveButton(R.string.action_delete) { _, _ -> EnrollController.deleteAll(this) }
                .setNegativeButton(R.string.action_cancel, null)
        )
    }

    private fun render(state: EnrollController.State) {
        rendering = true
        try {
            profileGroup.check(if (state.profile == Profile.B) R.id.enroll_profile_b else R.id.enroll_profile_a)
        } finally {
            rendering = false
        }
        val total = EnrollmentRules.PHRASE_COUNT
        val idle = state.busy == Busy.IDLE && state.loaded
        val hasTake = state.phrase in state.recorded

        profileA.isEnabled = state.busy == Busy.IDLE
        profileB.isEnabled = state.busy == Busy.IDLE
        counterView.text = getString(R.string.enroll_counter, state.recorded.size, total)
        phraseNumberView.text = getString(R.string.enroll_phrase_number, state.phrase, total)
        phraseView.text = phrases.getOrElse(state.phrase - 1) { "" }
        phraseStatusView.setText(
            when {
                !state.loaded -> R.string.enroll_status_loading
                hasTake -> R.string.enroll_status_recorded
                else -> R.string.enroll_status_missing
            }
        )
        phraseStatusView.setTextColor(getColor(if (hasTake && state.loaded) R.color.ok else R.color.accent))

        recordButton.setText(
            when {
                state.busy == Busy.RECORDING -> R.string.enroll_stop
                hasTake -> R.string.enroll_rerecord
                else -> R.string.enroll_record
            }
        )
        recordButton.isEnabled = idle || state.busy == Busy.RECORDING
        playButton.setText(if (state.busy == Busy.PLAYING) R.string.enroll_stop_listen else R.string.enroll_listen)
        playButton.isEnabled = (idle && hasTake) || state.busy == Busy.PLAYING
        previousButton.isEnabled = idle
        nextButton.isEnabled = idle
        deleteProfileButton.isEnabled = idle
        deleteAllButton.isEnabled = idle

        val bad = when (state.notice) {
            Notice.NONE, Notice.ACCEPTED, Notice.PROFILE_DELETED, Notice.ALL_DELETED -> false
            else -> true
        }
        noticeView.text = when (state.busy) {
            Busy.RECORDING -> getString(
                R.string.enroll_elapsed, state.elapsedMs / 1000, EnrollmentRules.MAX_TAKE_SECONDS,
            )
            Busy.SAVING -> getString(R.string.enroll_saving)
            Busy.DELETING -> getString(R.string.enroll_deleting)
            else -> noticeText(state.notice)?.let { getString(it) } ?: ""
        }
        val busyText = state.busy == Busy.RECORDING || state.busy == Busy.SAVING || state.busy == Busy.DELETING
        noticeView.setTextColor(
            getColor(
                when {
                    busyText -> R.color.accent
                    bad -> R.color.warning
                    else -> R.color.ok
                }
            )
        )
    }

    private fun noticeText(notice: Notice): Int? = when (notice) {
        Notice.NONE -> null
        Notice.ACCEPTED -> R.string.notice_accepted
        Notice.TOO_SHORT -> R.string.notice_too_short
        Notice.TOO_QUIET -> R.string.notice_too_quiet
        Notice.CLIPPED -> R.string.notice_clipped
        Notice.MIC_ERROR -> R.string.notice_mic_error
        Notice.SAVE_ERROR -> R.string.notice_save_error
        Notice.PLAY_ERROR -> R.string.notice_play_error
        Notice.KEY_LOST -> R.string.notice_key_lost
        Notice.PROFILE_DELETED -> R.string.notice_profile_deleted
        Notice.ALL_DELETED -> R.string.notice_all_deleted
        Notice.DELETE_ERROR -> R.string.notice_delete_error
        Notice.PROTECTION_RUNNING -> R.string.notice_protection_running
    }

    companion object {
        private const val KEY_PROFILE = "profile"
        private const val KEY_PHRASE = "phrase"
    }
}
