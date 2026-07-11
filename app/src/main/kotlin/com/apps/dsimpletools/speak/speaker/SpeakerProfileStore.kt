package com.apps.dsimpletools.speak.speaker

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

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
 *
 * Backup safety net: any operation that REPLACES or DELETES an existing profile ([save]
 * over an existing file, or [clear]) first copies the current file to a single rolling
 * backup ([BACKUP_FILE_NAME]), overwritten each time — never a history, just "the profile
 * as it was immediately before the last destructive operation". [restoreFromBackup] can
 * bring it back (e.g. after an accidental Clear). This class deliberately has zero Android
 * framework dependencies (no [android.util.Log]) so it stays plain-JVM unit-testable, same
 * as before this change.
 */
class SpeakerProfileStore(private val dir: File) {

    private val file: File
        get() = File(dir, FILE_NAME)

    private val backupFile: File
        get() = File(dir, BACKUP_FILE_NAME)

    /** Whether a profile file exists on disk (does not validate its contents). */
    fun exists(): Boolean = file.exists()

    /** Whether a rolling backup exists (see [save]/[clear]) — used to offer "Restore". */
    fun hasBackup(): Boolean = backupFile.exists()

    /** Persist [embeddings] (raw, un-normalised). An empty list clears the profile. */
    fun save(embeddings: List<FloatArray>) {
        if (embeddings.isEmpty()) {
            clear()
            return
        }
        val dim = embeddings.first().size
        require(embeddings.all { it.size == dim }) { "ragged embeddings: mismatched dimensions" }
        if (!dir.exists()) dir.mkdirs()
        backupCurrentFile() // preserve whatever profile this write is about to replace
        val tmp = File(dir, "$FILE_NAME.tmp")
        try {
            writeProfile(tmp, dim, embeddings)
        } catch (t: Throwable) {
            // Never leave a half-written tmp file lying around; the real file (if any) was
            // never touched, so the existing profile survives a write failure untouched.
            runCatching { tmp.delete() }
            throw t
        }
        commitTmp(tmp, file)
    }

    /** Write the SPK1 payload to [tmp], fsync'ing best-effort before the caller renames it in. */
    private fun writeProfile(tmp: File, dim: Int, embeddings: List<FloatArray>) {
        FileOutputStream(tmp).use { fos ->
            val out = DataOutputStream(fos.buffered())
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(dim)
            out.writeInt(embeddings.size)
            for (emb in embeddings) {
                for (v in emb) out.writeFloat(v)
            }
            out.flush()
            // Best-effort fsync: makes the tmp file's bytes durable on disk before the
            // rename that publishes it, so a crash right after rename can't expose a
            // half-flushed file. One extra syscall; never fatal if unsupported.
            runCatching { fos.fd.sync() }
        }
    }

    /** Atomic-ish publish of [tmp] as [dest] (rename, falling back to copy+delete). */
    private fun commitTmp(tmp: File, dest: File) {
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    /** Copy the current profile file to [backupFile] if one exists. Best-effort. */
    private fun backupCurrentFile() {
        val f = file
        if (f.exists()) {
            runCatching { f.copyTo(backupFile, overwrite = true) }
        }
    }

    /**
     * Append [newEmbeddings] to the persisted profile (no replacement), capping the total at
     * [maxCount] utterances by dropping the OLDEST first — FIFO rotation, so once the cap is
     * reached the earliest samples (including the original enrollment utterances) rotate out
     * in favour of the freshest ones. Persists and returns the resulting list.
     *
     * The SPK1 format is unchanged (its count field was always generic), so profiles written
     * before append-enrollment existed load identically.
     *
     * @throws IllegalArgumentException if a new embedding's dimension mismatches the persisted
     *         ones (propagated from [save]; callers treat it as an append failure).
     */
    fun append(
        newEmbeddings: List<FloatArray>,
        maxCount: Int = MAX_UTTERANCES
    ): List<FloatArray> {
        val combined = load() + newEmbeddings
        val capped = if (combined.size > maxCount) combined.takeLast(maxCount) else combined
        save(capped)
        return capped
    }

    /** Load the raw per-utterance embeddings. Returns an empty list if absent/corrupt. */
    fun load(): List<FloatArray> = loadFrom(file)

    /** Shared defensive parse, usable against any candidate file (e.g. a backup being validated). */
    private fun loadFrom(f: File): List<FloatArray> {
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

    /** Delete the profile file, first backing it up. Safe if it does not exist. */
    fun clear() {
        backupCurrentFile()
        runCatching { file.delete() }
        runCatching { File(dir, "$FILE_NAME.tmp").delete() }
    }

    /**
     * Restore the profile from the rolling backup left by the last destructive [save]/[clear]
     * (e.g. to undo an accidental Clear). The backup is parsed and validated (must yield a
     * non-empty embedding list) before it is committed over [file], so a corrupt or foreign
     * `.bak` can never clobber a good profile. Returns false — a no-op — if there is no backup
     * or it fails to validate.
     *
     * Does NOT itself back up whatever is currently at [file] first: this is a recovery action
     * for the not-enrolled state (no current file), not a swap between two live profiles.
     */
    fun restoreFromBackup(): Boolean {
        val bak = backupFile
        if (!bak.exists()) return false
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, "$FILE_NAME.tmp")
        val copied = runCatching { bak.copyTo(tmp, overwrite = true) }.isSuccess
        if (!copied || loadFrom(tmp).isEmpty()) {
            runCatching { tmp.delete() }
            return false
        }
        commitTmp(tmp, file)
        return true
    }

    companion object {
        const val FILE_NAME = "speaker_profile.bin"

        /** Single rolling backup of the profile a [save]/[clear] is about to replace/delete. */
        const val BACKUP_FILE_NAME = "speaker_profile.bak"

        /**
         * Maximum utterances kept in the profile. Appends beyond this rotate the OLDEST
         * utterance out (see [append]). 10 varied samples are plenty for a stable composite
         * score while keeping the per-segment best-utterance scan trivially cheap.
         */
        const val MAX_UTTERANCES = 10

        private const val MAGIC = 0x53504B31 // "SPK1"
        private const val VERSION = 1
        private const val MAX_DIM = 8192
        private const val MAX_COUNT = 64
    }
}
