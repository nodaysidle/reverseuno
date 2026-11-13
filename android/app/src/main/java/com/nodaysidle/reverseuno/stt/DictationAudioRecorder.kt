package com.nodaysidle.reverseuno.stt

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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
 *
 * Capture survives a dead input stream (route change, audio server restart): the AudioRecord
 * is rebuilt and keeps writing to the same WAV. A second of exact digital silence is reported
 * once through `onSilentMic`, with whether this client was silenced in favour of another app.
 */
class DictationAudioRecorder(private val context: Context) {
    private var session: Session? = null

    @Volatile private var peak = 0

    val isRecording: Boolean get() = session?.recording == true

    private class Session(
        val file: File,
        val out: FileOutputStream,
        @Volatile var record: AudioRecord,
    ) {
        @Volatile var recording = true
        var thread: Thread? = null
        var dataBytes = 0
        var restarts = 0
    }

    private val audioManager get() = context.getSystemService(AudioManager::class.java)

    /**
     * Starts a new recording; the returned file is a WAV that becomes valid once [stop] finalizes it.
     * Both callbacks run on the recorder thread.
     */
    fun start(
        onChunk: ((ByteArray) -> Unit)? = null,
        onSilentMic: ((silencedByOtherApp: Boolean) -> Unit)? = null,
    ): File {
        stopInternal(discard = true)
        // filesDir, not cacheDir: the system may clear the cache while a failed dictation waits for a retry.
        val file = File(recordingsDir(context), "dictation_${System.currentTimeMillis()}.wav")
        val out = FileOutputStream(file)
        out.write(wavHeader(0))

        val record = runCatching { openRecord() }.getOrElse { failure ->
            runCatching { out.close() }
            file.delete()
            throw failure
        }

        val current = Session(file, out, record)
        session = current
        peak = 0
        current.thread = Thread({ pump(current, onChunk, onSilentMic) }, "reverseuno-recorder").apply {
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

    /** A started AudioRecord, or an exception; never a half-initialized one. */
    private fun openRecord(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            WAV_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(WAV_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, PCM_CHUNK_BYTES * 2))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            error("Microphone unavailable")
        }
        runCatching { record.startRecording() }.onFailure {
            runCatching { record.release() }
            throw it
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { record.release() }
            error("Microphone unavailable")
        }
        record.addOnRoutingChangedListener({ routed ->
            Log.i(TAG, "Input routed to ${describe(routed.routedDevice)}")
        }, null)
        Log.i(TAG, "Recording from ${describe(record.routedDevice)}")
        return record
    }

    private fun pump(current: Session, onChunk: ((ByteArray) -> Unit)?, onSilentMic: ((Boolean) -> Unit)?) {
        val buffer = ByteArray(PCM_CHUNK_BYTES)
        val silence = SilenceGuard()
        try {
            while (current.recording) {
                val read = current.record.read(buffer, 0, buffer.size)
                if (read == 0) continue
                if (read < 0) {
                    if (!current.recording || !restart(current, read)) break
                    continue
                }
                current.out.write(buffer, 0, read)
                current.dataBytes += read
                peak = peakOf(buffer, read)
                if (onChunk != null) runCatching { onChunk(buffer.copyOf(read)) }
                if (onSilentMic != null && silence.feed(buffer, read)) {
                    val silenced = silencedByOtherApp(current.record)
                    Log.w(TAG, "Input is digital silence; silencedByOtherApp=$silenced")
                    runCatching { onSilentMic(silenced) }
                }
            }
        } catch (t: Throwable) {
            // read() throws after stop(); only a genuine I/O failure is worth a line in logcat.
            if (current.recording) Log.w(TAG, "recorder loop ended: ${t.message}")
        }
    }

    /** The input stream died (ERROR_DEAD_OBJECT after a route change or audio server restart): reopen it. */
    private fun restart(current: Session, code: Int): Boolean {
        if (current.restarts >= MAX_RESTARTS) {
            Log.e(TAG, "AudioRecord read failed ($code) after ${current.restarts} restarts; giving up")
            return false
        }
        current.restarts++
        Log.w(TAG, "AudioRecord read failed ($code); reopening the microphone (${current.restarts}/$MAX_RESTARTS)")
        runCatching { current.record.stop() }
        runCatching { current.record.release() }
        return runCatching { openRecord() }
            .onSuccess { current.record = it }
            .onFailure { Log.e(TAG, "Could not reopen the microphone", it) }
            .isSuccess
    }

    /**
     * True only when another client is recording. Our own client being silenced is not enough:
     * Android 12's microphone privacy toggle silences it too, with nobody else on the mic.
     */
    private fun silencedByOtherApp(record: AudioRecord): Boolean = runCatching {
        val ours = record.audioSessionId
        val others = audioManager.activeRecordingConfigurations.filter { it.clientAudioSessionId != ours }
        Log.i(TAG, "Silent input: ownSilenced=${record.activeRecordingConfiguration?.isClientSilenced} otherRecorders=${others.size}")
        others.isNotEmpty()
    }.getOrDefault(false)

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

    companion object {
        private const val TAG = "DictationAudioRecorder"
        private const val MAX_RESTARTS = 3

        /** Where recordings live until their transcription succeeds. */
        fun recordingsDir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }

        private fun describe(device: AudioDeviceInfo?): String =
            if (device == null) "default input" else "${device.productName} (type ${device.type})"

        private fun peakOf(bytes: ByteArray, length: Int): Int {
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
