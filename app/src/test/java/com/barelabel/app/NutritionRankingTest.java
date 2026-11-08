package com.barelabel.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.barelabel.app.model.NutritionProfile;
import com.barelabel.app.model.ProductResult;
import com.barelabel.app.nutrition.CohortPeer;
import com.barelabel.app.nutrition.NutrientComparison;
import com.barelabel.app.nutrition.NutrientDefinition;
import com.barelabel.app.nutrition.NutritionAnalysis;
import com.barelabel.app.nutrition.NutritionAnalyzer;
import com.barelabel.app.nutrition.NutritionConfig;
import com.barelabel.app.nutrition.NutritionConfigLoader;
import com.barelabel.app.nutrition.NutritionStatus;
import com.barelabel.app.nutrition.PeerCohort;
import com.barelabel.app.nutrition.RankingProfile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AlternateRanker.rankNutritionAware: Contract §6, PRD §9.3/§9.4.
 *
 * Judgment calls pinned here (also documented on the implementation):
 * <ul>
 *   <li>Component helpers (profileFor / ingredientAdvantage /
 *       focusedNutrition / preferencesScore) are package-private
 *       statics, tested directly.</li>
 *   <li>A flagged ingredient counts as removed from an alternative
 *       when it is absent from the alternative's flagged set AND does
 *       not appear (case-insensitive) in its ingredient text.</li>
 *   <li>focusedNutrition falls back to absoluteQuality differences
 *       (the per-100 direction-aware gain) when combined quality is
 *       unavailable on either side; a nutrient the alternative does
 *       not report at all is skipped and weights renormalize.</li>
 *   <li>CLEAN candidates without a CONCERNING analysis (no ranking
 *       profile exists for them in the contract) use nutrition_only.</li>
 * </ul>
 */
public class NutritionRankingTest {

    private static final double EPS = 0.001;

    // ------------------------------------------------------------------
    // Synthetic config: real contract weights, direct construction.
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

    // ------------------------------------------------------------------
    // Synthetic products / analyses.
    // ------------------------------------------------------------------

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

    private static NutrientComparison comp(String key, Double per100,
                                           Double quality) {
        return new NutrientComparison(key, per100, null, 12, null, null,
                null, null, quality, false, "");
    }

