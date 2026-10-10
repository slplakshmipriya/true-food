package com.barelabel.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * IngredientExplainer: every real flagged category has curated reason text;
 * anything else falls back to a neutral line (never null/empty).
 */
public class IngredientExplainerTest {

    @Test
    public void knownCategory_returnsCuratedText() {
        assertTrue(IngredientExplainer.reasonFor("Artificial Colors")
                .contains("Synthetic dyes"));
    }

    @Test
    public void unknownCategory_returnsFallback() {
        assertEquals("You flagged this category in your avoid list.",
                IngredientExplainer.reasonFor("Some New Category"));
    }

    @Test
    public void nullCategory_returnsFallback() {
        assertEquals("You flagged this category in your avoid list.",
                IngredientExplainer.reasonFor(null));
    }

    @Test
    public void allCuratedReasons_nonEmpty() {
        for (String reason : IngredientExplainer.allReasons().values()) {
            assertFalse(reason.trim().isEmpty());
        }
    }

    @Test
    public void whitespace_trimmedBeforeLookup() {
        assertTrue(IngredientExplainer.reasonFor("  Dairy  ")
                .contains("Milk-derived"));
    }
}
