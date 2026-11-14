package com.nodaysidle.reverseuno.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** An unreadable store.json is kept aside, never overwritten, and blocks the recording sweep. */
class StoreLoadTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aMissingFileIsAFirstRunAndMovesNothing() {
        val dir = tmp.root
        val (data, kept) = Store.load(File(dir, "store.json"))
        assertEquals(StoreData(createdAt = 0), data.copy(createdAt = 0))
        assertNull(kept)
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun aCorruptFileIsKeptAsideWithItsContent() {
        val file = File(tmp.root, "store.json").apply { writeText("{ not json") }
        val (data, kept) = Store.load(file, now = 42)
        assertEquals(StoreData(createdAt = 0), data.copy(createdAt = 0))
        assertEquals("store.json.corrupt-42", kept!!.name)
        assertTrue(kept.exists())
        assertEquals("{ not json", kept.readText())
        assertFalse(file.exists())
    }

    @Test fun aValidFileIsReadAndLeftInPlace() {
        val value = StoreData(version = STORE_VERSION, settings = Settings(stt = SttSettings(keytermLimit = 30)))
        val file = File(tmp.root, "store.json")
        file.writeText(Json { encodeDefaults = true }.encodeToString(StoreData.serializer(), value))
        val (data, kept) = Store.load(file)
        assertEquals(value, data)
        assertNull(kept)
        assertTrue(file.exists())
    }

    @Test fun onlyAKeptCorruptStoreBlocksTheSweep() {
        assertFalse(Store.hasCorruptStore(listOf("store.json", "recordings")))
        assertTrue(Store.hasCorruptStore(listOf("store.json", "store.json.corrupt-42")))
    }
}
