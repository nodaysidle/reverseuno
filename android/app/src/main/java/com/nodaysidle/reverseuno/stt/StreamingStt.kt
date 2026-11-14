package com.nodaysidle.reverseuno.stt

import kotlinx.coroutines.flow.StateFlow

/** Live speech-to-text over a WebSocket; implementers buffer frames until their socket is ready. */
interface StreamingStt {
    /** In-flight text for the overlay; empty once a segment is finalized. */
    val interim: StateFlow<String>

    /**
     * True when the stream missed audio: frames were dropped, the socket failed or closed
     * early, or the final result never arrived. The streamed text is then incomplete, so the
     * caller should transcribe the saved WAV instead.
     */
    val droppedAudio: Boolean

    /** Queue one raw PCM16 mono 16 kHz frame. */
    fun send(pcm: ByteArray)

    /** Flush and return the transcript, or null when nothing was heard. Throws on stream failure. */
    suspend fun finish(): SttResult?

    fun cancel()
}

/**
 * Frames held while the socket connects. Sized for the full 5-minute utterance at 250 ms per
 * frame (about 10 MB worst case), so a slow handshake never costs the start of the dictation.
 */
internal const val MAX_PENDING_FRAMES = 1_300
