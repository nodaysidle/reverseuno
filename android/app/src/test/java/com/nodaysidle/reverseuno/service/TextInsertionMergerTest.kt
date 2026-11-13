package com.nodaysidle.reverseuno.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInsertionMergerTest {
    @Test
    fun insertsIntoEmptyField() {
        val plan = TextInsertionMerger.merge(existing = "", cursor = 0, inserted = "Hello")

        assertEquals("Hello", plan.mergedText)
        assertEquals(5, plan.cursorAfterInsert)
    }

    @Test
    fun insertsAtCursorWithWordSpacing() {
        val plan = TextInsertionMerger.merge(
            existing = "Ship today",
            cursor = 4,
            inserted = "this"
        )

        assertEquals("Ship this today", plan.mergedText)
        assertEquals(9, plan.cursorAfterInsert)
    }

    @Test
    fun preservesExistingSpacesAroundCursor() {
        val plan = TextInsertionMerger.merge(
            existing = "Ship  today",
            cursor = 5,
            inserted = "this"
        )

        assertEquals("Ship this today", plan.mergedText)
        assertEquals(9, plan.cursorAfterInsert)
    }

    @Test
    fun preservesNewlineBoundaries() {
        val plan = TextInsertionMerger.merge(
            existing = "First\nSecond",
            cursor = 6,
            inserted = "Inserted"
        )

        assertEquals("First\nInserted Second", plan.mergedText)
        assertEquals(14, plan.cursorAfterInsert)
    }

    @Test
    fun clampsOutOfRangeCursorToEnd() {
        val plan = TextInsertionMerger.merge(
            existing = "Hello",
            cursor = 200,
            inserted = "world"
        )

        assertEquals("Hello world", plan.mergedText)
        assertEquals(11, plan.cursorAfterInsert)
    }

    @Test
    fun neverDoublesTheTrailingSpaceTheDictationAlreadyHas() {
        // Seen on device: "…Android. " inserted before "Before" became "…Android.  Before".
        val plan = TextInsertionMerger.merge(existing = "Before ", cursor = 0, inserted = "Hello there. ")

        assertEquals("Hello there. Before ", plan.mergedText)
        assertEquals(13, plan.cursorAfterInsert)
    }

    @Test
    fun noSpaceBeforePunctuationOnEitherSide() {
        assertEquals(SpacedInsertion(", right", 7), spacedInsertion("Okay", "", ", right"))
        assertEquals(SpacedInsertion(" done", 5), spacedInsertion("We are", ".", "done"))
    }

    @Test
    fun commitSpacingComesFromTheSurroundingTextOnly() {
        assertEquals(SpacedInsertion(" this ", 5), spacedInsertion("Ship", "today", "this"))
        assertEquals(SpacedInsertion("this", 4), spacedInsertion("", "", "this"))
        assertEquals(SpacedInsertion("this", 4), spacedInsertion("Ship ", " today", "this"))
    }
}

class CommitTextPolicyTest {
    private val text = android.text.InputType.TYPE_CLASS_TEXT
    private val password = text or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD

    @Test
    fun commitsOnAndroid13WhenTheConnectionIsOnTheTargetField() {
        assertEquals(true, shouldCommitText(33, true, "com.android.chrome", "com.android.chrome", text, 2_000))
        assertEquals(true, shouldCommitText(36, true, "com.app", "", text, 2_000))
    }

    @Test
    fun keepsTheExistingPathOnAndroid12AndBelow() {
        assertEquals(false, shouldCommitText(31, true, "com.app", "com.app", text, 2_000))
        assertEquals(false, shouldCommitText(32, true, "com.app", "com.app", text, 2_000))
    }

    @Test
    fun skipsAStaleOrForeignOrUnsafeConnection() {
        assertEquals("no input started", false, shouldCommitText(33, false, "com.app", "com.app", text, 2_000))
        assertEquals("other app's field", false, shouldCommitText(33, true, "com.other", "com.app", text, 2_000))
        assertEquals("password", false, shouldCommitText(33, true, "com.app", "com.app", password, 2_000))
        assertEquals("just restarted", false, shouldCommitText(33, true, "com.app", "com.app", text, INPUT_RESTART_GUARD_MS - 1))
    }

    @Test
    fun readsBackWhetherTheCommitLanded() {
        assertEquals(true, commitLanded("Ship", "Ship this ", " this "))
        assertEquals("editor trimmed our trailing space", true, commitLanded("Ship", "Ship this", " this "))
        assertEquals(false, commitLanded("Ship", "Ship", " this "))
        assertEquals("field already ended in the same words", false, commitLanded("Ship this", "Ship this", " this"))
        assertEquals("field reports nothing: trust the commit", null, commitLanded(null, null, "this"))
        val long = "x".repeat(200)
        assertEquals("only the readable tail is compared", true, commitLanded("", long.takeLast(SURROUNDING_CHARS), long))
    }
}
