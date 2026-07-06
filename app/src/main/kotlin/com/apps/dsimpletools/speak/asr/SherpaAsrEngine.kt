package com.apps.dsimpletools.speak.asr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.apps.dsimpletools.speak.speaker.SessionGatePolicy
import com.apps.dsimpletools.speak.speaker.SpeakerDecision
import com.apps.dsimpletools.speak.speaker.SpeakerReason
import com.apps.dsimpletools.speak.speaker.SpeakerVerifier
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuation
import com.k2fsa.sherpa.onnx.OnlinePunctuationConfig
import com.k2fsa.sherpa.onnx.OnlinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * sherpa-onnx-backed [AsrEngine]:
 *   Silero VAD segments the incoming stream; each closed speech segment is decoded
 *   by the NeMo Parakeet-TDT-0.6b-v2 (int8) offline transducer; segments accumulate
 *   in order. All decode work is serialised onto one dedicated thread.
 *
 * Models are loaded from external storage (see [modelsDir]) rather than APK assets
 * because the int8 encoder alone is 622 MB. Missing files are a graceful,
 * non-crashing failure ([AsrInitResult.ModelsMissing]).
 */
class SherpaAsrEngine(context: Context) : AsrEngine {

    private val appContext = context.applicationContext

    @Volatile
    override var isReady: Boolean = false
        private set

