package com.apps.dsimpletools.speak.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.apps.dsimpletools.speak.R
import com.apps.dsimpletools.speak.accessibility.DictationAccessibilityService
import com.apps.dsimpletools.speak.asr.AsrEngine
import com.apps.dsimpletools.speak.asr.AsrInitResult
import com.apps.dsimpletools.speak.asr.AsrSession
import com.apps.dsimpletools.speak.asr.SherpaAsrEngine
import com.apps.dsimpletools.speak.format.FormatController
import com.apps.dsimpletools.speak.insert.InsertMode
import com.apps.dsimpletools.speak.insert.InsertResult
import com.apps.dsimpletools.speak.insert.TextInserter
import com.apps.dsimpletools.speak.overlay.BubbleController
import com.apps.dsimpletools.speak.overlay.BubbleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Bubble-tap-driven dictation. On the first tap it self-promotes the accessibility
 * service to a microphone foreground service, starts an [AudioRecord] capture thread,
 * and pipes the PCM into an [AsrEngine] session. On the second tap it stops capture,
 * finalises the transcript off the main thread, and inserts it via [TextInserter].
 *
 * The engine is loaded once (warmed up on service connect) and kept resident across
 * dictations; it is released only when the accessibility service is torn down.
 */
class CaptureController(
    private val service: DictationAccessibilityService,
    private val bubbleController: BubbleController,
    private val textInserter: TextInserter,
    private val asrEngine: AsrEngine = SherpaAsrEngine(service)
) {
    enum class State { IDLE, LISTENING, PROCESSING }

    /** Where engine loading currently sits; drives the toast shown on a first tap. */
    private enum class AsrPhase { NOT_STARTED, LOADING, READY, MODELS_MISSING, ERROR }

    @Volatile
    var state: State = State.IDLE
        private set

    private var audioRecord: AudioRecord? = null

    @Volatile
    private var keepReading = false
    private var readThread: Thread? = null
    private var notificationChannelReady = false

    @Volatile
    private var session: AsrSession? = null

    @Volatile
    private var asrPhase = AsrPhase.NOT_STARTED

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Kick off model loading in the background. Called from the service's
     * onServiceConnected so the engine is (usually) ready by the first bubble tap;
     * the service is a long-lived singleton, making it the natural owner of a
     * ~1 GB-resident engine that must survive between dictations. Idempotent.
     */
    fun warmUpAsr() {
        if (asrPhase == AsrPhase.LOADING || asrPhase == AsrPhase.READY) return
        asrPhase = AsrPhase.LOADING
        Log.i(TAG, "CAPTURE: ASR warm-up starting")
        scope.launch {
            asrPhase = phaseFor(asrEngine.initialize())
            Log.i(TAG, "CAPTURE: ASR warm-up finished phase=$asrPhase")
        }
    }

    fun onBubbleTapped() {
        Log.i(TAG, "CAPTURE: bubble tapped, state=$state")
        when (state) {
            State.IDLE -> startCapture()
            State.LISTENING -> stopCaptureAndInsert()
            State.PROCESSING -> Log.d(TAG, "CAPTURE: tap ignored while processing")
        }
    }

    /** Hard-stop used when the accessibility service is being torn down mid-capture. */
    fun forceStopAndReset(reason: String) {
        Log.w(TAG, "CAPTURE: force stop ($reason), previous state=$state")
        keepReading = false
        runCatching { readThread?.join(300) }
        readThread = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        session = null
        runCatching { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        // Free the ~1 GB engine; teardown is the only caller, so this is the right place.
        runCatching { asrEngine.release() }
        asrPhase = AsrPhase.NOT_STARTED
        state = State.IDLE
        bubbleController.setBubbleState(BubbleState.IDLE)
    }

    private fun startCapture() {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "CAPTURE: RECORD_AUDIO permission not granted, aborting")
            toast("Speak: microphone permission needed")
            return
        }

        when (asrPhase) {
            AsrPhase.READY -> Unit // fall through and start recording
            AsrPhase.NOT_STARTED -> {
                warmUpAsr()
                toast("Speak: still loading models")
                return
            }
            AsrPhase.LOADING -> {
                toast("Speak: still loading models")
                return
            }
            AsrPhase.MODELS_MISSING -> {
                val path = (asrEngine as? SherpaAsrEngine)?.modelsDir?.absolutePath
                Log.w(TAG, "MODELS_MISSING (bubble tap) expected=$path")
                toast("Speak: ASR models not installed")
                return
            }
            AsrPhase.ERROR -> {
                toast("Speak: ASR failed to load")
                return
            }
        }

        val newSession = runCatching { asrEngine.startSession() }.getOrElse {
            Log.e(TAG, "CAPTURE: startSession failed ${it.javaClass.simpleName}: ${it.message}")
            toast("Speak: ASR not ready")
            return
        }

        ensureNotificationChannel()
        try {
            service.startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            Log.i(TAG, "FGS_SPIKE_RESULT: OK")
        } catch (e: Exception) {
            Log.e(TAG, "FGS_SPIKE_RESULT: FAIL ${e.javaClass.simpleName}: ${e.message}")
            bubbleController.setBubbleState(BubbleState.IDLE)
            return
        }

        session = newSession
        if (!startAudioRecord()) {
            Log.e(TAG, "CAPTURE: AudioRecord failed to start, rolling back foreground state")
            session = null
            runCatching { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) }
            bubbleController.setBubbleState(BubbleState.IDLE)
            return
        }

        state = State.LISTENING
        bubbleController.setBubbleState(BubbleState.LISTENING)
    }

    private fun startAudioRecord(): Boolean {
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                Log.e(TAG, "CAPTURE: getMinBufferSize returned $minBuffer")
                return false
            }
            val bufferSize = minBuffer * 4
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "CAPTURE: AudioRecord not initialized, state=${record.state}")
                record.release()
                return false
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "CAPTURE: AudioRecord did not enter RECORDING state")
                record.release()
                return false
            }
            audioRecord = record
            keepReading = true
            readThread = thread(name = "speak-mic-read") { readLoop(record, bufferSize) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "CAPTURE: AudioRecord exception ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun readLoop(record: AudioRecord, bufferSize: Int) {
        val buffer = ShortArray(bufferSize / 2)
        var lastLogAt = 0L
        while (keepReading) {
            val n = record.read(buffer, 0, buffer.size)
            if (n > 0) {
                // Non-blocking hand-off; recognition happens on the engine's own thread.
                session?.acceptAudio(buffer, n)
                val now = SystemClock.elapsedRealtime()
                if (now - lastLogAt >= 500L) {
                    Log.i(TAG, "MIC_RMS: ${computeRms(buffer, n)}")
                    lastLogAt = now
                }
            } else if (n < 0) {
                Log.e(TAG, "CAPTURE: AudioRecord.read returned error $n")
                break
            }
        }
    }

    private fun computeRms(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / length)
    }

    private fun stopCaptureAndInsert() {
        state = State.PROCESSING
        bubbleController.setBubbleState(BubbleState.PROCESSING)

        keepReading = false
        val thread = readThread
        readThread = null
        val record = audioRecord
        audioRecord = null
        val activeSession = session
        session = null

        scope.launch {
            val transcript = withContext(Dispatchers.Default) {
                runCatching { thread?.join(500) }
                runCatching { record?.stop() }
                runCatching { record?.release() }
                // finish() flushes the VAD + decodes the tail on the engine's decode thread.
                runCatching { activeSession?.finish() }.getOrDefault("").orEmpty()
            }
            runCatching { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) }

            if (transcript.isBlank()) {
                Log.i(TAG, "CAPTURE: empty transcript (no speech), skipping insert")
                toast("Speak: no speech detected")
            } else {
                // Step 2: clean the raw transcript (rules + optional on-device LLM) before
                // it is handed to the inserter. The pipeline never throws and falls back to
                // the raw text, so this can't break the dictation flow. Insertion glue
                // (space between existing field text and the new text) lives in the inserter.
                val formatted = FormatController.getInstance(service).format(transcript)
                when (val result = textInserter.insert(formatted, InsertMode.APPEND)) {
                    is InsertResult.Verified -> Log.i(TAG, "CAPTURE: insert verified")
                    is InsertResult.Mismatch -> Log.w(TAG, "CAPTURE: insert mismatch, actual='${result.actual}'")
                    is InsertResult.Failed -> Log.e(TAG, "CAPTURE: insert failed, reason=${result.reason}")
                }
            }

            state = State.IDLE
            bubbleController.setBubbleState(BubbleState.IDLE)
        }
    }

    /**
     * Debug-only: run the full VAD -> ASR -> (punct) pipeline over a WAV file on disk
     * (no mic, no FGS). Ensures the engine is loaded first. ASR_FINAL is logged by the
     * engine. Invoked via the DEBUG_TRANSCRIBE_WAV broadcast.
     */
    fun debugTranscribeWav(path: String) {
        scope.launch {
            if (!asrEngine.isReady) {
                Log.i(TAG, "CAPTURE: debug transcribe - engine not ready, initialising")
                asrPhase = phaseFor(asrEngine.initialize())
                if (asrPhase != AsrPhase.READY) {
                    Log.e(TAG, "CAPTURE: debug transcribe aborted, phase=$asrPhase")
                    return@launch
                }
            }
            Log.i(TAG, "CAPTURE: debug transcribe wav path=$path")
            withContext(Dispatchers.Default) {
                runCatching { (asrEngine as SherpaAsrEngine).transcribeWav(path) }
                    .onFailure { Log.e(TAG, "CAPTURE: debug transcribe failed", it) }
            }
        }
    }

    private fun phaseFor(result: AsrInitResult): AsrPhase = when (result) {
        is AsrInitResult.Ready -> AsrPhase.READY
        is AsrInitResult.ModelsMissing -> AsrPhase.MODELS_MISSING
        is AsrInitResult.Error -> AsrPhase.ERROR
    }

    private fun toast(message: String) {
        // All callers are on the main thread (bubble tap / debug broadcast onReceive).
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
    }

    private fun ensureNotificationChannel() {
        if (notificationChannelReady) return
        val manager = service.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Speak dictation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while Speak is actively listening to the microphone"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        notificationChannelReady = true
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle("Speak is listening")
            .setContentText("Tap the bubble to stop and insert text")
            .setSmallIcon(R.drawable.ic_mic_glyph)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "Speak.Capture"
        private const val CHANNEL_ID = "speak_dictation"
        private const val NOTIF_ID = 42
        private const val SAMPLE_RATE_HZ = 16000
    }
}
