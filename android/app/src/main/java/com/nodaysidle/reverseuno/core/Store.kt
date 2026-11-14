package com.nodaysidle.reverseuno.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Port of src/main/store.ts: one JSON file holding settings, history, dictionary, snippets
 * and suggestions. The hub and the accessibility service share it in-process through [data].
 */
object Store {
    private const val TAG = "Store"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private lateinit var file: File
    private val state = MutableStateFlow(StoreData())
    val data: StateFlow<StoreData> get() = state
    val settings: Settings get() = state.value.settings

    /** Prefix of a store file that could not be read and was kept for recovery. */
    internal const val CORRUPT_PREFIX = "store.json.corrupt-"

    /**
     * Reads [file]. A missing file is a first run: defaults, nothing moved. A file that cannot be
     * decoded is moved aside as store.json.corrupt-<time> (so the next save cannot overwrite it)
     * and defaults are returned; the second value is that file, null when the read was fine.
     */
    internal fun load(file: File, now: Long = System.currentTimeMillis()): Pair<StoreData, File?> {
        if (!file.exists()) return StoreData() to null
        return runCatching<Pair<StoreData, File?>> { migrate(json.decodeFromString<StoreData>(file.readText())) to null }
            .getOrElse {
                val aside = File(file.parentFile, "$CORRUPT_PREFIX$now")
                StoreData() to (if (file.renameTo(aside)) aside else file)
            }
    }

