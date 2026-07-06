package com.apps.dsimpletools.speak.speaker

import kotlin.math.max
import kotlin.math.sqrt

/**
 * The composite similarity of one segment embedding against the enrolled profile.
 *
 * @property meanSim cosine against the L2-normalised mean profile.
 * @property bestUtteranceSim best cosine against any single enrollment utterance
 *        (equals [meanSim] when the utterance list is empty).
 * @property used the score the gate actually applies: `max(meanSim, bestUtteranceSim)`.
 */
data class CompositeScore(
    val meanSim: Float,
    val bestUtteranceSim: Float,
    val used: Float
)

/**
 * Pure vector maths for speaker embeddings — no Android/native dependencies, so it is
 * fully unit-testable on the JVM. All operations work on the raw 512-dim CAM++ vectors
 * the [SpeakerEmbeddingExtractor] emits.
 */
object SpeakerMath {

    /**
     * Return an L2-normalised copy of [v] (unit length). A zero (or near-zero) vector is
     * returned unchanged to avoid a divide-by-zero — its cosine against anything is 0.
     */
    fun l2Normalize(v: FloatArray): FloatArray {
        var sumSq = 0.0
        for (x in v) sumSq += x.toDouble() * x
        val norm = sqrt(sumSq)
        if (norm < 1e-12) return v.copyOf()
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = (v[i] / norm).toFloat()
        return out
    }

    /**
     * Cosine similarity of two vectors in [-1, 1]. Works on non-normalised inputs (it
     * divides by both magnitudes). Mismatched lengths or a zero-magnitude vector yield 0.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        if (denom < 1e-12) return 0f
        return (dot / denom).toFloat()
    }

    /**
     * Build the owner profile from N enrollment embeddings: L2-normalise each utterance
     * embedding, average them component-wise, then L2-normalise the mean. Normalising
     * before averaging keeps a single loud/long utterance from dominating the profile.
     *
     * @return the profile vector, or null if [embeddings] is empty / dimensionally ragged.
     */
    fun meanEmbedding(embeddings: List<FloatArray>): FloatArray? {
        if (embeddings.isEmpty()) return null
        val dim = embeddings.first().size
        if (dim == 0 || embeddings.any { it.size != dim }) return null
        val acc = DoubleArray(dim)
        for (emb in embeddings) {
            val n = l2Normalize(emb)
            for (i in 0 until dim) acc[i] += n[i]
        }
        val mean = FloatArray(dim)
        for (i in 0 until dim) mean[i] = (acc[i] / embeddings.size).toFloat()
        return l2Normalize(mean)
    }

    /**
     * Composite score of a segment [embedding] against the enrolled profile: the max of the
     * cosine to the [meanProfile] and the best cosine to any single enrollment utterance in
     * [utterances]. The mean is a good "average acoustic condition" match; the per-utterance
     * max additionally matches a segment that closely resembles *one* enrollment condition
     * (e.g. the car sample) even when the blended mean dilutes it.
     *
     * Honest caveat: when all enrollment utterances were recorded in one sitting they point
     * in nearly the same direction, so `bestUtteranceSim ≈ meanSim` and the composite adds
     * little — the max only pays off once the utterances span varied conditions.
     *
     * An empty [utterances] list falls back to the mean alone (`bestUtteranceSim = meanSim`).
     */
    fun compositeScore(
        embedding: FloatArray,
        meanProfile: FloatArray,
        utterances: List<FloatArray>
    ): CompositeScore {
        val meanSim = cosine(embedding, meanProfile)
        if (utterances.isEmpty()) return CompositeScore(meanSim, meanSim, meanSim)
        var best = Float.NEGATIVE_INFINITY
        for (u in utterances) {
            val c = cosine(embedding, u)
            if (c > best) best = c
        }
        return CompositeScore(meanSim, best, max(meanSim, best))
    }
}
