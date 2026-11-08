package com.barelabel.app.nutrition;

/**
 * One alternative-ranking profile (Contract §1 / §6): the component weights
 * used to rank eligible alternatives for a given candidate state. The four
 * weights are validated to sum to 1.0 (±0.001) at load time.
 */
public final class RankingProfile {

    public final double ingredientAdvantage;
    public final double focusedNutrition;
    public final double overallNutrition;
    public final double preferences;

    public RankingProfile(double ingredientAdvantage, double focusedNutrition,
                          double overallNutrition, double preferences) {
        this.ingredientAdvantage = ingredientAdvantage;
        this.focusedNutrition = focusedNutrition;
        this.overallNutrition = overallNutrition;
        this.preferences = preferences;
    }
}
