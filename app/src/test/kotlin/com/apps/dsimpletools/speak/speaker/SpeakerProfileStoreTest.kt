package com.apps.dsimpletools.speak.speaker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Pure-JVM tests for the length-prefixed binary embedding persistence (temp dir). */
class SpeakerProfileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = SpeakerProfileStore(tmp.root)

    @Test fun roundTripPreservesEmbeddingsExactly() {
        val s = store()
        val embeddings = listOf(
            floatArrayOf(0.1f, -0.2f, 0.3f, 0.4f),
            floatArrayOf(1.5f, 2.5f, -3.5f, 4.5f),
            floatArrayOf(-0.01f, 0.02f, -0.03f, 0.04f)
        )
        s.save(embeddings)
        assertTrue(s.exists())

        val loaded = store().load() // fresh instance, same dir
        assertEquals(3, loaded.size)
        for (i in embeddings.indices) {
            // writeFloat/readFloat is bit-exact, so equality is exact.
            assertArrayEquals(embeddings[i], loaded[i], 0f)
        }
    }

    @Test fun loadOfMissingFileIsEmpty() {
        assertTrue(store().load().isEmpty())
        assertFalse(store().exists())
    }

    @Test fun saveEmptyClearsProfile() {
        val s = store()
        s.save(listOf(floatArrayOf(1f, 2f, 3f)))
        assertTrue(s.exists())
        s.save(emptyList())
        assertFalse(s.exists())
        assertTrue(s.load().isEmpty())
    }

    @Test fun clearRemovesProfile() {
        val s = store()
        s.save(listOf(floatArrayOf(1f, 2f, 3f)))
        s.clear()
        assertFalse(s.exists())
        assertTrue(s.load().isEmpty())
    }

    @Test fun corruptFileLoadsAsEmptyNotCrash() {
        File(tmp.root, SpeakerProfileStore.FILE_NAME).writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        assertTrue(store().load().isEmpty())
    }

    @Test fun rebuiltMeanFromLoadedMatchesOriginal() {
        val s = store()
        val embeddings = listOf(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f)
        )
        s.save(embeddings)
        val meanBefore = SpeakerMath.meanEmbedding(embeddings)!!
        val meanAfter = SpeakerMath.meanEmbedding(store().load())!!
        assertArrayEquals(meanBefore, meanAfter, 1e-6f)
    }

    // ---- append: no replacement, order preserved, oldest-out rotation at the cap ----

    /** Embedding whose first component identifies it, so rotation order is assertable. */
    private fun emb(id: Int) = floatArrayOf(id.toFloat(), 0f)

    @Test fun appendAddsToExistingProfilePreservingOrderAcrossInstances() {
        store().save(listOf(emb(1), emb(2), emb(3))) // the original 3-utterance enrollment
        val result = store().append(listOf(emb(4)))
        assertEquals(4, result.size)

        val loaded = store().load() // fresh instance: round-trips through disk
        assertEquals(4, loaded.size)
        for (i in 0 until 4) assertEquals((i + 1).toFloat(), loaded[i][0], 0f)
    }

    @Test fun appendToEmptyStoreCreatesProfile() {
        val result = store().append(listOf(emb(1)))
        assertEquals(1, result.size)
        assertEquals(1, store().load().size)
    }

    @Test fun appendBeyondCapDropsOldestFirst() {
        val s = store()
        s.save((1..3).map { emb(it) })
        for (id in 4..10) s.append(listOf(emb(id)))
        assertEquals(SpeakerProfileStore.MAX_UTTERANCES, s.load().size) // at the cap (10)

        s.append(listOf(emb(11))) // 11th: utterance 1 (an original) rotates out
        val loaded = store().load()
        assertEquals(SpeakerProfileStore.MAX_UTTERANCES, loaded.size)
        for (i in loaded.indices) assertEquals((i + 2).toFloat(), loaded[i][0], 0f) // 2..11
    }

    @Test fun appendWithExplicitSmallCapKeepsOnlyTheNewest() {
        val s = store()
        s.save(listOf(emb(1), emb(2), emb(3)))
        val result = s.append(listOf(emb(4), emb(5)), maxCount = 3)
        assertEquals(3, result.size)
        for (i in result.indices) assertEquals((i + 3).toFloat(), result[i][0], 0f) // 3,4,5
    }

    // ---- backup: created before any replace/delete, single rolling file, restore round-trip ----

    private fun backupFile() = File(tmp.root, SpeakerProfileStore.BACKUP_FILE_NAME)

    @Test fun firstSaveOnEmptyStoreCreatesNoBackup() {
        val s = store()
        assertFalse(s.hasBackup())
        s.save(listOf(emb(1)))
        // Nothing existed to replace, so there is nothing to back up yet.
        assertFalse(s.hasBackup())
        assertFalse(backupFile().exists())
    }

    @Test fun savingOverAnExistingProfileBacksUpTheOldOneNotTheNewOne() {
        val s = store()
        s.save(listOf(emb(1), emb(2))) // original enrollment
        s.save(listOf(emb(9))) // re-enroll: backs up the original, then replaces it
        assertTrue(s.hasBackup())

        // Current file is the NEW profile.
        val current = store().load()
        assertEquals(1, current.size)
        assertEquals(9f, current[0][0], 0f)

        // The backup holds the OLD profile, not the new one (restore is the only public way
        // to inspect backup contents; round-trip coverage lives in the restore tests below).
        assertTrue(s.restoreFromBackup())
        val afterRestore = store().load()
        assertEquals(2, afterRestore.size)
        assertEquals(1f, afterRestore[0][0], 0f)
        assertEquals(2f, afterRestore[1][0], 0f)
    }

    @Test fun clearBacksUpBeforeDeleting() {
        val s = store()
        s.save(listOf(emb(1), emb(2), emb(3)))
        assertFalse(s.hasBackup())
        s.clear()
        assertFalse(s.exists())
        assertTrue(s.hasBackup())
    }

    @Test fun restoreFromBackupRoundTripsAfterClear() {
        val s = store()
        val original = listOf(emb(1), emb(2), emb(3))
        s.save(original)
        s.clear()
        assertFalse(s.exists())
        assertTrue(s.hasBackup())

        val restored = s.restoreFromBackup()
        assertTrue(restored)
        assertTrue(s.exists())
        val loaded = store().load() // fresh instance: round-trips through disk
        assertEquals(original.size, loaded.size)
        for (i in original.indices) assertArrayEquals(original[i], loaded[i], 0f)
    }

    @Test fun restoreFromBackupRoundTripsAfterReplace() {
        val s = store()
        val original = listOf(emb(1), emb(2))
        s.save(original)
        s.save(listOf(emb(9))) // re-enroll: backs up `original`, then replaces it

        val restored = s.restoreFromBackup()
        assertTrue(restored)
        val loaded = store().load()
        assertEquals(original.size, loaded.size)
        for (i in original.indices) assertArrayEquals(original[i], loaded[i], 0f)
    }

    @Test fun restoreFromBackupIsARollingSingleFileNotAHistory() {
        val s = store()
        s.save(listOf(emb(1))) // no backup yet
        s.save(listOf(emb(2))) // backs up [1]
        s.save(listOf(emb(3))) // backs up [2] — overwrites the [1] backup, not a history

        s.restoreFromBackup()
        val loaded = store().load()
        assertEquals(1, loaded.size)
        assertEquals(2f, loaded[0][0], 0f) // the most recent backup, not the original
    }

    @Test fun restoreFromBackupFailsGracefullyWhenNoBackupExists() {
        val s = store()
        assertFalse(s.hasBackup())
        assertFalse(s.restoreFromBackup())
        assertFalse(s.exists()) // no-op: nothing was created
    }

    @Test fun restoreFromBackupFailsGracefullyOnACorruptBackupFile() {
        val s = store()
        s.save(listOf(emb(1))) // creates a current profile (no backup yet)
        backupFile().parentFile?.mkdirs()
        backupFile().writeBytes(byteArrayOf(1, 2, 3)) // corrupt/foreign .bak, too short to parse
        assertTrue(s.hasBackup())

        assertFalse(s.restoreFromBackup())
        // The good current profile must be untouched by a failed restore attempt.
        val loaded = store().load()
        assertEquals(1, loaded.size)
        assertEquals(1f, loaded[0][0], 0f)
    }

    // ---- tmp-file hygiene: a write failure must not corrupt the existing profile or leak a tmp file ----

    @Test fun tmpFileIsCleanedUpAndProfileUntouchedOnSimulatedWriteFailure() {
        val s = store()
        s.save(listOf(emb(1), emb(2))) // existing, good profile

        // Sabotage the write: pre-create the tmp path as a directory so FileOutputStream(tmp)
        // throws inside writeProfile(), simulating a mid-write disk failure.
        val tmpPath = File(tmp.root, "${SpeakerProfileStore.FILE_NAME}.tmp")
        tmpPath.mkdir()

        assertThrows(Exception::class.java) { s.save(listOf(emb(3))) }

        assertFalse("failed write must not leak the tmp file/dir", tmpPath.exists())
        val loaded = store().load()
        assertEquals("existing profile must survive a failed replace untouched", 2, loaded.size)
        assertEquals(1f, loaded[0][0], 0f)
        assertEquals(2f, loaded[1][0], 0f)
    }
}
