package com.nodaysidle.reverseuno.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors tests/text.test.ts so the Kotlin port and the desktop stay in step. */
class TextTest {
    private fun entry(word: String, correction: String? = null, starred: Boolean = false, uses: Int = 1) =
        DictionaryEntry(word, word, correction, starred, uses, 0)

    private fun snippet(trigger: String, content: String) = Snippet(trigger, trigger, content, 0, 0)

    @Test fun replacesWholeWordsCaseInsensitively() = assertEquals(
        "I use nodaysidle and nodaysidle every day.",
        applyDictionary("I use NodaysIdle and nodaysidle every day.", listOf(entry("nodaysidle", "nodaysidle"), entry("Deepgram", "Deepgram"))),
    )

    @Test fun doesNotReplaceInsideLargerWords() =
        assertEquals("nodaysidleness", applyDictionary("nodaysidleness", listOf(entry("nodaysidle", "X"))))

    @Test fun appliesLongestPhraseFirst() = assertEquals(
        "the architect is here",
        applyDictionary("the nodaysidle architect is here", listOf(entry("nodaysidle", "NoDaysIdle"), entry("nodaysidle architect", "architect"))),
    )

    @Test fun enforcesExactSpelling() = assertEquals(
        "fastfetch shows Hyprland on Node.js.",
        applyDictionary("Fastfetch shows HYPRLAND on node.js.", listOf(entry("fastfetch"), entry("Hyprland"), entry("Node.js"))),
    )

    @Test fun mapsSpokenFormsBeforeSpelling() = assertEquals(
        "nodaysidle ships. nodaysidle too.",
        applyDictionary("No days idle ships. Nodaysidle too.", listOf(entry("nodaysidle"), entry("no days idle", "nodaysidle"))),
    )

    @Test fun expandsSnippets() {
        val snippets = listOf(snippet("my calendar link", "https://cal.com/nodaysidle"), snippet("sign off", "Thanks!"))
        assertEquals("https://cal.com/nodaysidle", expandSnippet("My calendar link.", snippets).first)
        assertEquals("Thanks!", expandSnippet("SIGN OFF!", snippets).first)
        assertEquals("https://cal.com/nodaysidle", expandSnippet("insert my calendar link", snippets).first)
        val miss = expandSnippet("my calendar link is broken", snippets)
        assertEquals("my calendar link is broken", miss.first)
        assertNull(miss.second)
    }

    @Test fun sanitizes() {
        assertEquals("", sanitizeTranscript("Thank you for watching."))
        assertEquals("", sanitizeTranscript("Please like and subscribe"))
        assertEquals("", sanitizeTranscript("Hvala za ogled"))
        assertEquals("hello there", sanitizeTranscript("  hello​   there  "))
        assertEquals("and then", sanitizeTranscript("and then ".repeat(8)))
        assertEquals("Let us meet at six.", sanitizeTranscript("Let us meet at six."))
    }

    @Test fun diffsTokens() {
        assertEquals(listOf(TokenChange("nodaysidle", "NoDaysIdle")), diffTokens("send it to nodaysidle today", "send it to NoDaysIdle today"))
        assertTrue(TokenChange("key", "shortcut") in diffTokens("meet with arch about the key", "meet with Arch about the shortcut"))
    }

    @Test fun learnability() {
        assertTrue(isLearnable("nodaysidle", "NoDaysIdle"))
        assertTrue(isLearnable("kubernetis", "Kubernetes"))
        assertFalse(isLearnable("and", "the"))
        assertFalse(isLearnable("one", "1"))
        assertFalse(isLearnable("nodaysidle", "nodaysidle"))
    }

    @Test fun countsWords() {
        assertEquals(3, wordCount("  one two   three "))
        assertEquals(0, wordCount(""))
    }

    @Test fun preferredSpellingsMergeAndRank() {
        assertEquals(listOf("nodaysidle"), preferredSpellings(listOf(entry("nodaysidle"), entry("no days idle", "nodaysidle"), entry("nodays idle", "nodaysidle")), 10))
        val ranked = listOf(entry("htop"), entry("Koper"), entry("Ljubljana", starred = true), entry("no days idle", "nodaysidle"), entry("nodays idle", "nodaysidle"))
        assertEquals(listOf("Ljubljana", "nodaysidle", "htop"), preferredSpellings(ranked, 3))
        val top = preferredSpellings(DEVELOPER_VOCABULARY.map { entry(it.word, it.correction, uses = 0) }, 50)
        assertTrue("nodaysidle" in top)
        assertFalse("no days idle" in top)
    }
}
