package com.barelabel.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.regex.Pattern;

/**
 * Negation handling in the verdict: "no X" / "X free" must not flag X.
 * Tests the package-visible pure helpers directly (no Context needed).
 */
public class FlaggedIngredientManagerTest {

    private static final Pattern WHEAT = Pattern.compile("\\bwheats?\\b");
    private static final Pattern MAIDA = Pattern.compile("\\bmaidas?\\b");
    private static final Pattern GLUTEN = Pattern.compile("\\bglutens?\\b");
    private static final Pattern DAIRY = Pattern.compile("\\bdairys?\\b");
    private static final Pattern MILK = Pattern.compile("\\bmilks?\\b");
    private static final Pattern SOY = Pattern.compile("\\bsoys?\\b");
    private static final Pattern SUGAR = Pattern.compile("\\bsugars?\\b");

    @Test
    public void noWheatNoMaida_doesNotFlag() {
        assertFalse(FlaggedIngredientManager.termMatches("no wheat no maida", WHEAT));
        assertFalse(FlaggedIngredientManager.termMatches("no wheat no maida", MAIDA));
    }

    @Test
    public void plainOccurrence_flags() {
        assertTrue(FlaggedIngredientManager.termMatches("wheat flour, salt", WHEAT));
        assertTrue(FlaggedIngredientManager.termMatches("wheat", WHEAT));
    }

    @Test
    public void negatedThenPlain_stillFlags() {
        assertTrue(FlaggedIngredientManager.termMatches(
                "no wheat added, wheat flour", WHEAT));
    }

    @Test
    public void negationDoesNotBleedAcrossComma() {
        // "no" scopes "preservatives", not the later "wheat flour"
        assertTrue(FlaggedIngredientManager.termMatches(
                "no preservatives, wheat flour", WHEAT));
        assertTrue(FlaggedIngredientManager.termMatches(
                "no preservatives. wheat flour", WHEAT));
    }

    @Test
    public void xFree_doesNotFlag() {
        assertFalse(FlaggedIngredientManager.termMatches("gluten free bread", GLUTEN));
        assertFalse(FlaggedIngredientManager.termMatches("dairy-free milk", DAIRY));
    }

    @Test
    public void freeBeforeTerm_stillFlags() {
        // "fat free milk" contains milk: free-before is not a negation
        assertTrue(FlaggedIngredientManager.termMatches("fat free milk", MILK));
    }

    @Test
    public void withoutAndContainsNo() {
        assertFalse(FlaggedIngredientManager.termMatches("without milk", MILK));
        assertFalse(FlaggedIngredientManager.termMatches("contains no soy", SOY));
        assertFalse(FlaggedIngredientManager.termMatches("does not contain wheat", WHEAT));
    }

    @Test
    public void noAddedSugar_doesNotFlag() {
        assertFalse(FlaggedIngredientManager.termMatches("no added sugar", SUGAR));
    }

    @Test
    public void noInsideAnotherWord_isNotACue() {
        assertTrue(FlaggedIngredientManager.termMatches("know wheat", WHEAT));
    }

    @Test
    public void isNegated_spotChecks() {
        String t = "no wheat";
        assertTrue(FlaggedIngredientManager.isNegated(t, t.indexOf("wheat"), t.indexOf("wheat") + 5));
        String t2 = "wheat flour";
        assertFalse(FlaggedIngredientManager.isNegated(t2, 0, 5));
    }
}
