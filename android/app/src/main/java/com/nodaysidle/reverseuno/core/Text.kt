package com.nodaysidle.reverseuno.core

// Kotlin port of src/shared/text.ts. Keep the two in step; TextTest mirrors tests/text.test.ts.

private val HALLUCINATIONS = listOf(
    Regex("\\bthank(s| you) for watching\\b", RegexOption.IGNORE_CASE),
    Regex("\\bplease (like and )?subscribe\\b", RegexOption.IGNORE_CASE),
    Regex("\\bsubtitles? (by|provided by)\\b", RegexOption.IGNORE_CASE),
    Regex("\\bsubtitled by\\b", RegexOption.IGNORE_CASE),
    Regex("\\btranslated by\\b", RegexOption.IGNORE_CASE),
    Regex("\\bgrazie per la visione\\b", RegexOption.IGNORE_CASE),
    Regex("\\biscriviti( al canale)?\\b", RegexOption.IGNORE_CASE),
    Regex("\\bsottotitoli (a cura di|creati da)\\b", RegexOption.IGNORE_CASE),
    Regex("\\bhvala za ogled\\b", RegexOption.IGNORE_CASE),
    Regex("\\bpodnapisi\\b", RegexOption.IGNORE_CASE),
    Regex("\\bnaročite se\\b", RegexOption.IGNORE_CASE),
    Regex("^\\s*(you|thank you)\\.?\\s*$", RegexOption.IGNORE_CASE),
    Regex("^\\s*[\\[(].*(music|applause|laughter).*[\\])]\\s*$", RegexOption.IGNORE_CASE),
)

fun sanitizeTranscript(input: String): String {
    var text = input
        .replace(Regex("[​‌‍﻿]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    if (HALLUCINATIONS.any { it.containsMatchIn(text) }) return ""

    // Repetition loops: collapse "and then and then and then and then" style output.
    val tokens = text.split(" ")
    if (tokens.size >= 12) {
        for (n in 2..6) {
            val gram = tokens.take(n).joinToString(" ").lowercase()
            if (gram.isEmpty()) continue
            var repeats = 0
            var i = 0
            while (i + n <= tokens.size) {
                if (tokens.subList(i, i + n).joinToString(" ").lowercase() == gram) repeats++ else break
                i += n
            }
            if (repeats >= 4) {
                text = tokens.take(n).joinToString(" ")
                break
            }
        }
    }
    return text
}

/** The spellings worth telling the speech and formatting models about, best first. See text.ts. */
fun preferredSpellings(entries: List<DictionaryEntry>, limit: Int): List<String> {
    data class Slot(val term: String, var starred: Boolean, var uses: Int, var variants: Int, val order: Int)
    val merged = LinkedHashMap<String, Slot>()
    entries.forEachIndexed { order, entry ->
        val word = entry.word.trim()
        val correction = entry.correction?.trim().orEmpty()
        val term = correction.ifEmpty { word }
        if (term.isEmpty()) return@forEachIndexed
        val slot = merged.getOrPut(term.lowercase()) { Slot(term, false, 0, 0, order) }
        slot.starred = slot.starred || entry.starred
        slot.uses += entry.uses
        if (correction.isNotEmpty() && !correction.equals(word, ignoreCase = true)) slot.variants += 1
    }
    return merged.values
        .sortedWith(
            compareByDescending<Slot> { it.starred }
                .thenByDescending { it.uses }
                .thenByDescending { it.variants }
                .thenBy { it.order }
        )
        .take(limit.coerceAtLeast(0))
        .map { it.term }
}

/** Rewrites dictionary words; an entry without a correction enforces its own spelling. */
fun applyDictionary(text: String, entries: List<DictionaryEntry>): String {
    var output = text
    val sorted = entries.filter { it.word.isNotBlank() }.sortedByDescending { it.word.trim().length }
    for (entry in sorted) {
        val word = entry.word.trim()
        val replacement = entry.correction?.trim().orEmpty().ifEmpty { word }
        val re = Regex("(?<![\\w-])${Regex.escape(word)}(?![\\w-])", RegexOption.IGNORE_CASE)
        output = re.replace(output) { replacement }
    }
    return output
}

private val EDGE_PUNCTUATION = "[.,!?;:'\"“”‘’…]+"

fun normaliseTrigger(value: String): String = value
    .lowercase()
    .replace(Regex("$EDGE_PUNCTUATION$"), "")
    .replace(Regex("^$EDGE_PUNCTUATION"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

/** Saying the trigger (or "insert <trigger>") inserts the snippet's content. */
fun expandSnippet(text: String, snippets: List<Snippet>): Pair<String, Snippet?> {
    val normalised = normaliseTrigger(text)
    for (snippet in snippets) {
        val trigger = normaliseTrigger(snippet.trigger)
        if (trigger.isEmpty()) continue
        if (normalised == trigger || normalised == "insert $trigger") return snippet.content to snippet
    }
    return text to null
}

fun wordCount(text: String): Int = text.trim().let { if (it.isEmpty()) 0 else it.split(Regex("\\s+")).size }

fun truncate(text: String, max: Int): String =
    if (text.length <= max) text else text.take((max - 1).coerceAtLeast(0)).trimEnd() + "…"

data class TokenChange(val from: String, val to: String)

/** Word-level LCS diff for learn-from-edits. */
fun diffTokens(before: String, after: String): List<TokenChange> {
    val a = before.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val b = after.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (a.isEmpty() || b.isEmpty() || a.size > 400 || b.size > 400) return emptyList()

    val n = a.size
    val m = b.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
        dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
    }

    val changes = mutableListOf<TokenChange>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> { i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> changes += TokenChange(a[i++], "")
            else -> changes += TokenChange("", b[j++])
        }
    }
    while (i < n) changes += TokenChange(a[i++], "")
    while (j < m) changes += TokenChange("", b[j++])

    // Pair a deletion with the next insertion as a spelling correction.
    val paired = mutableListOf<TokenChange>()
    var k = 0
    while (k < changes.size) {
        val current = changes[k]
        val next = changes.getOrNull(k + 1)
        if (current.from.isNotEmpty() && current.to.isEmpty() && next != null && next.from.isEmpty() && next.to.isNotEmpty()) {
            paired += TokenChange(current.from, next.to)
            k++
        } else if (current.from.isNotEmpty() && current.to.isNotEmpty()) {
            paired += current
        }
        k++
    }
    return paired
}

private val COMMON_WORDS = ("a an the and or but if then than that this these those i you he she it we they me him her us them " +
    "my your his its our their is are was were be been being am do does did done have has had having to of in on at for with " +
    "from by as about into over after before between out up down off again further once here there when where why how all any " +
    "both each few more most other some such no nor not only own same so too very can will just should now would could may " +
    "might must shall").split(" ").toSet()

fun isLearnable(from: String, to: String): Boolean {
    val clean = to.replace(Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$"), "")
    if (from.isEmpty() || to.isEmpty() || clean.length < 3 || clean.length > 60) return false
    if (clean.lowercase() in COMMON_WORDS) return false
    if (Regex("^\\d+$").matches(clean)) return false
    if (from == clean) return false
    if (Regex("[/\\\\.]").containsMatchIn(clean) &&
        !Regex("^[\\w.-]+\\.(com|ai|dev|io|org)$", RegexOption.IGNORE_CASE).matches(clean)
    ) return false
    return true
}
