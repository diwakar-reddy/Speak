package com.apps.dsimpletools.speak.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the whole-session VAD-fallback trigger + buffer-cap arithmetic. */
class VadFallbackPolicyTest {

    private val sr = 16_000
    // 0.3 s at 16 kHz = 4800 samples is the minimum buffered audio the fallback needs.
    private val minSamples = 4_800

    @Test fun triggersWhenZeroDecodedAndEnoughAudio() {
        // The reported bug: whispered dictation, Silero yields 0 segments, nothing rejected.
        assertTrue(
            VadFallbackPolicy.shouldDecodeWholeSession(
                decodedSegments = 0, rejectedSegments = 0, bufferedSamples = minSamples, sampleRate = sr
            )
        )
        // A comfortably long (2.4 s) zero-segment session also triggers.
        assertTrue(
            VadFallbackPolicy.shouldDecodeWholeSession(
                decodedSegments = 0, rejectedSegments = 0, bufferedSamples = 38_400, sampleRate = sr
            )
        )
    }

    @Test fun doesNotTriggerWhenSegmentsWereDecoded() {
        // Normal path already produced text -> no fallback.
        assertFalse(
            VadFallbackPolicy.shouldDecodeWholeSession(
                decodedSegments = 1, rejectedSegments = 0, bufferedSamples = 38_400, sampleRate = sr
            )
        )
    }

    @Test fun doesNotTriggerWhenSomethingWasRejected() {
        // Speaker gate rejected foreign speech -> re-decoding wholesale would re-admit it -> no fallback.
        assertFalse(
            VadFallbackPolicy.shouldDecodeWholeSession(
                decodedSegments = 0, rejectedSegments = 1, bufferedSamples = 38_400, sampleRate = sr
            )
        )
    }

    @Test fun doesNotTriggerBelowMinimumDuration() {
        // Just under 0.3 s -> too short to bother.
        assertFalse(
            VadFallbackPolicy.shouldDecodeWholeSession(
                decodedSegments = 0, rejectedSegments = 0, bufferedSamples = minSamples - 1, sampleRate = sr
            )
        )
        // Exactly 0.3 s is the boundary and DOES trigger.
        assertTrue(
            VadFallbackPolicy.shouldDecodeWholeSession(
                decodedSegments = 0, rejectedSegments = 0, bufferedSamples = minSamples, sampleRate = sr
            )
        )
    }

    @Test fun bufferCapArithmetic() {
        // 12 s at 16 kHz = 192000 floats (~768 KB).
        assertEquals(192_000, VadFallbackPolicy.maxBufferedSamples(sr))
        assertEquals(96_000, VadFallbackPolicy.maxBufferedSamples(8_000))
    }
}
