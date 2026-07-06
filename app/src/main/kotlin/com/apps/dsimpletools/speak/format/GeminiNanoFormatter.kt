package com.apps.dsimpletools.speak.format

import android.content.Context
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.proofreading.Proofreader
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * On-device LLM cleanup via the ML Kit GenAI **Proofreading** API (Gemini Nano
 * running through AICore).
 *
 * Why Proofreading (not Rewriting / Prompt)? The task is "clean up a dictated
 * transcript: remove fillers/self-corrections, fix grammar, DO NOT paraphrase or
 * add content, preserve meaning and tone". The Proofreading API is purpose-built
 * to *polish short content by refining grammar and fixing spelling* — it is the
 * conservative, meaning-preserving option and even exposes an
 * [ProofreaderOptions.InputType.VOICE] mode designed for transcribed speech. The
 * Rewriting API does the opposite (Elaborate/Emojify/Shorten all change length,
 * tone or content) and the free-form Prompt API is the most prone to
 * paraphrasing/hallucination/expansion — exactly the over-editing we must avoid.
 *
 * Safety contract: this class NEVER throws out of [format] and NEVER blocks the
 * dictation flow. Every failure mode (feature unavailable, download needed,
 * timeout, exception, over-edit) returns the input string unchanged and logs a
 * `FORMAT_LLM_*` marker.
 *
 * Artifact: `com.google.mlkit:genai-proofreading:1.0.0-beta1` (minSdk 26).
 */
class GeminiNanoFormatter(context: Context) : TranscriptFormatter {

    private val appContext = context.applicationContext

    // Lazily created and held for the app lifetime; getClient() is cheap and the
    // feature-status/model handshake happens on the first checkFeatureStatus() call.
    @Volatile
    private var client: Proofreader? = null

    private fun proofreaderOrNull(): Proofreader? {
        client?.let { return it }
        return synchronized(this) {
            client ?: runCatching {
                val options = ProofreaderOptions.builder(appContext)
                    .setInputType(ProofreaderOptions.InputType.VOICE)
                    .setLanguage(ProofreaderOptions.Language.ENGLISH)
                    .build()
                Proofreading.getClient(options)
            }.onFailure {
                Log.w(TAG, "FORMAT_LLM_ERROR getClient ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()?.also { client = it }
        }
    }

    /** Feature availability, mapped for the settings status line. Never throws. */
    suspend fun featureState(): NanoFeatureState {
        val proofreader = proofreaderOrNull() ?: return NanoFeatureState.UNAVAILABLE
        val status = runCatching { proofreader.checkFeatureStatus().await() }.getOrElse {
            Log.d(TAG, "featureState check failed ${it.javaClass.simpleName}: ${it.message}")
            return NanoFeatureState.UNAVAILABLE
        }
        return when (status) {
            FeatureStatus.AVAILABLE -> NanoFeatureState.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> NanoFeatureState.DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> NanoFeatureState.DOWNLOADING
            else -> NanoFeatureState.UNAVAILABLE
        }
    }

    override suspend fun format(raw: String): String {
        if (raw.isBlank()) return raw
        if (raw.length > MAX_INPUT_CHARS) {
            // Proofreading input must be short (< 256 tokens); skip rather than error out.
            Log.i(TAG, "FORMAT_LLM_SKIP too_long len=${raw.length}")
            return raw
        }
        val proofreader = proofreaderOrNull() ?: return raw

        val status = runCatching { proofreader.checkFeatureStatus().await() }.getOrElse {
            Log.w(TAG, "FORMAT_LLM_ERROR checkFeatureStatus ${it.javaClass.simpleName}: ${it.message}")
            return raw
        }
        return when (status) {
            FeatureStatus.AVAILABLE -> runInferenceGuarded(proofreader, raw)
            FeatureStatus.DOWNLOADABLE -> {
                Log.i(TAG, "FORMAT_LLM_DOWNLOADING kicking off feature download")
                startDownload(proofreader)
                raw
            }
            FeatureStatus.DOWNLOADING -> {
                Log.i(TAG, "FORMAT_LLM_DOWNLOADING already in progress")
                raw
            }
            else -> {
                Log.i(TAG, "FORMAT_LLM_UNAVAILABLE status=$status")
                raw
            }
        }
    }

    private suspend fun runInferenceGuarded(proofreader: Proofreader, input: String): String {
        val output = try {
            withTimeout(LLM_TIMEOUT_MS) {
                val request = ProofreadingRequest.builder(input).build()
                val result = proofreader.runInference(request).await()
                // Suggestions come sorted by descending confidence; take the top one.
                result.results.firstOrNull()?.text?.trim().orEmpty()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "FORMAT_LLM_TIMEOUT after ${LLM_TIMEOUT_MS}ms")
            return input
        } catch (e: Throwable) {
            Log.w(TAG, "FORMAT_LLM_ERROR runInference ${e.javaClass.simpleName}: ${e.message}")
            return input
        }

        // Over-edit guard: the model should be cleaning, not rewriting or expanding.
        if (output.isEmpty()) {
            Log.w(TAG, "FORMAT_LLM_REJECTED empty in=\"${esc(input)}\"")
            return input
        }
        val deviation = abs(output.length - input.length).toDouble() / input.length
        if (deviation > MAX_LENGTH_DEVIATION) {
            Log.w(
                TAG,
                "FORMAT_LLM_REJECTED deviation=${"%.2f".format(deviation)} " +
                    "in=\"${esc(input)}\" out=\"${esc(output)}\""
            )
            return input
        }
        return output
    }

    private fun startDownload(proofreader: Proofreader) {
        runCatching {
            proofreader.downloadFeature(object : DownloadCallback {
                override fun onDownloadStarted(bytesToDownload: Long) {
                    Log.i(TAG, "FORMAT_LLM_DOWNLOADING started bytes=$bytesToDownload")
                }

                override fun onDownloadProgress(totalBytesDownloaded: Long) {}

                override fun onDownloadCompleted() {
                    Log.i(TAG, "FORMAT_LLM_DOWNLOADING completed")
                }

                override fun onDownloadFailed(e: GenAiException) {
                    Log.w(TAG, "FORMAT_LLM_ERROR download ${e.javaClass.simpleName}: ${e.message}")
                }
            })
        }.onFailure {
            Log.w(TAG, "FORMAT_LLM_ERROR downloadFeature ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    fun close() {
        runCatching { client?.close() }
        client = null
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    companion object {
        private const val TAG = "Speak.Format"
        private const val LLM_TIMEOUT_MS = 2000L
        private const val MAX_LENGTH_DEVIATION = 0.40
        private const val MAX_INPUT_CHARS = 800
    }
}

/**
 * Await a [ListenableFuture] from a coroutine without pulling in the full Guava /
 * kotlinx-coroutines-guava artifacts — the `listenablefuture` stub that ML Kit
 * already brings in is enough. Cancels the future if the coroutine is cancelled
 * (e.g. by [withTimeout]).
 */
internal suspend fun <T> ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: ExecutionException) {
                cont.resumeWithException(e.cause ?: e)
            } catch (e: CancellationException) {
                cont.cancel(e)
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }, Executor { it.run() })
        cont.invokeOnCancellation { runCatching { cancel(true) } }
    }
