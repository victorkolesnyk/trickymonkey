package io.github.victorkolesnyk.hamir

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** Shared plumbing of the screens: up navigation, one tracked dialog, the microphone permission box. */
abstract class BaseActivity : Activity() {
    private var dialog: AlertDialog? = null
    /** A request was answered with "denied" at least once: offer the settings screen as a second way. */
    private var micDenied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hardware volume keys always change the media volume, the one the noise is played with.
        volumeControlStream = AudioManager.STREAM_MUSIC
        micDenied = savedInstanceState?.getBoolean(KEY_MIC_DENIED) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_MIC_DENIED, micDenied)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    protected fun enableUpNavigation() {
        actionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** Shows a dialog and remembers it, so it is dismissed with the activity instead of leaking its window. */
    protected fun showTracked(builder: AlertDialog.Builder) {
        dialog?.dismiss()
        dialog = builder.show()
    }

    protected fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- microphone permission

    protected fun hasMicPermission(): Boolean = hasPermission(Manifest.permission.RECORD_AUDIO)

    /**
     * Keeps the explanation box (layout/permission_box) in sync with the permission: hidden when granted,
     * otherwise it explains why the microphone is needed and offers the request. "Grant" always stays the
     * primary action: a dismissed system dialog also reports "denied" and cannot be told from a final
     * refusal. After any denial the app settings are offered as a second way.
     */
    protected fun updateMicPermissionBox() {
        val box = findViewById<View>(R.id.permission_box) ?: return
        if (hasMicPermission()) {
            micDenied = false
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        findViewById<Button>(R.id.permission_button).setOnClickListener { requestMicPermission() }
        val settingsVisibility = if (micDenied) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.permission_settings_hint).visibility = settingsVisibility
        val settingsButton = findViewById<Button>(R.id.permission_settings_button)
        settingsButton.visibility = settingsVisibility
        settingsButton.setOnClickListener { openAppSettings() }
    }

    /** Returns true when recording may start; otherwise asks for the permission (the box explains why). */
    protected fun ensureMicPermission(): Boolean {
        if (hasMicPermission()) return true
        requestMicPermission()
        // The box sits at the top of the scrolling content: bring it into view.
        (findViewById<View>(R.id.permission_box)?.parent?.parent as? ScrollView)?.smoothScrollTo(0, 0)
        return false
    }

    private fun requestMicPermission() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        // An empty result means the request was interrupted, not answered.
        if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) micDenied = true
        updateMicPermissionBox()
    }

    protected fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No settings screen on this device: nothing more we can do.
        }
    }

    companion object {
        const val REQUEST_MIC = 1
        const val REQUEST_NOTIFICATIONS = 2
        private const val KEY_MIC_DENIED = "mic_denied"

        val UKRAINIAN: Locale = Locale.forLanguageTag("uk-UA")

        /** One decimal, Ukrainian decimal comma, typographic minus: "−5,0". */
        fun formatDb(value: Double, signed: Boolean = false): String {
            val text = String.format(UKRAINIAN, if (signed) "%+.1f" else "%.1f", value)
            return text.replace('-', '−')
        }

        /** Whole decibels with a typographic minus: "−5", "0". */
        fun formatDb(value: Int): String = value.toString().replace('-', '−')
    }
}
