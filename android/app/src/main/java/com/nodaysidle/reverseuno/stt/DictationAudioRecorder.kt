package com.nodaysidle.reverseuno.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Streams 16 kHz mono PCM16 (Wispr Flow's capture format) while keeping a WAV copy for the
 * one-shot fallback. Each read is one 250 ms frame handed to [start]'s callback, so the
 * streaming client sees the same batching window the reference implementation uses.
 * Amplitude is tracked per frame for the overlay waveform.
 */
class DictationAudioRecorder(private val context: Context) {
    private var session: Session? = null

    @Volatile private var peak = 0

    val isRecording: Boolean get() = session?.recording == true

    private class Session(
        val file: File,
        val out: FileOutputStream,
        val record: AudioRecord,
    ) {
        @Volatile var recording = true
        var thread: Thread? = null
        var dataBytes = 0
    }

    /** Starts a new recording; the returned file is a WAV that becomes valid once [stop] finalizes it. */
    fun start(onChunk: ((ByteArray) -> Unit)? = null): File {
        stopInternal(discard = true)
        val file = File(context.cacheDir, "dictation_${System.currentTimeMillis()}.wav")
        val out = FileOutputStream(file)
        out.write(wavHeader(0))

        val minBuffer = AudioRecord.getMinBufferSize(
            WAV_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBuffer, PCM_CHUNK_BYTES * 2)
        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(WAV_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }.getOrElse { failure ->
            runCatching { out.close() }
            file.delete()
            throw failure
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            runCatching { out.close() }
            file.delete()
            error("Microphone unavailable")
        }

        val current = Session(file, out, record)
        session = current
        peak = 0
        runCatching { record.startRecording() }.onFailure { failure ->
            session = null
            runCatching { record.release() }
            runCatching { out.close() }
            file.delete()
            throw failure
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            session = null
            runCatching { record.release() }
            runCatching { out.close() }
            file.delete()
            error("Microphone unavailable")
        }

        current.thread = Thread({ pump(current, onChunk) }, "reverseuno-recorder").apply {
            isDaemon = true
            start()
        }
        return file
    }

    /** Peak sample (0..32767) of the most recent frame. */
    fun maxAmplitude(): Int = peak

    fun stop(): File? {
        val current = session ?: return null
        stopInternal(discard = false)
        return current.file.takeIf { it.length() > WAV_HEADER_BYTES && current.dataBytes > 0 }
    }

    fun cancel() {
        stopInternal(discard = true)
    }

    private fun pump(current: Session, onChunk: ((ByteArray) -> Unit)?) {
        val buffer = ByteArray(PCM_CHUNK_BYTES)
        try {
            while (current.recording) {
                val read = current.record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read < 0) break else continue
                }
                current.out.write(buffer, 0, read)
                current.dataBytes += read
                peak = peakOf(buffer, read)
                if (onChunk != null) runCatching { onChunk(buffer.copyOf(read)) }
            }
        } catch (t: Throwable) {
            // read() throws after stop(); only a genuine I/O failure is worth a line in logcat.
            if (current.recording) Log.w(TAG, "recorder loop ended: ${t.message}")
        }
    }

    private fun stopInternal(discard: Boolean) {
        val current = session ?: return
        session = null
        current.recording = false
        runCatching { current.record.stop() }
        val thread = current.thread
        thread?.let { runCatching { it.join(1_500) } }
        if (thread?.isAlive == true) {
            runCatching { current.record.stop() }
            thread.interrupt()
            runCatching { thread.join(500) }
        }
        runCatching { current.record.release() }
        runCatching {
            current.out.flush()
            current.out.close()
        }
        runCatching { finalizeWav(current.file, current.dataBytes) }
        if (discard || current.dataBytes <= 0) current.file.delete()
        peak = 0
    }

    private companion object {
        const val TAG = "DictationAudioRecorder"

        fun peakOf(bytes: ByteArray, length: Int): Int {
            var peak = 0
            var i = 0
            while (i + 1 < length) {
                val sample = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                val magnitude = abs(sample)
                if (magnitude > peak) peak = magnitude
                i += 2
            }
            return peak
        }
    }
}
