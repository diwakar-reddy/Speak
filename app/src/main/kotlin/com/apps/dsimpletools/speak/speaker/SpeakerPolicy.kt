package com.apps.dsimpletools.speak.speaker

/** Why a segment was accepted, rejected, or held by the speaker gate. */
enum class SpeakerReason {
    /** Gate not in force (not enrolled, gate toggled off, or model missing): always accept. */
    UNGATED,

    /** Gate active, segment long enough to gate, and it matched the owner profile. */
    ACCEPT,

    /** Gate active, segment long enough to gate, and it did NOT match: dropped before decode. */
    REJECT,

    /**
     * Gate active but the segment is too short to gate reliably (< [SpeakerPolicy.MIN_GATED_SEGMENT_SEC]).
     * CAM++ embeddings on sub-~2 s speech are noise, indistinguishable between the owner and
     * strangers, so no similarity threshold can classify them. Such a segment is decoded
     * unconditionally (decode is cheap) and its text is HELD, to be resolved at session finish
     * by [SessionGatePolicy]. The similarity is still computed and logged for calibration.
     */
    BYPASS,

    /** Embedding could not be computed (native failure) on a gated segment: fail open — accept. */
    ERROR
}

/**
 * The outcome of gating one VAD segment.
 *
 * @property accepted true if the segment should be decoded; false if it should be dropped.
 * @property held true if the segment was decoded but its text must be HELD until session finish
 *        (short-segment bypass) rather than appended to the transcript immediately. Only ever true
 *        together with [accepted].
 * @property similarity cosine similarity against the owner profile (0f when not computed).
 * @property reason why the decision was reached.
 */
data class SpeakerDecision(
    val accepted: Boolean,
    val held: Boolean,
    val similarity: Float,
    val reason: SpeakerReason
)

/**
 * Pure gate policy — no Android/native dependencies, fully unit-testable.
 *
 * Threshold policy: the owner is accepted when cosine similarity >= threshold.
 * sherpa-onnx's documented default cosine threshold for CAM++ is 0.60.
 *
 * Short-segment policy: CAM++ embeddings on segments shorter than [MIN_GATED_SEGMENT_SEC] are
 * unreliable — on the owner's own single words ("much", "better") they score in the same 0.26–0.55
 * band as strangers, so NO threshold can distinguish them. Rather than reject the owner's short
 * words (the old, useless "relaxed threshold" behaviour), short segments are NOT gated standalone.
 * They are decoded unconditionally and their text is held, to be resolved at session finish by
 * [SessionGatePolicy] based on what the long (reliably gated) segments in the same session decided.
 */
object SpeakerPolicy {

    /** sherpa-onnx documented default cosine threshold for CAM++. */
    const val DEFAULT_THRESHOLD = 0.60f

    /**
     * Minimum segment length (seconds) for the embedding gate to be trusted. Segments >= this are
     * gated by similarity; shorter segments are bypassed (held) — CAM++ voiceprints on sub-~2 s
     * speech carry too little speaker information to classify reliably.
     */
    const val MIN_GATED_SEGMENT_SEC = 2.0f

    /**
     * Decide the fate of one segment.
     *
     * @param enrolled whether an owner profile exists.
     * @param gateEnabled whether the "only my voice" gate is toggled on.
     * @param modelPresent whether the CAM++ model loaded successfully.
     * @param similarity the computed cosine similarity, or null if the embedding could not
     *        be computed (native failure) — which fails open on gated (long) segments.
     * @param durationSec the segment length in seconds.
     * @param baseThreshold the configured base cosine threshold.
     */
    fun decide(
        enrolled: Boolean,
        gateEnabled: Boolean,
        modelPresent: Boolean,
        similarity: Float?,
        durationSec: Float,
        baseThreshold: Float
    ): SpeakerDecision {
        if (!enrolled || !gateEnabled || !modelPresent) {
            // Gate not in force: decode + append normally, whatever the duration.
            return SpeakerDecision(
                accepted = true, held = false, similarity = similarity ?: 0f, reason = SpeakerReason.UNGATED
            )
        }
        if (durationSec < MIN_GATED_SEGMENT_SEC) {
            // Too short to gate reliably: decode + hold (resolved at finish by SessionGatePolicy).
            // The similarity (if computed) is carried only for logging/calibration.
            return SpeakerDecision(
                accepted = true, held = true, similarity = similarity ?: 0f, reason = SpeakerReason.BYPASS
            )
        }
        if (similarity == null) {
            // Embedding compute failed on a gated segment -> fail open so a broken extractor
            // never blocks dictation.
            return SpeakerDecision(accepted = true, held = false, similarity = 0f, reason = SpeakerReason.ERROR)
        }
        return if (similarity >= baseThreshold) {
            SpeakerDecision(accepted = true, held = false, similarity = similarity, reason = SpeakerReason.ACCEPT)
        } else {
            SpeakerDecision(accepted = false, held = false, similarity = similarity, reason = SpeakerReason.REJECT)
        }
    }
}
