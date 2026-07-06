package com.apps.dsimpletools.speak.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Pure-JVM tests for the speaker embedding maths (no Android/native deps). */
class SpeakerMathTest {

    private fun magnitude(v: FloatArray): Double {
        var s = 0.0
        for (x in v) s += x.toDouble() * x
        return sqrt(s)
    }

    // ---- cosine ----

    @Test fun cosineOfIdenticalVectorsIsOne() {
        val v = floatArrayOf(1f, 2f, 3f, 4f)
        assertEquals(1.0f, SpeakerMath.cosine(v, v), 1e-5f)
    }

    @Test fun cosineOfOrthogonalVectorsIsZero() {
        assertEquals(0f, SpeakerMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test fun cosineOfOppositeVectorsIsMinusOne() {
        assertEquals(-1f, SpeakerMath.cosine(floatArrayOf(1f, 2f), floatArrayOf(-1f, -2f)), 1e-5f)
    }

    @Test fun cosineIsScaleInvariant() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(10f, 20f, 30f) // same direction, different magnitude
        assertEquals(1f, SpeakerMath.cosine(a, b), 1e-5f)
    }

    @Test fun cosineMismatchedLengthIsZero() {
        assertEquals(0f, SpeakerMath.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f)), 0f)
    }

    @Test fun cosineWithZeroVectorIsZero() {
        assertEquals(0f, SpeakerMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 0f)
    }

    // ---- l2Normalize ----

    @Test fun l2NormalizeProducesUnitVector() {
        val n = SpeakerMath.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(1.0, magnitude(n), 1e-6)
        assertEquals(0.6f, n[0], 1e-5f)
        assertEquals(0.8f, n[1], 1e-5f)
    }

    @Test fun l2NormalizeOfZeroVectorDoesNotDivideByZero() {
        val n = SpeakerMath.l2Normalize(floatArrayOf(0f, 0f, 0f))
        assertTrue(n.all { it == 0f })
    }

    // ---- meanEmbedding ----

    @Test fun meanEmbeddingOfEmptyListIsNull() {
        assertNull(SpeakerMath.meanEmbedding(emptyList()))
    }

    @Test fun meanEmbeddingOfRaggedListIsNull() {
        assertNull(SpeakerMath.meanEmbedding(listOf(floatArrayOf(1f, 2f), floatArrayOf(1f))))
    }

    @Test fun meanEmbeddingIsUnitLength() {
        val mean = SpeakerMath.meanEmbedding(
            listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))
        )!!
        assertEquals(1.0, magnitude(mean), 1e-6)
    }

    @Test fun meanEmbeddingAveragesDirections() {
        // Two utterances pointing the same way -> mean points the same way.
        val mean = SpeakerMath.meanEmbedding(
            listOf(floatArrayOf(2f, 0f), floatArrayOf(5f, 0f))
        )!!
        assertEquals(1f, mean[0], 1e-5f)
        assertEquals(0f, mean[1], 1e-5f)
    }

    @Test fun meanEmbeddingNormalizesPerUtteranceSoLoudOneDoesNotDominate() {
        // A tiny-magnitude and a huge-magnitude utterance at 90 degrees contribute equally
        // after per-utterance normalization -> mean bisects them (45 degrees).
        val mean = SpeakerMath.meanEmbedding(
            listOf(floatArrayOf(0.001f, 0f), floatArrayOf(0f, 1000f))
        )!!
        assertEquals(mean[0], mean[1], 1e-5f) // equal components => 45 degrees
    }

    // ---- compositeScore: used = max(cosine to mean, best per-utterance cosine) ----

    @Test fun compositeUsesBestUtteranceWhenItBeatsTheMean() {
        // Segment aligned with utterance 1; the blended mean sits 45 degrees away.
        val utterances = listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
        val mean = SpeakerMath.meanEmbedding(utterances)!!
        val score = SpeakerMath.compositeScore(floatArrayOf(1f, 0f), mean, utterances)
        assertEquals(0.7071f, score.meanSim, 1e-3f)
        assertEquals(1f, score.bestUtteranceSim, 1e-5f)
        assertEquals(score.bestUtteranceSim, score.used, 0f)
    }

    @Test fun compositeUsesMeanWhenItBeatsEveryUtterance() {
        // Segment along the bisector: closer to the mean than to either utterance.
        val utterances = listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))
        val mean = SpeakerMath.meanEmbedding(utterances)!!
        val score = SpeakerMath.compositeScore(floatArrayOf(1f, 1f), mean, utterances)
        assertEquals(1f, score.meanSim, 1e-5f)
        assertEquals(0.7071f, score.bestUtteranceSim, 1e-3f)
        assertEquals(score.meanSim, score.used, 0f)
    }

    @Test fun compositeBestUtteranceIsMaxAcrossAllUtterances() {
        val utterances = listOf(
            floatArrayOf(0f, 1f), // 90 degrees from segment -> 0
            floatArrayOf(1f, 1f), // 45 degrees -> ~0.707
            floatArrayOf(1f, 0f)  // aligned -> 1 (the max)
        )
        val mean = SpeakerMath.meanEmbedding(utterances)!!
        val score = SpeakerMath.compositeScore(floatArrayOf(2f, 0f), mean, utterances)
        assertEquals(1f, score.bestUtteranceSim, 1e-5f)
    }

    @Test fun compositeWithEmptyUtterancesFallsBackToMeanAlone() {
        val mean = floatArrayOf(0.6f, 0.8f)
        val score = SpeakerMath.compositeScore(floatArrayOf(1f, 0f), mean, emptyList())
        assertEquals(0.6f, score.meanSim, 1e-5f)
        assertEquals(score.meanSim, score.bestUtteranceSim, 0f)
        assertEquals(score.meanSim, score.used, 0f)
    }

    @Test fun compositeWithSingleUtteranceEqualsMean() {
        // One utterance: the mean profile IS that utterance (normalised), so the composite
        // degenerates to the plain mean score — matches the honest "≈ mean" caveat.
        val utterances = listOf(floatArrayOf(3f, 4f))
        val mean = SpeakerMath.meanEmbedding(utterances)!!
        val score = SpeakerMath.compositeScore(floatArrayOf(1f, 0f), mean, utterances)
        assertEquals(score.meanSim, score.bestUtteranceSim, 1e-6f)
        assertEquals(score.meanSim, score.used, 1e-6f)
    }
}
