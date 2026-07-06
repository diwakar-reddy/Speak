package com.apps.dsimpletools.speak.insert

import com.apps.dsimpletools.speak.insert.AccessibilityTextInserter.Companion.combine
import com.apps.dsimpletools.speak.insert.AccessibilityTextInserter.Companion.computeInsertion
import com.apps.dsimpletools.speak.insert.AccessibilityTextInserter.Companion.resolveExistingText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure APPEND/REPLACE combination logic — in particular that an empty
 * (hint-showing) field never gets the placeholder concatenated, and the glue space
 * behaviour.
 */
class AccessibilityTextInserterTest {

    @Test fun appendIntoEmptyFieldInsertsOnlyTheText() {
        // A hint-showing field is resolved to "" by the caller; nothing leaks in.
        assertEquals("hello", combine("", "hello", InsertMode.APPEND))
    }

    @Test fun appendAddsSeparatingSpaceWhenExistingHasNoTrailingWhitespace() {
        assertEquals("EditText speak", combine("EditText", "speak", InsertMode.APPEND))
    }

    @Test fun appendDoesNotDoubleSpaceWhenExistingEndsInWhitespace() {
        assertEquals("EditText speak", combine("EditText ", "speak", InsertMode.APPEND))
        assertEquals("EditText\nspeak", combine("EditText\n", "speak", InsertMode.APPEND))
    }

    @Test fun replaceIgnoresExistingText() {
        assertEquals("new", combine("whatever was here", "new", InsertMode.REPLACE))
    }

    // ---- hint / prefix resolution (the "prefix still getting appended" fix) ----

    @Test fun resolveTreatsShowingHintAsEmpty() {
        assertEquals("", resolveExistingText("Message", "Message", isShowingHintText = true))
    }

    @Test fun resolveTreatsTextEqualToHintAsEmpty() {
        // The hint leaked via getText() even though isShowingHintText() reported false
        // (Compose fields / some vendor EditTexts): must not be treated as real content.
        assertEquals(
            "",
            resolveExistingText(
                text = "Tap here to focus the classic EditText",
                hint = "Tap here to focus the classic EditText",
                isShowingHintText = false
            )
        )
    }

    @Test fun resolveKeepsRealTextThatIsNotTheHint() {
        assertEquals("hello", resolveExistingText("hello", "Message", isShowingHintText = false))
    }

    @Test fun resolveEmptyOrNullTextIsEmpty() {
        assertEquals("", resolveExistingText(null, "Message", isShowingHintText = false))
        assertEquals("", resolveExistingText("", "Message", isShowingHintText = false))
    }

    @Test fun endToEnd_hintLeakedAsTextIsNotPrependedToDictation() {
        // Reproduces the reported bug: hint returned as text, then combined for APPEND.
        val existing = resolveExistingText(
            text = "Compose test field",
            hint = "Compose test field",
            isShowingHintText = false
        )
        assertEquals("hello world", combine(existing, "hello world", InsertMode.APPEND))
    }

    // ---- cursor-aware insertion (insert at caret / replace selection) ----

    private fun insertAt(existing: String, inserted: String, start: Int, end: Int) =
        computeInsertion(existing, inserted, start, end, InsertMode.APPEND)

    @Test fun caretAtEnd_appendsWithSpace() {
        val e = insertAt("hello", "world", 5, 5)
        assertEquals("hello world", e.newText)
        assertEquals(11, e.newCursor) // caret after inserted text
    }

    @Test fun emptyField_insertsOnlyText() {
        val e = insertAt("", "world", 0, 0)
        assertEquals("world", e.newText)
        assertEquals(5, e.newCursor)
    }

    @Test fun caretAtStart_insertsBeforeExistingWithSpace() {
        val e = insertAt("world", "hello", 0, 0)
        assertEquals("hello world", e.newText)
        assertEquals(5, e.newCursor) // caret sits right after "hello"
    }

    @Test fun caretInMiddle_splicesInPlace() {
        // "ab | cd" (caret at index 3, right after "ab ")
        val e = insertAt("ab cd", "X", 3, 3)
        assertEquals("ab X cd", e.newText)
        assertEquals(4, e.newCursor)
    }

    @Test fun selection_isReplaced() {
        // "hello [world] foo" -> replace "world" with "there"
        val e = insertAt("hello world foo", "there", 6, 11)
        assertEquals("hello there foo", e.newText)
        assertEquals(11, e.newCursor)
    }

    @Test fun reversedSelection_isNormalized() {
        val e = insertAt("hello world foo", "there", 11, 6)
        assertEquals("hello there foo", e.newText)
        assertEquals(11, e.newCursor)
    }

    @Test fun unknownSelection_fallsBackToAppend() {
        val e = insertAt("hello", "world", -1, -1)
        assertEquals("hello world", e.newText)
        assertEquals(11, e.newCursor)
    }

    @Test fun outOfRangeSelection_fallsBackToAppend() {
        val e = insertAt("hello", "world", 999, 999)
        assertEquals("hello world", e.newText)
        assertEquals(11, e.newCursor)
    }

    @Test fun leadingClingPunctuation_getsNoSpaceBefore() {
        val e = insertAt("hello", ", world", 5, 5)
        assertEquals("hello, world", e.newText)
    }

    @Test fun replaceMode_overwritesWholeField() {
        val e = computeInsertion("whatever was here", "new", 3, 3, InsertMode.REPLACE)
        assertEquals("new", e.newText)
        assertEquals(3, e.newCursor)
    }
}
