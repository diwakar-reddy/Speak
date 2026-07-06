package com.apps.dsimpletools.speak.capture

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Attaches platform audio effects (noise suppression + acoustic echo cancellation) to an
 * [android.media.AudioRecord] session, when the device offers them, to clean up captured
 * audio before it reaches the VAD / recogniser / speaker gate. Used by BOTH capture paths
 * (live dictation and enrollment recording). Effects are tied to the AudioRecord's session
 * id and must be released alongside the recorder.
 */
object AudioEffects {

    private const val TAG = "Speak.AudioFx"

    /**
     * Attach NoiseSuppressor (and AcousticEchoCanceler when available) to [audioSessionId].
     * Logs `NS_ATTACHED <true|false>` exactly once so each capture session records whether
     * hardware/software noise suppression is in play. Returns the created effects so the
     * caller can [release] them with the recorder.
     */
    fun attach(audioSessionId: Int): List<AudioEffect> {
        val effects = mutableListOf<AudioEffect>()

        var nsAttached = false
        if (NoiseSuppressor.isAvailable()) {
            runCatching {
                NoiseSuppressor.create(audioSessionId)?.also {
                    it.enabled = true
                    effects.add(it)
                    nsAttached = true
                }
            }.onFailure { Log.w(TAG, "NoiseSuppressor.create failed: ${it.message}") }
        }
        Log.i(TAG, "NS_ATTACHED $nsAttached")

        if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(audioSessionId)?.also {
                    it.enabled = true
                    effects.add(it)
                }
            }.onFailure { Log.w(TAG, "AcousticEchoCanceler.create failed: ${it.message}") }
        }

        return effects
    }

    /** Release all previously [attach]ed effects. Safe to call with an empty list. */
    fun release(effects: List<AudioEffect>) {
        effects.forEach { runCatching { it.release() } }
    }
}
