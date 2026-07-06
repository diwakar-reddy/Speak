package com.apps.dsimpletools.speak.format

/**
 * Cleans a raw dictation transcript into polished text.
 *
 * Implementations must be safe to call off the main thread and must never throw
 * out of [format]; on any internal failure they return their input unchanged so
 * the dictation flow can never be broken by the formatting layer.
 */
interface TranscriptFormatter {
    suspend fun format(raw: String): String
}

/**
 * How aggressively a dictated transcript is cleaned before insertion.
 *
 *  - [OFF]   nothing runs; the raw ASR transcript is inserted verbatim.
 *  - [LIGHT] the deterministic [RuleBasedFormatter] only (instant, on-device, no model).
 *  - [FULL]  rules first, then an on-device Gemini Nano proofreading pass (if ready).
 */
enum class FormatLevel { OFF, LIGHT, FULL }

/**
 * Runtime availability of the on-device Gemini Nano proofreading feature, surfaced
 * in the settings UI. Mirrors ML Kit's `FeatureStatus` int constants.
 */
enum class NanoFeatureState { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }
