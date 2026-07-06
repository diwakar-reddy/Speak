package com.apps.dsimpletools.speak.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth-table coverage of the bubble-visibility rule:
 *   show iff (editable focused AND IME visible) OR a dictation session is active.
 */
class BubbleVisibilityPolicyTest {

    private fun show(editable: Boolean, ime: Boolean, session: Boolean) =
        BubbleVisibilityPolicy.shouldShow(editable, ime, session)

    @Test fun focusedFieldWithImeVisible_shows() {
        assertTrue(show(editable = true, ime = true, session = false))
    }

    @Test fun focusedFieldWithImeHidden_hides() {
        // The IME-dismissed-via-back case: field still focused but keyboard gone.
        assertFalse(show(editable = true, ime = false, session = false))
    }

    @Test fun noFocusButImeVisible_hides() {
        assertFalse(show(editable = false, ime = true, session = false))
    }

    @Test fun nothingFocusedNoImeNoSession_hides() {
        assertFalse(show(editable = false, ime = false, session = false))
    }

    @Test fun activeSessionKeepsBubble_evenWithoutFocusOrIme() {
        // Mid-dictation: the bubble must never disappear even if the IME hides or the
        // field loses focus — the user still needs to tap it to stop.
        assertTrue(show(editable = false, ime = false, session = true))
        assertTrue(show(editable = true, ime = false, session = true))
        assertTrue(show(editable = false, ime = true, session = true))
        assertTrue(show(editable = true, ime = true, session = true))
    }
}
