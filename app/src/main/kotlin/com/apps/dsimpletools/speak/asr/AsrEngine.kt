package com.apps.dsimpletools.speak.asr

/**
 * Outcome of [AsrEngine.initialize]. The engine deliberately never throws out of
 * initialize(): a missing-models situation is an expected, recoverable state that
 * the UI turns into a toast, not a crash.
 */
sealed interface AsrInitResult {
    /** Models found and loaded; [AsrEngine.isReady] is now true. */
    data object Ready : AsrInitResult

    /**
     * One or more required model files were not present under [expectedModelsPath].
     * The bubble tap surfaces this as "Speak: ASR models not installed".
     */
    data class ModelsMissing(
        val expectedModelsPath: String,
        val missing: List<String>
    ) : AsrInitResult

    /** Models were present but loading/initialising the native pipeline failed. */
    data class Error(val message: String, val cause: Throwable? = null) : AsrInitResult
}

/**
 * A single dictation's worth of streaming recognition. Created by
 * [AsrEngine.startSession] when the mic starts, fed PCM frames off the capture
 * thread, and finalised by [finish] when the user stops.
 *
 * Threading contract: [acceptAudio] is a non-blocking hand-off callable from the
 * AudioRecord capture thread. All actual VAD + decode work happens on the engine's
 * own single decode thread — never on the caller's thread and never on the main
 * thread.
 */
interface AsrSession {
    /**
     * Hand off a block of 16 kHz mono PCM16 samples ([length] valid samples in
     * [frame]). Copies what it needs and returns immediately; safe to reuse [frame].
     */
    fun acceptAudio(frame: ShortArray, length: Int)

    /** Text finalised so far (segments closed by the VAD), joined in order. */
    val finalizedText: String

    /**
     * Stop accepting audio, flush the VAD (closing any in-progress speech segment),
     * decode the tail, and return the full transcript. Suspends until the decode
     * queue has drained.
     */
    suspend fun finish(): String
}

/**
 * On-device speech recogniser. A single heavyweight instance is created once and
 * kept loaded across dictations (the int8 Parakeet encoder alone is ~1 GB resident).
 */
interface AsrEngine {
    /** True once [initialize] has returned [AsrInitResult.Ready]. */
    val isReady: Boolean

    /**
     * Load the VAD + recogniser (+ optional punctuation) from external storage.
     * Idempotent: calling again once ready is a cheap no-op returning [AsrInitResult.Ready].
     */
    suspend fun initialize(): AsrInitResult

    /** Begin a new dictation session. Only one session is expected to be live at a time. */
    fun startSession(): AsrSession

    /** Free all native resources. Safe to call more than once. */
    fun release()
}
