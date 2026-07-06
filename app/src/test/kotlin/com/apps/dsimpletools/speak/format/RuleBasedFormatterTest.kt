package com.apps.dsimpletools.speak.format

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Thorough coverage of the deterministic rule-based cleanup. These are plain JVM
 * tests — [RuleBasedFormatter] has no Android dependencies.
 */
class RuleBasedFormatterTest {

    private val formatter = RuleBasedFormatter()
    private fun clean(input: String) = formatter.clean(input)

    // ---- filler removal: position ----

    @Test fun removesFillerAtSentenceStart_andRecapitalizes() {
        assertEquals("Hello world.", clean("Um, hello world."))
    }

    @Test fun removesFillerInMiddle() {
        assertEquals("So, I think so.", clean("So, um, I think so."))
    }

    @Test fun removesFillerAtEnd() {
        assertEquals("I think so", clean("I think so um"))
    }

    @Test fun removesMultipleFillers() {
        assertEquals("So I think.", clean("Um, uh, so I I think."))
    }

    @Test fun removesVariousFillerTokens() {
        assertEquals("Okay, then.", clean("Er, okay, hmm, then."))
        assertEquals("Yes.", clean("Ah, yes."))
    }

    @Test fun removesMmHmmVariant() {
        // Documented caveat: the mm-hmm affirmation family is treated as a filler.
        assertEquals("That works.", clean("Mm-hmm, that works."))
    }

    // ---- filler removal must not touch real words ----

    @Test fun doesNotStripFillerSubstringsInsideWords() {
        assertEquals("The umbrella is huge.", clean("The umbrella is huge."))
        assertEquals("Her water is over here.", clean("Her water is over here."))
        assertEquals("Aha moment.", clean("Aha moment."))
    }

    // ---- punctuation handling around fillers ----

    @Test fun collapsesCommaLeftDanglingByFiller() {
        // "Hello, um." -> the comma before the filler + trailing period must not double up.
        assertEquals("Hello.", clean("Hello, um."))
    }

    @Test fun stripsTrailingCommaLeftByFiller() {
        assertEquals("Okay", clean("Okay, um"))
    }

    // ---- duplicate collapse ----

    @Test fun collapsesImmediateDuplicateWord() {
        assertEquals("The dog.", clean("the the dog."))
    }

    @Test fun collapsesDuplicatePronoun() {
        assertEquals("I think we should meet.", clean("I I think we should meet."))
    }

    @Test fun collapseKeepsFirstCasing() {
        // Second occurrence is dropped; the first occurrence's casing is preserved.
        assertEquals("The dog.", clean("The THE dog."))
        assertEquals("The dog.", clean("the The dog."))
    }

    @Test fun doesNotCollapseWhitelistedDoubles() {
        assertEquals("I had had enough.", clean("I had had enough."))
        assertEquals("The book that that shop sells.", clean("The book that that shop sells."))
    }

    @Test fun doesNotCollapseAcrossPunctuation() {
        // A word repeated across a sentence boundary is not an immediate duplicate.
        assertEquals("I know. Know this.", clean("I know. know this."))
    }

    // ---- capitalization repair ----

    @Test fun capitalizesFirstLetter() {
        assertEquals("Hello there.", clean("hello there."))
    }

    @Test fun capitalizesAfterSentenceBoundary() {
        assertEquals("I see. Okay then.", clean("i see. um, okay then."))
    }

    @Test fun doesNotCapitalizeLetterDirectlyAfterPeriodWithoutSpace() {
        // The no-whitespace guard keeps dotted forms (URLs, abbreviations) intact.
        assertEquals("Go to www.example.com now.", clean("go to www.example.com now."))
    }

    // ---- whitespace normalization ----

    @Test fun normalizesWhitespaceAndSpaceBeforePunctuation() {
        assertEquals("Hello world, now.", clean("hello    world  ,  now."))
    }

    // ---- pass-through / idempotence ----

    @Test fun cleanInputPassesThroughUnchanged() {
        val cleanText = "The quick brown fox jumps over the lazy dog."
        assertEquals(cleanText, clean(cleanText))
    }

    @Test fun blankInputYieldsEmpty() {
        assertEquals("", clean(""))
        assertEquals("", clean("    "))
    }

    @Test fun isIdempotent() {
        val samples = listOf(
            "Um, so I think, uh, we should we should meet at at 3pm, er, tomorrow.",
            "the the dog.",
            "Hello, um.",
            "I had had enough.",
            "The quick brown fox jumps over the lazy dog."
        )
        for (s in samples) {
            val once = clean(s)
            assertEquals("idempotence failed for: $s", once, clean(once))
        }
    }

    // ---- the orchestrator's canonical sample ----

    @Test fun canonicalDictationSample() {
        val raw = "Um, so I think, uh, we should we should meet at at 3pm, er, tomorrow."
        // Single-word duplicate "at at" collapses; the phrase repeat "we should we should"
        // is left for the LLM (FULL) path by design.
        assertEquals(
            "So I think, we should we should meet at 3pm, tomorrow.",
            clean(raw)
        )
    }
}
