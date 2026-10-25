package com.nodaysidle.reverseuno.dictation

import android.content.Context
import android.util.Log
import com.nodaysidle.reverseuno.cleanup.PromptContext
import com.nodaysidle.reverseuno.cleanup.formatTranscript
import com.nodaysidle.reverseuno.cleanup.needsFormatting
import com.nodaysidle.reverseuno.core.DictationEntry
import com.nodaysidle.reverseuno.core.Store
import com.nodaysidle.reverseuno.core.applyDictionary
import com.nodaysidle.reverseuno.core.expandSnippet
import com.nodaysidle.reverseuno.core.preferredSpellings
import com.nodaysidle.reverseuno.core.sanitizeTranscript
import com.nodaysidle.reverseuno.core.wordCount
import com.nodaysidle.reverseuno.security.KeyVault
import com.nodaysidle.reverseuno.security.Secret
import com.nodaysidle.reverseuno.stt.OpenRouterSttClient
import java.io.File
import java.util.UUID

/** Where the text goes: a package in another app, or the hub itself (empty). */
data class Target(val appClass: String = "", val appTitle: String = "")

/** A finished transcript ready to insert, before it is saved to history. */
data class Transcript(
    val text: String,
    val raw: String,
    val language: String,
    val asrMs: Long,
    val formatMs: Long,
)

/**
 * The desktop dictation pipeline (src/main/dictation.ts), minus streaming:
 * transcribe -> sanitize -> snippet or format -> dictionary spellings.
 */
class Pipeline(private val context: Context) {

    /** Null when ready; otherwise what the bubble should say. */
    fun setupProblem(): String? =
        if (KeyVault.get(context, Secret.OPENROUTER).isBlank()) "Set up OpenRouter" else null

    /** Null when nothing was said. Throws when transcription fails. */
    suspend fun process(audio: File, target: Target): Transcript? {
        val settings = Store.settings
        val data = Store.data.value
        val asrStarted = System.currentTimeMillis()
        val stt = OpenRouterSttClient(KeyVault.get(context, Secret.OPENROUTER), settings.stt.model)
            .transcribe(audio, settings.stt.language, preferredSpellings(data.dictionary, settings.stt.keytermLimit))
        val asrMs = System.currentTimeMillis() - asrStarted
        val raw = sanitizeTranscript(stt.text).ifEmpty { return null }

        var final = raw
        var formatMs = 0L
        var cleanedBy = "none"
        val (expanded, snippet) = expandSnippet(raw, data.snippets)
        if (snippet != null) {
            final = expanded
            Store.updateSnippet(snippet.id) { it.copy(uses = it.uses + 1) }
        } else if (needsFormatting(raw, settings)) {
            val result = formatTranscript(
                raw,
                settings,
                KeyVault.get(context, Secret.ANTHROPIC),
                KeyVault.get(context, Secret.DEEPSEEK),
                PromptContext(
                    cleanup = settings.format.cleanup,
                    style = settings.format.style,
                    customInstructions = settings.format.customInstructions,
                    dictionary = preferredSpellings(data.dictionary, 80),
                    appClass = target.appClass,
                    appTitle = target.appTitle,
                    includeAppContext = settings.format.includeAppContext,
                ),
            )
            if (result.error != null) Log.w(TAG, "formatting fell back (used ${result.provider ?: "raw"}): ${result.error}")
            final = result.text.ifEmpty { raw }
            formatMs = result.latencyMs
            cleanedBy = result.provider ?: "raw"
        }
        final = sanitizeTranscript(applyDictionary(final, data.dictionary)).ifEmpty { raw }
        if (snippet != null) cleanedBy = "snippet"
        // Timings and sizes only: transcripts never go to logcat.
        Log.i(TAG, "dictation model=${settings.stt.model} lang=${stt.language} asrMs=$asrMs cleanup=$cleanedBy formatMs=$formatMs words=${wordCount(final)} audioKb=${audio.length() / 1024}")
        return Transcript(final, raw, stt.language, asrMs, formatMs)
    }

    /** Text as it should be typed: with the trailing space desktop adds between dictations. */
    fun forInsertion(text: String): String =
        if (Store.settings.insert.appendSpace && !text.last().isWhitespace()) "$text " else text

    fun save(transcript: Transcript, target: Target, startedAt: Long, durationMs: Long, inserted: Boolean, error: String? = null): DictationEntry {
        val settings = Store.settings
        val entry = DictationEntry(
            id = UUID.randomUUID().toString(),
            text = transcript.text,
            rawText = transcript.raw,
            createdAt = System.currentTimeMillis(),
            provider = "openrouter",
            model = settings.stt.model,
            language = transcript.language,
            durationMs = durationMs,
            asrMs = transcript.asrMs,
            formatMs = transcript.formatMs,
            totalMs = System.currentTimeMillis() - startedAt,
            appClass = target.appClass,
            appTitle = target.appTitle,
            cleanup = settings.format.cleanup,
            style = settings.format.style,
            inserted = inserted,
            error = error,
            wordCount = wordCount(transcript.text),
        )
        Store.addHistory(entry)
        return entry
    }

    private companion object {
        const val TAG = "Pipeline"
    }
}
