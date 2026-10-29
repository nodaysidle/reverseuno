package com.nodaysidle.reverseuno.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors src/shared/types.ts, minus the desktop-only parts (hotkey, paste keys, overlay pill, tray).

@Serializable
enum class CleanupLevel(val label: String, val blurb: String) {
    @SerialName("none") NONE("None", "Exactly what you said, including the ums and false starts."),
    @SerialName("light") LIGHT("Light", "Filler words and obvious slips removed. Your wording stays."),
    @SerialName("medium") MEDIUM("Medium", "Grammar and repetition tightened, meaning and voice kept."),
    @SerialName("high") HIGH("High", "Rewritten for brevity and polish. Every fact preserved."),
}

@Serializable
enum class WritingStyle(val label: String, val blurb: String) {
    @SerialName("formal") FORMAL("Formal", "Composed, complete sentences."),
    @SerialName("casual") CASUAL("Casual", "Natural, contractions welcome."),
    @SerialName("very-casual") VERY_CASUAL("Very casual", "Loose and everyday."),
    @SerialName("excited") EXCITED("Excited", "Upbeat, never shouty."),
    @SerialName("genz") GENZ("Gen Z", "Current, never cringe."),
}

@Serializable
data class SttSettings(
    /** OpenRouter transcription model id. */
    val model: String = "openai/gpt-4o-mini-transcribe",
    /** "auto" leaves the language out so the model detects it per dictation. */
    val language: String = "auto",
    val keytermLimit: Int = 50,
)

@Serializable
data class FormatSettings(
    val enabled: Boolean = true,
    /** Primary cleanup model, through the Anthropic API. */
    val anthropicModel: String = "claude-haiku-5-5",
    /** Fallback when Anthropic fails. DeepSeek-V4.1-Flash; "deepseek-v4.1-flash" is only its OpenRouter id, the direct API calls it this. */
    val deepseekModel: String = "deepseek-flash",
    val cleanup: CleanupLevel = CleanupLevel.LIGHT,
    val style: WritingStyle = WritingStyle.CASUAL,
    val customInstructions: String = "",
    // Desktop uses 2.5 s; mobile networks need more headroom.
    val timeoutMs: Int = 6000,
    val includeAppContext: Boolean = true,
)

@Serializable
data class InsertSettings(val appendSpace: Boolean = true)

@Serializable
data class BubbleSettings(val enabled: Boolean = true)

@Serializable
data class HistorySettings(val maxEntries: Int = 500, val autoLearn: Boolean = true)

@Serializable
data class AppearanceSettings(
    /** "night" is the dark navy look; "paper" the built-in light look. */
    val theme: String = "night",
)

@Serializable
data class Settings(
    val stt: SttSettings = SttSettings(),
    val format: FormatSettings = FormatSettings(),
    val insert: InsertSettings = InsertSettings(),
    val bubble: BubbleSettings = BubbleSettings(),
    val history: HistorySettings = HistorySettings(),
    val appearance: AppearanceSettings = AppearanceSettings(),
)

@Serializable
data class DictationEntry(
    val id: String,
    val text: String,
    val rawText: String,
    val createdAt: Long,
    val provider: String,
    val model: String,
    val language: String,
    val durationMs: Long,
    val asrMs: Long,
    val formatMs: Long,
    val totalMs: Long,
    /** Package name of the app the text went into; empty when dictated inside reverseuno. */
    val appClass: String,
    /** The app's user-facing label. */
    val appTitle: String,
    val cleanup: CleanupLevel,
    val style: WritingStyle,
    val inserted: Boolean,
    val error: String? = null,
    val wordCount: Int,
    val editedText: String? = null,
    val editDistance: Int? = null,
    val flagged: Boolean = false,
)

@Serializable
data class DictionaryEntry(
    val id: String,
    val word: String,
    val correction: String? = null,
    val starred: Boolean = false,
    val uses: Int = 1,
    val createdAt: Long,
)

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

@Serializable
data class StoreData(
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val settings: Settings = Settings(),
    val history: List<DictationEntry> = emptyList(),
    val dictionary: List<DictionaryEntry> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(),
)

data class LanguageOption(val value: String, val label: String)

val LANGUAGE_OPTIONS = listOf(
    LanguageOption("auto", "Auto-detect"),
    LanguageOption("en", "English"),
    LanguageOption("sl", "Slovenian"),
    LanguageOption("it", "Italian"),
    LanguageOption("de", "German"),
    LanguageOption("es", "Spanish"),
    LanguageOption("fr", "French"),
    LanguageOption("pt", "Portuguese"),
    LanguageOption("nl", "Dutch"),
    LanguageOption("ja", "Japanese"),
    LanguageOption("ko", "Korean"),
    LanguageOption("zh", "Chinese"),
)

