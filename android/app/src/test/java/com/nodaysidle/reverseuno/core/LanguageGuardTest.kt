package com.nodaysidle.reverseuno.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageGuardTest {
    private val enSl = listOf("en", "sl")

    @Test fun rejectsSerbianCyrillicEvenWhenTheProviderSaysEnglish() {
        assertTrue(violatesAllowedLanguages("Тест номер један.", "en", enSl))
    }

    @Test fun acceptsEnglishAndSlovenianLatinText() {
        assertFalse(violatesAllowedLanguages("Test number one.", "en", enSl))
        assertFalse(violatesAllowedLanguages("Čez šest žab že leze.", "sl", enSl))
    }

    @Test fun rejectsAReportedLanguageOutsideTheSet() {
        assertTrue(violatesAllowedLanguages("Test broj jedan.", "sr", enSl))
    }

    @Test fun readsRegionCodesAndIgnoresNonCodeReports() {
        assertFalse(violatesAllowedLanguages("Test number one.", "en-US", enSl))
        listOf("auto", "multi", "english", "").forEach {
            assertFalse(it, violatesAllowedLanguages("Test number one.", it, enSl))
        }
    }

    @Test fun anEmptySetTurnsTheCheckOff() {
        assertFalse(violatesAllowedLanguages("Тест номер један.", "sr", emptyList()))
    }

    @Test fun aFewForeignLettersAreUnderTheThreshold() {
        assertFalse(violatesAllowedLanguages("The value of π is 3.14", "en", listOf("en")))
    }

    @Test fun textWithoutLettersPasses() {
        assertFalse(violatesAllowedLanguages("12345 ...", "auto", enSl))
    }

    @Test fun japaneseScriptsCountOnlyWhenJapaneseIsAllowed() {
        assertFalse(violatesAllowedLanguages("こんにちは", "auto", listOf("ja")))
        assertTrue(violatesAllowedLanguages("こんにちは", "auto", listOf("en")))
    }

    @Test fun strictRetryForcesTheSettingOrTheFirstAllowedLanguage() {
        assertEquals("en", strictRetryLanguage("en", enSl))
        assertEquals("sl", strictRetryLanguage("auto", listOf("sl", "en")))
        assertEquals("en", strictRetryLanguage("de", enSl))
    }
}
