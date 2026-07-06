package com.apps.dsimpletools.speak.speaker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