    /** True while an unreadable store is kept in the folder: its recordings must not be swept. */
    internal fun hasCorruptStore(fileNames: List<String>): Boolean = fileNames.any { it.startsWith(CORRUPT_PREFIX) }

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "store.json")
        val (loaded, kept) = load(file)
        if (kept != null) Log.w(TAG, "Store unreadable; kept as ${kept.name}, starting fresh")
        state.value = loaded
    }

    @Synchronized
    private fun mutate(block: (StoreData) -> StoreData) {
        val next = block(state.value)
        state.value = next
        // Write to a temp file and rename, so a crash mid-write never leaves a torn store.
        val tmp = File(file.parentFile, "store.json.tmp")
        tmp.writeText(json.encodeToString(StoreData.serializer(), next))
        tmp.renameTo(file)
    }

    private fun id() = UUID.randomUUID().toString()

    fun updateSettings(transform: (Settings) -> Settings) = mutate { it.copy(settings = transform(it.settings)) }

    fun addHistory(entry: DictationEntry) {
        var dropped: List<DictationEntry> = emptyList()
        mutate {
            val (kept, gone) = withNewEntry(it.history, entry, maxOf(20, it.settings.history.maxEntries))
            dropped = gone
            it.copy(history = kept)
        }
        dropped.forEach(::deleteAudio)
    }

    fun updateHistory(id: String, transform: (DictationEntry) -> DictationEntry) = mutate { data ->
        data.copy(history = data.history.map { if (it.id == id) transform(it) else it })
    }

    /** Marks entries a dead process left pending as failed, so they show a retry instead of spinning forever. */
    fun recoverInterrupted() {
        if (state.value.history.none { it.status == EntryStatus.PENDING }) return
        mutate { it.copy(history = interruptedToFailed(it.history)) }
    }

    /** Deletes recordings in [dir] that no entry points at. Skipped while an unreadable store is kept: its entries are unknown. */
    fun sweepRecordings(dir: File) {
        if (hasCorruptStore(file.parentFile?.list()?.toList().orEmpty())) return
        val names = dir.list()?.toList() ?: return
        orphanRecordings(names, state.value.history).forEach { File(dir, it).delete() }
    }

    private fun deleteAudio(entry: DictationEntry) {
        entry.audioPath?.let { runCatching { File(it).delete() } }
    }

    /** An edited transcript; learns spelling corrections from the diff (or suggests them when auto-learn is off). */
    fun recordEdit(id: String, editedText: String) = mutate { data ->
        val entry = data.history.find { it.id == id } ?: return@mutate data
        val clean = sanitizeTranscript(editedText).ifEmpty { return@mutate data }
        val changes = diffTokens(entry.text, clean)
        val edited = entry.copy(editedText = clean, text = clean, wordCount = wordCount(clean), editDistance = changes.size)
        var dictionary = data.dictionary
        var suggestions = data.suggestions
        for (change in changes.take(3)) {
            if (!isLearnable(change.from, change.to)) continue
            if (data.settings.history.autoLearn) {
                val existing = dictionary.find { it.word.equals(change.from, ignoreCase = true) }
                dictionary = if (existing != null) {
                    dictionary.map { if (it.id == existing.id) it.copy(correction = change.to, uses = it.uses + 1) else it }
                } else {
                    dictionary + DictionaryEntry(id(), change.from, change.to, createdAt = System.currentTimeMillis(), source = DictionarySource.AUTO)
                }
            } else if (suggestions.none { it.from.equals(change.from, ignoreCase = true) && it.to == change.to }) {
                suggestions = suggestions + Suggestion(id(), change.from, change.to, System.currentTimeMillis())
            }
        }
        data.copy(
            history = data.history.map { if (it.id == id) edited else it },
            dictionary = dictionary,
            suggestions = suggestions,
        )
    }

    fun deleteHistory(id: String) {
        val entry = state.value.history.find { it.id == id }
        mutate { data -> data.copy(history = data.history.filter { it.id != id }) }
        entry?.let(::deleteAudio)
    }

    fun clearHistory() {
        val all = state.value.history
        mutate { it.copy(history = emptyList(), suggestions = emptyList()) }
        all.forEach(::deleteAudio)
    }

    /** Applies "Keep dictations for": removes finished dictations past the age limit. Returns how many went. */
    fun pruneExpired(now: Long = System.currentTimeMillis()): Int {
        val expired = expiredEntries(state.value.history, now, keepMillis(settings.history.keepDays))
        if (expired.isEmpty()) return 0
        val ids = expired.map { it.id }.toSet()
        mutate { data -> data.copy(history = data.history.filter { it.id !in ids }) }
        expired.forEach(::deleteAudio)
        return expired.size
    }

    fun addDictionary(word: String, correction: String? = null) = mutate { data ->
        data.copy(dictionary = withWord(data.dictionary, word, correction, DictionarySource.MANUAL))
    }

    /** Removes several words at once (multi-select); returns how many went. */
    fun deleteDictionary(ids: Set<String>): Int {
        var removed = 0
        mutate { data ->
            val kept = data.dictionary.filter { it.id !in ids }
            removed = data.dictionary.size - kept.size
            data.copy(dictionary = kept)
        }
        return removed
    }

    /** A word typed by hand becomes manual even if it was learned before; a learned word never demotes a manual one. */
    private fun withWord(dictionary: List<DictionaryEntry>, word: String, correction: String?, source: DictionarySource): List<DictionaryEntry> {
        val cleaned = word.trim().take(60)
        val fixed = correction?.trim()?.take(60)?.ifEmpty { null }
        val existing = dictionary.find { it.word.equals(cleaned, ignoreCase = true) }
        val keptSource = { e: DictionaryEntry -> if (source == DictionarySource.MANUAL) DictionarySource.MANUAL else e.source }
        return when {
            existing == null -> dictionary + DictionaryEntry(id(), cleaned, fixed, createdAt = System.currentTimeMillis(), source = source)
            correction != null -> dictionary.map { if (it.id == existing.id) it.copy(correction = fixed, source = keptSource(it)) else it }
            else -> dictionary.map { if (it.id == existing.id) it.copy(source = keptSource(it)) else it }
        }
    }

    /** Adds the words not already present; returns how many were new. */
    fun addDictionaryPack(words: List<VocabularyEntry> = DEVELOPER_VOCABULARY): Int {
        var added = 0
        mutate { data ->
            val known = data.dictionary.map { it.word.lowercase() }.toSet()
            val fresh = words.filter { it.word.lowercase() !in known }
            added = fresh.size
            // uses = 0 keeps them below anything the user added, so their own words win the keyterm slots.
            data.copy(dictionary = data.dictionary + fresh.map {
                DictionaryEntry(id(), it.word, it.correction, uses = 0, createdAt = System.currentTimeMillis())
            })
        }
        return added
    }

    fun updateDictionary(id: String, transform: (DictionaryEntry) -> DictionaryEntry) = mutate { data ->
        data.copy(dictionary = data.dictionary.map {
            if (it.id != id) it else transform(it).let { e ->
                e.copy(word = e.word.trim().take(60), correction = e.correction?.trim()?.take(60)?.ifEmpty { null })
            }
        })
    }

    fun deleteDictionary(id: String) = mutate { data -> data.copy(dictionary = data.dictionary.filter { it.id != id }) }

    fun addSnippet(trigger: String, content: String) = mutate { data ->
        val cleaned = trigger.trim().take(80)
        val existing = data.snippets.find { it.trigger.equals(cleaned, ignoreCase = true) }
        data.copy(
            snippets = if (existing != null) {
                data.snippets.map { if (it.id == existing.id) it.copy(content = content) else it }
            } else {
                data.snippets + Snippet(id(), cleaned, content, createdAt = System.currentTimeMillis())
            }
        )
    }

    fun updateSnippet(id: String, transform: (Snippet) -> Snippet) = mutate { data ->
        data.copy(snippets = data.snippets.map { if (it.id == id) transform(it).let { s -> s.copy(trigger = s.trigger.trim().take(80)) } else it })
    }

    fun deleteSnippet(id: String) = mutate { data -> data.copy(snippets = data.snippets.filter { it.id != id }) }

    fun acceptSuggestion(id: String) = mutate { data ->
        val suggestion = data.suggestions.find { it.id == id } ?: return@mutate data
        data.copy(
            dictionary = withWord(data.dictionary, suggestion.from, suggestion.to, DictionarySource.AUTO),
            suggestions = data.suggestions.filter { it.id != id },
        )
    }

    fun dismissSuggestion(id: String) = mutate { data -> data.copy(suggestions = data.suggestions.filter { it.id != id }) }

    fun exportJson(): String = json.encodeToString(StoreData.serializer(), state.value)
}
