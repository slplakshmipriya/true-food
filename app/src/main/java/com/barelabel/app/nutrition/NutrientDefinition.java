package com.barelabel.app.nutrition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One nutrient definition from the nutrition configuration (Contract §1.1).
 * Immutable; every value comes from nutrition_config.json so tuning happens
 * in the asset, not in code.
 *
 * <p>Flag-rule fields are direction-specific and nullable:
 * {@code flagPercentile} / {@code flagFloor} / {@code severeAt} apply to
 * {@code higher_bad} nutrients, {@code lowFlagPercentile} /
 * {@code lowFlagAtOrBelow} to {@code higher_good} nutrients. A nutrient that
 * is not flag-eligible (e.g. protein in v1) leaves all of them null.
 *
 * <p>{@code fallbackKey} and {@code fallbackWeightFactor} are optional: when
 * this nutrient is not reported, the scoring group may fall back to the
 * nutrient named by {@code fallbackKey} (added_sugars falls back to
 * total_sugars in the shared "sugars" group).
 */
public final class NutrientDefinition {

    /** Stable internal key, e.g. "added_sugars". */
    public final String key;
    /** USDA nutrient IDs; the first is primary. Unmodifiable. */
    public final List<Integer> usdaIds;
    public final String label;
    /** "g", "mg", or "kcal". */
    public final String unit;
    /** "higher_good" or "higher_bad". */
    public final String direction;
    public final boolean display;
    public final boolean scoreEligible;
    public final boolean flagEligible;
    /** Scoring weight, always &gt; 0. */
    public final double weight;
    /** Group that contributes once to the Nutrition Score. */
    public final String scoringGroup;
    /** Plausible per-100 range; values outside it are dropped. */
    public final double plausibleMin;
    public final double plausibleMax;
    /** Absolute-quality anchors: goodAt scores 100, badAt scores 0. */
    public final double goodAt;
    public final double badAt;
    /** higher_bad flag rule; null when not applicable. */
    public final Double flagPercentile;
    public final Double flagFloor;
    public final Double severeAt;
    /** higher_good low-flag rule; null when not applicable. */
    public final Double lowFlagPercentile;
    public final Double lowFlagAtOrBelow;
    /** Optional fallback nutrient key; null when there is no fallback. */
    public final String fallbackKey;
    public final Double fallbackWeightFactor;

    public NutrientDefinition(String key, List<Integer> usdaIds, String label,
                              String unit, String direction, boolean display,
                              boolean scoreEligible, boolean flagEligible,
                              double weight, String scoringGroup,
                              double plausibleMin, double plausibleMax,
                              double goodAt, double badAt,
                              Double flagPercentile, Double flagFloor,
                              Double severeAt, Double lowFlagPercentile,
                              Double lowFlagAtOrBelow, String fallbackKey,
                              Double fallbackWeightFactor) {
        this.key = key;
        this.usdaIds = Collections.unmodifiableList(
                new ArrayList<>(usdaIds));
        this.label = label;
        this.unit = unit;
        this.direction = direction;
        this.display = display;
        this.scoreEligible = scoreEligible;
        this.flagEligible = flagEligible;
        this.weight = weight;
        this.scoringGroup = scoringGroup;
        this.plausibleMin = plausibleMin;
        this.plausibleMax = plausibleMax;
        this.goodAt = goodAt;
        this.badAt = badAt;
        this.flagPercentile = flagPercentile;
        this.flagFloor = flagFloor;
        this.severeAt = severeAt;
        this.lowFlagPercentile = lowFlagPercentile;
        this.lowFlagAtOrBelow = lowFlagAtOrBelow;
        this.fallbackKey = fallbackKey;
        this.fallbackWeightFactor = fallbackWeightFactor;
    }
}
