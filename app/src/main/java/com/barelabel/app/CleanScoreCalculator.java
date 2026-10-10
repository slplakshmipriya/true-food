package com.barelabel.app;

/**
 * Pure 0-100 "Clean Score" shown next to the verdict (unit-testable).
 *
 * The binary CLEAN/DIRTY verdict stays the headline; the score only makes
 * degrees visible so users can compare two dirty products (or two clean
 * ones) at a glance. Rules: unavailable data -> -1; no flagged ingredients
 * -> 100; otherwise 100 minus 15 per flagged category and 3 per flagged
 * ingredient, floored at 5 so a score never reads as a false zero.
 */
public final class CleanScoreCalculator {

    /** Score meaning "cannot be computed" (no product / no ingredients). */
    public static final int UNAVAILABLE = -1;

    static final int PER_CATEGORY_PENALTY = 15;
    static final int PER_INGREDIENT_PENALTY = 3;
    static final int MIN_SCORE = 5;

    private CleanScoreCalculator() {
    }

    /**
     * @param available           false when there is no usable verdict data
     * @param flaggedIngredients  number of individually flagged ingredients
     * @param flaggedCategories   number of flagged categories that fired
     */
    public static int score(boolean available, int flaggedIngredients,
                            int flaggedCategories) {
        if (!available) {
            return UNAVAILABLE;
        }
        if (flaggedIngredients <= 0) {
            return 100;
        }
        int penalty = Math.max(0, flaggedCategories) * PER_CATEGORY_PENALTY
                + flaggedIngredients * PER_INGREDIENT_PENALTY;
        return Math.max(MIN_SCORE, 100 - penalty);
    }
}
