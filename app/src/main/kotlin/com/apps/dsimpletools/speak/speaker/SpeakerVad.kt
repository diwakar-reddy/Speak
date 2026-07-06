package com.apps.dsimpletools.speak.speaker

import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * A short-lived Silero VAD used to trim silence out of enrollment / verification audio.
 *
 * Deliberately a *separate* Vad instance from the one [com.apps.dsimpletools.speak.asr.SherpaAsrEngine]
 * owns: enrollment (and the debug WAV probes) must never touch the live recogniser's
 * stateful native VAD, which is single-flight-owned by a dictation session. Each helper
 * is created for one operation and released immediately.
 *
 * The windowing/segmenting mirrors the engine (512-sample windows for Silero at 16 kHz).
 */
class SpeakerVad private constructor(private val vad: Vad) {

    /**
     * Run VAD over [samples] and return every detected speech segment's samples, in order.
     * Silence between/around speech is dropped. Returns an empty list if no speech is found.
     */
    fun segments(samples: FloatArray): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        vad.reset()
        var offset = 0
        while (offset + WINDOW <= samples.size) {
            vad.acceptWaveform(samples.copyOfRange(offset, offset + WINDOW))
            offset += WINDOW
            drain(out)
        }
        if (offset < samples.size) {
            // Pad the final partial window with zeros so the tail speech is still seen.
            val tail = FloatArray(WINDOW)
            System.arraycopy(samples, offset, tail, 0, samples.size - offset)
            vad.acceptWaveform(tail)
            drain(out)
        }
        vad.flush()
        drain(out)
        return out
    }

    /** Concatenate all detected speech segments into one contiguous float buffer. */
    fun trim(samples: FloatArray): FloatArray {
        val segs = segments(samples)
        if (segs.isEmpty()) return FloatArray(0)
        val total = segs.sumOf { it.size }
        val out = FloatArray(total)
        var pos = 0
        for (s in segs) {
            System.arraycopy(s, 0, out, pos, s.size)
            pos += s.size
        }
        return out
    }

    private fun drain(out: MutableList<FloatArray>) {
        while (!vad.empty()) {
            out.add(vad.front().samples)
            vad.pop()
        }
    }

    fun release() {
        runCatching { vad.release() }
    }

    companion object {
        private const val TAG = "Speak.SpeakerVad"
        private const val WINDOW = 512
        private const val SAMPLE_RATE = 16000
        private const val THRESHOLD = 0.5f
        private const val MIN_SILENCE_S = 0.5f
        private const val MIN_SPEECH_S = 0.25f
        private const val MAX_SPEECH_S = 30.0f

        /**
         * Create a VAD from the silero model at [modelPath]. Returns null (logged) if the
         * model cannot be loaded, so callers can degrade gracefully.
         */
        fun create(modelPath: String): SpeakerVad? = runCatching {
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelPath,
                    threshold = THRESHOLD,
                    minSilenceDuration = MIN_SILENCE_S,
                    minSpeechDuration = MIN_SPEECH_S,
                    windowSize = WINDOW,
                    maxSpeechDuration = MAX_SPEECH_S
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu"
            )
            SpeakerVad(Vad(assetManager = null, config = config))
        }.onFailure {
            Log.e(TAG, "SPEAKER_VAD_LOAD_FAILED ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }
}
