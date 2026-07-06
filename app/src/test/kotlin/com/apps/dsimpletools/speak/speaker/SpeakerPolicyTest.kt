package com.apps.dsimpletools.speak.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the speaker gate decision + threshold policy. */
class SpeakerPolicyTest {

    private val base = SpeakerPolicy.DEFAULT_THRESHOLD // 0.60

    // ---- threshold policy ----

    @Test fun longSegmentUsesBaseThreshold() {
        assertEquals(base, SpeakerPolicy.effectiveThreshold(base, 1.0f), 0f)
        assertEquals(base, SpeakerPolicy.effectiveThreshold(base, 3.5f), 0f)
    }

    @Test fun shortSegmentRelaxesThreshold() {
        assertEquals(
            base - SpeakerPolicy.SHORT_SEGMENT_RELAXATION,
            SpeakerPolicy.effectiveThreshold(base, 0.5f),
            1e-6f
        )
    }

    // ---- ungated (gate not in force) ----

    @Test fun notEnrolledIsUngatedAccept() {
        val d = SpeakerPolicy.decide(
            enrolled = false, gateEnabled = true, modelPresent = true,
            similarity = 0.1f, durationSec = 2f, baseThreshold = base
        )
        assertTrue(d.accepted)
        assertEquals(SpeakerReason.UNGATED, d.reason)
    }

    @Test fun gateOffIsUngatedAccept() {
        val d = SpeakerPolicy.decide(
            enrolled = true, gateEnabled = false, modelPresent = true,
            similarity = 0.1f, durationSec = 2f, baseThreshold = base
        )
        assertTrue(d.accepted)
        assertEquals(SpeakerReason.UNGATED, d.reason)
    }

    @Test fun modelMissingIsUngatedAccept() {
        val d = SpeakerPolicy.decide(
            enrolled = true, gateEnabled = true, modelPresent = false,
            similarity = 0.1f, durationSec = 2f, baseThreshold = base
        )
        assertTrue(d.accepted)
        assertEquals(SpeakerReason.UNGATED, d.reason)
    }

    // ---- fail-open ----

    @Test fun nullSimilarityFailsOpenWithErrorReason() {
        val d = SpeakerPolicy.decide(
            enrolled = true, gateEnabled = true, modelPresent = true,
            similarity = null, durationSec = 2f, baseThreshold = base
        )
        assertTrue(d.accepted)
        assertEquals(SpeakerReason.ERROR, d.reason)
    }

    // ---- accept / reject ----

    @Test fun similarityAtOrAboveThresholdAccepts() {
        val d = SpeakerPolicy.decide(
            enrolled = true, gateEnabled = true, modelPresent = true,
            similarity = 0.60f, durationSec = 2f, baseThreshold = base
        )
        assertTrue(d.accepted)
        assertEquals(SpeakerReason.ACCEPT, d.reason)
        assertEquals(0.60f, d.similarity, 0f)
    }

    @Test fun similarityBelowThresholdRejects() {
        val d = SpeakerPolicy.decide(
            enrolled = true, gateEnabled = true, modelPresent = true,
            similarity = 0.45f, durationSec = 2f, baseThreshold = base
        )
        assertFalse(d.accepted)
        assertEquals(SpeakerReason.REJECT, d.reason)
    }

    // ---- short-segment adjustment changes the outcome of a borderline segment ----

    @Test fun borderlineShortSegmentAcceptedButLongSegmentRejected() {
        val sim = 0.57f // between relaxed 0.55 and base 0.60
        val short = SpeakerPolicy.decide(
            enrolled = true, gateEnabled = true, modelPresent = true,
            similarity = sim, durationSec = 0.5f, baseThreshold = base
        )
        val long = SpeakerPolicy.decide(
            enrolled = true, gateEnabled = true, modelPresent = true,
            similarity = sim, durationSec = 2.0f, baseThreshold = base
        )
        assertTrue("short segment should be accepted under relaxed threshold", short.accepted)
        assertEquals(SpeakerReason.ACCEPT, short.reason)
        assertFalse("long segment should be rejected under base threshold", long.accepted)
        assertEquals(SpeakerReason.REJECT, long.reason)
    }
}
