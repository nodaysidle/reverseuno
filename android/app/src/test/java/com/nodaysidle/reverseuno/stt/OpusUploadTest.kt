package com.nodaysidle.reverseuno.stt

import com.nodaysidle.reverseuno.core.SttSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusUploadTest {
    @Test fun timestampsFollowThe16kHzSampleClock() {
        assertEquals(0L, pcmPresentationTimeUs(0))
        assertEquals(250_000L, pcmPresentationTimeUs(4_000)) // one 250 ms frame
        assertEquals(300_000_000L, pcmPresentationTimeUs(16_000L * 300)) // the 5-minute cap
    }

    @Test fun resendsAsWavOnlyWhenTheRequestShapeWasRejected() {
        listOf(400, 415, 422).forEach { assertTrue("$it", shouldResendAsWav(it)) }
        listOf(200, 401, 402, 403, 408, 429, 500, 502).forEach { assertFalse("$it", shouldResendAsWav(it)) }
    }

    @Test fun oggIsAnAcceptedUploadFormat() {
        val body = openRouterSttBody("openai/gpt-4o-mini-transcribe", byteArrayOf(79, 103, 103, 83), "ogg", "auto", emptyList())
        assertEquals("ogg", body.getJSONObject("input_audio").getString("format"))
    }

    @Test fun compressionIsOnByDefaultAndForStoresSavedBeforeIt() {
        assertTrue(SttSettings().compressUploads)
        val saved = """{"model":"openai/gpt-4o-mini-transcribe","provider":"openrouter","streaming":false}"""
        assertTrue(Json { ignoreUnknownKeys = true }.decodeFromString(SttSettings.serializer(), saved).compressUploads)
    }
}
