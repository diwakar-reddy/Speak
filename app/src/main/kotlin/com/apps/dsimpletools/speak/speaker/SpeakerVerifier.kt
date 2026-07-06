package com.apps.dsimpletools.speak.speaker

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File

/**
 * App-lifetime singleton (manual, no DI — matches [com.apps.dsimpletools.speak.format.FormatController])
 * that owns the speaker-isolation gate: the CAM++ embedding extractor, the enrolled
 * owner profile, and the persisted gate/threshold settings.
 *
 * The gate answers one question per VAD segment: is this the enrolled owner speaking?
 * If so the segment is decoded; if not it is dropped before the recogniser ever sees it,
 * so nearby voices / TV / cross-talk never turn into inserted text.
 *
 * Fail-safe by construction — the gate can never crash or block dictation:
 *  - Missing model file  -> gate disabled, [SPEAKER_MODEL_MISSING] logged once, all
 *    segments accepted (UNGATED).
 *  - Not enrolled / toggled off -> all segments accepted (UNGATED).
 *  - Embedding compute throws  -> [SPEAKER_ERROR] logged, segment accepted (fail-open).
 *
 * The owner profile is the L2-normalised mean of N per-utterance embeddings. The raw
 * per-utterance embeddings are persisted (see [SpeakerProfileStore]); the mean is rebuilt
 * from them on construction. We compute cosine similarity ourselves (in [SpeakerMath]) so
 * the score is loggable — sherpa-onnx's own [com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager]
 * only returns a boolean and keeps its store in native memory, so it is not used here.
 */
