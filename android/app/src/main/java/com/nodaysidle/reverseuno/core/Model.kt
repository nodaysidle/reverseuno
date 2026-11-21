package com.nodaysidle.reverseuno.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors src/shared/types.ts, minus the desktop-only parts (hotkey, paste keys, overlay pill, tray).

@Serializable
data class SttSettings(
    /** Deepgram model. Nova-3 streams live over WebSocket and handles direct REST retries. */
    val model: String = "nova-3",
    /** Locked to English for maximum Nova-3 monolingual accuracy and speed. */
    val language: String = "en",
    val keytermLimit: Int = 15,
    /** Fallback/retry uploads go as Ogg/Opus (~8x smaller than WAV); a rejected or failed encode sends the WAV. */
    val compressUploads: Boolean = true,
)

@Serializable
data class InsertSettings(val appendSpace: Boolean = true)

@Serializable
data class BubbleSettings(val enabled: Boolean = true)

@Serializable
data class HistorySettings(
    val maxEntries: Int = 500,
    val autoLearn: Boolean = true,
    /** Finished dictations older than this many days are removed; 0 keeps them forever. */
    val keepDays: Int = 0,
)

@Serializable
data class AppearanceSettings(
    /** "night" is the dark navy look; "paper" the built-in light look. */
    val theme: String = "night",
)

@Serializable
data class Settings(
    val stt: SttSettings = SttSettings(),
    val insert: InsertSettings = InsertSettings(),
    val bubble: BubbleSettings = BubbleSettings(),
    val history: HistorySettings = HistorySettings(),
    val appearance: AppearanceSettings = AppearanceSettings(),
)

@Serializable
enum class EntryStatus {
    /** Transcribed; text is final (it may still have failed to insert, see [DictationEntry.inserted]). */
    @SerialName("done") DONE,
    /** Recorded and saved; transcription has not finished yet. */
    @SerialName("pending") PENDING,
    /** Transcription failed; the audio is kept at [DictationEntry.audioPath] so it can be retried. */
    @SerialName("failed") FAILED,
}

@Serializable
data class DictationEntry(
    val id: String,
    val text: String,
    val createdAt: Long,
    val provider: String,
    val model: String,
    val language: String,
    val durationMs: Long,
    val asrMs: Long,
    val totalMs: Long,
    /** Package name of the app the text went into; empty when dictated inside reverseuno. */
    val appClass: String,
    /** The app's user-facing label. */
    val appTitle: String,
    val inserted: Boolean,
    val error: String? = null,
    val wordCount: Int,
    val editedText: String? = null,
    val editDistance: Int? = null,
    val flagged: Boolean = false,
    /** Entries written before v3 had no status and were always finished. */
    val status: EntryStatus = EntryStatus.DONE,
    /** The recording on disk while the entry is pending or failed; null once transcribed. */
    val audioPath: String? = null,
    /** Transcription attempts that failed. */
    val attempts: Int = 0,
)

@Serializable
data class DictionaryEntry(
    val id: String,
    val word: String,
    val correction: String? = null,
    val starred: Boolean = false,
    val uses: Int = 1,
    val createdAt: Long,
    /** Where the word came from; words saved before this existed read as manual. */
    val source: DictionarySource = DictionarySource.MANUAL,
)

/** Typeless' split: words you added vs words learned from your edits. */
@Serializable
enum class DictionarySource {
    @SerialName("manual") MANUAL,
    @SerialName("auto") AUTO,
}

@Serializable
data class Snippet(
    val id: String,
    val trigger: String,
    val content: String,
    val uses: Int = 1,
    val createdAt: Long,
)

@Serializable
data class Suggestion(
    val id: String,
    val from: String,
    val to: String,
    val createdAt: Long,
    val source: String = "edit",
)

/** v2: 15 keyterms; v3: history status; v4: nova-3; v5: English-only. See [migrate]. */
const val STORE_VERSION = 5

/**
 * Moves an older store off defaults that changed. Past ~15 keyterms, sound-alike developer
 * terms (Node.js, Neovim) win over the user's own words in Deepgram's final transcript.
 */
fun migrate(data: StoreData): StoreData {
    if (data.version >= STORE_VERSION) return data
    // v1 -> v2 only: a v2 store with 50 keyterms chose it deliberately.
    var stt = if (data.version < 2 && data.settings.stt.keytermLimit == 50) data.settings.stt.copy(keytermLimit = SttSettings().keytermLimit) else data.settings.stt
    // v3 -> v4: old OpenRouter model slugs (e.g. "openai/gpt-4o-mini-transcribe") reset to Deepgram nova-3.
    if (data.version < 4 && ('/' in stt.model || stt.model.isBlank())) stt = stt.copy(model = SttSettings().model)
    // v4 -> v5: lock language to English.
    if (data.version < 5) stt = stt.copy(language = "en")
    return data.copy(version = STORE_VERSION, settings = data.settings.copy(stt = stt))
}

@Serializable
data class StoreData(
    val version: Int = STORE_VERSION,
    val createdAt: Long = System.currentTimeMillis(),
    val settings: Settings = Settings(),
    val history: List<DictationEntry> = emptyList(),
    val dictionary: List<DictionaryEntry> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(),
)

