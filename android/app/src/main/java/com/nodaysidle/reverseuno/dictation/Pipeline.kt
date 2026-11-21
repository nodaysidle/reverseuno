package com.nodaysidle.reverseuno.dictation

import android.content.Context
import android.util.Log
import com.nodaysidle.reverseuno.core.DictationEntry
import com.nodaysidle.reverseuno.core.EntryStatus
import com.nodaysidle.reverseuno.core.Store
import com.nodaysidle.reverseuno.core.applyDictionary
import com.nodaysidle.reverseuno.core.expandSnippet
import com.nodaysidle.reverseuno.core.preferredSpellings
import com.nodaysidle.reverseuno.core.sanitizeTranscript
import com.nodaysidle.reverseuno.core.wordCount
import com.nodaysidle.reverseuno.security.KeyVault
import com.nodaysidle.reverseuno.security.Secret
import com.nodaysidle.reverseuno.stt.DeepgramRestClient
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
    val language: String,
    val asrMs: Long,
    val provider: String = "deepgram",
    val model: String = "nova-3",
)

/**
 * The dictation pipeline:
 * transcribe (Deepgram Nova-3 streaming, or direct Deepgram REST on retry/fallback) -> sanitize -> snippet -> dictionary spellings.
 */
class Pipeline(private val context: Context) {

    /** Null when Deepgram is configured; otherwise what the bubble should say. */
    fun setupProblem(): String? =
        if (KeyVault.get(context, Secret.DEEPGRAM).isNotBlank()) null else "Add a Deepgram key"

    /**
     * [streamed] is the result of the live stream when it produced text; it skips the REST call.
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
        val deepgramKey = KeyVault.get(context, Secret.DEEPGRAM)
        val keyterms = preferredSpellings(data.dictionary, settings.stt.keytermLimit)
        var stt: SttResult
        var asrMs: Long
        val provider = streamedProvider.ifBlank { "deepgram" }
        val model = streamedModel.ifBlank { settings.stt.model }
        if (streamed != null) {
            stt = streamed
            asrMs = streamedMs
        } else {
            val asrStarted = System.currentTimeMillis()
            val compressed = if (settings.stt.compressUploads && audio.length() > OPUS_MIN_WAV_BYTES) compressForUpload(audio) else null
            try {
                stt = DeepgramRestClient(deepgramKey, model)
                    .transcribe(audio, settings.stt.language, keyterms, compressed)
            } finally {
                compressed?.delete()
            }
            asrMs = System.currentTimeMillis() - asrStarted
        }
        val raw = sanitizeTranscript(stt.text).ifEmpty { return null }

        val (expanded, snippet) = expandSnippet(raw, data.snippets)
        val final = if (snippet != null) {
            Store.updateSnippet(snippet.id) { it.copy(uses = it.uses + 1) }
            sanitizeTranscript(applyDictionary(expanded, data.dictionary)).ifEmpty { expanded }
        } else {
            sanitizeTranscript(applyDictionary(raw, data.dictionary)).ifEmpty { raw }
        }
        // Timings and sizes only: transcripts never go to logcat.
        Log.i(TAG, "dictation provider=$provider model=$model lang=${stt.language} asrMs=$asrMs words=${wordCount(final)} audioKb=${audio.length() / 1024}")
        return Transcript(final, stt.language, asrMs, provider, model)
    }

    /** Text as it should be typed: with the trailing space desktop adds between dictations. */
    fun forInsertion(text: String): String =
        if (Store.settings.insert.appendSpace && !text.last().isWhitespace()) "$text " else text

    /** True when the direct Deepgram REST path can run for retries and dropped-stream recovery. */
    fun hasOneShot(): Boolean = KeyVault.get(context, Secret.DEEPGRAM).isNotBlank()

    /**
     * Saves the recording as a pending entry before transcription starts, so a failure, a crash or
     * going offline never loses the dictation: the entry keeps [audio] until [complete] runs.
     */
    fun savePending(audio: File, target: Target, startedAt: Long, durationMs: Long): DictationEntry {
        val settings = Store.settings
        val entry = DictationEntry(
            id = UUID.randomUUID().toString(),
            text = "",
            createdAt = startedAt,
            provider = "",
            model = "",
            language = settings.stt.language,
            durationMs = durationMs,
            asrMs = 0,
            totalMs = 0,
            appClass = target.appClass,
            appTitle = target.appTitle,
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
                provider = transcript.provider,
                model = transcript.model.ifBlank { settings.stt.model },
                language = transcript.language,
                asrMs = transcript.asrMs,
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
        /** Skip Opus encoding overhead (~180 ms) on short clips (< 8 s); raw WAV uploads faster. */
        const val OPUS_MIN_WAV_BYTES = 256 * 1024L
    }
}

const val NO_AUDIO = "The recording is no longer on this device"

/** What the bubble and history show for a failed transcription; never contains transcript text. */
fun failureReason(e: Throwable): String = when {
    e is java.net.UnknownHostException || e is java.net.ConnectException -> "Offline"
    e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException -> "Timed out"
    else -> e.message?.take(160)?.ifBlank { null } ?: "Transcription failed"
}
