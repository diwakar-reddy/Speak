package com.apps.dsimpletools.speak.insert

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Writes text into the currently tracked [AccessibilityNodeInfo] via
 * `ACTION_SET_TEXT`, then reads the node back to confirm the write actually
 * stuck (some fields silently reject/reformat programmatic text).
 *
 * [targetProvider] is a lambda rather than a stored node reference because
 * the accessibility service is the sole owner of "what is currently
 * focused" — this class just asks for it fresh at insert time.
 */
class AccessibilityTextInserter(
    private val targetProvider: () -> AccessibilityNodeInfo?
) : TextInserter {

    override suspend fun insert(text: String, mode: InsertMode): InsertResult =
        withContext(Dispatchers.Main) {
            val node = targetProvider()
            if (node == null) {
                Log.w(TAG, "INSERT_RESULT: MISMATCH no-target-node")
                return@withContext InsertResult.Failed("no target node")
            }

            val refreshed = node.refresh()
            if (!refreshed || !node.isEditable) {
                Log.w(TAG, "INSERT_RESULT: MISMATCH target-not-editable (refreshed=$refreshed)")
                return@withContext InsertResult.Failed("target not editable (refreshed=$refreshed)")
            }

            // An empty field that displays a hint reports the *hint* string via
            // getText(); isShowingHintText() disambiguates (API 26+, minSdk is 29).
            // Treat a hint-showing field as empty so the placeholder is never
            // concatenated into the inserted text.
            val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
            val newText = combine(existing, text, mode)

            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
            val performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!performed) {
                Log.w(TAG, "INSERT_RESULT: MISMATCH action-set-text-returned-false")
                return@withContext InsertResult.Failed("ACTION_SET_TEXT returned false")
            }

            // Give the target view a beat to process the action before reading it back.
            delay(150)
            node.refresh()
            val readBack = node.text?.toString()
            return@withContext if (readBack == newText) {
                Log.i(TAG, "INSERT_RESULT: VERIFIED")
                InsertResult.Verified
            } else {
                Log.w(TAG, "INSERT_RESULT: MISMATCH $readBack")
                InsertResult.Mismatch(readBack)
            }
        }

    companion object {
        private const val TAG = "Speak.Insert"

        /**
         * Pure text-combination logic (extracted so it can be unit-tested without the
         * `AccessibilityNodeInfo` framework class). [existing] is the field's current
         * text, already resolved to "" for an empty hint-showing field by the caller.
         *
         * APPEND glue: when appending onto non-empty text that doesn't already end in
         * whitespace, a single separating space is inserted so dictation doesn't run
         * into the previous word (fixes the "EditTextspeak" artifact); an empty
         * [existing] yields just the inserted text (no leading placeholder, no space).
         */
        internal fun combine(existing: String, inserted: String, mode: InsertMode): String =
            when (mode) {
                InsertMode.REPLACE -> inserted
                InsertMode.APPEND -> {
                    val separator = if (existing.isNotEmpty() && !existing.last().isWhitespace()) " " else ""
                    existing + separator + inserted
                }
            }
    }
}
