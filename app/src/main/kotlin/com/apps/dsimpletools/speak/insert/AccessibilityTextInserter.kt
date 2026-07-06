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
 * Insertion is **cursor-aware**: the dictated text is spliced in at the field's
 * current caret position (or, when there is a selection, it *replaces* the selected
 * text), rather than always being appended at the end. The caret is then moved to
 * just after the inserted text so the user can keep going. When the field exposes no
 * usable selection (`textSelectionStart/End == -1`), it degrades to appending at the
 * end — the previous behaviour.
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

            // Resolve the field's *real* current text, treating a placeholder/hint as
            // empty so it can never be concatenated ahead of the dictated text as a
            // spurious "prefix".
            val existing = resolveExistingText(
                text = node.text?.toString(),
                hint = node.hintText?.toString(),
                isShowingHintText = node.isShowingHintText
            )
            // Current caret / selection. -1 (or out of range) means "unknown" -> append.
            // A hint-showing field reports selection into the *hint* string, which is
            // meaningless once we treat it as empty, so force end-of-(empty) there.
            val selStart = if (node.isShowingHintText) existing.length else node.textSelectionStart
            val selEnd = if (node.isShowingHintText) existing.length else node.textSelectionEnd

            val edit = computeInsertion(existing, text, selStart, selEnd, mode)
            Log.d(
                TAG,
                "INSERT: existing=\"$existing\" sel=[$selStart,$selEnd] showingHint=${node.isShowingHintText} " +
                    "-> newText=\"${edit.newText}\" caret=${edit.newCursor}"
            )

            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, edit.newText)
            }
            val performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!performed) {
                Log.w(TAG, "INSERT_RESULT: MISMATCH action-set-text-returned-false")
                return@withContext InsertResult.Failed("ACTION_SET_TEXT returned false")
            }

            // Move the caret to just after the spliced-in text (ACTION_SET_TEXT resets it
            // to the end otherwise). Best-effort: some fields reject ACTION_SET_SELECTION.
            node.refresh()
            val caret = edit.newCursor.coerceIn(0, edit.newText.length)
            val selectionArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            }
            val caretMoved = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
            }.getOrDefault(false)
            if (!caretMoved) Log.d(TAG, "INSERT: caret reposition to $caret not applied (field rejected it)")

            // Give the target view a beat to process the action before reading it back.
            delay(150)
            node.refresh()
            val readBack = node.text?.toString()
            return@withContext if (readBack == edit.newText) {
                Log.i(TAG, "INSERT_RESULT: VERIFIED")
                InsertResult.Verified
            } else {
                Log.w(TAG, "INSERT_RESULT: MISMATCH $readBack")
                InsertResult.Mismatch(readBack)
            }
        }

    companion object {
        private const val TAG = "Speak.Insert"

        // Punctuation that should "cling" to the preceding text with no space in front.
        private val CLING_PUNCT = charArrayOf(
            ',', '.', '!', '?', ';', ':', ')', ']', '}', '\'', '"', '%'
        )

        /**
         * Resolve the field's *real* current text, treating a placeholder/hint as empty.
         *
         * An empty field that displays a hint reports the *hint* string via getText().
         * `isShowingHintText()` (API 26+; minSdk is 29) is meant to disambiguate this,
         * but several widgets — Compose text fields and some vendor `EditText` builds —
         * return the hint from getText() while `isShowingHintText()` still reports
         * `false`. Relying on that flag alone let the hint leak in as a "prefix" that got
         * appended ahead of the dictated text. So a field is *also* treated as empty when
         * its text is exactly equal to its hint text. Extracted as a pure function so it
         * is unit-testable without the framework `AccessibilityNodeInfo`.
         */
        internal fun resolveExistingText(
            text: String?,
            hint: String?,
            isShowingHintText: Boolean
        ): String {
            if (isShowingHintText) return ""
            if (!text.isNullOrEmpty() && text == hint) return ""
            return text.orEmpty()
        }

        /** Result of splicing text into a field: the full new text + where the caret lands. */
        data class InsertionEdit(val newText: String, val newCursor: Int)

        /**
         * Pure, framework-free cursor-aware insertion logic (unit-testable).
         *
         * REPLACE overwrites the whole field. APPEND splices [inserted] in at the caret,
         * replacing any selected range `[selStart, selEnd)`; when the selection is unknown
         * (`-1`) or out of range it falls back to the end of [existing] (classic append).
         * A single separating space is added on either side only where needed so the
         * inserted text neither runs into an adjacent word nor gets a stray space before
         * clinging punctuation. The returned caret sits immediately after the inserted
         * text (before any right-hand glue/trailing text).
         */
        internal fun computeInsertion(
            existing: String,
            inserted: String,
            selStart: Int,
            selEnd: Int,
            mode: InsertMode
        ): InsertionEdit {
            if (mode == InsertMode.REPLACE) return InsertionEdit(inserted, inserted.length)

            val len = existing.length
            val validSel = selStart in 0..len && selEnd in 0..len
            val start = if (validSel) minOf(selStart, selEnd) else len
            val end = if (validSel) maxOf(selStart, selEnd) else len
            val before = existing.substring(0, start)
            val after = existing.substring(end)

            val leftGlue = if (needsLeftGlue(before, inserted)) " " else ""
            val rightGlue = if (needsRightGlue(after, inserted)) " " else ""

            val newText = before + leftGlue + inserted + rightGlue + after
            val newCursor = before.length + leftGlue.length + inserted.length
            return InsertionEdit(newText, newCursor)
        }

        /**
         * Backwards-compatible append helper (caret at end, no selection). Retained so the
         * append-glue behaviour stays exercised by unit tests.
         */
        internal fun combine(existing: String, inserted: String, mode: InsertMode): String =
            computeInsertion(existing, inserted, existing.length, existing.length, mode).newText

        private fun needsLeftGlue(before: String, inserted: String): Boolean =
            before.isNotEmpty() && !before.last().isWhitespace() &&
                inserted.isNotEmpty() && !inserted.first().isWhitespace() &&
                inserted.first() !in CLING_PUNCT

        private fun needsRightGlue(after: String, inserted: String): Boolean =
            after.isNotEmpty() && !after.first().isWhitespace() &&
                after.first() !in CLING_PUNCT &&
                inserted.isNotEmpty() && !inserted.last().isWhitespace()
    }
}
