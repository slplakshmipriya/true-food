package com.barelabel.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.barelabel.app.model.ProductResult;
import com.barelabel.app.nutrition.NutrientComparison;
import com.barelabel.app.nutrition.NutrientDefinition;
import com.barelabel.app.nutrition.NutritionAnalysis;
import com.barelabel.app.nutrition.NutritionConfig;
import com.barelabel.app.nutrition.NutritionStatus;
import com.barelabel.app.nutrition.RankingProfile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AlternativeExplainer: Contract §6, PRD §9.5. Deterministic reasons
 * (priority order, at most 2) and per-nutrient deltas with badges.
 *
 * Badge grammar pinned here (documented on AlternativeExplainer):
 * <ul>
 *   <li>higher_bad, alternative lower (improvement): percent badge
 *       "N% less" when the candidate amount &gt; 0 and the improvement
 *       is &gt;= 10%; otherwise the absolute badge "&lt;amt&gt; less".</li>
 *   <li>higher_bad, alternative higher (worse): "N% more" under the
 *       same percent gate; otherwise "&lt;amt&gt; more".</li>
 *   <li>higher_good, alternative higher (improvement): always the
 *       absolute badge "+&lt;amt&gt;".</li>
 *   <li>higher_good, alternative lower (worse): "-&lt;amt&gt;".</li>
 * </ul>
 * where &lt;amt&gt; is {@code formatAmount(|difference|, unit)} and N is
 * the percent change rounded to a whole number.
 */
public class AlternativeExplainerTest {

    private static final double EPS = 0.001;

    // ------------------------------------------------------------------
    // Helpers (same synthetic config shape as NutritionRankingTest).
    // ------------------------------------------------------------------

    private static NutrientDefinition def(String key, String label, String unit,
                                          String direction, double weight) {
        boolean higherGood = "higher_good".equals(direction);
        return new NutrientDefinition(key, Collections.singletonList(9999),
                label, unit, direction, true, true, true, weight, key,
                0.0, 5000.0, higherGood ? 100.0 : 0.0, higherGood ? 0.0 : 100.0,
                null, null, null, null, null, null, null);
    }

    private static NutritionConfig config() {
        Map<String, NutrientDefinition> byKey = new LinkedHashMap<>();
        byKey.put("energy", def("energy", "Energy", "kcal", "higher_bad", 0.10));
        byKey.put("protein", def("protein", "Protein", "g", "higher_good", 0.12));
        byKey.put("total_fat", def("total_fat", "Total Fat", "g", "higher_bad", 0.06));
        byKey.put("carbohydrates", def("carbohydrates", "Carbohydrates", "g", "higher_bad", 0.06));
        byKey.put("total_sugars", def("total_sugars", "Total Sugars", "g", "higher_bad", 0.16));
        byKey.put("added_sugars", def("added_sugars", "Added Sugars", "g", "higher_bad", 0.16));
        byKey.put("fiber", def("fiber", "Fiber", "g", "higher_good", 0.14));
        byKey.put("sodium", def("sodium", "Sodium", "mg", "higher_bad", 0.16));
        byKey.put("saturated_fat", def("saturated_fat", "Saturated Fat", "g", "higher_bad", 0.10));
        byKey.put("trans_fat", def("trans_fat", "Trans Fat", "g", "higher_bad", 0.05));
        byKey.put("cholesterol", def("cholesterol", "Cholesterol", "mg", "higher_bad", 0.05));
        Map<String, RankingProfile> profiles = new LinkedHashMap<>();
        profiles.put("dirty_concerning", new RankingProfile(0.40, 0.35, 0.15, 0.10));
        profiles.put("dirty_only", new RankingProfile(0.75, 0.00, 0.15, 0.10));
        profiles.put("nutrition_only", new RankingProfile(0.00, 0.60, 0.25, 0.15));
        return new NutritionConfig(1, 0.70, 0.30, 10, 20, 3, 5,
                0.50, 0.65, 0.35, 3, 60, byKey, profiles);
    }

    private static ProductResult product(String name, String ingredients,
                                         String... flaggedItems) {
        ProductResult p = new ProductResult();
        p.found = true;
        p.name = name;
        p.brand = "";
        p.ingredients = ingredients;
        p.flagged = new HashSet<>(Arrays.asList(flaggedItems));
        p.matchResult = new FlaggedIngredientManager.MatchResult();
        if (flaggedItems.length > 0) {
            p.matchResult.categoryMap.put("Test Category",
                    new ArrayList<>(Arrays.asList(flaggedItems)));
        }
        return p;
    }

