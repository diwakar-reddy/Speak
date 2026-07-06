package com.apps.dsimpletools.speak.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.apps.dsimpletools.speak.accessibility.DictationAccessibilityService

/**
 * Debug-only backup trigger for adb-driven testing when a real tap on the
 * overlay bubble isn't convenient/reliable to script:
 *
 *   adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_TAP \
 *       -p com.apps.dsimpletools.speak
 *
 * Invokes the exact same [DictationAccessibilityService.triggerDebugTap]
 * code path as a real bubble tap. Only declared in src/debug/AndroidManifest.xml
 * so it never ships in a release build.
 */
class DebugTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "DEBUG_TAP: broadcast received")
        val handled = DictationAccessibilityService.debugTap()
        Log.i(TAG, "DEBUG_TAP: handled=$handled")
    }

    companion object {
        private const val TAG = "Speak.DebugTap"
    }
}
