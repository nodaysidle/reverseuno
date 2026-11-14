package com.nodaysidle.reverseuno.dictation

import android.content.Context
import android.util.Log
import com.nodaysidle.reverseuno.cleanup.PromptContext
import com.nodaysidle.reverseuno.cleanup.formatTranscript
import com.nodaysidle.reverseuno.cleanup.needsFormatting
import com.nodaysidle.reverseuno.core.DictationEntry
import com.nodaysidle.reverseuno.core.EntryStatus
import com.nodaysidle.reverseuno.core.Store
import com.nodaysidle.reverseuno.core.WRONG_LANGUAGE
import com.nodaysidle.reverseuno.core.applyDictionary
import com.nodaysidle.reverseuno.core.expandSnippet
import com.nodaysidle.reverseuno.core.preferredSpellings
import com.nodaysidle.reverseuno.core.sanitizeTranscript
import com.nodaysidle.reverseuno.core.strictRetryLanguage
import com.nodaysidle.reverseuno.core.usesStreaming
import com.nodaysidle.reverseuno.core.violatesAllowedLanguages
import com.nodaysidle.reverseuno.core.wordCount
import com.nodaysidle.reverseuno.security.KeyVault
import com.nodaysidle.reverseuno.security.Secret
import com.nodaysidle.reverseuno.stt.OpenRouterSttClient
import com.nodaysidle.reverseuno.stt.OpusEncoder
import com.nodaysidle.reverseuno.stt.SttResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the text goes: a package in another app, or the hub itself (empty). */
data class Target(val appClass: String = "", val appTitle: String = "")

/** A finished transcript ready to insert, before it is saved to history. */
data class Transcript(
    val text: String,
    val raw: String,
    val language: String,
    val asrMs: Long,
    val formatMs: Long,
    val provider: String = "openrouter",
    val model: String = "",
)

/**
 * The desktop dictation pipeline (src/main/dictation.ts):
 * transcribe (streaming when available, one-shot otherwise) -> sanitize -> snippet or format -> dictionary spellings.
 */
class Pipeline(private val context: Context) {

    /** Null when some transcription path is configured; otherwise what the bubble should say. */
    fun setupProblem(): String? {
        val stt = Store.settings.stt
        val oneShotReady = KeyVault.get(context, Secret.OPENROUTER).isNotBlank()
        if (!stt.usesStreaming()) return if (oneShotReady) null else "Add an OpenRouter key"
        val streamingKey = when (stt.provider) {
            "xai" -> Secret.XAI
            else -> Secret.DEEPGRAM
        }
        val streamReady = KeyVault.get(context, streamingKey).isNotBlank()
        return if (streamReady || oneShotReady) null else "Add a transcription key"
    }

    /**
     * [streamed] is the result of the live stream when it produced text; it skips the one-shot call.
     * Null when nothing was said. Throws when transcription fails, and throws [WRONG_LANGUAGE]
     * when the transcript is still outside `stt.allowedLanguages` after the strict second try.
     */
    suspend fun process(
        audio: File,
        target: Target,
        streamed: SttResult? = null,
        streamedMs: Long = 0L,
        streamedProvider: String = "deepgram",
        streamedModel: String = "",
    ): Transcript? {
        val settings = Store.settings
        val data = Store.data.value
        var stt: SttResult
        var asrMs: Long
        var provider: String
        var model: String
        if (streamed != null) {
            stt = streamed
            asrMs = streamedMs
            provider = streamedProvider
            model = streamedModel.ifBlank { settings.stt.model }
        } else {
            val asrStarted = System.currentTimeMillis()
            val compressed = if (settings.stt.compressUploads) compressForUpload(audio) else null
            try {
                stt = OpenRouterSttClient(KeyVault.get(context, Secret.OPENROUTER), settings.stt.model)
                    .transcribe(audio, settings.stt.language, preferredSpellings(data.dictionary, settings.stt.keytermLimit), compressed)
            } finally {
                compressed?.delete()
            }
            asrMs = System.currentTimeMillis() - asrStarted
            provider = "openrouter"
            model = settings.stt.model
        }
        val allowed = settings.stt.allowedLanguages
        if (violatesAllowedLanguages(stt.text, stt.language, allowed)) {
            val forced = strictRetryLanguage(settings.stt.language, allowed)
            val sameCall = provider == "openrouter" && model == settings.stt.strictModel && settings.stt.language.equals(forced, ignoreCase = true)
            // No second try possible (no OpenRouter key) or it would repeat the same request.
            if (!hasOneShot() || sameCall) error(WRONG_LANGUAGE)
            Log.w(TAG, "Transcript outside the allowed languages; retrying with ${settings.stt.strictModel} lang=$forced")
            val retryStarted = System.currentTimeMillis()
            stt = OpenRouterSttClient(KeyVault.get(context, Secret.OPENROUTER), settings.stt.strictModel)
                .transcribe(audio, forced, preferredSpellings(data.dictionary, settings.stt.keytermLimit))
            asrMs += System.currentTimeMillis() - retryStarted
            provider = "openrouter"
            model = settings.stt.strictModel
            if (violatesAllowedLanguages(stt.text, stt.language, allowed)) error(WRONG_LANGUAGE)
        }
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
        Log.i(TAG, "dictation provider=$provider model=$model lang=${stt.language} asrMs=$asrMs cleanup=$cleanedBy formatMs=$formatMs words=${wordCount(final)} audioKb=${audio.length() / 1024}")
        return Transcript(final, raw, stt.language, asrMs, formatMs, provider, model)
    }

