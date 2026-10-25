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

    @Synchronized
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "store.json")
        state.value = runCatching { json.decodeFromString<StoreData>(file.readText()) }
            .onFailure { if (file.exists()) Log.w(TAG, "Store unreadable; starting fresh", it) }
            .getOrDefault(StoreData())
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

    fun addHistory(entry: DictationEntry) = mutate {
        val max = maxOf(20, it.settings.history.maxEntries)
        it.copy(history = (listOf(entry) + it.history).take(max))
    }

    fun updateHistory(id: String, transform: (DictationEntry) -> DictationEntry) = mutate { data ->
        data.copy(history = data.history.map { if (it.id == id) transform(it) else it })
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
                    dictionary + DictionaryEntry(id(), change.from, change.to, createdAt = System.currentTimeMillis())
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

    fun deleteHistory(id: String) = mutate { data -> data.copy(history = data.history.filter { it.id != id }) }

    fun clearHistory() = mutate { it.copy(history = emptyList(), suggestions = emptyList()) }

    fun addDictionary(word: String, correction: String? = null) = mutate { data ->
        data.copy(dictionary = withWord(data.dictionary, word, correction))
    }

    private fun withWord(dictionary: List<DictionaryEntry>, word: String, correction: String?): List<DictionaryEntry> {
        val cleaned = word.trim().take(60)
        val fixed = correction?.trim()?.take(60)?.ifEmpty { null }
        val existing = dictionary.find { it.word.equals(cleaned, ignoreCase = true) }
        return when {
            existing == null -> dictionary + DictionaryEntry(id(), cleaned, fixed, createdAt = System.currentTimeMillis())
            correction != null -> dictionary.map { if (it.id == existing.id) it.copy(correction = fixed) else it }
            else -> dictionary
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
            dictionary = withWord(data.dictionary, suggestion.from, suggestion.to),
            suggestions = data.suggestions.filter { it.id != id },
        )
    }

    fun dismissSuggestion(id: String) = mutate { data -> data.copy(suggestions = data.suggestions.filter { it.id != id }) }

    fun exportJson(): String = json.encodeToString(StoreData.serializer(), state.value)
}
