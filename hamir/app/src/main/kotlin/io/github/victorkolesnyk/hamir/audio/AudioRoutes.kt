package io.github.victorkolesnyk.hamir.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.github.victorkolesnyk.hamir.logic.OutputRoute

/** Maps Android output devices to the coarse route stored with the calibration. */
object AudioRoutes {
    /**
     * The TYPE_* values newer than API 26 (hearing aid 28, speaker-safe 30, BLE 31, BLE broadcast 33)
     * are compile-time int constants: comparing against them is safe on every API level.
     */
    fun classify(deviceType: Int): OutputRoute = when (deviceType) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputRoute.WIRED

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputRoute.BLUETOOTH

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputRoute.SPEAKER

        else -> OutputRoute.OTHER
    }

    /**
     * Current media route. [routedDeviceType] comes from AudioTrack.getRoutedDevice() while playing and is
     * authoritative; without it the connected outputs are inspected (Bluetooth, then wired, then speaker).
     * Returns null when nothing can be determined.
     */
    fun current(audioManager: AudioManager, routedDeviceType: Int?): OutputRoute? {
        if (routedDeviceType != null) return classify(routedDeviceType)
        val routes = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { classify(it.type) }
        } catch (e: RuntimeException) {
            return null
        }
        return when {
            OutputRoute.BLUETOOTH in routes -> OutputRoute.BLUETOOTH
            OutputRoute.WIRED in routes -> OutputRoute.WIRED
            OutputRoute.SPEAKER in routes -> OutputRoute.SPEAKER
            else -> null
        }
    }
}
