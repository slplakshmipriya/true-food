package com.barelabel.app.search;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * OcrCorrector.correct: conservative dictionary spellcheck for OCR'd
 * ingredient text. Only unambiguous corrections are applied.
 */
public class OcrCorrectorTest {

    private static Set<String> dict(String... words) {
        return new HashSet<>(Arrays.asList(words));
    }

    private static final Set<String> FLAGGED_WORDS = dict(
            "sugar", "salt", "preservative", "monosodium", "glutamate",
            "color", "caramel", "nitrate", "nitrite", "sulfite", "sulfites",
            "sulfate", "vanillin", "flour", "wheat", "milk");

    @Test
    public void correctsTypicalOcrErrors() {
        assertEquals("sugar", OcrCorrector.correct("suger", FLAGGED_WORDS));
        assertEquals("preservative",
                OcrCorrector.correct("preservatlve", FLAGGED_WORDS));
        assertEquals("monosodium glutamate",
                OcrCorrector.correct("monosodlum glutamate", FLAGGED_WORDS));
    }

    @Test
    public void correctsWithinSentencePreservingPunctuation() {
        assertEquals("flour, sugar, salt & preservative.",
                OcrCorrector.correct("flour, suger, salt & preservatlve.",
                        FLAGGED_WORDS));
    }

    @Test
    public void validWordsPassThroughUntouched() {
        // "quinoa" is not a flagged word and has no close dictionary
        // neighbor: left alone rather than force-corrected.
        assertEquals("quinoa, water, salt",
                OcrCorrector.correct("quinoa, water, salt", FLAGGED_WORDS));
        assertEquals("SUGAR", OcrCorrector.correct("SUGAR", FLAGGED_WORDS));
    }

    @Test
    public void ambiguousCorrectionIsLeftAlone() {
        // "sulfite" is one edit from both "sulfites" and "sulfate":
        // ambiguous, so untouched (a wrong fix is worse than a miss).
        assertEquals("sulfite", OcrCorrector.correct("sulfite", FLAGGED_WORDS));
    }

    @Test
    public void shortTokensAreNeverCorrected() {
        assertEquals("ab", OcrCorrector.correct("ab", FLAGGED_WORDS));
    }

    @Test
    public void allCapsCorrectionKeepsCase() {
        assertEquals("SUGAR", OcrCorrector.correct("SUGER", FLAGGED_WORDS));
    }

    @Test
    public void nullAndEmptyInputs() {
        assertEquals("", OcrCorrector.correct(null, FLAGGED_WORDS));
        assertEquals("", OcrCorrector.correct("", FLAGGED_WORDS));
        assertEquals("suger",
                OcrCorrector.correct("suger", new HashSet<String>()));
        assertEquals("suger", OcrCorrector.correct("suger", null));
    }

    // ---- correctPhrases: ingredient-level edit distance ----

    private static final Set<String> PHRASE_TERMS = dict(
            "all purpose flour", "wheat flour", "high fructose corn syrup",
            "palm oil", "corn oil", "cod oil");

    @Test
    public void phraseFixesMultiTokenMangle() {
        assertEquals("all purpose flour, yogurt",
                OcrCorrector.correctPhrases(
                        "AM purpose Fou, yogurt", PHRASE_TERMS));
    }

    @Test
    public void phraseFixesEachTokenOnce() {
        assertEquals("wheat flour and water",
                OcrCorrector.correctPhrases("whet flor and water", PHRASE_TERMS));
    }

    @Test
    public void phraseLeavesTiesAlone() {
        // "con oil" is 1 edit from both "corn oil" and "cod oil": ambiguous.
        assertEquals("con oil",
                OcrCorrector.correctPhrases("con oil", PHRASE_TERMS));
    }

    @Test
    public void phraseKeepsExactMatchVerbatim() {
        assertEquals("All Purpose Flour",
                OcrCorrector.correctPhrases("All Purpose Flour", PHRASE_TERMS));
    }

    @Test
    public void phrasePreservesPunctuation() {
        assertEquals("all purpose flour, yogurt!",
                OcrCorrector.correctPhrases("AM purpose Fou, yogurt!", PHRASE_TERMS));
    }

    @Test
    public void phraseEmptyInputs() {
        assertEquals("", OcrCorrector.correctPhrases("", PHRASE_TERMS));
        assertEquals("", OcrCorrector.correctPhrases(null, PHRASE_TERMS));
        assertEquals("flour",
                OcrCorrector.correctPhrases("flour", new HashSet<String>()));
    }
}
