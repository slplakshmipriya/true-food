package com.barelabel.app.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * TitleMatchScorer.score: 0-100 query-to-title match used to trigger
 * search disambiguation.
 */
public class TitleMatchScorerTest {

    @Test
    public void exactMatchScores100() {
        assertEquals(100, TitleMatchScorer.score("cheez-it", "Cheez-It"));
        assertEquals(100, TitleMatchScorer.score("  OREO ", "oreo"));
    }

    @Test
    public void possessiveMatchesAcrossApostrophe() {
        // "world's" must match "worlds": the apostrophe is dropped before
        // normalizing (StringNormalizer alone would split "world s").
        int s = TitleMatchScorer.score("worlds finest chocolate",
                "World's Finest, Chocolate Covered Pecans");
        assertTrue("expected > 90, got " + s, s > 90);
    }

    @Test
    public void allTokensFewExtraWordsScoresAboveThreshold() {
        assertTrue(TitleMatchScorer.score("cheez-it",
                "Cheez-It Baked Snack Crackers") > 90);
        assertTrue(TitleMatchScorer.score("oreo",
                "Oreo Chocolate Sandwich Cookies") > 90);
    }

    @Test
    public void substringIsNotAWholeWordMatch() {
        // "bread" must not match "breaded".
        assertEquals(0, TitleMatchScorer.score("bread", "Breaded Chicken Strips"));
    }

    @Test
    public void noSharedTokensScoresZero() {
        assertEquals(0, TitleMatchScorer.score("quinoa", "Cheez-It"));
    }

    @Test
    public void partialCoverageScoresBelowThreshold() {
        // 3 of 4 query tokens: 75 base minus extras -> well under 90.
        int s = TitleMatchScorer.score("worlds finest chocolate almond",
                "World's Finest Chocolate Bar");
        assertTrue("expected < 90, got " + s, s < 90);
    }

    @Test
    public void extraWordsLowerTheScore() {
        int shortTitle = TitleMatchScorer.score("cheez-it", "Cheez-It Crackers");
        int longTitle = TitleMatchScorer.score("cheez-it",
                "Cheez-It Baked Snack Crackers Original 12 Pack");
        assertTrue(shortTitle > longTitle);
    }

    @Test
    public void nullAndBlankInputsScoreZero() {
        assertEquals(0, TitleMatchScorer.score(null, "Cheez-It"));
        assertEquals(0, TitleMatchScorer.score("cheez-it", null));
        assertEquals(0, TitleMatchScorer.score("  ", "Cheez-It"));
        assertEquals(0, TitleMatchScorer.score("cheez-it", ""));
    }
}
