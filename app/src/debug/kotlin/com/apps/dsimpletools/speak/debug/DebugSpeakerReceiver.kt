package com.apps.dsimpletools.speak.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.apps.dsimpletools.speak.speaker.SpeakerVerifier
import kotlin.concurrent.thread

/**
 * Debug-only broadcast hooks for scripted testing of the Step 3 speaker-isolation gate.
 * Only declared in src/debug/AndroidManifest.xml, so they never ship in release. All the
 * heavy work (WAV read, VAD, embedding compute) runs on a background thread; the app
 * process is kept alive by the always-running accessibility service.
 *
 * Enroll the owner from WAV files (VAD-trims each, same pipeline as UI enrollment; replaces
 * any existing profile):
 *   adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_ENROLL_WAV \
 *       -p com.apps.dsimpletools.speak --es paths "/sdcard/.../a.wav,/sdcard/.../b.wav"
 *
 * APPEND WAV utterances to the existing profile (no replacement; capped at
 * [SpeakerProfileStore.MAX_UTTERANCES] with oldest-out rotation):
 *   adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_APPEND_ENROLL_WAV \
 *       -p com.apps.dsimpletools.speak --es paths "/sdcard/.../d.wav"
 *
 * Verify a WAV against the current profile (logs per-segment SPEAKER_ACCEPT/REJECT + sim;
 * NO enrollment change, NO insert):
 *   adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_VERIFY_WAV \
 *       -p com.apps.dsimpletools.speak --es path /sdcard/.../c.wav
 *
 * Set the gate flag and/or threshold (both extras optional):
 *   adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_SET_SPEAKER \
 *       -p com.apps.dsimpletools.speak --es gate on|off --ef threshold 0.55
 *
 * Dump the gate state to logcat:
 *   adb shell am broadcast -a com.apps.dsimpletools.speak.DEBUG_SPEAKER_STATUS \
 *       -p com.apps.dsimpletools.speak
 */
class DebugSpeakerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val verifier = SpeakerVerifier.getInstance(context)
        when (intent.action) {
            ACTION_ENROLL_WAV -> {
                val paths = intent.getStringExtra("paths")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                if (paths.isEmpty()) {
                    Log.w(TAG, "DEBUG_ENROLL_WAV: missing/empty --es paths extra")
                    return
                }
                Log.i(TAG, "DEBUG_ENROLL_WAV: enrolling from ${paths.size} wav(s)")
                thread(name = "speak-debug-enroll") {
                    val ok = verifier.enrollFromWavs(paths)
                    Log.i(TAG, "DEBUG_ENROLL_WAV: ok=$ok")
                    verifier.logStatus()
                }
            }
            ACTION_APPEND_ENROLL_WAV -> {
                val paths = intent.getStringExtra("paths")
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                if (paths.isEmpty()) {
                    Log.w(TAG, "DEBUG_APPEND_ENROLL_WAV: missing/empty --es paths extra")
                    return
                }
                Log.i(TAG, "DEBUG_APPEND_ENROLL_WAV: appending ${paths.size} wav(s)")
                thread(name = "speak-debug-append") {
                    val ok = verifier.appendFromWavs(paths)
                    Log.i(TAG, "DEBUG_APPEND_ENROLL_WAV: ok=$ok")
                    verifier.logStatus()
                }
            }
            ACTION_VERIFY_WAV -> {
                val path = intent.getStringExtra("path")
                if (path.isNullOrBlank()) {
                    Log.w(TAG, "DEBUG_VERIFY_WAV: missing --es path extra")
                    return
                }
                Log.i(TAG, "DEBUG_VERIFY_WAV: $path")
                thread(name = "speak-debug-verify") { verifier.verifyWav(path) }
            }
            ACTION_SET_SPEAKER -> {
                intent.getStringExtra("gate")?.trim()?.lowercase()?.let { g ->
                    when (g) {
                        "on", "true", "1" -> verifier.gateEnabled = true
                        "off", "false", "0" -> verifier.gateEnabled = false
                        else -> Log.w(TAG, "DEBUG_SET_SPEAKER: bad --es gate '$g' (want on|off)")
                    }
                }
                if (intent.hasExtra("threshold")) {
                    val t = intent.getFloatExtra("threshold", verifier.threshold)
                    verifier.threshold = t
                }
                verifier.logStatus()
            }
            ACTION_STATUS -> verifier.logStatus()
            else -> Log.w(TAG, "DEBUG_SPEAKER: unexpected action ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "Speak.DebugSpeaker"
        private const val ACTION_ENROLL_WAV = "com.apps.dsimpletools.speak.DEBUG_ENROLL_WAV"
        private const val ACTION_APPEND_ENROLL_WAV = "com.apps.dsimpletools.speak.DEBUG_APPEND_ENROLL_WAV"
        private const val ACTION_VERIFY_WAV = "com.apps.dsimpletools.speak.DEBUG_VERIFY_WAV"
        private const val ACTION_SET_SPEAKER = "com.apps.dsimpletools.speak.DEBUG_SET_SPEAKER"
        private const val ACTION_STATUS = "com.apps.dsimpletools.speak.DEBUG_SPEAKER_STATUS"
    }
}