    private static NutrientComparison compFull(String key, Double per100,
                                               Double favorable, Double absQ,
                                               Double quality) {
        return new NutrientComparison(key, per100, null, 12, null, null,
                favorable, absQ, quality, false, "");
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

    private static NutritionAnalysis safe(int score, NutrientComparison... comps) {
        return analysis(NutritionStatus.SAFE, score,
                Collections.<String>emptyList(), comps);
    }

    private static ProductResult eligibleAlt(String name, String ingredients,
                                             NutritionAnalysis altAnalysis) {
        ProductResult alt = product(name, ingredients);
        alt.nutritionAnalysis = altAnalysis;
        return alt;
    }

    // ------------------------------------------------------------------
    // Profile selection (Contract §6)
    // ------------------------------------------------------------------

    @Test
    public void profileFor_dirtyConcerning() {
        ProductResult candidate = product("C", "A, B", "soy lecithin");
        NutritionAnalysis a = analysis(NutritionStatus.CONCERNING, 30,
                Arrays.asList("sodium"));
        assertEquals("dirty_concerning", AlternateRanker.profileFor(candidate, a));
    }

    @Test
    public void profileFor_dirtySafe_isDirtyOnly() {
        ProductResult candidate = product("C", "A, B", "soy lecithin");
        assertEquals("dirty_only", AlternateRanker.profileFor(candidate,
                analysis(NutritionStatus.SAFE, 80, Collections.<String>emptyList())));
    }

    @Test
    public void profileFor_dirtyUnavailableOrNull_isDirtyOnly() {
        ProductResult candidate = product("C", "A, B", "soy lecithin");
        assertEquals("dirty_only", AlternateRanker.profileFor(candidate,
                analysis(NutritionStatus.UNAVAILABLE, null,
                        Collections.<String>emptyList())));
        assertEquals("dirty_only", AlternateRanker.profileFor(candidate, null));
    }

    @Test
    public void profileFor_cleanConcerning_isNutritionOnly() {
        ProductResult candidate = product("C", "A, B");
        NutritionAnalysis a = analysis(NutritionStatus.CONCERNING, 40,
                Arrays.asList("sodium"));
        assertEquals("nutrition_only", AlternateRanker.profileFor(candidate, a));
    }

    // ------------------------------------------------------------------
    // Eligibility defense (Contract §6 precondition, enforced)
    // ------------------------------------------------------------------

    private static ProductResult dirtyCandidate() {
        return product("Candidate", "POTATOES, OIL, SALT, SOY LECITHIN",
                "soy lecithin");
    }

    private static NutritionAnalysis dirtySafeAnalysis() {
        return analysis(NutritionStatus.SAFE, 70, Collections.<String>emptyList());
    }

    @Test
    public void rankNutritionAware_dropsFlaggedAlternative() {
        ProductResult bad = product("Bad", "X, Y", "soy lecithin");
        bad.nutritionAnalysis = safe(90);
        ProductResult good = eligibleAlt("Good", "X, Y", safe(50));
        List<AlternateRanker.RankedProduct> out = AlternateRanker.rankNutritionAware(
                dirtyCandidate(), dirtySafeAnalysis(),
                Arrays.asList(bad, good), Collections.<String>emptySet(),
                false, config());
        assertEquals(1, out.size());
        assertEquals("Good", out.get(0).product.name);
    }

    @Test
    public void rankNutritionAware_dropsNullAnalysisAlternative() {
        ProductResult bad = product("Bad", "X, Y"); // nutritionAnalysis null
        ProductResult good = eligibleAlt("Good", "X, Y", safe(50));
        List<AlternateRanker.RankedProduct> out = AlternateRanker.rankNutritionAware(
                dirtyCandidate(), dirtySafeAnalysis(),
                Arrays.asList(bad, good), Collections.<String>emptySet(),
                false, config());
        assertEquals(1, out.size());
        assertEquals("Good", out.get(0).product.name);
    }

    @Test
    public void rankNutritionAware_dropsNonSafeAlternative() {
        ProductResult concerning = eligibleAlt("Concerning", "X, Y",
                analysis(NutritionStatus.CONCERNING, 90, Arrays.asList("sodium")));
        ProductResult unavailable = eligibleAlt("Unavailable", "X, Y",
                analysis(NutritionStatus.UNAVAILABLE, null,
                        Collections.<String>emptyList()));
        ProductResult good = eligibleAlt("Good", "X, Y", safe(50));
        List<AlternateRanker.RankedProduct> out = AlternateRanker.rankNutritionAware(
                dirtyCandidate(), dirtySafeAnalysis(),
                Arrays.asList(concerning, unavailable, good),
                Collections.<String>emptySet(), false, config());
        assertEquals(1, out.size());
        assertEquals("Good", out.get(0).product.name);
    }

    // ------------------------------------------------------------------
    // ingredientAdvantage
    // ------------------------------------------------------------------

    @Test
    public void ingredientAdvantage_fourFlagged_altAvoidsThree_is75() {
        ProductResult candidate = product("C", "A, B, C, D",
                "alpha", "beta", "gamma", "delta");
        // The alternative's ingredient text still contains "beta"; the
        // other three flagged items are absent.
        ProductResult alt = product("Alt", "RICE, BETA, SALT");
        assertEquals(75.0, AlternateRanker.ingredientAdvantage(candidate, alt), EPS);
    }

    @Test
    public void ingredientAdvantage_categoriesWeightedEqually() {
        // Category A: 2 items, alt avoids 1 (50%). Category B: 1 item,
        // alt avoids it (100%). Equal category weighting gives 75;
        // per-item weighting would give 2/3 = 66.7.
        ProductResult candidate = product("C", "A, B, C");
        candidate.flagged = new HashSet<>(Arrays.asList("a-one", "a-two", "b-one"));
        candidate.matchResult = new FlaggedIngredientManager.MatchResult();
        candidate.matchResult.categoryMap.put("A",
                new ArrayList<>(Arrays.asList("a-one", "a-two")));
        candidate.matchResult.categoryMap.put("B",
                new ArrayList<>(Collections.singletonList("b-one")));
        ProductResult alt = product("Alt", "RICE, A-ONE, SALT");
        assertEquals(75.0, AlternateRanker.ingredientAdvantage(candidate, alt), EPS);
    }

    @Test
    public void ingredientAdvantage_noFlaggedCandidate_is100() {
        ProductResult candidate = product("C", "A, B");
        ProductResult alt = product("Alt", "X, Y");
        assertEquals(100.0, AlternateRanker.ingredientAdvantage(candidate, alt), EPS);
    }

    @Test
    public void ingredientAdvantage_altFlaggedWithItem_countsAsPresent() {
        ProductResult candidate = product("C", "A, B", "alpha", "beta");
        ProductResult alt = product("Alt", "RICE, SALT", "beta");
        // Not eligible in the real flow, but the component itself must
        // treat an item in the alternative's flagged set as present.
        assertEquals(50.0, AlternateRanker.ingredientAdvantage(candidate, alt), EPS);
    }

    // ------------------------------------------------------------------
    // focusedNutrition
    // ------------------------------------------------------------------

    private static NutritionAnalysis concerningWithSodium(double candQuality) {
        return analysis(NutritionStatus.CONCERNING, 30, Arrays.asList("sodium"),
                comp("sodium", 900.0, candQuality),
                comp("fiber", 5.0, 90.0));
    }

    @Test
    public void focusedNutrition_qualityGainClampsAt100() {
        NutritionAnalysis cand = concerningWithSodium(20.0);
        NutritionAnalysis alt = safe(80, comp("sodium", 100.0, 80.0),
                comp("fiber", 5.0, 90.0));
        // clamp(50 + (80 - 20)) = clamp(110) = 100.
        assertEquals(100.0, AlternateRanker.focusedNutrition(cand, alt, config()), EPS);
    }

    @Test
    public void focusedNutrition_equalQuality_is50() {
        NutritionAnalysis cand = concerningWithSodium(60.0);
        NutritionAnalysis alt = safe(80, comp("sodium", 900.0, 60.0));
        assertEquals(50.0, AlternateRanker.focusedNutrition(cand, alt, config()), EPS);
    }

    @Test
    public void focusedNutrition_worseAltQuality_clampsAt0() {
        NutritionAnalysis cand = concerningWithSodium(60.0);
        NutritionAnalysis alt = safe(80, comp("sodium", 1500.0, 10.0));
        // clamp(50 + (10 - 60)) = 0.
        assertEquals(0.0, AlternateRanker.focusedNutrition(cand, alt, config()), EPS);
    }

    @Test
    public void focusedNutrition_usesOnlyCandidateFlaggedNutrients() {
        // Candidate flags sodium only. The alternative is far better on
        // fiber but identical on sodium: focused must stay 50 (sodium
        // alone), not be lifted by the unflagged fiber gain.
        NutritionAnalysis cand = concerningWithSodium(50.0);
        NutritionAnalysis alt = safe(80, comp("sodium", 900.0, 50.0),
                comp("fiber", 50.0, 100.0));
        assertEquals(50.0, AlternateRanker.focusedNutrition(cand, alt, config()), EPS);
    }

    @Test
    public void focusedNutrition_missingAltValue_skipsAndRenormalizes() {
        // Candidate flags sodium (w 0.16) and fiber (w 0.14). The
        // alternative reports no fiber at all: fiber is skipped, so the
        // focused value comes from sodium alone (gain 100). Counting
        // fiber as 50-neutral would give (16*100 + 14*50) / 30 = 76.7.
        NutritionAnalysis cand = analysis(NutritionStatus.CONCERNING, 30,
                Arrays.asList("fiber", "sodium"),
                comp("fiber", 1.0, 20.0), comp("sodium", 900.0, 20.0));
        NutritionAnalysis alt = safe(80,
                comp("fiber", null, null), comp("sodium", 100.0, 80.0));
        assertEquals(100.0, AlternateRanker.focusedNutrition(cand, alt, config()), EPS);
    }

    @Test
    public void focusedNutrition_absoluteQualityFallbackWhenNoCombinedQuality() {
        // No combined quality on either side: the per-100
        // direction-aware gain uses absoluteQuality differences:
        // clamp(50 + (60 - 30)) = 80.
        NutritionAnalysis cand = analysis(NutritionStatus.CONCERNING, 30,
                Arrays.asList("sodium"),
                compFull("sodium", 900.0, null, 30.0, null));
        NutritionAnalysis alt = safe(80,
                compFull("sodium", 300.0, null, 60.0, null));
        assertEquals(80.0, AlternateRanker.focusedNutrition(cand, alt, config()), EPS);
    }

    @Test
    public void focusedNutrition_noFlaggedNutrients_isZero() {
        NutritionAnalysis cand = analysis(NutritionStatus.SAFE, 70,
                Collections.<String>emptyList(), comp("sodium", 100.0, 90.0));
        NutritionAnalysis alt = safe(80, comp("sodium", 100.0, 90.0));
        assertEquals(0.0, AlternateRanker.focusedNutrition(cand, alt, config()), EPS);
    }

    // ------------------------------------------------------------------
    // preferences component
    // ------------------------------------------------------------------

    private static ProductResult prefAlt(String ingredients) {
        return product("Alt", ingredients);
    }

    @Test
    public void preferences_superiorCountScaling() {
        ProductResult candidate = prefAlt("A, B, C, D, E, F, G, H");
        Set<String> terms = new HashSet<>(Arrays.asList("oats", "honey", "kale", "flax"));
        // Same-length lists: no shorter-list bonus in play.
        assertEquals(0.0, AlternateRanker.preferencesScore(candidate,
                prefAlt("RICE, SALT, OIL, WATER, CORN, PEAS, BEANS, HERBS"), terms), EPS);
        assertEquals(33.0, AlternateRanker.preferencesScore(candidate,
                prefAlt("OATS, SALT, OIL, WATER, CORN, PEAS, BEANS, HERBS"), terms), EPS);
        assertEquals(66.0, AlternateRanker.preferencesScore(candidate,
                prefAlt("OATS, HONEY, OIL, WATER, CORN, PEAS, BEANS, HERBS"), terms), EPS);
        assertEquals(100.0, AlternateRanker.preferencesScore(candidate,
                prefAlt("OATS, HONEY, KALE, WATER, CORN, PEAS, BEANS, HERBS"), terms), EPS);
        assertEquals(100.0, AlternateRanker.preferencesScore(candidate,
                prefAlt("OATS, HONEY, KALE, FLAX, CORN, PEAS, BEANS, HERBS"), terms), EPS);
    }

    @Test
    public void preferences_shorterListBonusAndCap() {
        ProductResult candidate = prefAlt("A, B, C, D, E");
        Set<String> terms = new HashSet<>(Arrays.asList("oats", "honey", "kale"));
        // No superior terms, strictly shorter list: +10.
        assertEquals(10.0, AlternateRanker.preferencesScore(candidate,
                prefAlt("RICE, SALT"), terms), EPS);
        // Three superior terms (100) plus shorter list: capped at 100.
        assertEquals(100.0, AlternateRanker.preferencesScore(candidate,
                prefAlt("OATS, HONEY, KALE"), terms), EPS);
        // Equal length is not shorter: no bonus.
        assertEquals(0.0, AlternateRanker.preferencesScore(candidate,
                prefAlt("RICE, SALT, OIL, WATER, CORN"), terms), EPS);
    }

    // ------------------------------------------------------------------
    // rankNutritionAware: scores, tiers, tie-breakers
    // ------------------------------------------------------------------

    @Test
    public void rankNutritionAware_dirtyOnlyProfileMathAndRankedFields() {
        // dirty_only weights: IA .75, focused 0, overall .15, pref .10.
        // Clean alt (IA 100), nutrition score 80, no preferences (the
        // alt's 4-ingredient list is not shorter than the candidate's):
        // rankScore = 75 + 12 + 0 = 87. Overall = blend(100, 80) = 94.
        ProductResult alt = eligibleAlt("Alt", "RICE, SALT, OIL, WATER", safe(80));
        List<AlternateRanker.RankedProduct> out = AlternateRanker.rankNutritionAware(
                dirtyCandidate(), dirtySafeAnalysis(),
                Collections.singletonList(alt), Collections.<String>emptySet(),
                false, config());
        assertEquals(1, out.size());
        AlternateRanker.RankedProduct rp = out.get(0);
        assertEquals(87.0, rp.rankScore, EPS);
        assertEquals(Integer.valueOf(94), rp.overallScore);
        assertEquals(Integer.valueOf(80), rp.nutritionScore);
        assertNotNull(rp.reasons);
        assertNotNull(rp.deltas);
    }

    @Test
    public void preferOrganicTier_beatsHigherRankScore() {
        ProductResult strong = eligibleAlt("Strong", "RICE, SALT", safe(90));
        ProductResult organic = eligibleAlt("Organic",
                "ORGANIC RICE, SALT", safe(40));
        // dirty_only rank scores (both alts earn the +10 shorter-list
        // preference, worth 1.0): strong 75+13.5+1=89.5, organic 75+6+1=82.
        List<AlternateRanker.RankedProduct> plain = AlternateRanker.rankNutritionAware(
                dirtyCandidate(), dirtySafeAnalysis(),
                Arrays.asList(organic, strong), Collections.<String>emptySet(),
                false, config());
        assertEquals("Strong", plain.get(0).product.name);
        assertEquals("Organic", plain.get(1).product.name);

        List<AlternateRanker.RankedProduct> organicFirst =
                AlternateRanker.rankNutritionAware(
                        dirtyCandidate(), dirtySafeAnalysis(),
                        Arrays.asList(strong, organic),
                        Collections.<String>emptySet(), true, config());
        assertEquals("Organic", organicFirst.get(0).product.name);
        assertEquals("Strong", organicFirst.get(1).product.name);
    }

    @Test
    public void preferOrganicTier_nonGmoBetweenOrganicAndRest() {
        ProductResult plainAlt = eligibleAlt("Plain", "RICE, SALT", safe(95));
        ProductResult nonGmo = eligibleAlt("NonGmo",
                "RICE, SALT, NON-GMO CANOLA OIL", safe(60));
        ProductResult organic = eligibleAlt("Organic",
                "ORGANIC RICE, SALT", safe(30));
        List<AlternateRanker.RankedProduct> out = AlternateRanker.rankNutritionAware(
                dirtyCandidate(), dirtySafeAnalysis(),
                Arrays.asList(plainAlt, nonGmo, organic),
                Collections.<String>emptySet(), true, config());
        assertEquals("Organic", out.get(0).product.name);
        assertEquals("NonGmo", out.get(1).product.name);
        assertEquals("Plain", out.get(2).product.name);
    }

    @Test
    public void tieBreak_equalRankScore_higherOverallScoreFirst() {
        // nutrition_only weights: focused .60, overall .25, pref .15.
        // Candidate CLEAN + CONCERNING, flags sodium (quality 50).
        // Alt A: sodium quality 50 -> focused 50; score 80 -> 30+20=50.
        // Alt B: sodium quality 75 -> focused 75; score 20 -> 45+5=50.
        // Equal rankScore 50; Overall A = blend(100,80) = 94 beats
        // Overall B = blend(100,20) = 76.
        ProductResult candidate = product("Candidate", "A, B, C");
        NutritionAnalysis candAnalysis = analysis(NutritionStatus.CONCERNING, 40,
                Arrays.asList("sodium"), comp("sodium", 900.0, 50.0));
        ProductResult altA = eligibleAlt("A", "X, Y, Z",
                safe(80, comp("sodium", 900.0, 50.0)));
        ProductResult altB = eligibleAlt("B", "X, Y, Z",
                safe(20, comp("sodium", 500.0, 75.0)));
        List<AlternateRanker.RankedProduct> out = AlternateRanker.rankNutritionAware(
                candidate, candAnalysis, Arrays.asList(altB, altA),
                Collections.<String>emptySet(), false, config());
        assertEquals(50.0, out.get(0).rankScore, EPS);
        assertEquals(50.0, out.get(1).rankScore, EPS);
        assertEquals("A", out.get(0).product.name);
        assertEquals(Integer.valueOf(94), out.get(0).overallScore);
        assertEquals("B", out.get(1).product.name);
        assertEquals(Integer.valueOf(76), out.get(1).overallScore);
    }

    @Test
    public void tieBreak_equalRankAndOverall_fewerIngredientsFirst() {
        // Both alts shorter than the candidate (both earn the +10
        // shorter-list bonus), identical scores: rankScore and Overall
        // tie, so the shorter ingredient list wins.
        StringBuilder many = new StringBuilder("I0");
        for (int i = 1; i < 20; i++) {
            many.append(", I").append(i);
        }
        ProductResult candidate = product("Candidate", many.toString());
        NutritionAnalysis candAnalysis = analysis(NutritionStatus.CONCERNING, 40,
                Arrays.asList("sodium"), comp("sodium", 900.0, 50.0));
        ProductResult five = eligibleAlt("Five", "A, B, C, D, E",
                safe(70, comp("sodium", 900.0, 50.0)));
        ProductResult three = eligibleAlt("Three", "A, B, C",
                safe(70, comp("sodium", 900.0, 50.0)));
        List<AlternateRanker.RankedProduct> out = AlternateRanker.rankNutritionAware(
                candidate, candAnalysis, Arrays.asList(five, three),
                Collections.<String>emptySet(), false, config());
        assertEquals(out.get(0).rankScore, out.get(1).rankScore, EPS);
        assertEquals(out.get(0).overallScore, out.get(1).overallScore);
        assertEquals("Three", out.get(0).product.name);
        assertEquals("Five", out.get(1).product.name);
    }

    @Test
    public void tieBreak_fullyEqual_keepsOriginalOrder() {
        ProductResult first = eligibleAlt("First", "A, B, C",
                safe(70, comp("sodium", 900.0, 50.0)));
        ProductResult second = eligibleAlt("Second", "A, B, C",
                safe(70, comp("sodium", 900.0, 50.0)));
        ProductResult candidate = product("Candidate", "A, B, C");
        NutritionAnalysis candAnalysis = analysis(NutritionStatus.CONCERNING, 40,
                Arrays.asList("sodium"), comp("sodium", 900.0, 50.0));
        List<AlternateRanker.RankedProduct> out = AlternateRanker.rankNutritionAware(
                candidate, candAnalysis, Arrays.asList(first, second),
                Collections.<String>emptySet(), false, config());
        assertEquals("First", out.get(0).product.name);
        assertEquals("Second", out.get(1).product.name);
    }

    @Test
    public void legacyRankedProductConstructor_defaultsNewFields() {
        ProductResult p = product("P", "A, B");
        AlternateRanker.RankedProduct rp = new AlternateRanker.RankedProduct(
                p, 1, 2, 0, false, false, false);
        assertEquals(0.0, rp.rankScore, EPS);
        assertNull(rp.overallScore);
        assertNull(rp.nutritionScore);
        assertTrue(rp.reasons.isEmpty());
        assertTrue(rp.deltas.isEmpty());
    }

    // ------------------------------------------------------------------
    // Mockup scenario over the chips fixture (real parser+cohort+
    // analyzer pipeline, like NutritionAnalyzerTest).
    // ------------------------------------------------------------------

    private static final String CHIPS_CATEGORY = "Chips, Pretzels & Snacks";

    private static final String CONFIG_JSON = "{"
            + "\"version\": 1,"
            + "\"blend\": {\"ingredientWeight\": 0.70, \"nutritionWeight\": 0.30},"
            + "\"peers\": {\"minPeers\": 10, \"preferredPeers\": 20,"
            + " \"maxCategoryPages\": 3, \"roundBelowPreferredToNearest\": 5},"
            + "\"scoring\": {\"minCoverage\": 0.50, \"relativeWeight\": 0.65,"
            + " \"absoluteWeight\": 0.35},"
            + "\"alternatives\": {\"maxPagesPerQuery\": 3, \"poolCap\": 60},"
            + "\"rankingProfiles\": {"
            + " \"dirty_concerning\": {\"ingredientAdvantage\": 0.40,"
            + "  \"focusedNutrition\": 0.35, \"overallNutrition\": 0.15,"
            + "  \"preferences\": 0.10},"
            + " \"dirty_only\": {\"ingredientAdvantage\": 0.75,"
            + "  \"focusedNutrition\": 0.00, \"overallNutrition\": 0.15,"
            + "  \"preferences\": 0.10},"
            + " \"nutrition_only\": {\"ingredientAdvantage\": 0.00,"
            + "  \"focusedNutrition\": 0.60, \"overallNutrition\": 0.25,"
            + "  \"preferences\": 0.15}},"
            + "\"nutrients\": ["
            + nutrientJson("energy", 1008, "Energy", "kcal", "higher_bad", 0.10,
                    "energy", 0, 900, 150, 650,
                    ",\"flagPercentile\": 85, \"flagFloor\": 500, \"severeAt\": 750")
            + ","
            + nutrientJson("protein", 1003, "Protein", "g", "higher_good", 0.12,
                    "protein", 0, 100, 20, 0, "")
                    .replace("\"flagEligible\": true", "\"flagEligible\": false")
            + ","
            + nutrientJson("total_fat", 1004, "Total Fat", "g", "higher_bad", 0.06,
                    "total_fat", 0, 100, 3, 45,
                    ",\"flagPercentile\": 85, \"flagFloor\": 30, \"severeAt\": 60")
            + ","
            + nutrientJson("carbohydrates", 1005, "Carbohydrates", "g",
                    "higher_bad", 0.06, "carbohydrates", 0, 100, 10, 75,
                    ",\"flagPercentile\": 85, \"flagFloor\": 60, \"severeAt\": 85")
            + ","
            + nutrientJson("total_sugars", 2000, "Total Sugars", "g", "higher_bad",
                    0.16, "sugars", 0, 100, 0, 35,
                    ",\"flagPercentile\": 85, \"flagFloor\": 20, \"severeAt\": 50")
            + ","
            + nutrientJson("added_sugars", 1235, "Added Sugars", "g", "higher_bad",
                    0.16, "sugars", 0, 100, 0, 25,
                    ",\"flagPercentile\": 85, \"flagFloor\": 10, \"severeAt\": 35,"
                    + "\"fallbackKey\": \"total_sugars\","
                    + " \"fallbackWeightFactor\": 1.0")
            + ","
            + nutrientJson("fiber", 1079, "Fiber", "g", "higher_good", 0.14,
                    "fiber", 0, 100, 12, 0,
                    ",\"lowFlagPercentile\": 15, \"lowFlagAtOrBelow\": 3")
            + ","
            + nutrientJson("sodium", 1093, "Sodium", "mg", "higher_bad", 0.16,
                    "sodium", 0, 5000, 40, 1200,
                    ",\"flagPercentile\": 85, \"flagFloor\": 600,"
                    + " \"severeAt\": 2000")
            + ","
            + nutrientJson("saturated_fat", 1258, "Saturated Fat", "g",
                    "higher_bad", 0.10, "saturated_fat", 0, 100, 0.5, 20,
                    ",\"flagPercentile\": 85, \"flagFloor\": 10, \"severeAt\": 30")
            + ","
            + nutrientJson("trans_fat", 1257, "Trans Fat", "g", "higher_bad",
                    0.05, "trans_fat", 0, 20, 0, 2,
                    ",\"flagPercentile\": 85, \"flagFloor\": 0.5, \"severeAt\": 2")
            + ","
            + nutrientJson("cholesterol", 1253, "Cholesterol", "mg", "higher_bad",
                    0.05, "cholesterol", 0, 1500, 0, 300,
                    ",\"flagPercentile\": 85, \"flagFloor\": 150,"
                    + " \"severeAt\": 500")
            + "]"
            + "}";

    private static String nutrientJson(String key, int usdaId, String label,
                                       String unit, String direction, double weight,
                                       String group, double plausibleMin,
                                       double plausibleMax, double goodAt,
                                       double badAt, String extraFields) {
        return "{\"key\": \"" + key + "\", \"usdaIds\": [" + usdaId + "],"
                + " \"label\": \"" + label + "\", \"unit\": \"" + unit + "\","
                + " \"direction\": \"" + direction + "\", \"display\": true,"
                + " \"scoreEligible\": true, \"flagEligible\": true,"
                + " \"weight\": " + weight + ","
                + " \"scoringGroup\": \"" + group + "\","
                + " \"plausibleMin\": " + plausibleMin + ","
                + " \"plausibleMax\": " + plausibleMax + ","
                + " \"goodAt\": " + goodAt + ", \"badAt\": " + badAt
                + extraFields + "}";
    }

    private static JSONArray fixtureFoods() throws Exception {
        InputStream is = NutritionRankingTest.class
                .getResourceAsStream("/usda_chips_nutrition.json");
        assertNotNull("fixture on test classpath", is);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        is.close();
        return new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8))
                .getJSONArray("foods");
    }

    private static JSONObject fixtureFood(JSONArray foods, long fdcId) throws Exception {
        for (int i = 0; i < foods.length(); i++) {
            JSONObject food = foods.getJSONObject(i);
            if (food.optLong("fdcId", 0) == fdcId) {
                return food;
            }
        }
        fail("fixture has no food with fdcId " + fdcId);
        return null;
    }

    /** Product + real analysis for one fixture food, against a cohort
     *  built with that food as the excluded candidate. */
    private static ProductResult fixtureProduct(JSONArray foods, long fdcId,
                                                NutritionConfig config) throws Exception {
        List<CohortPeer> pool = new ArrayList<>();
        for (int i = 0; i < foods.length(); i++) {
            JSONObject food = foods.getJSONObject(i);
            NutritionProfile profile = com.barelabel.app.nutrition.UsdaNutritionParser
                    .parse(food, config);
            String gtin = food.optString("gtinUpc", "");
            pool.add(new CohortPeer(profile,
                    gtin.isEmpty() ? "" : "gtin:" + gtin,
                    food.optString("foodCategory", "")));
        }
        JSONObject food = fixtureFood(foods, fdcId);
        NutritionProfile profile = com.barelabel.app.nutrition.UsdaNutritionParser
                .parse(food, config);
        PeerCohort cohort = PeerCohort.build(profile, CHIPS_CATEGORY, pool, config);
        ProductResult p = new ProductResult();
        p.found = true;
        p.fdcId = fdcId;
        p.name = food.optString("description", "");
        p.brand = food.optString("brandName", "");
        p.ingredients = food.optString("ingredients", "");
        p.foodCategory = CHIPS_CATEGORY;
        p.nutritionProfile = profile;
        p.nutritionAnalysis = NutritionAnalyzer.analyze(profile, cohort, config);
        p.matchResult = new FlaggedIngredientManager.MatchResult();
        return p;
    }

    @Test
    public void mockup_chips_betterSodiumImproverRanksFirst() throws Exception {
        NutritionConfig config = NutritionConfigLoader.parse(CONFIG_JSON);
        JSONArray foods = fixtureFoods();

        ProductResult candidate = fixtureProduct(foods, 990001L, config);
        candidate.flagged = new HashSet<>(Collections.singletonList("soy lecithin"));
        candidate.matchResult.categoryMap.put("Emulsifiers",
                new ArrayList<>(Collections.singletonList("soy lecithin")));
        assertEquals(NutritionStatus.CONCERNING, candidate.nutritionAnalysis.status);
        assertTrue(candidate.nutritionAnalysis.flaggedNutrients.contains("sodium"));

        ProductResult alt13 = fixtureProduct(foods, 990013L, config);
        ProductResult alt12 = fixtureProduct(foods, 990012L, config);
        assertEquals(NutritionStatus.SAFE, alt13.nutritionAnalysis.status);
        assertEquals(NutritionStatus.SAFE, alt12.nutritionAnalysis.status);

        List<AlternateRanker.RankedProduct> out = AlternateRanker.rankNutritionAware(
                candidate, candidate.nutritionAnalysis,
                Arrays.asList(alt12, alt13), Collections.<String>emptySet(),
                false, config);

        assertEquals(2, out.size());
        // 990013 (sodium 140 mg) beats 990012 (sodium 180 mg): both hit
        // the focused ceiling (100) and both earn the shorter-list
        // preference bonus (4 and 5 ingredients vs the candidate's 6),
        // so the higher Nutrition Score (81 vs 72) decides: rankScore
        // 88.15 vs 86.80 (hand-verified Python mirror of the
        // parser+cohort+analyzer+ranking math).
        assertEquals(990013L, out.get(0).product.fdcId);
        assertEquals(990012L, out.get(1).product.fdcId);
        assertEquals(Integer.valueOf(81), out.get(0).nutritionScore);
        assertEquals(Integer.valueOf(72), out.get(1).nutritionScore);
        assertEquals(Integer.valueOf(94), out.get(0).overallScore);
        assertEquals(Integer.valueOf(92), out.get(1).overallScore);
        assertEquals(88.15, out.get(0).rankScore, 0.1);
        assertEquals(86.80, out.get(1).rankScore, 0.1);

        // Reasons lead with the sodium improvement, then the removal.
        assertEquals("85% less sodium per 100 g.", out.get(0).reasons.get(0));
        assertEquals("Avoids soy lecithin.", out.get(0).reasons.get(1));
        assertEquals("80% less sodium per 100 g.", out.get(1).reasons.get(0));

        // Deltas cover every differing displayed nutrient, config order.
        List<String> keys = new ArrayList<>();
        NutrientDelta sodium = null;
        for (NutrientDelta d : out.get(0).deltas) {
            keys.add(d.key);
            if ("sodium".equals(d.key)) {
                sodium = d;
            }
        }
        assertEquals(Arrays.asList("energy", "protein", "total_fat",
                "carbohydrates", "total_sugars", "fiber", "sodium",
                "saturated_fat", "trans_fat"), keys);
        assertNotNull(sodium);
        assertEquals(920.0, sodium.candidatePer100, EPS);
        assertEquals(140.0, sodium.alternativePer100, EPS);
        assertEquals("85% less", sodium.badge);
    }
}
