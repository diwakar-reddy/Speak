package com.apps.dsimpletools.speak.overlay

/**
 * Pure decision for whether the floating mic bubble should be visible, kept
 * separate from the accessibility service so it can be reasoned about and unit
 * tested without the Android framework.
 *
 * Rule (Step-3 hardening): the bubble is visible iff
 *   (an editable field currently has input focus AND the IME window is visible)
 *   OR a dictation session is active (LISTENING/PROCESSING).
 *
 * The session-active clause is what keeps the bubble on screen mid-dictation even
 * if the IME hides — losing the bubble while listening/processing would strand the
 * user with no way to stop the session. The IME-visibility clause is what makes the
 * bubble disappear when the keyboard is dismissed (e.g. via the back button) even
 * though the field technically still holds focus.
 */
object BubbleVisibilityPolicy {

    fun shouldShow(
        editableFocused: Boolean,
        imeVisible: Boolean,
        sessionActive: Boolean
    ): Boolean = (editableFocused && imeVisible) || sessionActive
}
