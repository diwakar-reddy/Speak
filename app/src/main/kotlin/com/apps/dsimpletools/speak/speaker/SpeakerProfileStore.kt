package com.apps.dsimpletools.speak.speaker

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Persists the owner's raw per-utterance speaker embeddings to a single file, so the
 * L2-normalised mean profile can be rebuilt on every app start.
 *
 * We deliberately persist the raw per-utterance embeddings (not just the mean, and not
 * via sherpa-onnx's [SpeakerEmbeddingManager], whose store is in-memory native only):
 * keeping the individual utterances lets the profile be recomputed if the averaging
 * strategy ever changes, and makes the on-disk state trivially inspectable/testable.
 *
 * Format (length-prefixed big-endian binary via Data{Output,Input}Stream — no JSON lib,
 * so the round-trip is fully JVM-unit-testable):
 *
 *   int32  MAGIC (0x53504B31, "SPK1")
 *   int32  version (= 1)
 *   int32  dim      (embedding dimension, e.g. 512)
 *   int32  count    (number of utterances)
 *   count * dim * float32   (raw embeddings, row-major)
 *
 * All reads are defensive: a missing, truncated, or malformed file yields an empty list
 * rather than throwing, so a corrupt profile degrades to "not enrolled", never a crash.
 */
class SpeakerProfileStore(private val dir: File) {

    private val file: File
        get() = File(dir, FILE_NAME)

    /** Whether a profile file exists on disk (does not validate its contents). */
    fun exists(): Boolean = file.exists()

    /** Persist [embeddings] (raw, un-normalised). An empty list clears the profile. */
    fun save(embeddings: List<FloatArray>) {
        if (embeddings.isEmpty()) {
            clear()
            return
        }
        val dim = embeddings.first().size
        require(embeddings.all { it.size == dim }) { "ragged embeddings: mismatched dimensions" }
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, "$FILE_NAME.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(dim)
            out.writeInt(embeddings.size)
            for (emb in embeddings) {
                for (v in emb) out.writeFloat(v)
            }
        }
        // Atomic-ish replace so a crash mid-write can't leave a half-written profile.
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /** Load the raw per-utterance embeddings. Returns an empty list if absent/corrupt. */
    fun load(): List<FloatArray> {
        val f = file
        if (!f.exists()) return emptyList()
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return emptyList()
                val version = input.readInt()
                if (version != VERSION) return emptyList()
                val dim = input.readInt()
                val count = input.readInt()
                if (dim <= 0 || count <= 0 || dim > MAX_DIM || count > MAX_COUNT) return emptyList()
                val out = ArrayList<FloatArray>(count)
                for (i in 0 until count) {
                    val emb = FloatArray(dim)
                    for (j in 0 until dim) emb[j] = input.readFloat()
                    out.add(emb)
                }
                out
            }
        }.getOrDefault(emptyList())
    }

    /** Delete the profile file. Safe if it does not exist. */
    fun clear() {
        runCatching { file.delete() }
        runCatching { File(dir, "$FILE_NAME.tmp").delete() }
    }

    companion object {
        const val FILE_NAME = "speaker_profile.bin"
        private const val MAGIC = 0x53504B31 // "SPK1"
        private const val VERSION = 1
        private const val MAX_DIM = 8192
        private const val MAX_COUNT = 64
    }
}
