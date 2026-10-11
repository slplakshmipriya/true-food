package com.barelabel.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * CleanScoreCalculator: 0-100 score beside the binary verdict.
 * 100 when clean, -1 when unavailable, 15/category + 3/ingredient
 * penalty, floored at 5.
 */
public class CleanScoreCalculatorTest {

    @Test
    public void unavailable_returnsMinusOne() {
        assertEquals(-1, CleanScoreCalculator.score(false, 0, 0));
    }

    @Test
    public void unavailable_ignoresCounts() {
        assertEquals(-1, CleanScoreCalculator.score(false, 5, 3));
    }

    @Test
    public void clean_returns100() {
        assertEquals(100, CleanScoreCalculator.score(true, 0, 0));
    }

    @Test
    public void oneCategoryTwoIngredients_penalty21() {
        // 100 - 15*1 - 3*2 = 79
        assertEquals(79, CleanScoreCalculator.score(true, 2, 1));
    }

    @Test
    public void categoriesOnly() {
        // 100 - 15*2 = 70
        assertEquals(70, CleanScoreCalculator.score(true, 0, 2));
    }

    @Test
    public void heavyFlagging_clampsAtFloor() {
        assertEquals(5, CleanScoreCalculator.score(true, 20, 5));
    }

    @Test
    public void negativeCategoryCount_treatedAsZero() {
        // 100 - 3*1 = 97
        assertEquals(97, CleanScoreCalculator.score(true, 1, -2));
    }
}
