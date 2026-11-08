package com.barelabel.app;

import com.barelabel.app.model.ProductResult;
import com.barelabel.app.nutrition.NutrientComparison;
import com.barelabel.app.nutrition.NutrientDefinition;
import com.barelabel.app.nutrition.NutritionAnalysis;
import com.barelabel.app.nutrition.NutritionConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic explanations for a ranked alternative (Contract §6,
 * PRD §9.5): up to two reasons in a fixed priority order, and one
 * {@link NutrientDelta} per displayed nutrient whose per-100 values
 * differ. No randomness, no LLM — every string is computed from the
 * ranking facts.
 *
 * <p>Reason priority: (1) focused-nutrient improvement on a nutrient
 * the candidate is flagged for, (2) ingredient removal naming at most
 * two removed flagged ingredients, (3) Nutrition Score comparison
 * (only when the alternative leads by at least 5), (4) a preference
 * fact (organic, else non-GMO, else a shorter ingredient list).
 *
 * <p>The focused nutrient named is the flagged nutrient contributing
 * most to the alternative's focused advantage: configured weight ×
 * unclamped quality gain (combined quality when available, else
 * absolute quality), ties broken by config order. Its sentence form:
 * higher-bad with a percent improvement of at least 10% and a positive
 * candidate amount uses the percent form ("85% less sodium per
 * 100 g."); higher-good improvements and smaller higher-bad
 * improvements use the absolute form ("4 g more fiber per 100 g.",
 * "50 mg less sodium per 100 g."). Labels are lower-cased inline; the
 * basis ("g"/"ml") comes from the candidate's nutrition profile.
 *
 * <p>Badge grammar for {@link NutrientDelta#badge} (&lt;amt&gt; is
 * {@link #formatAmount} of the absolute difference, unit included;
 * N is the percent change rounded to a whole number):
 * <ul>
 *   <li>higher-bad, alternative lower (improvement): "N% less" when
 *       the candidate amount &gt; 0 and improvement &gt;= 10%, else
 *       "&lt;amt&gt; less".</li>
 *   <li>higher-bad, alternative higher (worse): "N% more" under the
 *       same percent gate, else "&lt;amt&gt; more".</li>
 *   <li>higher-good, alternative higher (improvement): always
 *       "+&lt;amt&gt;" (e.g. "+2.7 g").</li>
 *   <li>higher-good, alternative lower (worse): "-&lt;amt&gt;".</li>
 * </ul>
 *
 * <p>Formatting: grams trim to at most one decimal ("2.7 g", "6 g");
 * mg and kcal round to whole numbers ("120 mg", "50 kcal"). A percent
 * badge never carries a unit.
 */
public final class AlternativeExplainer {

    /** Minimum Nutrition Score lead for the score-comparison reason. */
    private static final int SCORE_REASON_MIN_GAP = 5;
    /** Minimum percent improvement for percent-form text/badges. */
    private static final double PERCENT_FORM_MIN = 10.0;

    private AlternativeExplainer() {
    }

    // ------------------------------------------------------------------
    // Reasons
    // ------------------------------------------------------------------

    /**
     * Up to two deterministic reasons for choosing {@code alt} over
     * {@code candidate}, in the priority order documented above.
     * Empty when nothing applies or inputs are missing.
     */
    public static List<String> reasons(ProductResult candidate,
                                       NutritionAnalysis candidateAnalysis,
                                       ProductResult alt,
                                       NutritionAnalysis altAnalysis,
                                       NutritionConfig config) {
        List<String> out = new ArrayList<>();
        if (candidate == null || alt == null || config == null) {
            return out;
        }
        String focused = focusedReason(candidate, candidateAnalysis,
                alt, altAnalysis, config);
        if (focused != null) {
            out.add(focused);
        }
        List<String> removed = removedFlaggedIngredients(candidate, alt);
        if (!removed.isEmpty()) {
            StringBuilder sb = new StringBuilder("Avoids ");
            sb.append(removed.get(0));
            if (removed.size() > 1) {
                sb.append(" and ").append(removed.get(1));
            }
            sb.append('.');
            out.add(sb.toString());
        }
        if (candidateAnalysis != null && altAnalysis != null
                && candidateAnalysis.nutritionScore != null
                && altAnalysis.nutritionScore != null
                && altAnalysis.nutritionScore - candidateAnalysis.nutritionScore
                        >= SCORE_REASON_MIN_GAP) {
            out.add("Nutrition Score " + altAnalysis.nutritionScore
                    + ", compared with " + candidateAnalysis.nutritionScore + ".");
        }
        String preference = preferenceFact(candidate, alt);
        if (preference != null) {
            out.add(preference);
        }
        if (out.size() > 2) {
            return new ArrayList<>(out.subList(0, 2));
        }
        return out;
    }

    /**
     * The focused-nutrient reason, or null when the alternative does
     * not improve any nutrient the candidate is flagged for.
     */
    private static String focusedReason(ProductResult candidate,
                                        NutritionAnalysis candidateAnalysis,
                                        ProductResult alt,
                                        NutritionAnalysis altAnalysis,
                                        NutritionConfig config) {
        if (candidateAnalysis == null || altAnalysis == null) {
            return null;
        }
        String basis = candidate.nutritionProfile != null
                && candidate.nutritionProfile.basis != null
                ? candidate.nutritionProfile.basis : "g";
        NutrientDefinition bestDef = null;
        NutrientComparison bestCand = null;
        NutrientComparison bestAlt = null;
        double bestContribution = 0.0;
        for (String key : candidateAnalysis.flaggedNutrients) {
            NutrientDefinition def = config.byKey.get(key);
            if (def == null) {
                continue;
            }
            NutrientComparison candC = candidateAnalysis.comparisons.get(key);
            NutrientComparison altC = altAnalysis.comparisons.get(key);
            if (candC == null || altC == null
                    || candC.per100 == null || altC.per100 == null) {
                continue;
            }
            double candAmt = candC.per100;
            double altAmt = altC.per100;
            boolean improves = "higher_bad".equals(def.direction)
                    ? altAmt < candAmt : altAmt > candAmt;
            if (!improves) {
                continue;
            }
            double gain;
            if (candC.quality != null && altC.quality != null) {
                gain = altC.quality - candC.quality;
            } else if (candC.absoluteQuality != null
                    && altC.absoluteQuality != null) {
                gain = altC.absoluteQuality - candC.absoluteQuality;
            } else {
                gain = percentImprovement(def, candAmt, altAmt);
            }
            double contribution = def.weight * gain;
            if (bestDef == null || contribution > bestContribution) {
                bestDef = def;
                bestCand = candC;
                bestAlt = altC;
                bestContribution = contribution;
            }
        }
        if (bestDef == null) {
            return null;
        }
        double candAmt = bestCand.per100;
        double altAmt = bestAlt.per100;
        String label = bestDef.label.toLowerCase(Locale.US);
        if ("higher_bad".equals(bestDef.direction)) {
            double pct = percentImprovement(bestDef, candAmt, altAmt);
            if (candAmt > 0 && pct >= PERCENT_FORM_MIN) {
                return Math.round(pct) + "% less " + label
                        + " per 100 " + basis + ".";
            }
            return formatAmount(candAmt - altAmt, bestDef.unit) + " less "
                    + label + " per 100 " + basis + ".";
        }
        return formatAmount(altAmt - candAmt, bestDef.unit) + " more "
                + label + " per 100 " + basis + ".";
    }

    /** Preference fact, in order: organic, non-GMO, shorter list. */
    private static String preferenceFact(ProductResult candidate, ProductResult alt) {
        if (AlternateRanker.hasOrganicIngredient(alt)) {
            return "Contains organic ingredients.";
        }
        if (AlternateRanker.isNonGmo(alt)) {
            return "Non-GMO product.";
        }
        if (candidate != null
                && AlternateRanker.countIngredients(alt)
                        < AlternateRanker.countIngredients(candidate)) {
            return "Shorter ingredient list.";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Deltas
    // ------------------------------------------------------------------

    /**
     * One delta per displayed nutrient (config order) whose per-100
     * values are reported on both sides and differ. Favorable values
     * are the comparisons' favorable percentiles (the display values
     * that drive the bar fills); null stays null.
     */
    public static List<NutrientDelta> deltas(ProductResult candidate,
                                             NutritionAnalysis candidateAnalysis,
                                             ProductResult alt,
                                             NutritionAnalysis altAnalysis,
                                             NutritionConfig config) {
        List<NutrientDelta> out = new ArrayList<>();
        if (candidateAnalysis == null || altAnalysis == null || config == null) {
            return out;
        }
        for (NutrientDefinition def : config.byKey.values()) {
            if (!def.display) {
                continue;
            }
            NutrientComparison candC = candidateAnalysis.comparisons.get(def.key);
            NutrientComparison altC = altAnalysis.comparisons.get(def.key);
            if (candC == null || altC == null
                    || candC.per100 == null || altC.per100 == null) {
                continue;
            }
            double candAmt = candC.per100;
            double altAmt = altC.per100;
            if (Double.compare(candAmt, altAmt) == 0) {
                continue;
            }
            out.add(new NutrientDelta(def.key, def.label, def.unit,
                    candAmt, altAmt,
                    candC.favorablePercentile, altC.favorablePercentile,
                    badgeFor(def, candAmt, altAmt)));
        }
        return out;
    }

    /** Badge for one differing nutrient; grammar in the class doc. */
    static String badgeFor(NutrientDefinition def, double candidateAmt,
                           double altAmt) {
        if ("higher_bad".equals(def.direction)) {
            if (altAmt < candidateAmt) {
                double pct = percentImprovement(def, candidateAmt, altAmt);
                if (candidateAmt > 0 && pct >= PERCENT_FORM_MIN) {
                    return Math.round(pct) + "% less";
                }
                return formatAmount(candidateAmt - altAmt, def.unit) + " less";
            }
            // Worsening branch: percentImprovement is signed (negative
            // for a higher-bad worsening), so gate/round on its magnitude
            // — the percent change — not the signed improvement. Do not
            // change percentImprovement itself: focusedReason relies on
            // its signed, improves-only semantics.
            double pct = Math.abs(percentImprovement(def, candidateAmt, altAmt));
            if (candidateAmt > 0 && pct >= PERCENT_FORM_MIN) {
                return Math.round(pct) + "% more";
            }
            return formatAmount(altAmt - candidateAmt, def.unit) + " more";
        }
        if (altAmt > candidateAmt) {
            return "+" + formatAmount(altAmt - candidateAmt, def.unit);
        }
        return "-" + formatAmount(candidateAmt - altAmt, def.unit);
    }

    // ------------------------------------------------------------------
    // Shared helpers (also used by AlternateRanker's components)
    // ------------------------------------------------------------------

    /**
     * Direction-aware percent improvement of the alternative over the
     * candidate (positive when the alternative is better). Returns 0
     * when the candidate amount is not positive (percent undefined).
     */
    static double percentImprovement(NutrientDefinition def, double candidateAmt,
                                     double altAmt) {
        if (candidateAmt <= 0) {
            return 0.0;
        }
        if ("higher_bad".equals(def.direction)) {
            return (candidateAmt - altAmt) / candidateAmt * 100.0;
        }
        return (altAmt - candidateAmt) / candidateAmt * 100.0;
    }

    /**
     * The candidate's flagged ingredients that the alternative avoids,
     * sorted alphabetically (case-insensitive) for determinism. An
     * item counts as present in the alternative when it appears in the
     * alternative's flagged set or (case-insensitive) in its
     * ingredient text.
     */
    static List<String> removedFlaggedIngredients(ProductResult candidate,
                                                  ProductResult alt) {
        List<String> removed = new ArrayList<>();
        if (candidate == null || candidate.flagged == null || alt == null) {
            return removed;
        }
        for (String item : candidate.flagged) {
            if (item != null && !ingredientPresent(item, alt)) {
                removed.add(item);
            }
        }
        Collections.sort(removed, String.CASE_INSENSITIVE_ORDER);
        return removed;
    }

    /** True when a flagged item is present in the alternative. */
    static boolean ingredientPresent(String item, ProductResult alt) {
        if (alt == null || item == null) {
            return false;
        }
        if (alt.flagged != null) {
            for (String flagged : alt.flagged) {
                if (flagged != null && flagged.equalsIgnoreCase(item)) {
                    return true;
                }
            }
        }
        return alt.ingredients != null
                && alt.ingredients.toLowerCase(Locale.US)
                        .contains(item.toLowerCase(Locale.US));
    }

    /**
     * Formats an amount with its unit: grams trim to at most one
     * decimal ("2.7 g", "6 g"); mg/kcal round to whole numbers
     * ("120 mg", "50 kcal").
     */
    public static String formatAmount(double value, String unit) {
        if ("g".equals(unit)) {
            double rounded = Math.round(value * 10.0) / 10.0;
            if (rounded == Math.floor(rounded)) {
                return (long) rounded + " g";
            }
            return String.format(Locale.US, "%.1f g", rounded);
        }
        return Math.round(value) + " " + unit;
    }
}
