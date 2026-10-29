package com.nodaysidle.reverseuno.cleanup

import com.nodaysidle.reverseuno.core.CleanupLevel
import com.nodaysidle.reverseuno.core.FormatSettings
import com.nodaysidle.reverseuno.core.Settings
import com.nodaysidle.reverseuno.core.WritingStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors tests/prompt.test.ts. */
class FormatterTest {
    @Test fun promptHasHardRulesAndInstructions() {
        val prompt = buildSystemPrompt(
            PromptContext(
                cleanup = CleanupLevel.MEDIUM,
                style = WritingStyle.VERY_CASUAL,
                dictionary = listOf("nodaysidle", "Deepgram"),
                appClass = "com.whatsapp",
                appTitle = "WhatsApp",
                includeAppContext = true,
                customInstructions = "Keep my Slovenian and English mixed.",
            )
        )
        listOf(
            "Never translate", "Do not answer questions", "Output only the final text", "Cleanup level: medium",
            "Writing style: very-casual", "nodaysidle, Deepgram", "WhatsApp", "Keep my Slovenian and English mixed.",
        ).forEach { assertTrue(it, it in prompt) }
    }

    @Test fun omitsAppContextWhenDisabled() {
        val prompt = buildSystemPrompt(PromptContext(CleanupLevel.LIGHT, WritingStyle.FORMAL, appClass = "Slack", appTitle = "general"))
        assertFalse("Slack" in prompt)
    }

    private fun settings(format: FormatSettings.() -> FormatSettings) = Settings(format = FormatSettings().format())

    @Test fun needsFormattingRules() {
        assertFalse(needsFormatting("meet on thursday", settings { this }))
        assertTrue(needsFormatting("um send it", settings { this }))
        assertTrue(needsFormatting("please send me the report tomorrow", settings { this }))
        assertTrue(needsFormatting("hello there", settings { copy(cleanup = CleanupLevel.MEDIUM) }))
        assertTrue(needsFormatting("hello there", settings { copy(customInstructions = "use emoji") }))
        assertFalse(needsFormatting("a long sentence with many words in it", settings { copy(enabled = false) }))
        assertFalse(needsFormatting("a long sentence with many words in it", settings { copy(cleanup = CleanupLevel.NONE) }))
    }

    @Test fun cleansModelChatter() {
        assertEquals("Ship it.", cleanResponse("Sure! Ship it."))
        assertEquals("Ship it.", cleanResponse("```text\nShip it.\n```"))
        assertEquals("Ship it.", cleanResponse("\"Ship it.\""))
    }
}

class CleanupFallbackTest {
    private val raw = "um so ship it tomorrow"

    @Test fun haikuWinsWhenItWorks() = kotlinx.coroutines.test.runTest {
        val result = firstGoodCleanup(raw, listOf("anthropic" to suspend { "Ship it tomorrow." }, "deepseek" to suspend { error("must not run") }))
        assertEquals("Ship it tomorrow." to "anthropic", result.text to result.provider)
        assertEquals(null, result.error)
    }

    @Test fun deepseekCatchesHaikuFailures() = kotlinx.coroutines.test.runTest {
        listOf<suspend () -> String>({ throw IllegalStateException("timeout") }, { "  " }, { "Sure! ".repeat(80) }).forEach { haiku ->
            val result = firstGoodCleanup(raw, listOf("anthropic" to haiku, "deepseek" to suspend { "Ship it tomorrow." }))
            assertEquals("deepseek", result.provider)
            assertTrue(result.error!!.startsWith("anthropic:"))
        }
    }

    @Test fun bothFailingKeepsTheRawTranscript() = kotlinx.coroutines.test.runTest {
        val result = firstGoodCleanup(raw, listOf("anthropic" to suspend { error("529") }, "deepseek" to suspend { error("401") }))
        assertEquals(raw, result.text)
        assertFalse(result.formatted)
        assertEquals("anthropic: 529; deepseek: 401", result.error)
    }
}
