package com.apps.dsimpletools.speak.format

import android.os.SystemClock
import android.util.Log

/**
 * Orchestrates the two-stage cleanup: raw -> [RuleBasedFormatter] -> (FULL only)
 * [GeminiNanoFormatter]. The LLM stage only runs at [FormatLevel.FULL] and always
 * receives the rule-cleaned text; if it declines/fails it returns that text
 * unchanged, so the pipeline degrades to LIGHT-quality output, never worse.
 *
 * Emits a single [Log] line per call:
 *   `FORMAT_RESULT: level=<L> ruleMs=<x> llmMs=<y> "<raw>" -> "<final>"`
 */
class FormattingPipeline(
    private val ruleBased: RuleBasedFormatter,
    private val nano: GeminiNanoFormatter?
) {
    suspend fun format(raw: String, level: FormatLevel): String {
        if (raw.isBlank()) return raw

        var ruleMs = 0L
        var llmMs = 0L

        val ruled = if (level == FormatLevel.OFF) {
            raw
        } else {
            val t0 = SystemClock.elapsedRealtime()
            ruleBased.clean(raw).also { ruleMs = SystemClock.elapsedRealtime() - t0 }
        }

        val finalText = if (level == FormatLevel.FULL && nano != null) {
            val t0 = SystemClock.elapsedRealtime()
            nano.format(ruled).also { llmMs = SystemClock.elapsedRealtime() - t0 }
        } else {
            ruled
        }

        Log.i(
            TAG,
            "FORMAT_RESULT: level=$level ruleMs=$ruleMs llmMs=$llmMs " +
                "\"${esc(raw)}\" -> \"${esc(finalText)}\""
        )
        return finalText
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    companion object {
        private const val TAG = "Speak.Format"
    }
}
