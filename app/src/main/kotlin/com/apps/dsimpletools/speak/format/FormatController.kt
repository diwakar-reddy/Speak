package com.apps.dsimpletools.speak.format

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App-lifetime singleton (manual, no DI — matches the project style) that owns the
 * formatting stack: the rule-based formatter, the Gemini Nano formatter, the
 * pipeline, and the persisted [FormatLevel].
 *
 * The default level is FULL when the device reports the Nano feature as
 * available/downloadable, else LIGHT. The default is resolved asynchronously on
 * first construction; until it resolves the getter returns a safe provisional
 * LIGHT. Once the user picks a level explicitly it is persisted and always wins.
 */
class FormatController private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val ruleBased = RuleBasedFormatter()
    private val nano = GeminiNanoFormatter(appContext)
    private val pipeline = FormattingPipeline(ruleBased, nano)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var resolvedDefault: FormatLevel = FormatLevel.LIGHT

    init {
        // Resolve the availability-driven default without blocking construction.
        scope.launch {
            resolvedDefault = when (nano.featureState()) {
                NanoFeatureState.AVAILABLE, NanoFeatureState.DOWNLOADABLE -> FormatLevel.FULL
                else -> FormatLevel.LIGHT
            }
            Log.i(TAG, "FORMAT_DEFAULT resolved=$resolvedDefault (explicit=${storedLevel() != null})")
        }
    }

    /** The effective level: an explicit user choice if present, else the resolved default. */
    var level: FormatLevel
        get() = storedLevel() ?: resolvedDefault
        set(value) {
            prefs.edit().putString(KEY_LEVEL, value.name).apply()
            Log.i(TAG, "FORMAT_LEVEL set to $value")
        }

    private fun storedLevel(): FormatLevel? =
        prefs.getString(KEY_LEVEL, null)?.let { runCatching { FormatLevel.valueOf(it) }.getOrNull() }

    /** Runs the full pipeline at the current level; logs FORMAT_RESULT. */
    suspend fun format(raw: String): String = pipeline.format(raw, level)

    /** Current Nano feature availability for the settings status line. */
    suspend fun nanoFeatureState(): NanoFeatureState = nano.featureState()

    /** Debug hook (DEBUG_FORMAT_TEXT): run the pipeline on arbitrary text, fire-and-forget. */
    fun debugFormat(text: String) {
        scope.launch { runCatching { format(text) } }
    }

    companion object {
        private const val TAG = "Speak.Format"
        private const val PREFS = "speak_format"
        private const val KEY_LEVEL = "format_level"

        @Volatile
        private var instance: FormatController? = null

        fun getInstance(context: Context): FormatController =
            instance ?: synchronized(this) {
                instance ?: FormatController(context).also { instance = it }
            }
    }
}
