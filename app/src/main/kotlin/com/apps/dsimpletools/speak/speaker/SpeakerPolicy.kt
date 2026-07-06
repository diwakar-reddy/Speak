package com.apps.dsimpletools.speak.speaker

/** Why a segment was accepted or rejected by the speaker gate. */
enum class SpeakerReason {
    /** Gate not in force (not enrolled, gate toggled off, or model missing): always accept. */
    UNGATED,

    /** Gate active and the segment matched the owner profile. */
    ACCEPT,

    /** Gate active and the segment did NOT match the owner profile: dropped before decode. */
    REJECT,

    /** Embedding could not be computed (native failure): fail open — accept. */
    ERROR
}

/**
 * The outcome of gating one VAD segment.
 *
 * @property accepted true if the segment should be decoded; false if it should be dropped.
 * @property similarity cosine similarity against the owner profile (0f when not computed).
 * @property reason why the decision was reached.
 */
data class SpeakerDecision(
    val accepted: Boolean,
    val similarity: Float,
    val reason: SpeakerReason
)

/**
 * Pure gate policy — no Android/native dependencies, fully unit-testable.
 *
 * Threshold policy: the owner is accepted when cosine similarity >= threshold.
 * sherpa-onnx's documented default cosine threshold for CAM++ is 0.60. Short segments
 * carry less speaker information and score more noisily, so segments shorter than
 * [SHORT_SEGMENT_SEC] are judged against a slightly relaxed threshold
 * (threshold - [SHORT_SEGMENT_RELAXATION]) to avoid dropping the owner's own brief
 * utterances ("yes", "okay") — the gate errs toward keeping the owner's speech.
 */
object SpeakerPolicy {

    /** sherpa-onnx documented default cosine threshold for CAM++. */
    const val DEFAULT_THRESHOLD = 0.60f

    /** Segments shorter than this (seconds) are treated as short/unreliable. */
    const val SHORT_SEGMENT_SEC = 1.0f

    /** How much the threshold is relaxed for short segments. */
    const val SHORT_SEGMENT_RELAXATION = 0.05f

    /** The threshold actually applied to a segment of the given duration. */
    fun effectiveThreshold(baseThreshold: Float, durationSec: Float): Float =
        if (durationSec < SHORT_SEGMENT_SEC) baseThreshold - SHORT_SEGMENT_RELAXATION else baseThreshold

    /**
     * Decide the fate of one segment.
     *
     * @param enrolled whether an owner profile exists.
     * @param gateEnabled whether the "only my voice" gate is toggled on.
     * @param modelPresent whether the CAM++ model loaded successfully.
     * @param similarity the computed cosine similarity, or null if the embedding could not
     *        be computed (native failure) — which fails open.
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
            return SpeakerDecision(accepted = true, similarity = similarity ?: 0f, reason = SpeakerReason.UNGATED)
        }
        if (similarity == null) {
            // Embedding compute failed -> fail open so a broken extractor never blocks dictation.
            return SpeakerDecision(accepted = true, similarity = 0f, reason = SpeakerReason.ERROR)
        }
        val threshold = effectiveThreshold(baseThreshold, durationSec)
        return if (similarity >= threshold) {
            SpeakerDecision(accepted = true, similarity = similarity, reason = SpeakerReason.ACCEPT)
        } else {
            SpeakerDecision(accepted = false, similarity = similarity, reason = SpeakerReason.REJECT)
        }
    }
}
