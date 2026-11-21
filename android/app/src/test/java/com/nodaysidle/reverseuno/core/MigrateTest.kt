package com.nodaysidle.reverseuno.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors the keyterm limit migration in tests/store.test.ts. */
class MigrateTest {
    private fun store(version: Int, keytermLimit: Int) =
        StoreData(version = version, settings = Settings(stt = SttSettings(keytermLimit = keytermLimit)))

    @Test fun movesAnOldStoreOffTheFiftyKeytermDefault() {
        val migrated = migrate(store(1, 50))
        assertEquals(15, migrated.settings.stt.keytermLimit)
        assertEquals(STORE_VERSION, migrated.version)
    }

    @Test fun keepsAChosenLimitAndLeavesACurrentStoreAlone() {
        assertEquals(30, migrate(store(1, 30)).settings.stt.keytermLimit)
        assertEquals(50, migrate(store(STORE_VERSION, 50)).settings.stt.keytermLimit)
    }

    @Test fun aV2StoreKeepsItsDeliberateFiftyWhenMovingToCurrent() {
        val migrated = migrate(store(2, 50))
        assertEquals(50, migrated.settings.stt.keytermLimit)
        assertEquals(STORE_VERSION, migrated.version)
    }

    @Test fun v3StoreWithOpenRouterSlugMigratesToNova3AndEnglish() {
        val old = StoreData(version = 3, settings = Settings(stt = SttSettings(model = "openai/gpt-4o-mini-transcribe", language = "auto")))
        val migrated = migrate(old)
        assertEquals("nova-3", migrated.settings.stt.model)
        assertEquals("en", migrated.settings.stt.language)
        assertEquals(STORE_VERSION, migrated.version)
    }
}
