package com.apps.dsimpletools.speak.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.apps.dsimpletools.speak.accessibility.DictationAccessibilityService

/**
 * Debug-only hook to exercise the insertion path deterministically, with no
 * microphone: inserts a fixed string into the currently focused editable field via
 * the exact same [com.apps.dsimpletools.speak.insert.TextInserter] path a real
 * dictation uses (APPEND). Used to verify the hint/"prefix" resolution — that a
 * field's placeholder is never concatenated ahead of the inserted text.
 *
 *   adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_INSERT_TEXT \
 *       -p com.apps.dsimpletools.speak --es text "hello world"
 *
 * Only declared in src/debug/AndroidManifest.xml, so it never ships in release.
 */
class DebugInsertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text")
        Log.i(TAG, "DEBUG_INSERT_TEXT: broadcast received text=\"$text\"")
        if (text.isNullOrEmpty()) {
            Log.w(TAG, "DEBUG_INSERT_TEXT: missing --es text extra")
            return
        }
        val handled = DictationAccessibilityService.debugInsert(text)
        Log.i(TAG, "DEBUG_INSERT_TEXT: handled=$handled")
    }

    companion object {
        private const val TAG = "Speak.DebugInsert"
    }
}
