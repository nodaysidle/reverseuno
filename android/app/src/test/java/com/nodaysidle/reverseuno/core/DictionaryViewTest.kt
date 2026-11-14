package com.nodaysidle.reverseuno.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryViewTest {
    private fun word(w: String, source: DictionarySource = DictionarySource.MANUAL, starred: Boolean = false, uses: Int = 1, fix: String? = null) =
        DictionaryEntry(id = w, word = w, correction = fix, starred = starred, uses = uses, createdAt = 0, source = source)

    private val words = listOf(
        word("kotlin", uses = 5),
        word("nodaysidle", DictionarySource.AUTO, fix = "NoDaysIdle"),
        word("Typeless", starred = true),
        word("wispr", DictionarySource.AUTO, uses = 9),
    )

    @Test fun tabsSplitLearnedFromAdded() {
        assertEquals(listOf("Typeless", "wispr", "kotlin", "nodaysidle"), dictionaryView(words, DictionaryTab.ALL, "").map { it.word })
        assertEquals(listOf("wispr", "nodaysidle"), dictionaryView(words, DictionaryTab.AUTO, "").map { it.word })
        assertEquals(listOf("Typeless", "kotlin"), dictionaryView(words, DictionaryTab.MANUAL, "").map { it.word })
        assertEquals(listOf(4, 2, 2), DictionaryTab.entries.map { dictionaryTabCount(words, it) })
    }

    @Test fun searchMatchesWordOrCorrectionWithinTheTab() {
        assertEquals(listOf("nodaysidle"), dictionaryView(words, DictionaryTab.ALL, "  NoDays ").map { it.word })
        assertEquals(emptyList<String>(), dictionaryView(words, DictionaryTab.MANUAL, "nodays").map { it.word })
    }

    @Test fun wordsSavedBeforeSourcesExistedAreManual() {
        val json = """{"id":"x","word":"fastfetch","starred":false,"uses":3,"createdAt":1}"""
        val entry = Json { ignoreUnknownKeys = true }.decodeFromString(DictionaryEntry.serializer(), json)
        assertEquals(DictionarySource.MANUAL, entry.source)
        assertEquals("auto", Json.encodeToString(DictionarySource.serializer(), DictionarySource.AUTO).trim('"'))
    }
}
