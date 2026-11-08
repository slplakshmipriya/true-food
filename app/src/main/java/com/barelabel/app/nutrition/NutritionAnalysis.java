package com.barelabel.app.nutrition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The deterministic nutrition assessment of one product (Contract §4):
 * status, Nutrition Score, coverage, the cohort actually used, one
 * {@link NutrientComparison} per displayed nutrient, and the flagged
 * nutrient keys in config order. Immutable; produced by
 * {@link NutritionAnalyzer}.
 */
public final class NutritionAnalysis {

    public final NutritionStatus status;
    /** 0–100, or null when unavailable (coverage below the minimum). */
    public final Integer nutritionScore;
    /** Available scoring-group weight / total group weight, 0..1. */
    public final double coverage;
    /** Cohort category actually used; "" when there is no cohort. */
    public final String cohortCategory;
    /** Products in the cohort. */
    public final int cohortPeerCount;
    /** Every displayed nutrient, in config order. Unmodifiable. */
    public final Map<String, NutrientComparison> comparisons;
    /** Flagged nutrient keys, in config order. Unmodifiable. */
    public final List<String> flaggedNutrients;

    public NutritionAnalysis(NutritionStatus status, Integer nutritionScore,
                             double coverage, String cohortCategory,
                             int cohortPeerCount,
                             Map<String, NutrientComparison> comparisons,
                             List<String> flaggedNutrients) {
        this.status = status != null ? status : NutritionStatus.UNAVAILABLE;
        this.nutritionScore = nutritionScore;
        this.coverage = coverage;
        this.cohortCategory = cohortCategory != null ? cohortCategory : "";
        this.cohortPeerCount = cohortPeerCount;
        this.comparisons = Collections.unmodifiableMap(comparisons == null
                ? new LinkedHashMap<String, NutrientComparison>()
                : new LinkedHashMap<>(comparisons));
        this.flaggedNutrients = Collections.unmodifiableList(flaggedNutrients == null
                ? new ArrayList<String>()
                : new ArrayList<>(flaggedNutrients));
    }
}
