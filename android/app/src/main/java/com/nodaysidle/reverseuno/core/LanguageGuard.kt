package com.nodaysidle.reverseuno.core

import java.lang.Character.UnicodeScript

/** What a rejected transcript throws; the pill shows it with "tap to retry". */
const val WRONG_LANGUAGE = "Wrong language detected"

private val LANGUAGE_CODE = Regex("^[a-z]{2}(-[A-Za-z]{2,4})?$", RegexOption.IGNORE_CASE)

/** Scripts a selectable language is written in. Everything not listed is Latin. */
internal fun scriptsOf(language: String): Set<UnicodeScript> = when (language.lowercase()) {
    "ja" -> setOf(UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.HAN, UnicodeScript.LATIN)
    "ko" -> setOf(UnicodeScript.HANGUL, UnicodeScript.HAN, UnicodeScript.LATIN)
    "zh" -> setOf(UnicodeScript.HAN, UnicodeScript.LATIN)
    else -> setOf(UnicodeScript.LATIN)
}

/**
 * True when [text] is outside the [allowed] languages: the provider reported a language code
 * not in the set, or at least 30 percent of its letters are in a script none of them use.
 * An empty [allowed] turns the check off. Non-code reports ("auto", "multi", "") are ignored.
 */
fun violatesAllowedLanguages(text: String, reportedLanguage: String, allowed: List<String>): Boolean {
    if (allowed.isEmpty()) return false
    val allowedCodes = allowed.map { it.lowercase() }
    if (LANGUAGE_CODE.matches(reportedLanguage) && reportedLanguage.take(2).lowercase() !in allowedCodes) return true
    val scripts = allowedCodes.flatMap { scriptsOf(it) }.toSet()
    var letters = 0
    var foreign = 0
    text.codePoints().forEach { cp ->
        if (Character.isLetter(cp)) {
            letters++
            if (UnicodeScript.of(cp) !in scripts) foreign++
        }
    }
    if (letters == 0) return false
    return foreign * 100 >= letters * 30
}

/**
 * The language forced on the second try: the Language setting when it is a two-letter code
 * in [allowed], otherwise the first allowed language. [allowed] must not be empty.
 */
fun strictRetryLanguage(settingLanguage: String, allowed: List<String>): String {
    val setting = settingLanguage.lowercase()
    return if (setting.length == 2 && setting in allowed.map { it.lowercase() }) setting else allowed.first().lowercase()
}
