package com.apps.dsimpletools.speak.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the session-level held-short-segment resolution rule. */
class SessionGatePolicyTest {

    // ---- the three session-resolution cases ----

    @Test fun ownerSpokeLongSegments_includesHeldShorts() {
        // >= 1 long accepted -> owner clearly present -> include held shorts.
        assertTrue(SessionGatePolicy.includeHeldShortSegments(longAccepted = 1, longRejected = 0))
        assertTrue(SessionGatePolicy.includeHeldShortSegments(longAccepted = 3, longRejected = 0))
    }

    @Test fun noLongSegmentsAtAll_includesHeldShorts() {
        // Single-word dictation (no long segments) is a deliberate owner tap -> include.
        assertTrue(SessionGatePolicy.includeHeldShortSegments(longAccepted = 0, longRejected = 0))
    }

    @Test fun allLongSegmentsRejected_dropsHeldShorts() {
        // Long segments present and ALL rejected (sustained foreign speech) -> drop the shorts too.
        assertFalse(SessionGatePolicy.includeHeldShortSegments(longAccepted = 0, longRejected = 1))
        assertFalse(SessionGatePolicy.includeHeldShortSegments(longAccepted = 0, longRejected = 5))
    }

    @Test fun someLongAcceptedSomeRejected_includesHeldShorts() {
        // At least one accepted long segment -> owner session -> a brief foreign long segment does
        // not block the held shorts.
        assertTrue(SessionGatePolicy.includeHeldShortSegments(longAccepted = 1, longRejected = 1))
        assertTrue(SessionGatePolicy.includeHeldShortSegments(longAccepted = 2, longRejected = 3))
    }

    // ---- final assembly preserves original segment order (accepted-long + included shorts) ----

    private data class Seg(val order: Int, val text: String, val held: Boolean)

    private fun assemble(segs: List<Seg>, longAccepted: Int, longRejected: Int): String {
        val include = SessionGatePolicy.includeHeldShortSegments(longAccepted, longRejected)
        return segs.filter { !it.held || include }.joinToString(" ") { it.text }
    }

    @Test fun assemblyInterleavesHeldAndAcceptedByOrderWhenIncluded() {
        val segs = listOf(
            Seg(0, "hello", held = false),   // accepted long
            Seg(1, "much", held = true),     // held short
            Seg(2, "there", held = false),   // accepted long
            Seg(3, "good", held = true)      // held short
        )
        assertEquals("hello much there good", assemble(segs, longAccepted = 2, longRejected = 0))
    }

    @Test fun assemblyDropsHeldWhenSessionRejected() {
        val segs = listOf(
            Seg(0, "much", held = true),
            Seg(1, "better", held = true)
        )
        // A long foreign segment was rejected and nothing accepted -> drop the held shorts.
        assertEquals("", assemble(segs, longAccepted = 0, longRejected = 1))
    }

    @Test fun assemblyKeepsShortOnlySessionWhenNoLongSegments() {
        val segs = listOf(Seg(0, "much", held = true))
        assertEquals("much", assemble(segs, longAccepted = 0, longRejected = 0))
    }
}
