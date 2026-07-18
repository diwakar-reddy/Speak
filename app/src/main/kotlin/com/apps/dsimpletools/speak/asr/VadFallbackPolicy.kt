package com.apps.dsimpletools.speak.asr

/**
 * Pure trigger policy for the whole-session VAD-fallback decode — no Android/native deps, fully
 * unit-testable.
 *
 * Silero VAD (threshold 0.5) does not always classify a whispered or very quiet utterance as
 * speech, so a tap-bounded session can finish having produced ZERO VAD segments and surface as
 * "no speech detected" even though audio was captured. Because a session is explicitly bounded by
 * the user's tap, one that yielded nothing usable is decoded WHOLESALE as a last resort: silence
 * decodes to empty text and behaves exactly as before, so the fallback is self-limiting.
 */
object VadFallbackPolicy {

    /** Buffered session audio is retained only up to this many seconds (fallback targets short
     * sessions; a long session with no VAD segments is abnormal and not worth buffering). */
    const val FALLBACK_MAX_SEC = 12

    /** A session with less than this much buffered audio is too short to bother decoding. */
    const val MIN_FALLBACK_SEC = 0.3f

    /** Max buffered samples retained for the fallback at [sampleRate]. */
    fun maxBufferedSamples(sampleRate: Int): Int = FALLBACK_MAX_SEC * sampleRate

    /**
     * Whether to decode the whole buffered session as a VAD fallback.
     *
     * Triggers only when the VAD produced nothing usable — no segment decoded AND none rejected by
     * the speaker gate (truly nothing detected, not everything filtered out) — and at least
     * [MIN_FALLBACK_SEC] of audio was buffered.
     */
    fun shouldDecodeWholeSession(
        decodedSegments: Int,
        rejectedSegments: Int,
        bufferedSamples: Int,
        sampleRate: Int
    ): Boolean =
        decodedSegments == 0 &&
            rejectedSegments == 0 &&
            bufferedSamples >= MIN_FALLBACK_SEC * sampleRate
}
