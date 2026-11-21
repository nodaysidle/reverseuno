package com.nodaysidle.reverseuno.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * What a dictation holds while it runs:
 * - exclusive transient audio focus while recording, so music pauses and nothing plays into the mic;
 *   losing it (a call, another recorder, an alarm) ends the recording through [onFocusLost];
 * - a partial wake lock + low-latency Wi-Fi lock from the first word until the transcript is in,
 *   so MIUI does not throttle or sleep the radio mid-dictation.
 */
class RecordingGuards(private val context: Context, private val onFocusLost: (Int) -> Unit) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val wakeLock: PowerManager.WakeLock =
        context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reverseuno:dictation")
            .apply { setReferenceCounted(false) }
    private val wifiLock: WifiManager.WifiLock? =
        runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "reverseuno:dictation-wifi")
                ?.apply { setReferenceCounted(false) }
        }.getOrNull()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { change ->
            Log.i(TAG, "Audio focus change $change")
            if (holdingFocus && endsRecording(change)) onFocusLost(change)
        }
        .build()

    @Volatile private var holdingFocus = false

    /** Recording starts: take focus, wake lock, and low-latency Wi-Fi lock. Recording goes ahead even if focus is denied. */
    fun beginRecording(maxMillis: Long) {
        runCatching { wakeLock.acquire(maxMillis + PROCESSING_MARGIN_MS) }
            .onFailure { Log.w(TAG, "Wake lock unavailable: ${it.message}") }
        runCatching { wifiLock?.acquire() }
            .onFailure { Log.w(TAG, "Wi-Fi lock unavailable: ${it.message}") }
        val result = runCatching { audioManager.requestAudioFocus(focusRequest) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        holdingFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!holdingFocus) Log.w(TAG, "Audio focus not granted ($result); recording anyway")
    }

    /** Recording stopped: give focus back so paused audio resumes. Wake + Wi-Fi locks stay for transcription. */
    fun endRecording() {
        if (!holdingFocus) return
        holdingFocus = false
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    /** The dictation is over (transcribed, failed, cancelled, or saved for later). */
    fun releaseAll() {
        endRecording()
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
    }

    companion object {
        private const val TAG = "RecordingGuards"
        /** Time to upload and transcribe after the longest recording. */
        const val PROCESSING_MARGIN_MS = 90_000L

        /** A call, an alarm, or another app that records: the user is no longer dictating. Ducking is fine. */
        fun endsRecording(focusChange: Int): Boolean =
            focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    }
}
