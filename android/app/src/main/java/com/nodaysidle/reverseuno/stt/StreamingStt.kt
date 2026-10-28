package com.nodaysidle.reverseuno.stt

import kotlinx.coroutines.flow.StateFlow

/** Live speech-to-text over a WebSocket; implementers buffer frames until their socket is ready. */
interface StreamingStt {
    /** In-flight text for the overlay; empty once a segment is finalized. */
    val interim: StateFlow<String>

    /** Queue one raw PCM16 mono 16 kHz frame. */
    fun send(pcm: ByteArray)

    /** Flush and return the transcript, or null when nothing was heard. Throws on stream failure. */
    suspend fun finish(): SttResult?

    fun cancel()
}