class SpeakerVerifier private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val store = SpeakerProfileStore(appContext.filesDir)

    /** Serialises native extractor access across the decode thread and enrollment threads. */
    private val nativeLock = Any()

    @Volatile private var extractor: SpeakerEmbeddingExtractor? = null
    @Volatile private var modelPresent: Boolean = false
    @Volatile private var loadAttempted: Boolean = false
    @Volatile private var missingLogged: Boolean = false
    @Volatile private var embeddingDim: Int = 0

    @Volatile private var rawEmbeddings: List<FloatArray> = emptyList()
    @Volatile private var profileMean: FloatArray? = null

    init {
        // Rebuild the profile from persisted raw embeddings (cheap file IO, no native).
        rawEmbeddings = store.load()
        profileMean = SpeakerMath.meanEmbedding(rawEmbeddings)
        Log.i(TAG, "SPEAKER_LOADED enrolled=${isEnrolled} utterances=${rawEmbeddings.size}")
    }

    // ---- On-disk model layout (mirrors SherpaAsrEngine's external files models dir) ----
    private val modelsDir: File
        get() = File(appContext.getExternalFilesDir(null), MODELS_SUBDIR)
    private val speakerModelFile: File
        get() = File(modelsDir, SPEAKER_MODEL_FILE)
    private val vadModelFile: File
        get() = File(modelsDir, VAD_FILE)

    // ---- Observable state (for UI / DEBUG_SPEAKER_STATUS) ----
    val isEnrolled: Boolean get() = profileMean != null
    val utteranceCount: Int get() = rawEmbeddings.size
    val isModelFilePresent: Boolean get() = speakerModelFile.exists()
    val isModelLoaded: Boolean get() = modelPresent

    var threshold: Float
        get() = prefs.getFloat(KEY_THRESHOLD, SpeakerPolicy.DEFAULT_THRESHOLD)
        set(value) {
            prefs.edit().putFloat(KEY_THRESHOLD, value).apply()
            Log.i(TAG, "SPEAKER_THRESHOLD set to $value")
        }

    /** The "only my voice" toggle. Persisted; only effective when [isEnrolled]. */
    var gateEnabled: Boolean
        get() = prefs.getBoolean(KEY_GATE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_GATE, value).apply()
            Log.i(TAG, "SPEAKER_GATE set to $value")
        }

    /**
     * Load the native CAM++ extractor from the external files models dir. Idempotent and
     * safe to call from any thread. A missing model file is a graceful no-op: the gate is
     * left disabled and [SPEAKER_MODEL_MISSING] is logged once.
     */
    fun ensureLoaded() {
        if (loadAttempted && (modelPresent || missingLogged)) return
        synchronized(nativeLock) {
            if (loadAttempted && (modelPresent || missingLogged)) return
            loadAttempted = true
            val f = speakerModelFile
            if (!f.exists()) {
                modelPresent = false
                if (!missingLogged) {
                    Log.w(TAG, "SPEAKER_MODEL_MISSING expected=${f.absolutePath}")
                    missingLogged = true
                }
                return
            }
            try {
                val ext = SpeakerEmbeddingExtractor(
                    assetManager = null,
                    config = SpeakerEmbeddingExtractorConfig(
                        model = f.absolutePath,
                        numThreads = 1,
                        debug = false,
                        provider = "cpu"
                    )
                )
                embeddingDim = ext.dim()
                extractor = ext
                modelPresent = true
                Log.i(TAG, "SPEAKER_INIT: dim=$embeddingDim model=${f.name}")
            } catch (t: Throwable) {
                modelPresent = false
                Log.e(TAG, "SPEAKER_INIT_FAILED ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }
    }

    /**
     * Compute the CAM++ embedding for one segment of 16 kHz mono float samples.
     * Serialised on [nativeLock]; returns null (and logs SPEAKER_ERROR) on any failure.
     * The [com.k2fsa.sherpa.onnx.OnlineStream] is single-use and always released.
     */
    fun computeEmbedding(samples: FloatArray): FloatArray? {
        val ext = extractor ?: return null
        if (samples.isEmpty()) return null
        return synchronized(nativeLock) {
            runCatching {
                val stream = ext.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    stream.inputFinished()
                    ext.compute(stream)
                } finally {
                    runCatching { stream.release() }
                }
            }.onFailure {
                Log.e(TAG, "SPEAKER_ERROR compute ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()
        }
    }

    /**
     * Gate one VAD segment. Never throws — a compute failure fails open (accepts). The
     * ungated cases (not enrolled / gate off / model missing) accept without computing an
     * embedding, so there is zero cost when the gate is inactive.
     */
    fun verify(segment: FloatArray, durationSec: Float): SpeakerDecision {
        val enrolled = isEnrolled
        val gate = gateEnabled
        val present = modelPresent
        val mean = profileMean
        if (!enrolled || !gate || !present || mean == null) {
            return SpeakerPolicy.decide(enrolled && mean != null, gate, present, null, durationSec, threshold)
        }
        val emb = computeEmbedding(segment)
        val sim = emb?.let { SpeakerMath.cosine(it, mean) }
        return SpeakerPolicy.decide(enrolled, gate, present, sim, durationSec, threshold)
    }

    // ---- Enrollment ----

    /**
     * Enroll (replace) the owner from a list of already-computed embeddings. Persists the
     * raw embeddings, rebuilds the mean profile, and turns the gate on. Returns true on
     * success (a non-empty, dimensionally-valid profile was built).
     */
    @Synchronized
    fun enrollFromEmbeddings(embeddings: List<FloatArray>): Boolean {
        if (embeddings.isEmpty()) {
            Log.w(TAG, "SPEAKER_ENROLL_FAILED no embeddings")
            return false
        }
        val mean = SpeakerMath.meanEmbedding(embeddings)
        if (mean == null) {
            Log.w(TAG, "SPEAKER_ENROLL_FAILED could not build profile mean")
            return false
        }
        store.save(embeddings)
        rawEmbeddings = embeddings
        profileMean = mean
        gateEnabled = true // default ON after a successful enrollment
        Log.i(TAG, "SPEAKER_ENROLLED utterances=${embeddings.size} gate=on threshold=$threshold")
        return true
    }

    /**
     * Enroll (replace) the owner from a list of trimmed speech segments: compute each
     * segment's embedding then enroll. Loads the model first if needed. Returns true on
     * success.
     */
    @Synchronized
    fun enrollFromSegments(segments: List<FloatArray>): Boolean {
        ensureLoaded()
        if (!modelPresent) {
            Log.w(TAG, "SPEAKER_ENROLL_FAILED model not present")
            return false
        }
        val embeddings = segments.mapNotNull { computeEmbedding(it) }
        return enrollFromEmbeddings(embeddings)
    }

    /** Forget the owner profile. Leaves the gate flag as-is (it is inert while not enrolled). */
    @Synchronized
    fun clear() {
        store.clear()
        rawEmbeddings = emptyList()
        profileMean = null
        Log.i(TAG, "SPEAKER_CLEARED")
    }

    // ---- Debug WAV helpers (routed via DEBUG_* broadcasts) ----

    /**
     * Enroll the owner from WAV files (debug): each WAV is VAD-trimmed and turned into an
     * embedding, exactly like the UI enrollment pipeline, then all are enrolled (replace).
     * Runs on the caller's thread — invoke off the main thread.
     */
    fun enrollFromWavs(paths: List<String>): Boolean {
        ensureLoaded()
        if (!modelPresent) {
            Log.w(TAG, "DEBUG_ENROLL_WAV: model not present, aborting")
            return false
        }
        val vad = SpeakerVad.create(vadModelFile.absolutePath)
        if (vad == null) {
            Log.w(TAG, "DEBUG_ENROLL_WAV: VAD unavailable at ${vadModelFile.absolutePath}")
            return false
        }
        try {
            val embeddings = paths.mapNotNull { path ->
                val wave = runCatching { WaveReader.readWave(filename = path) }.getOrNull()
                if (wave == null) {
                    Log.w(TAG, "DEBUG_ENROLL_WAV: could not read $path")
                    return@mapNotNull null
                }
                val trimmed = vad.trim(wave.samples)
                val netSec = trimmed.size / SAMPLE_RATE.toFloat()
                Log.i(TAG, "DEBUG_ENROLL_WAV: $path netSpeech=${"%.2f".format(netSec)}s")
                if (trimmed.isEmpty()) return@mapNotNull null
                computeEmbedding(trimmed)
            }
            return enrollFromEmbeddings(embeddings)
        } finally {
            vad.release()
        }
    }

    /**
     * Run VAD over a WAV and log per-segment SPEAKER_ACCEPT/REJECT + similarity against the
     * current profile. Does NOT change enrollment. Runs on the caller's thread.
     */
    fun verifyWav(path: String) {
        ensureLoaded()
        val wave = runCatching { WaveReader.readWave(filename = path) }.getOrNull()
        if (wave == null) {
            Log.w(TAG, "DEBUG_VERIFY_WAV: could not read $path")
            return
        }
        if (!isEnrolled) {
            Log.w(TAG, "DEBUG_VERIFY_WAV: not enrolled — every segment would be UNGATED")
        }
        val vad = SpeakerVad.create(vadModelFile.absolutePath)
        if (vad == null) {
            Log.w(TAG, "DEBUG_VERIFY_WAV: VAD unavailable at ${vadModelFile.absolutePath}")
            return
        }
        try {
            val segs = vad.segments(wave.samples)
            Log.i(TAG, "DEBUG_VERIFY_WAV: $path segments=${segs.size}")
            for (seg in segs) {
                val durSec = seg.size / SAMPLE_RATE.toFloat()
                logDecision(verify(seg, durSec), durSec)
            }
        } finally {
            vad.release()
        }
    }

    /** DEBUG_SPEAKER_STATUS: dump the full gate state to logcat. */
    fun logStatus() {
        Log.i(
            TAG,
            "SPEAKER_STATUS enrolled=$isEnrolled utterances=$utteranceCount " +
                "threshold=$threshold gate=$gateEnabled modelFilePresent=$isModelFilePresent " +
                "modelLoaded=$modelPresent dim=$embeddingDim"
        )
    }

    companion object {
        private const val TAG = "Speak.Speaker"
        private const val PREFS = "speak_speaker"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_GATE = "gate_enabled"

        const val MODELS_SUBDIR = "models"
        const val SPEAKER_MODEL_FILE = "wespeaker_en_voxceleb_CAM++_LM.onnx"
        const val VAD_FILE = "silero_vad.onnx"
        private const val SAMPLE_RATE = 16000

        /** Shared log helper so the engine gate and the debug WAV probe log identically. */
        fun logDecision(decision: SpeakerDecision, durationSec: Float) {
            val sim = "%.3f".format(decision.similarity)
            val dur = "%.1f".format(durationSec)
            when (decision.reason) {
                SpeakerReason.ACCEPT -> Log.i(TAG, "SPEAKER_ACCEPT: sim=$sim dur=${dur}s")
                SpeakerReason.REJECT -> Log.i(TAG, "SPEAKER_REJECT: sim=$sim dur=${dur}s")
                SpeakerReason.ERROR -> Log.w(TAG, "SPEAKER_ERROR: fail-open accept dur=${dur}s")
                SpeakerReason.UNGATED -> Unit // no line: ungated decode is the normal path
            }
        }

        @Volatile
        private var instance: SpeakerVerifier? = null

        fun getInstance(context: Context): SpeakerVerifier =
            instance ?: synchronized(this) {
                instance ?: SpeakerVerifier(context).also { instance = it }
            }
    }
}
