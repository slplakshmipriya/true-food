package com.barelabel.app.nutrition;

import static org.junit.Assert.assertEquals;

import com.barelabel.app.CleanScoreCalculator;

import org.junit.Test;

import java.util.Collections;

/**
 * OverallScoreCalculator: Contract §4, PRD §8.4. Blend weights come from
 * the NutritionConfig, so a config with different weights changes the
 * result with no code change.
 */
public class OverallScoreCalculatorTest {

    /** Config carrying only blend weights (the calculator reads nothing else). */
    private static NutritionConfig configWithWeights(double ingredientWeight,
                                                     double nutritionWeight) {
        return new NutritionConfig(1, ingredientWeight, nutritionWeight,
                10, 20, 3, 5, 0.50, 0.65, 0.35, 3, 60,
                Collections.<String, NutrientDefinition>emptyMap(),
                Collections.<String, RankingProfile>emptyMap());
    }

    private static NutritionConfig defaultConfig() {
        return configWithWeights(0.70, 0.30);
    }

    @Test
    public void blend_bothAvailable_defaultWeights() {
        // round(0.7*80 + 0.3*50) = round(56 + 15) = 71.
        assertEquals(71, OverallScoreCalculator.blend(80, 50, defaultConfig()));
        assertEquals(100, OverallScoreCalculator.blend(100, 100, defaultConfig()));
    }

    @Test
    public void blend_roundsToNearest() {
        // 0.7*85 + 0.3*84 = 84.7 -> 85; 0.7*84 + 0.3*85 = 84.3 -> 84.
        assertEquals(85, OverallScoreCalculator.blend(85, 84, defaultConfig()));
        assertEquals(84, OverallScoreCalculator.blend(84, 85, defaultConfig()));
    }

    @Test
    public void blend_flooredAtMinScore() {
        // 0.7*6 + 0.3*0 = 4.2 -> rounds to 4, floored to MIN_SCORE (5).
        assertEquals(5, OverallScoreCalculator.blend(6, 0, defaultConfig()));
        assertEquals(5, OverallScoreCalculator.blend(5, 5, defaultConfig()));
    }

    @Test
    public void blend_nutritionNull_returnsIngredientScore() {
        assertEquals(73, OverallScoreCalculator.blend(73, null, defaultConfig()));
        assertEquals(100, OverallScoreCalculator.blend(100, null, defaultConfig()));
    }

    @Test
    public void blend_ingredientUnavailable_returnsUnavailable() {
        assertEquals(CleanScoreCalculator.UNAVAILABLE,
                OverallScoreCalculator.blend(
                        CleanScoreCalculator.UNAVAILABLE, 90, defaultConfig()));
        assertEquals(CleanScoreCalculator.UNAVAILABLE,
                OverallScoreCalculator.blend(
                        CleanScoreCalculator.UNAVAILABLE, null, defaultConfig()));
    }

    @Test
    public void blend_nullConfig_returnsIngredientScore() {
        // Pinned (NUT-11): with no config there are no blend weights,
        // so the ingredient score passes through unchanged — the same
        // posture as a null nutrition score.
        assertEquals(73, OverallScoreCalculator.blend(73, 50, null));
        assertEquals(73, OverallScoreCalculator.blend(73, null, null));
        assertEquals(CleanScoreCalculator.UNAVAILABLE,
                OverallScoreCalculator.blend(
                        CleanScoreCalculator.UNAVAILABLE, 50, null));
    }

    @Test
    public void blend_floorMatchesCleanScoreCalculatorFloor() {
        // MIN_SCORE cross-check (NUT-11): OverallScoreCalculator
        // restates CleanScoreCalculator's package-private MIN_SCORE.
        // Drive the real calculator to its own floor and require the
        // blend floor to agree, so the restated constant cannot drift.
        int ingredientFloor = CleanScoreCalculator.score(true, 100, 100);
        assertEquals(5, ingredientFloor);
        assertEquals(ingredientFloor,
                OverallScoreCalculator.blend(6, 0, defaultConfig()));
        assertEquals(ingredientFloor, OverallScoreCalculator.blend(
                ingredientFloor, 0, defaultConfig()));
    }

    @Test
    public void blend_customWeights_changeResult() {
        // 50/50: round(0.5*80 + 0.5*50) = 65.
        assertEquals(65, OverallScoreCalculator.blend(
                80, 50, configWithWeights(0.50, 0.50)));
        // 25/75: round(0.25*80 + 0.75*40) = 50.
        assertEquals(50, OverallScoreCalculator.blend(
                80, 40, configWithWeights(0.25, 0.75)));
    }
}
