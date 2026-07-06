package com.apps.dsimpletools.speak.insert

/** How new text should be combined with whatever is already in the target field. */
enum class InsertMode {
    /** Overwrite the field's entire contents. */
    REPLACE,

    /** Keep existing contents and add the new text after it. */
    APPEND
}

sealed interface InsertResult {
    /** The node's text after the write matched what we expected exactly. */
    data object Verified : InsertResult

    /** The write action reported success, but the read-back text didn't match. */
    data class Mismatch(val actual: String?) : InsertResult

    /** The write could not even be attempted (no target, not editable, action failed). */
    data class Failed(val reason: String) : InsertResult
}

/**
 * Abstraction over "put dictated text into whatever field is currently
 * focused". Phase 0 has exactly one implementation
 * ([com.apps.dsimpletools.speak.insert.AccessibilityTextInserter]) that
 * writes a fixed smoke-test string; Phase 1 (ASR wiring) will call this same
 * interface with real transcripts.
 */
interface TextInserter {
    suspend fun insert(text: String, mode: InsertMode = InsertMode.APPEND): InsertResult
}
