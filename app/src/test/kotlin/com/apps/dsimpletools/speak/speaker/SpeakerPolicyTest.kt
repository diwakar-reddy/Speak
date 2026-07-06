package com.apps.dsimpletools.speak.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the speaker gate per-segment decision policy. */
class SpeakerPolicyTest {

    private val base = SpeakerPolicy.DEFAULT_THRESHOLD // 0.60
    private val minGated = SpeakerPolicy.MIN_GATED_SEGMENT_SEC // 2.0

    private fun decide(
        enrolled: Boolean = true,
        gateEnabled: Boolean = true,
        modelPresent: Boolean = true,
        similarity: Float?,
        durationSec: Float
    ) = SpeakerPolicy.decide(enrolled, gateEnabled, modelPresent, similarity, durationSec, base)

    // ---- ungated (gate not in force): always accept, never held, whatever the duration ----

    @Test fun notEnrolledIsUngatedAccept() {
        val d = decide(enrolled = false, similarity = 0.1f, durationSec = 2f)
        assertTrue(d.accepted)
        assertFalse(d.held)
        assertEquals(SpeakerReason.UNGATED, d.reason)
    }

    @Test fun gateOffIsUngatedAccept() {
        val d = decide(gateEnabled = false, similarity = 0.1f, durationSec = 2f)
        assertTrue(d.accepted)
        assertFalse(d.held)
        assertEquals(SpeakerReason.UNGATED, d.reason)
    }

    @Test fun modelMissingIsUngatedAccept() {
        val d = decide(modelPresent = false, similarity = 0.1f, durationSec = 2f)
        assertTrue(d.accepted)
        assertFalse(d.held)
        assertEquals(SpeakerReason.UNGATED, d.reason)
    }

    @Test fun shortSegmentUngatedStillAppendsNotHeld() {
        // Even a very short segment appends normally when the gate is not in force.
        val d = decide(enrolled = false, similarity = null, durationSec = 0.5f)
        assertTrue(d.accepted)
        assertFalse(d.held)
        assertEquals(SpeakerReason.UNGATED, d.reason)
    }

    // ---- fail-open on a gated (long) segment ----

    @Test fun nullSimilarityOnLongSegmentFailsOpenWithErrorReason() {
        val d = decide(similarity = null, durationSec = 2f)
        assertTrue(d.accepted)
        assertFalse(d.held)
        assertEquals(SpeakerReason.ERROR, d.reason)
    }

    // ---- long segment: gated by similarity ----

    @Test fun longSegmentSimilarityAtOrAboveThresholdAccepts() {
        val d = decide(similarity = 0.60f, durationSec = 3f)
        assertTrue(d.accepted)
        assertFalse(d.held)
        assertEquals(SpeakerReason.ACCEPT, d.reason)
        assertEquals(0.60f, d.similarity, 0f)
    }

    @Test fun longSegmentSimilarityBelowThresholdRejects() {
        val d = decide(similarity = 0.45f, durationSec = 3f)
        assertFalse(d.accepted)
        assertFalse(d.held)
        assertEquals(SpeakerReason.REJECT, d.reason)
    }

    // ---- short segment: NEVER gated standalone -> decode + hold, regardless of similarity ----

    @Test fun shortSegmentIsBypassHeldEvenWhenSimilarityWouldReject() {
        val d = decide(similarity = 0.30f, durationSec = 0.8f) // owner's real short words score ~0.3
        assertTrue("short segment must still be decoded", d.accepted)
        assertTrue("short segment text must be held", d.held)
        assertEquals(SpeakerReason.BYPASS, d.reason)
        assertEquals(0.30f, d.similarity, 0f)
    }

    @Test fun shortSegmentIsBypassHeldEvenWhenSimilarityWouldAccept() {
        val d = decide(similarity = 0.90f, durationSec = 0.8f)
        assertTrue(d.accepted)
        assertTrue(d.held)
        assertEquals(SpeakerReason.BYPASS, d.reason)
    }

    @Test fun shortSegmentWithNullSimilarityIsBypassHeldNotError() {
        // A failed embedding on a short segment still bypasses/holds (short is never gated).
        val d = decide(similarity = null, durationSec = 0.5f)
        assertTrue(d.accepted)
        assertTrue(d.held)
        assertEquals(SpeakerReason.BYPASS, d.reason)
    }

    // ---- boundary at exactly MIN_GATED_SEGMENT_SEC (2.0 s) ----

    @Test fun exactlyMinGatedSecondsIsGatedNotHeld() {
        // 2.0 s is NOT < 2.0 s, so it is gated by similarity (here: below threshold -> REJECT).
        val d = decide(similarity = 0.45f, durationSec = minGated)
        assertFalse(d.held)
        assertEquals(SpeakerReason.REJECT, d.reason)
    }

    @Test fun justBelowMinGatedSecondsIsHeld() {
        val d = decide(similarity = 0.45f, durationSec = minGated - 0.01f)
        assertTrue(d.held)
        assertEquals(SpeakerReason.BYPASS, d.reason)
    }
}
