package com.nodaysidle.reverseuno.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingParseTest {
    @Test
    fun deepgramParsesInterimAndFinal() {
        val interim = parseDeepgramMessage(
            """{"type":"Results","is_final":false,"channel":{"alternatives":[{"transcript":"hello wor"}]}}""",
        )
        assertTrue(interim is DeepgramEvent.Transcript)
        interim as DeepgramEvent.Transcript
        assertEquals("hello wor", interim.text)
        assertFalse(interim.isFinal)

        val final = parseDeepgramMessage(
            """{"type":"Results","is_final":true,"channel":{"detected_language":"en","alternatives":[{"transcript":"hello world"}]}}""",
        )
        final as DeepgramEvent.Transcript
        assertTrue(final.isFinal)
        assertEquals("en", final.language)
    }

    @Test
    fun deepgramIgnoresMetadataAndReportsErrors() {
        assertEquals(DeepgramEvent.Ignored, parseDeepgramMessage("""{"type":"Metadata","request_id":"1"}"""))
        assertEquals(DeepgramEvent.Closed, parseDeepgramMessage("""{"type":"CloseStream"}"""))
        val error = parseDeepgramMessage("""{"type":"Error","description":"bad audio"}""")
        assertEquals(DeepgramEvent.Error("bad audio"), error)
        assertEquals(DeepgramEvent.Ignored, parseDeepgramMessage("not json"))
    }

    @Test
    fun deepgramUrlCarriesStreamingContract() {
        val url = deepgramRequestUrl("nova-3", "en", listOf("reverseuno", "Kurek", "reverseuno")).toString()
        assertTrue(url.startsWith("wss://api.deepgram.com/v1/listen?"))
        assertTrue(url.contains("model=nova-3"))
        assertTrue(url.contains("encoding=linear16"))
        assertTrue(url.contains("sample_rate=16000"))
        assertTrue(url.contains("channels=1"))
        assertTrue(url.contains("interim_results=true"))
        assertTrue(url.contains("language=en"))
        assertFalse(url.contains("detect_language"))
        assertTrue(url.contains("keyterm=reverseuno"))
        assertTrue(url.contains("keyterm=Kurek"))
        assertEquals(1, Regex("keyterm=reverseuno").findAll(url).count())

        val auto = deepgramRequestUrl("nova-3", "auto", emptyList()).toString()
        assertTrue(auto.contains("detect_language=true"))
    }

    @Test
    fun xaiParsesLifecycleEvents() {
        assertEquals(XaiEvent.Ready, parseXaiMessage("""{"type":"transcript.created"}"""))
        val partial = parseXaiMessage("""{"type":"transcript.partial","text":"hel","is_final":false}""")
        assertEquals(XaiEvent.Partial("hel", false), partial)
        val done = parseXaiMessage("""{"type":"transcript.done","text":"hello","language":"en"}""")
        assertEquals(XaiEvent.Done("hello", "en"), done)
        assertEquals(XaiEvent.Error("boom"), parseXaiMessage("""{"type":"error","message":"boom"}"""))
        assertEquals(XaiEvent.Ignored, parseXaiMessage("""{"type":"unknown"}"""))
    }

    @Test
    fun xaiUrlCarriesStreamingContract() {
        val url = xaiRequestUrl("grok-voice-transcribe-2.0", "en", listOf("reverseuno")).toString()
        assertTrue(url.startsWith("wss://api.x.ai/v1/stt?"))
        assertTrue(url.contains("encoding=pcm"))
        assertTrue(url.contains("sample_rate=16000"))
        assertTrue(url.contains("format=true"))
        assertTrue(url.contains("interim_results=true"))
        assertTrue(url.contains("language=en"))
        assertTrue(url.contains("keyterm=reverseuno"))

        val auto = xaiRequestUrl("grok-voice-transcribe-2.0", "auto", emptyList()).toString()
        assertFalse(auto.contains("language="))
    }
}
