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
}