    // Single decode thread: recogniser/VAD/punctuation are all stateful native
    // objects and are only ever touched from here, so no extra locking is needed.
    private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "speak-asr-decode")
    }
    private val decodeDispatcher = decodeExecutor.asCoroutineDispatcher()
    private val engineScope = CoroutineScope(SupervisorJob() + decodeDispatcher)

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var punctuation: OnlinePunctuation? = null

    // Speaker-isolation gate (Step 3). Shared app-lifetime singleton; loaded on the decode
    // thread during initialize(). Gates each VAD segment before decode — see decodeSegment().
    private val speakerVerifier = SpeakerVerifier.getInstance(appContext)

    // Single-flight session ownership. The recogniser/VAD are stateful native objects
    // shared by every session; allowing two sessions (e.g. a live dictation and the
    // DEBUG_TRANSCRIBE_WAV hook) to touch them concurrently corrupts the VAD circular
    // buffer -> "Invalid n" -> a decode with a {0,128} shape -> a native FATAL that
    // killed the whole process. Exactly one session may own the engine at a time; any
    // second acquire is rejected (ASR_BUSY) and becomes an inert no-op that never
    // touches native state. Uses object identity so a rejected session can never
    // release the owner's lock.
    private val sessionOwner = AtomicReference<SherpaAsrSession?>(null)

    /** Absolute path of the models root we expect on disk (used for logging + toasts). */
    val modelsDir: File
        get() = File(appContext.getExternalFilesDir(null), MODELS_SUBDIR)

    override suspend fun initialize(): AsrInitResult = withContext(decodeDispatcher) {
        if (isReady) return@withContext AsrInitResult.Ready

        val root = modelsDir
        val encoder = File(root, "$PARAKEET_DIR/encoder.int8.onnx")
        val decoder = File(root, "$PARAKEET_DIR/decoder.int8.onnx")
        val joiner = File(root, "$PARAKEET_DIR/joiner.int8.onnx")
        val tokens = File(root, "$PARAKEET_DIR/tokens.txt")
        val vadModel = File(root, VAD_FILE)

        val required = listOf(encoder, decoder, joiner, tokens, vadModel)
        val missing = required.filterNot { it.exists() }.map { it.absolutePath }
        if (missing.isNotEmpty()) {
            Log.e(TAG, "MODELS_MISSING expected=${root.absolutePath} missing=$missing")
            return@withContext AsrInitResult.ModelsMissing(root.absolutePath, missing)
        }

        val startedAt = SystemClock.elapsedRealtime()
        try {
            val recognizerConfig = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = ASR_NUM_THREADS,
                    provider = PROVIDER,
                    // Parakeet-TDT is a NeMo transducer; this string picks the right decode graph.
                    modelType = "nemo_transducer"
                ),
                decodingMethod = "greedy_search"
            )
            // assetManager = null -> load from filesystem paths (newFromFile).
            recognizer = OfflineRecognizer(assetManager = null, config = recognizerConfig)

            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadModel.absolutePath,
                    threshold = VAD_THRESHOLD,
                    minSilenceDuration = VAD_MIN_SILENCE_S,
                    minSpeechDuration = VAD_MIN_SPEECH_S,
                    windowSize = VAD_WINDOW_SIZE,
                    maxSpeechDuration = VAD_MAX_SPEECH_S
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = PROVIDER
            )
            vad = Vad(assetManager = null, config = vadConfig)

            // Punctuation model. Parakeet-TDT-v2 emits punctuation + casing natively, so
            // this is OFF by default (ENABLE_PUNCTUATION). The path is kept fully wired so
            // it can be flipped on for models that produce bare lowercase text.
            if (ENABLE_PUNCTUATION) {
                val punctModel = File(root, "$PUNCT_DIR/model.int8.onnx")
                val bpe = File(root, "$PUNCT_DIR/bpe.vocab")
                if (punctModel.exists() && bpe.exists()) {
                    punctuation = OnlinePunctuation(
                        assetManager = null,
                        config = OnlinePunctuationConfig(
                            model = OnlinePunctuationModelConfig(
                                cnnBilstm = punctModel.absolutePath,
                                bpeVocab = bpe.absolutePath,
                                numThreads = 1,
                                provider = PROVIDER
                            )
                        )
                    )
                } else {
                    Log.w(TAG, "ASR: punctuation enabled but model missing at ${punctModel.absolutePath}")
                }
            }

            // Load the speaker-isolation extractor on this same decode thread. A missing
            // model is a graceful no-op (gate stays disabled); it never fails ASR init.
            runCatching { speakerVerifier.ensureLoaded() }

            isReady = true
            val ms = SystemClock.elapsedRealtime() - startedAt
            Log.i(TAG, "ASR_INIT: $ms")
            AsrInitResult.Ready
        } catch (t: Throwable) {
            Log.e(TAG, "ASR: initialize failed", t)
            runCatching { recognizer?.release() }
            runCatching { vad?.release() }
            runCatching { punctuation?.release() }
            recognizer = null
            vad = null
            punctuation = null
            isReady = false
            AsrInitResult.Error(t.message ?: t.javaClass.simpleName, t)
        }
    }

    override fun startSession(): AsrSession {
        check(isReady) { "startSession() called before engine is ready" }
        val session = SherpaAsrSession(owner = "live")
        if (!session.acquired) {
            // A debug/other session is already running. Return the inert session so the
            // caller degrades to an empty transcript rather than corrupting native state.
            Log.w(TAG, "ASR_BUSY: startSession rejected, another session is already active")
        }
        return session
    }

    override fun release() {
        isReady = false
        engineScope.cancel()
        // Native teardown must run on the decode thread (same thread that owns the
        // pointers). Queued after any in-flight decode because the executor is serial.
        decodeExecutor.execute {
            runCatching { recognizer?.release() }
            runCatching { vad?.release() }
            runCatching { punctuation?.release() }
            recognizer = null
            vad = null
            punctuation = null
        }
        decodeExecutor.shutdown()
        Log.i(TAG, "ASR: engine released")
    }

    /**
     * Debug helper (used by the DEBUG_TRANSCRIBE_WAV hook): runs the full
     * VAD -> ASR -> (punct) pipeline over a WAV file exactly as if the samples had
     * arrived from the mic, and returns/logs the final transcript. No microphone
     * or foreground service involved.
     */
    suspend fun transcribeWav(path: String): String {
        check(isReady) { "transcribeWav() called before engine is ready" }
        // Acquire single-flight ownership BEFORE touching any native state. If a live
        // dictation session is running, reject the debug hook outright: it must never
        // steal or corrupt the live session's VAD/decoder.
        val session = SherpaAsrSession(owner = "debug-wav")
        if (!session.acquired) {
            Log.w(TAG, "ASR_BUSY: DEBUG_TRANSCRIBE_WAV rejected, a live session is active")
            return ""
        }
        val wave = runCatching { WaveReader.readWave(filename = path) }.getOrNull()
        if (wave == null) {
            Log.e(TAG, "ASR: could not read WAV at $path")
            // finish() closes the (empty) channel so the worker exits and releases ownership.
            return session.finish()
        }
        if (wave.sampleRate != SAMPLE_RATE) {
            Log.w(TAG, "ASR: WAV sampleRate=${wave.sampleRate} (expected $SAMPLE_RATE); feeding as-is")
        }
        val samples = wave.samples
        var i = 0
        while (i < samples.size) {
            val end = min(i + FEED_CHUNK_SAMPLES, samples.size)
            session.acceptFloat(samples.copyOfRange(i, end))
            i = end
        }
        return session.finish()
    }

    /**
     * One dictation's streaming recognition. PCM frames land in [audioChannel] via a
     * non-blocking [acceptAudio]; a single worker coroutine on the decode thread pulls
     * them, converts to float, windows them through the VAD, and decodes each closed
     * speech segment.
     */
    private inner class SherpaAsrSession(private val owner: String) : AsrSession {

        // Single-flight guard: claim engine ownership up front, using object identity so
        // a rejected session can never accidentally release the real owner's lock. An
        // unacquired session is fully inert (acceptAudio/finish no-op) and never launches
        // a worker, so it can never touch the shared native VAD/recogniser.
        val acquired: Boolean = sessionOwner.compareAndSet(null, this)

        private val audioChannel = Channel<FloatArray>(capacity = Channel.UNLIMITED)

        // Decoded VAD segments, in decode (= original) order. `held` short segments are withheld
        // from the transcript until finish(), where SessionGatePolicy decides their fate; non-held
        // segments (accepted-long / ungated / fail-open) are visible immediately.
        private val decoded = ArrayList<Decoded>()

        // Speaker session tally (only meaningful when the gate is active). Long = a segment the
        // embedding gate could judge (>= MIN_GATED_SEGMENT_SEC); short = a bypass-held segment.
        private var longAccepted = 0
        private var longRejected = 0

        @Volatile
        override var finalizedText: String = ""
            private set

        // Rolling remainder of samples that didn't fill a full VAD window yet.
        private var carry = FloatArray(0)
        private var segmentIndex = 0
        private var totalAudioSamples = 0L
        private var totalDecodeMs = 0L

        /** Transcript visible mid-session: only non-held segments, in order. */
        private fun immediateText(): String =
            decoded.asSequence().filterNot { it.held }.joinToString(" ") { it.text }

        private val worker: Job? = if (acquired) engineScope.launch { runWorker() } else null

        private suspend fun runWorker() {
            try {
                val localVad = vad ?: run {
                    Log.w(TAG, "ASR_ERROR: worker started with null VAD (owner=$owner)")
                    return
                }
                // Reset the VAD at session start so a crashed/abandoned prior session
                // can't poison this one with stale circular-buffer state.
                localVad.reset()
                for (chunk in audioChannel) {
                    processSamples(localVad, chunk)
                }
                // Channel closed by finish(): flush any open segment + the sub-window tail.
                flushTail(localVad)
            } catch (c: kotlinx.coroutines.CancellationException) {
                // Engine release()/teardown cancels the worker; propagate so structured
                // concurrency stays correct (the finally still releases ownership).
                throw c
            } catch (t: Throwable) {
                // A native/decode failure fails THIS session gracefully. It must never
                // propagate off the decode thread and kill the whole process (the old
                // concurrency crash). The transcript accumulated so far is still returned.
                Log.e(
                    TAG,
                    "ASR_ERROR: decode worker failed (owner=$owner) " +
                        "${t.javaClass.simpleName}: ${t.message}",
                    t
                )
            } finally {
                releaseOwnership()
            }
        }

        private fun releaseOwnership() {
            if (sessionOwner.compareAndSet(this, null)) {
                Log.d(TAG, "ASR: session ownership released (owner=$owner)")
            }
        }

        override fun acceptAudio(frame: ShortArray, length: Int) {
            if (!acquired) return
            val floats = FloatArray(length)
            for (i in 0 until length) floats[i] = frame[i] / 32768f
            // trySend never blocks on an UNLIMITED channel; safe from the capture thread.
            audioChannel.trySend(floats)
        }

        /** Feed float samples directly (WAV debug path). */
        fun acceptFloat(samples: FloatArray) {
            if (!acquired) return
            audioChannel.trySend(samples)
        }

        private fun processSamples(localVad: Vad, incoming: FloatArray) {
            totalAudioSamples += incoming.size
            val buf = if (carry.isEmpty()) {
                incoming
            } else {
                FloatArray(carry.size + incoming.size).also {
                    System.arraycopy(carry, 0, it, 0, carry.size)
                    System.arraycopy(incoming, 0, it, carry.size, incoming.size)
                }
            }
            var offset = 0
            while (offset + VAD_WINDOW_SIZE <= buf.size) {
                localVad.acceptWaveform(buf.copyOfRange(offset, offset + VAD_WINDOW_SIZE))
                offset += VAD_WINDOW_SIZE
                drainSegments(localVad)
            }
            carry = if (offset == buf.size) FloatArray(0) else buf.copyOfRange(offset, buf.size)
        }

        private fun flushTail(localVad: Vad) {
            if (carry.isNotEmpty()) {
                // Pad the final partial window with zeros so the VAD sees the last ~<32 ms.
                localVad.acceptWaveform(carry.copyOf(VAD_WINDOW_SIZE))
                carry = FloatArray(0)
            }
            localVad.flush()
            drainSegments(localVad)
        }

        private fun drainSegments(localVad: Vad) {
            while (!localVad.empty()) {
                decodeSegment(localVad.front().samples)
                localVad.pop()
            }
        }

        private fun decodeSegment(samples: FloatArray) {
            val rec = recognizer ?: return
            val durMs = samples.size * 1000L / SAMPLE_RATE
            val durSec = samples.size.toFloat() / SAMPLE_RATE

            // Speaker gate: decide whether/how this segment is gated BEFORE decode. Long segments
            // (>= MIN_GATED_SEGMENT_SEC) are gated by similarity (ACCEPT/REJECT); short segments
            // are decoded but HELD (BYPASS) since CAM++ can't judge them; ungated/fail-open decode
            // and append normally. verify() never throws (compute failure fails open); the extra
            // runCatching is a belt-and-braces guard so a gate exception can never kill the worker.
            val decision = runCatching { speakerVerifier.verify(samples, durSec) }
                .getOrElse {
                    Log.e(TAG, "SPEAKER_ERROR: gate threw ${it.javaClass.simpleName}: ${it.message} -> fail-open accept")
                    SpeakerDecision(accepted = true, held = false, similarity = 0f, reason = SpeakerReason.ERROR)
                }
            SpeakerVerifier.logDecision(decision, durSec)
            when (decision.reason) {
                SpeakerReason.REJECT -> {
                    // Rejected long segment: drop before the recogniser ever sees it.
                    longRejected++
                    return
                }
                SpeakerReason.ACCEPT -> longAccepted++
                else -> Unit // BYPASS (held) / UNGATED / ERROR: decode below
            }
            if (!decision.accepted) return // defensive: any non-accepted reason is not decoded

            val t0 = SystemClock.elapsedRealtime()
            val stream = rec.createStream()
            // Wrap the native decode so the stream is always released even if decode
            // throws; the throw then propagates to runWorker() which fails the session
            // gracefully instead of crashing the process.
            var text = try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                rec.getResult(stream).text.trim()
            } finally {
                runCatching { stream.release() }
            }
            if (ENABLE_PUNCTUATION && text.isNotEmpty()) {
                punctuation?.let { text = it.addPunctuation(text).trim() }
            }
            totalDecodeMs += SystemClock.elapsedRealtime() - t0
            if (text.isEmpty()) return
            segmentIndex++
            val held = decision.held
            decoded.add(Decoded(order = segmentIndex, text = text, held = held))
            // Held (short-bypass) text is withheld from finalizedText until finish() resolves it.
            if (!held) finalizedText = immediateText()
            Log.i(TAG, "ASR_SEGMENT: $segmentIndex ${durMs}ms -> \"$text\"${if (held) " (held)" else ""}")
        }

        override suspend fun finish(): String {
            if (!acquired) return ""
            audioChannel.close()
            worker?.join()
            val audioMs = totalAudioSamples * 1000L / SAMPLE_RATE
            val rtf = if (audioMs > 0) totalDecodeMs.toDouble() / audioMs else 0.0
            Log.i(TAG, "ASR_TIMING: audioMs=$audioMs decodeMs=$totalDecodeMs rtf=${"%.3f".format(rtf)}")

            // Resolve held short segments. They ride along if the owner clearly spoke (>=1 long
            // accepted) or the session had no long segments at all (deliberate single-word tap);
            // they are dropped only when long segments were present and ALL were rejected.
            val shortHeld = decoded.count { it.held }
            val includeHeld = SessionGatePolicy.includeHeldShortSegments(longAccepted, longRejected)
            if (shortHeld > 0 || longAccepted > 0 || longRejected > 0) {
                Log.i(
                    TAG,
                    "SPEAKER_SESSION: longAccepted=$longAccepted longRejected=$longRejected " +
                        "shortHeld=$shortHeld -> ${if (includeHeld) "included" else "dropped"}"
                )
            }
            val full = decoded
                .filter { !it.held || includeHeld }
                .joinToString(" ") { it.text }
            finalizedText = full
            Log.i(TAG, "ASR_FINAL: \"$full\"")
            return full
        }
    }

    /** One decoded VAD segment in original order; [held] short segments are resolved at finish(). */
    private data class Decoded(val order: Int, val text: String, val held: Boolean)

    companion object {
        private const val TAG = "Speak.Asr"

        // ---- On-disk layout (mirrors the repo-root models/ directory names) ----
        const val MODELS_SUBDIR = "models"
        const val PARAKEET_DIR = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
        const val VAD_FILE = "silero_vad.onnx"
        const val PUNCT_DIR = "sherpa-onnx-online-punct-en-2024-08-06"

        // ---- Recogniser tuning ----
        private const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
        private const val ASR_NUM_THREADS = 4
        private const val PROVIDER = "cpu"

        // Parakeet-TDT-v2 already emits punctuation + capitalisation, so the separate
        // punctuation model is off by default. Flip to true only for bare-text models.
        private const val ENABLE_PUNCTUATION = false

        // ---- Silero VAD tuning (windowSize MUST be 512 for silero at 16 kHz) ----
        private const val VAD_WINDOW_SIZE = 512
        private const val VAD_THRESHOLD = 0.5f
        private const val VAD_MIN_SILENCE_S = 0.5f
        private const val VAD_MIN_SPEECH_S = 0.25f
        private const val VAD_MAX_SPEECH_S = 20.0f

        // Debug WAV feed granularity (0.2 s at 16 kHz).
        private const val FEED_CHUNK_SAMPLES = 3200
    }
}
