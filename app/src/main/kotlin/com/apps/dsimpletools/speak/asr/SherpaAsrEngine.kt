package com.apps.dsimpletools.speak.asr

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
        return SherpaAsrSession()
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
        val wave = runCatching { WaveReader.readWave(filename = path) }.getOrNull()
            ?: run {
                Log.e(TAG, "ASR: could not read WAV at $path")
                return ""
            }
        if (wave.sampleRate != SAMPLE_RATE) {
            Log.w(TAG, "ASR: WAV sampleRate=${wave.sampleRate} (expected $SAMPLE_RATE); feeding as-is")
        }
        val session = SherpaAsrSession()
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
    private inner class SherpaAsrSession : AsrSession {

        private val audioChannel = Channel<FloatArray>(capacity = Channel.UNLIMITED)
        private val transcript = StringBuilder()

        @Volatile
        override var finalizedText: String = ""
            private set

        // Rolling remainder of samples that didn't fill a full VAD window yet.
        private var carry = FloatArray(0)
        private var segmentIndex = 0
        private var totalAudioSamples = 0L
        private var totalDecodeMs = 0L

        private val worker: Job = engineScope.launch {
            val localVad = vad ?: return@launch
            localVad.reset()
            for (chunk in audioChannel) {
                processSamples(localVad, chunk)
            }
            // Channel closed by finish(): flush any open segment + the sub-window tail.
            flushTail(localVad)
        }

        override fun acceptAudio(frame: ShortArray, length: Int) {
            val floats = FloatArray(length)
            for (i in 0 until length) floats[i] = frame[i] / 32768f
            // trySend never blocks on an UNLIMITED channel; safe from the capture thread.
            audioChannel.trySend(floats)
        }

        /** Feed float samples directly (WAV debug path). */
        fun acceptFloat(samples: FloatArray) {
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
            val t0 = SystemClock.elapsedRealtime()
            val stream = rec.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            var text = rec.getResult(stream).text.trim()
            stream.release()
            if (ENABLE_PUNCTUATION && text.isNotEmpty()) {
                punctuation?.let { text = it.addPunctuation(text).trim() }
            }
            totalDecodeMs += SystemClock.elapsedRealtime() - t0
            if (text.isEmpty()) return
            segmentIndex++
            if (transcript.isNotEmpty()) transcript.append(' ')
            transcript.append(text)
            finalizedText = transcript.toString()
            Log.i(TAG, "ASR_SEGMENT: $segmentIndex ${durMs}ms -> \"$text\"")
        }

        override suspend fun finish(): String {
            audioChannel.close()
            worker.join()
            val audioMs = totalAudioSamples * 1000L / SAMPLE_RATE
            val rtf = if (audioMs > 0) totalDecodeMs.toDouble() / audioMs else 0.0
            Log.i(TAG, "ASR_TIMING: audioMs=$audioMs decodeMs=$totalDecodeMs rtf=${"%.3f".format(rtf)}")
            val full = transcript.toString()
            Log.i(TAG, "ASR_FINAL: \"$full\"")
            return full
        }
    }

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