    private static NutrientComparison comp(String key, Double per100, Double quality) {
        return new NutrientComparison(key, per100, null, 12, null, null,
                null, null, quality, false, "");
    }

    private static NutrientComparison compFav(String key, Double per100,
                                              Double favorable, Double quality) {
        return new NutrientComparison(key, per100, null, 12, null, null,
                favorable, null, quality, false, "");
    }

    private static NutritionAnalysis analysis(NutritionStatus status, Integer score,
                                              List<String> flaggedNutrients,
                                              NutrientComparison... comps) {
        Map<String, NutrientComparison> map = new LinkedHashMap<>();
        for (NutrientComparison c : comps) {
            map.put(c.key, c);
        }
        return new NutritionAnalysis(status, score, 1.0, "Snacks", 12,
                map, flaggedNutrients);
    }

    private static NutrientDelta deltaFor(List<NutrientDelta> deltas, String key) {
        for (NutrientDelta d : deltas) {
            if (d.key.equals(key)) {
                return d;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Reasons: priority, cap, forms
    // ------------------------------------------------------------------

    @Test
    public void reasons_focusedPercentImprovement_firstAndCappedAtTwo() {
        // Sodium 1000 -> 390 = 61% less (>= 10%): focused reason first.
        // A removal and a >= 5 score gap also exist; the cap keeps the
        // first two in priority order (focused, removal).
        ProductResult candidate = product("Candidate",
                "POTATOES, OIL, SOY LECITHIN", "soy lecithin");
        NutritionAnalysis candA = analysis(NutritionStatus.CONCERNING, 41,
                Arrays.asList("sodium"), comp("sodium", 1000.0, 10.0));
        ProductResult alt = product("Alt", "RICE, SALT");
        NutritionAnalysis altA = analysis(NutritionStatus.SAFE, 68,
                Collections.<String>emptyList(), comp("sodium", 390.0, 90.0));
        List<String> reasons = AlternativeExplainer.reasons(
                candidate, candA, alt, altA, config());
        assertEquals(2, reasons.size());
        assertEquals("61% less sodium per 100 g.", reasons.get(0));
        assertEquals("Avoids soy lecithin.", reasons.get(1));
    }

    @Test
    public void reasons_focusedBeatsScoreComparison() {
        // No flagged ingredients to remove; the score gap is 27 (>= 5)
        // but the focused reason still comes first.
        ProductResult candidate = product("Candidate", "A, B, C");
        NutritionAnalysis candA = analysis(NutritionStatus.CONCERNING, 41,
                Arrays.asList("sodium"), comp("sodium", 1000.0, 10.0));
        ProductResult alt = product("Alt", "X, Y, Z");
        NutritionAnalysis altA = analysis(NutritionStatus.SAFE, 68,
                Collections.<String>emptyList(), comp("sodium", 390.0, 90.0));
        List<String> reasons = AlternativeExplainer.reasons(
                candidate, candA, alt, altA, config());
        assertEquals(Arrays.asList("61% less sodium per 100 g.",
                "Nutrition Score 68, compared with 41."), reasons);
    }

    @Test
    public void reasons_ingredientRemovalNamesAtMostTwoAlphabetical() {
        ProductResult candidate = product("Candidate", "A, B, C",
                "gamma item", "alpha item", "beta item");
        NutritionAnalysis candA = analysis(NutritionStatus.SAFE, 70,
                Collections.<String>emptyList());
        ProductResult alt = product("Alt", "RICE, SALT");
        NutritionAnalysis altA = analysis(NutritionStatus.SAFE, 70,
                Collections.<String>emptyList());
        List<String> reasons = AlternativeExplainer.reasons(
                candidate, candA, alt, altA, config());
        assertEquals(1, reasons.size());
        assertEquals("Avoids alpha item and beta item.", reasons.get(0));
    }

    @Test
    public void reasons_scoreComparisonOnlyWhenGapAtLeast5() {
        ProductResult candidate = product("Candidate", "A, B, C");
        NutritionAnalysis candA = analysis(NutritionStatus.SAFE, 63,
                Collections.<String>emptyList());
        ProductResult alt = product("Alt", "X, Y, Z");

        // Gap of 4: no score reason (and nothing else applies).
        NutritionAnalysis altGap4 = analysis(NutritionStatus.SAFE, 67,
                Collections.<String>emptyList());
        assertTrue(AlternativeExplainer.reasons(candidate, candA, alt, altGap4,
                config()).isEmpty());

        // Gap of exactly 5: the score reason appears (PRD §9.5 form).
        NutritionAnalysis candA2 = analysis(NutritionStatus.SAFE, 41,
                Collections.<String>emptyList());
        NutritionAnalysis altGap5 = analysis(NutritionStatus.SAFE, 46,
                Collections.<String>emptyList());
        assertEquals(Collections.singletonList("Nutrition Score 46, compared with 41."),
                AlternativeExplainer.reasons(candidate, candA2, alt, altGap5, config()));
    }

    @Test
    public void reasons_scoreComparison_prdExampleNumbers() {
        ProductResult candidate = product("Candidate", "A, B, C");
        NutritionAnalysis candA = analysis(NutritionStatus.CONCERNING, 41,
                Collections.<String>emptyList());
        ProductResult alt = product("Alt", "X, Y, Z");
        NutritionAnalysis altA = analysis(NutritionStatus.SAFE, 68,
                Collections.<String>emptyList());
        assertEquals(Collections.singletonList("Nutrition Score 68, compared with 41."),
                AlternativeExplainer.reasons(candidate, candA, alt, altA, config()));
    }

    @Test
    public void reasons_higherGoodFocused_usesAbsoluteForm() {
        // Fiber 2 -> 6 g: "+4 g" style gain, written per PRD §9.5 as
        // "4 g more fiber per 100 g." (no leading plus in sentences).
        ProductResult candidate = product("Candidate", "A, B, C");
        NutritionAnalysis candA = analysis(NutritionStatus.CONCERNING, 40,
                Arrays.asList("fiber"), comp("fiber", 2.0, 20.0));
        ProductResult alt = product("Alt", "X, Y, Z");
        NutritionAnalysis altA = analysis(NutritionStatus.SAFE, 70,
                Collections.<String>emptyList(), comp("fiber", 6.0, 90.0));
        List<String> reasons = AlternativeExplainer.reasons(
                candidate, candA, alt, altA, config());
        assertEquals("4 g more fiber per 100 g.", reasons.get(0));
    }

    @Test
    public void reasons_higherBadBelow10Percent_usesAbsoluteForm() {
        // Sodium 1000 -> 950 is only a 5% improvement: absolute form.
        ProductResult candidate = product("Candidate", "A, B, C");
        NutritionAnalysis candA = analysis(NutritionStatus.CONCERNING, 40,
                Arrays.asList("sodium"), comp("sodium", 1000.0, 10.0));
        ProductResult alt = product("Alt", "X, Y, Z");
        NutritionAnalysis altA = analysis(NutritionStatus.SAFE, 70,
                Collections.<String>emptyList(), comp("sodium", 950.0, 30.0));
        List<String> reasons = AlternativeExplainer.reasons(
                candidate, candA, alt, altA, config());
        assertEquals("50 mg less sodium per 100 g.", reasons.get(0));
    }

    @Test
    public void reasons_noFocusedReasonWhenAlternativeWorseOnFlaggedNutrient() {
        ProductResult candidate = product("Candidate", "A, B, C");
        NutritionAnalysis candA = analysis(NutritionStatus.CONCERNING, 40,
                Arrays.asList("sodium"), comp("sodium", 500.0, 60.0));
        ProductResult alt = product("Alt", "X, Y, Z");
        NutritionAnalysis altA = analysis(NutritionStatus.SAFE, 44,
                Collections.<String>emptyList(), comp("sodium", 800.0, 20.0));
        // Sodium got worse and the score gap is 4 (< 5): no reasons.
        assertTrue(AlternativeExplainer.reasons(candidate, candA, alt, altA,
                config()).isEmpty());
    }

    @Test
    public void reasons_preferenceFact_shorterListAndOrganic() {
        ProductResult candidate = product("Candidate", "A, B, C, D, E");
        NutritionAnalysis candA = analysis(NutritionStatus.SAFE, 70,
                Collections.<String>emptyList());
        NutritionAnalysis altA = analysis(NutritionStatus.SAFE, 70,
                Collections.<String>emptyList());

        ProductResult shorter = product("Alt", "X, Y");
        assertEquals(Collections.singletonList("Shorter ingredient list."),
                AlternativeExplainer.reasons(candidate, candA, shorter, altA, config()));

        ProductResult organic = product("Alt", "ORGANIC RICE, SALT, OIL, WATER, CORN");
        assertEquals(Collections.singletonList("Contains organic ingredients."),
                AlternativeExplainer.reasons(candidate, candA, organic, altA, config()));
    }

    @Test
    public void reasons_focusedPicksLargestWeightedQualityGain() {
        // Candidate flags fiber (w .14) and sodium (w .16). Both
        // improve; sodium's weight x quality gain (0.16 x 80 = 12.8)
        // beats fiber's (0.14 x 60 = 8.4), so sodium is named even
        // though fiber comes first in the flagged list.
        ProductResult candidate = product("Candidate", "A, B, C");
        NutritionAnalysis candA = analysis(NutritionStatus.CONCERNING, 30,
                Arrays.asList("fiber", "sodium"),
                comp("fiber", 1.0, 10.0), comp("sodium", 1000.0, 10.0));
        ProductResult alt = product("Alt", "X, Y, Z");
        NutritionAnalysis altA = analysis(NutritionStatus.SAFE, 80,
                Collections.<String>emptyList(),
                comp("fiber", 9.0, 70.0), comp("sodium", 200.0, 90.0));
        List<String> reasons = AlternativeExplainer.reasons(
                candidate, candA, alt, altA, config());
        assertEquals("80% less sodium per 100 g.", reasons.get(0));
    }

    // ------------------------------------------------------------------
    // formatAmount
    // ------------------------------------------------------------------

    @Test
    public void formatAmount_gramsTrimToOneDecimal() {
        assertEquals("2.7 g", AlternativeExplainer.formatAmount(2.7, "g"));
        assertEquals("6 g", AlternativeExplainer.formatAmount(6.0, "g"));
        assertEquals("0.4 g", AlternativeExplainer.formatAmount(0.4, "g"));
        assertEquals("2.8 g", AlternativeExplainer.formatAmount(2.75, "g"));
    }

    @Test
    public void formatAmount_mgAndKcalRoundWhole() {
        assertEquals("120 mg", AlternativeExplainer.formatAmount(120.0, "mg"));
        assertEquals("120 mg", AlternativeExplainer.formatAmount(120.4, "mg"));
        assertEquals("50 kcal", AlternativeExplainer.formatAmount(49.6, "kcal"));
        assertEquals("780 mg", AlternativeExplainer.formatAmount(780.0, "mg"));
    }

    // ------------------------------------------------------------------
    // Deltas and badges
    // ------------------------------------------------------------------

    private static List<NutrientDelta> deltasFor(NutrientComparison cand,
                                                 NutrientComparison alt) {
        ProductResult candidate = product("Candidate", "A, B, C");
        ProductResult altP = product("Alt", "X, Y, Z");
        NutritionAnalysis candA = analysis(NutritionStatus.CONCERNING, 40,
                Collections.<String>emptyList(), cand);
        NutritionAnalysis altA = analysis(NutritionStatus.SAFE, 70,
                Collections.<String>emptyList(), alt);
        return AlternativeExplainer.deltas(candidate, candA, altP, altA, config());
    }

    @Test
    public void deltas_badgePercentForHigherBadImprovement() {
        // 920 -> 140 mg sodium: (920-140)/920 = 84.8% -> "85% less".
        List<NutrientDelta> deltas = deltasFor(
                comp("sodium", 920.0, 10.0), comp("sodium", 140.0, 95.0));
        assertEquals(1, deltas.size());
        assertEquals("85% less", deltas.get(0).badge);
        assertEquals(920.0, deltas.get(0).candidatePer100, EPS);
        assertEquals(140.0, deltas.get(0).alternativePer100, EPS);
        assertEquals("Sodium", deltas.get(0).label);
        assertEquals("mg", deltas.get(0).unit);
    }

    @Test
    public void deltas_badgeHigherGoodImprovementIsAbsolutePlus() {
        // Fiber 3.0 -> 5.7 g: "+2.7 g" (absolute even though the gain
        // is 90% — percent badges are a higher-bad form).
        List<NutrientDelta> deltas = deltasFor(
                comp("fiber", 3.0, 30.0), comp("fiber", 5.7, 80.0));
        assertEquals("+2.7 g", deltas.get(0).badge);
    }

    @Test
    public void deltas_badgeBelow10PercentImprovementIsAbsolute() {
        // Sodium 1000 -> 950: 5% improvement -> "50 mg less".
        List<NutrientDelta> deltas = deltasFor(
                comp("sodium", 1000.0, 10.0), comp("sodium", 950.0, 20.0));
        assertEquals("50 mg less", deltas.get(0).badge);
    }

    @Test
    public void deltas_badgeCandidateZero_neverPercent() {
        // Candidate amount 0: percent is undefined, so a higher-bad
        // worsening falls back to the absolute badge.
        List<NutrientDelta> deltas = deltasFor(
                comp("total_sugars", 0.0, 90.0), comp("total_sugars", 5.0, 40.0));
        assertEquals("5 g more", deltas.get(0).badge);
    }

    @Test
    public void deltas_badgeHigherBadWorse_percentAndAbsolute() {
        // Total sugars 10 -> 12 g: +20% (>= 10%) -> "20% more".
        List<NutrientDelta> pct = deltasFor(
                comp("total_sugars", 10.0, 50.0), comp("total_sugars", 12.0, 40.0));
        assertEquals("20% more", pct.get(0).badge);
        // 10 -> 10.5 g: +5% -> absolute "0.5 g more".
        List<NutrientDelta> abs = deltasFor(
                comp("total_sugars", 10.0, 50.0), comp("total_sugars", 10.5, 45.0));
        assertEquals("0.5 g more", abs.get(0).badge);
    }

    @Test
    public void deltas_badgeHigherGoodWorseIsAbsoluteMinus() {
        // Fiber 5.0 -> 3.8 g: "-1.2 g".
        List<NutrientDelta> deltas = deltasFor(
                comp("fiber", 5.0, 60.0), comp("fiber", 3.8, 40.0));
        assertEquals("-1.2 g", deltas.get(0).badge);
    }

    @Test
    public void deltas_coverDifferingDisplayedInConfigOrder_skipEqualAndMissing() {
        ProductResult candidate = product("Candidate", "A, B, C");
        ProductResult alt = product("Alt", "X, Y, Z");
        NutritionAnalysis candA = analysis(NutritionStatus.CONCERNING, 40,
                Collections.<String>emptyList(),
                comp("sodium", 920.0, 10.0),
                comp("energy", 500.0, 20.0),
                comp("fiber", 1.0, 10.0),
                comp("protein", 5.0, 50.0),
                comp("added_sugars", 0.0, 90.0));
        NutritionAnalysis altA = analysis(NutritionStatus.SAFE, 70,
                Collections.<String>emptyList(),
                comp("sodium", 140.0, 95.0),
                comp("energy", 500.0, 20.0),      // equal -> skipped
                comp("fiber", 7.0, 90.0),
                comp("protein", 5.0, 50.0),       // equal -> skipped
                comp("added_sugars", null, null)); // missing -> skipped
        List<NutrientDelta> deltas = AlternativeExplainer.deltas(
                candidate, candA, alt, altA, config());
        List<String> keys = new ArrayList<>();
        for (NutrientDelta d : deltas) {
            keys.add(d.key);
        }
        // Config order: fiber precedes sodium.
        assertEquals(Arrays.asList("fiber", "sodium"), keys);
    }

    @Test
    public void deltas_favorableValuesComeFromComparisons() {
        List<NutrientDelta> deltas = deltasFor(
                compFav("sodium", 920.0, 5.0, 10.0),
                compFav("sodium", 140.0, 95.0, 95.0));
        assertEquals(Double.valueOf(5.0), deltas.get(0).candidateFavorable);
        assertEquals(Double.valueOf(95.0), deltas.get(0).alternativeFavorable);

        // Null favorable percentiles stay null (UI hides the bar pair).
        List<NutrientDelta> noFav = deltasFor(
                comp("sodium", 920.0, 10.0), comp("sodium", 140.0, 95.0));
        assertNull(noFav.get(0).candidateFavorable);
        assertNull(noFav.get(0).alternativeFavorable);
    }

    @Test
    public void deltas_emptyWhenAnalysesMissing() {
        ProductResult candidate = product("Candidate", "A, B, C");
        ProductResult alt = product("Alt", "X, Y, Z");
        assertTrue(AlternativeExplainer.deltas(candidate, null, alt, null,
                config()).isEmpty());
        assertTrue(AlternativeExplainer.reasons(candidate, null, alt, null,
                config()).isEmpty());
    }
}
