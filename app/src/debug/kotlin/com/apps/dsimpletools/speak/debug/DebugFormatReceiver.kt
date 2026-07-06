package com.apps.dsimpletools.speak.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.apps.dsimpletools.speak.format.FormatController
import com.apps.dsimpletools.speak.format.FormatLevel

/**
 * Debug-only broadcast hooks for scripted testing of the Step 2 formatting layer.
 * Only declared in src/debug/AndroidManifest.xml, so they never ship in release.
 *
 * Set the formatting level (persisted):
 *   adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_SET_FORMAT_LEVEL \
 *       -p com.apps.dsimpletools.speak --es level OFF|LIGHT|FULL
 *
 * Run the full pipeline on arbitrary text (logs FORMAT_RESULT + any FORMAT_LLM_*),
 * so the LLM path can be exercised without dictating:
 *   adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_FORMAT_TEXT \
 *       -p com.apps.dsimpletools.speak --es text "Um, so I think, uh, we should meet."
 */
class DebugFormatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = FormatController.getInstance(context)
        when (intent.action) {
            ACTION_SET_LEVEL -> {
                val raw = intent.getStringExtra("level")?.trim()?.uppercase()
                val level = raw?.let { runCatching { FormatLevel.valueOf(it) }.getOrNull() }
                if (level == null) {
                    Log.w(TAG, "DEBUG_SET_FORMAT_LEVEL: bad --es level '$raw' (want OFF|LIGHT|FULL)")
                    return
                }
                controller.level = level
                Log.i(TAG, "DEBUG_SET_FORMAT_LEVEL: level=$level")
            }
            ACTION_FORMAT_TEXT -> {
                val text = intent.getStringExtra("text")
                if (text.isNullOrEmpty()) {
                    Log.w(TAG, "DEBUG_FORMAT_TEXT: missing --es text extra")
                    return
                }
                Log.i(TAG, "DEBUG_FORMAT_TEXT: running pipeline (level=${controller.level})")
                controller.debugFormat(text)
            }
            else -> Log.w(TAG, "DEBUG_FORMAT: unexpected action ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "Speak.DebugFormat"
        private const val ACTION_SET_LEVEL = "com.apps.dsimpletools.speak.DEBUG_SET_FORMAT_LEVEL"
        private const val ACTION_FORMAT_TEXT = "com.apps.dsimpletools.speak.DEBUG_FORMAT_TEXT"
    }
}
