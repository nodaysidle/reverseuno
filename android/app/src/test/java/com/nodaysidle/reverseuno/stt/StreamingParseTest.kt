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
        assertTrue(auto.contains("language=multi"))
        assertFalse(auto.contains("detect_language"))
    }

    @Test
    fun deepgramRestUrlAndResponseParsing() {
        val url = deepgramRestUrl("nova-3", "sl", listOf("reverseuno", "reverseuno"))
        assertTrue(url.startsWith("https://api.deepgram.com/v1/listen?"))
        assertTrue(url.contains("model=nova-3"))
        assertTrue(url.contains("language=sl"))
        assertTrue(url.contains("smart_format=true"))
        assertEquals(1, Regex("keyterm=reverseuno").findAll(url).count())

        val auto = deepgramRestUrl("nova-3", "auto", emptyList())
        assertTrue(auto.contains("language=multi"))

        val parsed = parseDeepgramRestResponse(
            """{"results":{"channels":[{"detected_language":"en","alternatives":[{"transcript":" hello world "}]}]}}""",
            "auto",
        )
        assertEquals(SttResult("hello world", "en"), parsed)
    }
}
