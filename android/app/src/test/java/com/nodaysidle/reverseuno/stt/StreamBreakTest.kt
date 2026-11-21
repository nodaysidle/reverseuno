package com.nodaysidle.reverseuno.stt

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class FakeSocket(var accept: Boolean = true, val onText: (String) -> Unit = {}) : WebSocket {
    override fun request(): Request = Request.Builder().url("https://example.invalid/").build()
    override fun queueSize(): Long = 0
    override fun send(text: String): Boolean { onText(text); return accept }
    override fun send(bytes: ByteString): Boolean = accept
    override fun close(code: Int, reason: String?): Boolean = true
    override fun cancel() {}
}

private const val FINAL_HELLO_WORLD =
    """{"type":"Results","is_final":true,"channel":{"alternatives":[{"transcript":"hello world"}]}}"""

class StreamBreakTest {
    private lateinit var listener: WebSocketListener

    private fun deepgram(fake: FakeSocket) =
        DeepgramStreamingClient("test-key", "en", emptyList(), connect = { _, l -> listener = l; fake })

    @Test
    fun deepgramNormalStopReturnsImmediatelyOnFromFinalize() = runBlocking {
        lateinit var fake: FakeSocket
        fake = FakeSocket(onText = {
            if ("Finalize" in it) listener.onMessage(
                fake,
                """{"type":"Results","is_final":true,"from_finalize":true,"channel":{"alternatives":[{"transcript":"hello world"}]}}""",
            )
        })
        val client = deepgram(fake)
        assertEquals("hello world", client.finish()?.text)
        assertFalse(client.droppedAudio)
    }

    @Test
    fun deepgramFailureAfterTextFlagsDroppedAudio() = runBlocking {
        val fake = FakeSocket()
        val client = deepgram(fake)
        listener.onMessage(fake, FINAL_HELLO_WORLD)
        listener.onFailure(fake, IOException("reset"), null)
        assertEquals("hello world", client.finish()?.text)
        assertTrue(client.droppedAudio)
    }

    @Test
    fun deepgramServerCloseBeforeStopFlagsDroppedAudio() = runBlocking {
        val fake = FakeSocket()
        val client = deepgram(fake)
        listener.onMessage(fake, FINAL_HELLO_WORLD)
        listener.onClosed(fake, 1011, "")
        assertTrue(client.droppedAudio)
        assertEquals("hello world", client.finish()?.text)
        assertTrue(client.droppedAudio)
    }

    @Test
    fun deepgramRefusedFrameFlagsDroppedAudio() {
        val fake = FakeSocket()
        val client = deepgram(fake)
        assertFalse(client.droppedAudio)
        fake.accept = false
        client.send(ByteArray(8))
        assertTrue(client.droppedAudio)
    }
}
