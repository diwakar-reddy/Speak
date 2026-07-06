package com.apps.dsimpletools.speak.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.apps.dsimpletools.speak.accessibility.DictationAccessibilityService

/**
 * Debug-only deterministic test hook: runs the full VAD -> ASR -> (punct) pipeline
 * over a WAV file on disk, no microphone required. Lets the recognition pipeline be
 * verified end-to-end and its ASR_FINAL / ASR_TIMING logged:
 *
 *   adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_TRANSCRIBE_WAV \
 *       -p com.apps.dsimpletools.speak \
 *       --es path /sdcard/Android/data/com.apps.dsimpletools.speak/files/models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/test_wavs/0.wav
 *
 * Routes through the live accessibility service (which owns the warm engine), so the
 * service must be enabled. Only declared in src/debug so it never ships in release.
 */
class DebugTranscribeWavReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path")
        Log.i(TAG, "DEBUG_TRANSCRIBE_WAV: broadcast received path=$path")
        if (path.isNullOrBlank()) {
            Log.w(TAG, "DEBUG_TRANSCRIBE_WAV: missing --es path extra")
            return
        }
        val handled = DictationAccessibilityService.debugTranscribeWav(path)
        Log.i(TAG, "DEBUG_TRANSCRIBE_WAV: handled=$handled")
    }

    companion object {
        private const val TAG = "Speak.DebugWav"
    }
}
