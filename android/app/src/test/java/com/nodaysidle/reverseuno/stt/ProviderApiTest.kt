package com.nodaysidle.reverseuno.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderApiTest {
    @Test
    fun parsesOpenRouterModelIdsAndNames() {
        val json = """{"data":[{"id":"deepgram/nova-3","name":"Deepgram: Nova-3"},{"id":"openai/whisper-1","name":""}]}"""

        assertEquals(
            listOf(SttModel("deepgram/nova-3", "Deepgram: Nova-3"), SttModel("openai/whisper-1", "openai/whisper-1")),
            ProviderApi.parseModels(json),
        )
    }
}
