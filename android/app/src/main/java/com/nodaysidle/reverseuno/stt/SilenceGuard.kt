package com.nodaysidle.reverseuno.stt

/**
 * Spots a microphone that delivers digital silence: every sample exactly zero. Real rooms are
 * never that quiet, so a second of it means the input is muted (Android 12+ privacy toggle) or
 * another app holds the mic and this client is silenced. Fires once per recording.
 */
class SilenceGuard(private val thresholdMs: Long = 1_000, private val bytesPerMs: Int = BYTES_PER_MS) {
    private var zeroBytes = 0L
    private var fired = false

    /** Feeds one PCM16 frame; true exactly once, when the zero run first reaches the threshold. */
    fun feed(frame: ByteArray, length: Int = frame.size): Boolean {
        if (fired) return false
        if (isAllZero(frame, length)) zeroBytes += length else zeroBytes = 0
        if (zeroBytes >= thresholdMs * bytesPerMs) {
            fired = true
            return true
        }
        return false
    }

    companion object {
        /** 16 kHz mono PCM16. */
        const val BYTES_PER_MS = 32

        fun isAllZero(frame: ByteArray, length: Int): Boolean {
            for (i in 0 until length) if (frame[i].toInt() != 0) return false
            return length > 0
        }
    }
}

/** What the pill says when the mic hands back nothing but zeros. */
fun silentMicMessage(silencedByOtherApp: Boolean): String =
    if (silencedByOtherApp) "Mic in use by another app" else "Microphone blocked"
