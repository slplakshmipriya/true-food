package com.barelabel.app.nutrition;

import com.barelabel.app.CleanScoreCalculator;

/**
 * Overall Score blend (Contract §4, PRD §8.4): the weighted mean of the
 * Ingredient Score and the Nutrition Score, using the blend weights
 * from the {@link NutritionConfig} — changing the JSON weights changes
 * the result with no code change. The blend weights are never
 * displayed in the UI.
 *
 * <ul>
 *   <li>Ingredient unavailable ({@link CleanScoreCalculator#UNAVAILABLE})
 *       ⇒ UNAVAILABLE, whatever the nutrition side says.</li>
 *   <li>Nutrition Score null (or no config to blend with) ⇒ the
 *       Ingredient Score unchanged.</li>
 *   <li>Otherwise {@code round(wI*ingredient + wN*nutrition)}, floored
 *       at the Clean Score minimum of 5 and capped at 100. (The floor
 *       mirrors {@code CleanScoreCalculator.MIN_SCORE}; that constant
 *       is package-private to {@code com.barelabel.app}, so the value
 *       is restated here.)</li>
 * </ul>
 */
public final class OverallScoreCalculator {

    /** Mirrors CleanScoreCalculator.MIN_SCORE (package-private there). */
    private static final int MIN_SCORE = 5;

    private OverallScoreCalculator() {
    }

    public static int blend(int ingredientScore, Integer nutritionScore,
                            NutritionConfig config) {
        if (ingredientScore == CleanScoreCalculator.UNAVAILABLE) {
            return CleanScoreCalculator.UNAVAILABLE;
        }
        if (nutritionScore == null || config == null) {
            return ingredientScore;
        }
        double blended = config.ingredientWeight * ingredientScore
                + config.nutritionWeight * nutritionScore;
        int result = (int) Math.round(blended);
        if (result < MIN_SCORE) {
            return MIN_SCORE;
        }
        if (result > 100) {
            return 100;
        }
        return result;
    }
}
