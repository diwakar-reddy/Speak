package com.apps.dsimpletools.speak.insert

import com.apps.dsimpletools.speak.insert.AccessibilityTextInserter.Companion.combine
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
}
