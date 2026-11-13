package com.nodaysidle.reverseuno.stt

import com.nodaysidle.reverseuno.core.PROVIDER_OPENROUTER
import com.nodaysidle.reverseuno.core.SttSettings
import com.nodaysidle.reverseuno.core.isModelSlug
import com.nodaysidle.reverseuno.core.usesStreaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class OpenRouterSttBodyTest {
    private val audio = byteArrayOf(82, 73, 70, 70, 0, 1, 2, 3)

    @Test fun sendsExactlyModelAndBase64AudioWhenNothingElseApplies() {
        val body = openRouterSttBody("openai/gpt-4o-mini-transcribe", audio, "WAV", "auto", emptyList())
        assertEquals(setOf("model", "input_audio"), body.keys().asSequence().toSet())
        assertEquals("openai/gpt-4o-mini-transcribe", body.getString("model"))
        val input = body.getJSONObject("input_audio")
        assertEquals(setOf("data", "format"), input.keys().asSequence().toSet())
        assertEquals("wav", input.getString("format"))
        val data = input.getString("data")
        assertFalse("base64 must not be line-wrapped", data.contains('\n'))
        assertTrue(Base64.getDecoder().decode(data).contentEquals(audio))
    }

    @Test fun addsLanguageOnlyAsATwoLetterCodeAndKeytermsAsPrompt() {
        val body = openRouterSttBody("m/x", audio, "wav", "SL", listOf("reverseuno", "Typeless"))
        assertEquals("sl", body.getString("language"))
        assertTrue(body.getString("prompt").contains("reverseuno"))
        assertFalse(openRouterSttBody("m/x", audio, "wav", "slovenian", emptyList()).has("language"))
    }

    @Test fun refusesAFormatOpenRouterCannotTake() {
        assertThrows(IllegalArgumentException::class.java) { openRouterSttBody("m/x", audio, "pcm", "auto", emptyList()) }
    }

    @Test fun openRouterNeverStreamsAndIsTheNewDefault() {
        assertEquals(PROVIDER_OPENROUTER, SttSettings().provider)
        assertFalse(SttSettings().usesStreaming())
        assertFalse(SttSettings(provider = PROVIDER_OPENROUTER, streaming = true).usesStreaming())
        assertTrue(SttSettings(provider = "deepgram", streaming = true).usesStreaming())
        assertFalse(SttSettings(provider = "xai", streaming = false).usesStreaming())
    }

    @Test fun acceptsOpenRouterModelSlugsOnly() {
        assertTrue(isModelSlug("openai/gpt-4o-mini-transcribe"))
        assertTrue(isModelSlug("mistralai/voxtral-mini-transcribe:free"))
        assertFalse(isModelSlug("gpt-4o-mini-transcribe"))
        assertFalse(isModelSlug("openai/"))
        assertFalse(isModelSlug("open ai/whisper"))
    }
}
