package com.apps.dsimpletools.speak.format

/**
 * Deterministic, instant, always-safe transcript cleanup. Runs entirely in-process
 * (no model, no I/O) so it is cheap enough to run on every dictation and is fully
 * unit-testable on a plain JVM.
 *
 * The passes are intentionally conservative — the goal is to remove obvious spoken
 * artifacts without ever paraphrasing or dropping real content:
 *
 *  1. [removeFillers]        strip standalone filler tokens (um, uh, er, ...), including
 *                            an attached trailing comma, but never inside a real word.
 *  2. [collapseDuplicates]   collapse an immediately repeated word ("the the" -> "the"),
 *                            keeping the first casing and skipping a small whitelist of
 *                            legitimate doubles.
 *  3. [normalizeWhitespace]  single-space runs, no space before punctuation, no dangling
 *                            or doubled commas, trimmed.
 *  4. [repairCapitalization] re-capitalise the sentence start (needed after a leading
 *                            filler is removed) and the first letter of each sentence.
 *
 * Every pass is idempotent, so `format(format(x)) == format(x)`.
 */
class RuleBasedFormatter : TranscriptFormatter {

    override suspend fun format(raw: String): String = clean(raw)

    /** Synchronous entry point (the real work); called directly by unit tests. */
    fun clean(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw
        text = removeFillers(text)
        text = collapseDuplicates(text)
        text = normalizeWhitespace(text)
        text = repairCapitalization(text)
        return text
    }

    private fun removeFillers(input: String): String =
        FILLER_REGEX.replace(input, "")

    private fun collapseDuplicates(input: String): String {
        val tokens = input.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return input
        val out = ArrayList<String>(tokens.size)
        for (token in tokens) {
            val prev = out.lastOrNull()
            val collapsible = prev != null &&
                isPureWord(prev) &&
                isPureWord(token) &&
                prev.equals(token, ignoreCase = true) &&
                token.lowercase() !in ALLOWED_DOUBLES
            if (collapsible) continue // drop the repeat, keep the first (its casing)
            out.add(token)
        }
        return out.joinToString(" ")
    }

    private fun normalizeWhitespace(input: String): String {
        var text = WHITESPACE_REGEX.replace(input, " ")
        text = SPACE_BEFORE_PUNCT_REGEX.replace(text) { it.groupValues[1] }
        text = COMMA_BEFORE_TERMINAL_REGEX.replace(text) { it.groupValues[1] }
        text = DUP_COMMA_REGEX.replace(text, ",")
        text = LEADING_JUNK_REGEX.replace(text, "")
        text = TRAILING_JUNK_REGEX.replace(text, "")
        return text
    }

    private fun repairCapitalization(input: String): String {
        if (input.isEmpty()) return input
        var text = input
        // Very first letter of the transcript.
        val first = text.indexOfFirst { it.isLetter() }
        if (first >= 0 && text[first].isLowerCase()) {
            text = text.substring(0, first) + text[first].uppercaseChar() + text.substring(first + 1)
        }
        // First letter after each sentence-ending mark that is followed by whitespace
        // (the whitespace requirement avoids mangling abbreviations like "e.g.").
        text = SENTENCE_START_REGEX.replace(text) { m ->
            m.groupValues[1] + m.groupValues[2] + m.groupValues[3].uppercase()
        }
        return text
    }

    private fun isPureWord(token: String): Boolean =
        token.isNotEmpty() && token.all { it.isLetter() || it == '\'' }

    companion object {
        /**
         * Standalone spoken fillers. Elongations (umm/uhhh) are included; the
         * "mm-hmm" affirmation family is included per spec — note that removing it
         * can drop a spoken "yes", but in dictated prose these are hesitation noises,
         * not content. Matched case-insensitively and only as whole words.
         */
        private val FILLER_WORDS = listOf(
            "ummm", "umm", "um", "uhhh", "uhh", "uhmm", "uhm", "uh",
            "err", "erm", "er", "ahh", "ah", "hmmm", "hmm",
            "mm-hmm", "mmhmm", "mhm"
        ).sortedByDescending { it.length }

        /**
         * Legitimate immediate doubles we refuse to collapse (a conservative
         * whitelist — better to leave a rare real repeat than to silently delete a
         * word the user meant). e.g. "I had had enough", "the food that that shop sells".
         */
        private val ALLOWED_DOUBLES = setOf("had", "that", "is", "do", "can")

        // A filler as a whole word, plus any immediately-attached comma and the
        // surrounding spaces, so "So, um, I think" collapses cleanly to "So, I think".
        private val FILLER_REGEX = Regex(
            "\\b(?:${FILLER_WORDS.joinToString("|") { Regex.escape(it) }})\\b\\s*,?\\s*",
            RegexOption.IGNORE_CASE
        )

        private val WHITESPACE_REGEX = Regex("\\s+")
        private val SPACE_BEFORE_PUNCT_REGEX = Regex("\\s+([,.!?;:])")
        private val COMMA_BEFORE_TERMINAL_REGEX = Regex(",([.!?;:])")
        private val DUP_COMMA_REGEX = Regex(",{2,}")
        private val LEADING_JUNK_REGEX = Regex("^[\\s,;:]+")
        private val TRAILING_JUNK_REGEX = Regex("[\\s,;:]+$")
        private val SENTENCE_START_REGEX = Regex("([.!?])(\\s+)([a-z])")
    }
}
