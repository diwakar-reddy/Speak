package com.apps.dsimpletools.speak.speaker

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.util.Log
import com.apps.dsimpletools.speak.capture.AudioEffects
import java.io.File
import kotlin.concurrent.thread

/** Result of one enrollment utterance capture: the VAD-trimmed speech and its net length. */
data class UtteranceResult(
    val samples: FloatArray,
    val netSpeechSec: Float
)

/**
 * Records one enrollment utterance from the microphone and trims it to net speech.
 *
 * Uses the same capture configuration as live dictation (VOICE_RECOGNITION, 16 kHz mono
 * PCM16) and attaches the same [AudioEffects] (NoiseSuppressor / AEC). On stop the raw
 * audio is run through a fresh Silero VAD ([SpeakerVad]) and only the detected speech is
 * returned, so leading/trailing/inter-sentence silence does not pollute the voiceprint.
 *
 * Not thread-confined to the main thread: [start] is quick, but call [stopAndTrim] off the
 * main thread (it joins the read thread and runs native VAD).
 */
class EnrollmentRecorder(context: Context) {

    private val appContext = context.applicationContext

    private var record: AudioRecord? = null
    private var effects: List<AudioEffect> = emptyList()

    @Volatile private var keepReading = false
    private var readThread: Thread? = null

    private val lock = Any()
    private val chunks = ArrayList<FloatArray>()

    /** Open + start the recorder. Returns false (logged) if the mic could not be opened. */
    fun start(): Boolean {
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                Log.e(TAG, "ENROLL: getMinBufferSize returned $minBuffer")
                return false
            }
            val bufferSize = minBuffer * 4
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "ENROLL: AudioRecord not initialized, state=${rec.state}")
                rec.release()
                return false
            }
            effects = AudioEffects.attach(rec.audioSessionId)
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "ENROLL: AudioRecord did not enter RECORDING state")
                AudioEffects.release(effects)
                effects = emptyList()
                rec.release()
                return false
            }
            synchronized(lock) { chunks.clear() }
            record = rec
            keepReading = true
            readThread = thread(name = "speak-enroll-read") { readLoop(rec, bufferSize) }
            Log.i(TAG, "ENROLL: recording started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ENROLL: start exception ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun readLoop(rec: AudioRecord, bufferSize: Int) {
        val buffer = ShortArray(bufferSize / 2)
        while (keepReading) {
            val n = rec.read(buffer, 0, buffer.size)
            if (n > 0) {
                val floats = FloatArray(n)
                for (i in 0 until n) floats[i] = buffer[i] / 32768f
                synchronized(lock) { chunks.add(floats) }
            } else if (n < 0) {
                Log.e(TAG, "ENROLL: AudioRecord.read error $n")
                break
            }
        }
    }

    /**
     * Stop recording, release the mic + effects, and return the VAD-trimmed net speech.
     * Blocking / native — call off the main thread. Safe to call once per [start].
     */
    fun stopAndTrim(): UtteranceResult {
        keepReading = false
        runCatching { readThread?.join(500) }
        readThread = null
        val rec = record
        record = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        AudioEffects.release(effects)
        effects = emptyList()

        val raw = synchronized(lock) {
            val total = chunks.sumOf { it.size }
            val out = FloatArray(total)
            var pos = 0
            for (c in chunks) {
                System.arraycopy(c, 0, out, pos, c.size)
                pos += c.size
            }
            chunks.clear()
            out
        }

        val vadPath = File(
            File(appContext.getExternalFilesDir(null), SpeakerVerifier.MODELS_SUBDIR),
            SpeakerVerifier.VAD_FILE
        ).absolutePath
        val vad = SpeakerVad.create(vadPath)
        val trimmed = if (vad != null) {
            try {
                vad.trim(raw)
            } finally {
                vad.release()
            }
        } else {
            // No VAD available: fall back to the raw audio rather than failing enrollment.
            Log.w(TAG, "ENROLL: VAD unavailable at $vadPath; using untrimmed audio")
            raw
        }
        val netSec = trimmed.size / SAMPLE_RATE.toFloat()
        Log.i(TAG, "ENROLL: utterance netSpeech=${"%.2f".format(netSec)}s (raw=${"%.2f".format(raw.size / SAMPLE_RATE.toFloat())}s)")
        return UtteranceResult(trimmed, netSec)
    }

    /** Abort without processing (e.g. dialog dismissed). */
    fun cancel() {
        keepReading = false
        runCatching { readThread?.join(300) }
        readThread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        AudioEffects.release(effects)
        effects = emptyList()
        synchronized(lock) { chunks.clear() }
    }

    companion object {
        private const val TAG = "Speak.Enroll"
        private const val SAMPLE_RATE = 16000
    }
}