    /** Text as it should be typed: with the trailing space desktop adds between dictations. */
    fun forInsertion(text: String): String =
        if (Store.settings.insert.appendSpace && !text.last().isWhitespace()) "$text " else text

    /** True when the one-shot (OpenRouter) path can run, which also makes a retry from the saved audio possible. */
    fun hasOneShot(): Boolean = KeyVault.get(context, Secret.OPENROUTER).isNotBlank()

    /**
     * Saves the recording as a pending entry before transcription starts, so a failure, a crash or
     * going offline never loses the dictation: the entry keeps [audio] until [complete] runs.
     */
    fun savePending(audio: File, target: Target, startedAt: Long, durationMs: Long): DictationEntry {
        val settings = Store.settings
        val entry = DictationEntry(
            id = UUID.randomUUID().toString(),
            text = "",
            rawText = "",
            createdAt = startedAt,
            provider = "",
            model = "",
            language = settings.stt.language,
            durationMs = durationMs,
            asrMs = 0,
            formatMs = 0,
            totalMs = 0,
            appClass = target.appClass,
            appTitle = target.appTitle,
            cleanup = settings.format.cleanup,
            style = settings.format.style,
            inserted = false,
            wordCount = 0,
            status = EntryStatus.PENDING,
            audioPath = audio.absolutePath,
        )
        Store.addHistory(entry)
        return entry
    }

    /** The transcript is in: the entry is final and its audio is no longer needed. */
    fun complete(entryId: String, transcript: Transcript, startedAt: Long, inserted: Boolean, error: String? = null) {
        val settings = Store.settings
        var audioPath: String? = null
        Store.updateHistory(entryId) {
            audioPath = it.audioPath
            it.copy(
                text = transcript.text,
                rawText = transcript.raw,
                provider = transcript.provider,
                model = transcript.model.ifBlank { settings.stt.model },
                language = transcript.language,
                asrMs = transcript.asrMs,
                formatMs = transcript.formatMs,
                totalMs = System.currentTimeMillis() - startedAt,
                inserted = inserted,
                error = error,
                wordCount = wordCount(transcript.text),
                status = EntryStatus.DONE,
                audioPath = null,
            )
        }
        audioPath?.let { File(it).delete() }
    }

    /** Transcription failed; the audio stays for a retry. */
    fun fail(entryId: String, reason: String) {
        Store.updateHistory(entryId) { it.copy(status = EntryStatus.FAILED, error = reason, attempts = it.attempts + 1) }
    }

    /** Nothing was said: no entry is worth keeping. */
    fun discard(entryId: String) = Store.deleteHistory(entryId)

    /** Re-runs a pending or failed entry from its saved audio (one-shot path). Null when no speech was found. */
    suspend fun retranscribe(entry: DictationEntry): Transcript? {
        val audio = entry.audioPath?.let(::File)?.takeIf { it.exists() } ?: error(NO_AUDIO)
        Store.updateHistory(entry.id) { it.copy(status = EntryStatus.PENDING) }
        return process(audio, Target(entry.appClass, entry.appTitle))
    }

    /** Ogg/Opus copy of [wav] in the cache for one upload; null (send the WAV) if encoding fails. */
    private suspend fun compressForUpload(wav: File): File? = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "upload_${wav.nameWithoutExtension}.ogg")
        runCatching { OpusEncoder.wavToOgg(wav, out) }
            .onFailure {
                out.delete()
                Log.w(TAG, "Opus encode failed; uploading the WAV: ${it.message}")
            }
            .getOrNull()
    }

    private companion object {
        const val TAG = "Pipeline"
    }
}

const val NO_AUDIO = "The recording is no longer on this device"

/** What the bubble and history show for a failed transcription; never contains transcript text. */
fun failureReason(e: Throwable): String = when {
    e is java.net.UnknownHostException || e is java.net.ConnectException -> "Offline"
    e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException -> "Timed out"
    else -> e.message?.take(160)?.ifBlank { null } ?: "Transcription failed"
}
