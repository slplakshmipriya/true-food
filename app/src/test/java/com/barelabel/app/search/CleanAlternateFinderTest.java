package com.barelabel.app.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.barelabel.app.FlaggedIngredientManager;
import com.barelabel.app.model.AlternateSearchResult;
import com.barelabel.app.model.ProductResult;
import com.barelabel.app.nutrition.CohortPeer;
import com.barelabel.app.nutrition.NutritionAnalysis;
import com.barelabel.app.nutrition.NutritionConfig;
import com.barelabel.app.nutrition.NutritionConfigLoader;
import com.barelabel.app.nutrition.NutritionStatus;
import com.barelabel.app.nutrition.UsdaNutritionParser;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * CleanAlternateFinder eligibility integration: Contract §5 step 5,
 * PRD §9.2.
 *
 * Three pure seams are pinned here (the finder itself needs an Android
 * Context and the USDA client, so the deterministic logic is extracted
 * static, the same pattern as NutritionCohortService):
 * <ul>
 *   <li>{@code isEligibleAlternate} — the CLEAN ∧ SAFE predicate:
 *       DIRTY, CONCERNING, and UNAVAILABLE candidates are all dropped;
 *       only ingredient-CLEAN with nutrition status SAFE is kept.</li>
 *   <li>{@code recordBlockedCandidate} — flagged-category block
 *       statistics for ingredient-ineligible candidates.</li>
 *   <li>{@code buildScannedProduct} + {@code finalizeScannedPool} —
 *       scanned products carry profiles from the single parser path,
 *       the final cohort (step-3 cohort peers extended with the scanned
 *       pool) is rebuilt once, the primary and every scanned candidate
 *       are analyzed against it, and only eligible alternates are
 *       returned. A pool whose first clean candidates are CONCERNING
 *       still returns the later SAFE ones; when nothing qualifies the
 *       result is explicitly empty — never substituted.</li>
 * </ul>
 *
 * Fixture: {@code usda_chips_nutrition.json}. Against the full fixture
 * cohort, 990001 is the CONCERNING primary and 990012 / 990013 are the
 * SAFE exemplars.
 */
public class CleanAlternateFinderTest {

    private static final String CHIPS_CATEGORY = "Chips, Pretzels & Snacks";

    // ------------------------------------------------------------------
    // Real config through the real loader (same embedded §1.2 values as
    // NutritionAnalyzerTest — no mocked definitions).
    // ------------------------------------------------------------------

    private static String nutrient(String key, int usdaId, String label,
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
            + nutrient("energy", 1008, "Energy", "kcal", "higher_bad", 0.10,
                    "energy", 0, 900, 150, 650,
                    ",\"flagPercentile\": 85, \"flagFloor\": 500, \"severeAt\": 750")
            + ","
            + nutrient("protein", 1003, "Protein", "g", "higher_good", 0.12,
                    "protein", 0, 100, 20, 0, "")
                    .replace("\"flagEligible\": true", "\"flagEligible\": false")
            + ","
            + nutrient("total_fat", 1004, "Total Fat", "g", "higher_bad", 0.06,
                    "total_fat", 0, 100, 3, 45,
                    ",\"flagPercentile\": 85, \"flagFloor\": 30, \"severeAt\": 60")
            + ","
            + nutrient("carbohydrates", 1005, "Carbohydrates", "g",
                    "higher_bad", 0.06, "carbohydrates", 0, 100, 10, 75,
                    ",\"flagPercentile\": 85, \"flagFloor\": 60, \"severeAt\": 85")
            + ","
            + nutrient("total_sugars", 2000, "Total Sugars", "g", "higher_bad",
                    0.16, "sugars", 0, 100, 0, 35,
                    ",\"flagPercentile\": 85, \"flagFloor\": 20, \"severeAt\": 50")
            + ","
            + nutrient("added_sugars", 1235, "Added Sugars", "g", "higher_bad",
                    0.16, "sugars", 0, 100, 0, 25,
                    ",\"flagPercentile\": 85, \"flagFloor\": 10, \"severeAt\": 35,"
                    + "\"fallbackKey\": \"total_sugars\","
                    + " \"fallbackWeightFactor\": 1.0")
            + ","
            + nutrient("fiber", 1079, "Fiber", "g", "higher_good", 0.14,
                    "fiber", 0, 100, 12, 0,
                    ",\"lowFlagPercentile\": 15, \"lowFlagAtOrBelow\": 3")
            + ","
            + nutrient("sodium", 1093, "Sodium", "mg", "higher_bad", 0.16,
                    "sodium", 0, 5000, 40, 1200,
                    ",\"flagPercentile\": 85, \"flagFloor\": 600,"
                    + " \"severeAt\": 2000")
            + ","
            + nutrient("saturated_fat", 1258, "Saturated Fat", "g",
                    "higher_bad", 0.10, "saturated_fat", 0, 100, 0.5, 20,
                    ",\"flagPercentile\": 85, \"flagFloor\": 10, \"severeAt\": 30")
            + ","
            + nutrient("trans_fat", 1257, "Trans Fat", "g", "higher_bad",
                    0.05, "trans_fat", 0, 20, 0, 2,
                    ",\"flagPercentile\": 85, \"flagFloor\": 0.5, \"severeAt\": 2")
            + ","
            + nutrient("cholesterol", 1253, "Cholesterol", "mg", "higher_bad",
                    0.05, "cholesterol", 0, 1500, 0, 300,
                    ",\"flagPercentile\": 85, \"flagFloor\": 150,"
                    + " \"severeAt\": 500")
            + "]"
            + "}";

    private static NutritionConfig config() throws Exception {
        return NutritionConfigLoader.parse(CONFIG_JSON);
    }

    private static NutritionAnalysis analysis(NutritionStatus status) {
        return new NutritionAnalysis(status, null, 0.0, CHIPS_CATEGORY, 0,
                new LinkedHashMap<String, com.barelabel.app.nutrition.NutrientComparison>(),
                new ArrayList<String>());
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private static JSONArray fixtureFoods() throws Exception {
        InputStream is = CleanAlternateFinderTest.class
                .getResourceAsStream("/usda_chips_nutrition.json");
        assertNotNull("fixture on test classpath", is);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        is.close();
        return new JSONObject(new String(buf.toByteArray(),
                StandardCharsets.UTF_8)).getJSONArray("foods");
    }

    private static JSONObject fixtureFood(JSONArray foods, long fdcId)
            throws Exception {
        for (int i = 0; i < foods.length(); i++) {
            JSONObject food = foods.getJSONObject(i);
            if (food.optLong("fdcId", 0) == fdcId) {
                return food;
            }
        }
        fail("fixture has no food with fdcId " + fdcId);
        return null;
    }

    private static FlaggedIngredientManager.MatchResult matchResult(
            String... categoryThenItems) {
        FlaggedIngredientManager.MatchResult match =
                new FlaggedIngredientManager.MatchResult();
        for (int i = 0; i < categoryThenItems.length; i += 2) {
            match.categoryMap.put(categoryThenItems[i],
                    new ArrayList<>(Arrays.asList(
                            categoryThenItems[i + 1].split("\\|"))));
        }
        return match;
    }

    /**
     * A scanned product the way the finder builds it: manual fields plus
     * the profile/fdcId from the single parser path.
     */
    private static ProductResult scannedProduct(JSONObject food,
                                                boolean dirty,
                                                NutritionConfig config) {
        FlaggedIngredientManager.MatchResult match = dirty
                ? matchResult("Emulsifiers", "soy lecithin")
                : new FlaggedIngredientManager.MatchResult();
        return CleanAlternateFinder.buildScannedProduct(food, match, config);
    }

    /** Step-3 cohort peers: every fixture food parsed as a seed peer. */
    private static List<CohortPeer> seedPeers(JSONArray foods,
                                              NutritionConfig config)
            throws Exception {
        List<CohortPeer> peers = new ArrayList<>();
        for (int i = 0; i < foods.length(); i++) {
            JSONObject food = foods.getJSONObject(i);
            peers.add(new CohortPeer(
                    UsdaNutritionParser.parse(food, config),
                    food.optString("gtinUpc", ""),
                    food.optString("foodCategory", "")));
        }
        return peers;
    }

    private static List<Long> fdcIds(List<ProductResult> products) {
        List<Long> ids = new ArrayList<>();
        for (ProductResult p : products) {
            ids.add(p.fdcId);
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // Eligibility predicate (PRD §9.2): CLEAN ∧ SAFE, nothing else
    // ------------------------------------------------------------------

    @Test
    public void eligibility_dirtyAlt_dropped() {
        assertFalse(CleanAlternateFinder.isEligibleAlternate(
                false, analysis(NutritionStatus.SAFE)));
    }

    @Test
    public void eligibility_concerningAlt_dropped() {
        assertFalse(CleanAlternateFinder.isEligibleAlternate(
                true, analysis(NutritionStatus.CONCERNING)));
    }

    @Test
    public void eligibility_unavailableAlt_dropped() {
        assertFalse(CleanAlternateFinder.isEligibleAlternate(
                true, analysis(NutritionStatus.UNAVAILABLE)));
        // No analysis at all reads as UNAVAILABLE, never as SAFE.
        assertFalse(CleanAlternateFinder.isEligibleAlternate(true, null));
    }

    @Test
    public void eligibility_cleanAndSafe_kept() {
        assertTrue(CleanAlternateFinder.isEligibleAlternate(
                true, analysis(NutritionStatus.SAFE)));
    }

    @Test
    public void eligibility_productOverload_dirtySafeDropped_cleanSafeKept()
            throws Exception {
        NutritionConfig config = config();
        JSONArray foods = fixtureFoods();

        ProductResult dirtySafe = scannedProduct(
                fixtureFood(foods, 990013L), true, config);
        dirtySafe.nutritionAnalysis = analysis(NutritionStatus.SAFE);
        assertFalse(CleanAlternateFinder.isEligibleAlternate(dirtySafe));

        ProductResult cleanSafe = scannedProduct(
                fixtureFood(foods, 990013L), false, config);
        cleanSafe.nutritionAnalysis = analysis(NutritionStatus.SAFE);
        assertTrue(CleanAlternateFinder.isEligibleAlternate(cleanSafe));

        // A clean product whose analysis was never run is not eligible.
        ProductResult cleanUnanalyzed = scannedProduct(
                fixtureFood(foods, 990013L), false, config);
        assertFalse(CleanAlternateFinder.isEligibleAlternate(cleanUnanalyzed));
        assertFalse(CleanAlternateFinder.isEligibleAlternate(null));
    }

    // ------------------------------------------------------------------
    // Block statistics for ingredient-ineligible candidates
    // ------------------------------------------------------------------

    @Test
    public void blockStats_flaggedCategoriesAndCountsAccumulate() {
        AlternateSearchResult result = new AlternateSearchResult();
        CleanAlternateFinder.recordBlockedCandidate(result,
                matchResult("Gluten", "wheat flour", "Emulsifiers", "soy lecithin"));
        CleanAlternateFinder.recordBlockedCandidate(result,
                matchResult("Gluten", "wheat flour"));

        assertEquals(2, result.flaggedCategories.size());
        assertTrue(result.flaggedCategories.contains("Gluten"));
        assertTrue(result.flaggedCategories.contains("Emulsifiers"));
        assertEquals(Integer.valueOf(2), result.filterBlockCounts.get("Gluten"));
        assertEquals(Integer.valueOf(1),
                result.filterBlockCounts.get("Emulsifiers"));
    }

    @Test
    public void blockStats_cleanOrNullMatch_recordsNothing() {
        AlternateSearchResult result = new AlternateSearchResult();
        CleanAlternateFinder.recordBlockedCandidate(result,
                new FlaggedIngredientManager.MatchResult());
        CleanAlternateFinder.recordBlockedCandidate(result, null);
        assertTrue(result.flaggedCategories.isEmpty());
        assertTrue(result.filterBlockCounts.isEmpty());
    }

    // ------------------------------------------------------------------
    // Scanned-product build: profile + fdcId via the single parser path
    // ------------------------------------------------------------------

    @Test
    public void buildScannedProduct_attachesProfileAndFdcIdViaParser()
            throws Exception {
        NutritionConfig config = config();
        JSONObject food = fixtureFood(fixtureFoods(), 990012L);

        ProductResult product = CleanAlternateFinder.buildScannedProduct(
                food, new FlaggedIngredientManager.MatchResult(), config);

        assertEquals(990012L, product.fdcId);
        assertNotNull(product.nutritionProfile);
        assertEquals(990012L, product.nutritionProfile.fdcId);
        assertTrue(product.nutritionProfile.reported("sodium"));
        assertEquals(180.0, product.nutritionProfile.per100.get("sodium"),
                0.0001);
        assertEquals(CHIPS_CATEGORY, product.foodCategory);
        assertEquals("002800099012", product.gtinUpc);
    }

    @Test
    public void buildScannedProduct_nullConfig_emptyProfileStillCarriesFdcId()
            throws Exception {
        JSONObject food = fixtureFood(fixtureFoods(), 990013L);

        ProductResult product = CleanAlternateFinder.buildScannedProduct(
                food, new FlaggedIngredientManager.MatchResult(), null);

        assertEquals(990013L, product.fdcId);
        assertNotNull(product.nutritionProfile);
        assertTrue(product.nutritionProfile.per100.isEmpty());
    }

    // ------------------------------------------------------------------
    // Final cohort: acceptance scenario and explicit empty result
    // ------------------------------------------------------------------

    @Test
    public void finalize_firstCleanConcerning_laterSafeReturned()
            throws Exception {
        NutritionConfig config = config();
        JSONArray foods = fixtureFoods();
        ProductResult primary = scannedProduct(
                fixtureFood(foods, 990001L), true, config);

        // Scan order: every remaining fixture product, fdcId order. The
        // first clean candidates (990002, 990003) are nutrition
        // CONCERNING against the final cohort; the SAFE exemplars
        // 990012 / 990013 come last.
        List<ProductResult> scanned = new ArrayList<>();
        for (long id = 990002L; id <= 990013L; id++) {
            scanned.add(scannedProduct(fixtureFood(foods, id), false, config));
        }

        AlternateSearchResult result = CleanAlternateFinder.finalizeScannedPool(
                primary, scanned, seedPeers(foods, config), CHIPS_CATEGORY,
                config, new AlternateSearchResult());

        // The pool is the full scanned pool, deduped, with profiles.
        assertEquals(12, result.peerPool.size());
        assertEquals(scanned, result.peerPool);

        // The primary's analysis is refreshed against the final cohort:
        // set on the result AND on the primary, same instance.
        assertNotNull(result.primaryAnalysis);
        assertSame(result.primaryAnalysis, primary.nutritionAnalysis);
        assertEquals(NutritionStatus.CONCERNING, result.primaryAnalysis.status);
        assertEquals(CHIPS_CATEGORY, result.primaryAnalysis.cohortCategory);
        assertEquals(12, result.primaryAnalysis.cohortPeerCount);

        // Early clean candidates analyzed CONCERNING are dropped...
        ProductResult firstClean = scanned.get(0);
        assertEquals(990002L, firstClean.fdcId);
        assertNotNull(firstClean.nutritionAnalysis);
        assertEquals(NutritionStatus.CONCERNING,
                firstClean.nutritionAnalysis.status);
        assertFalse(fdcIds(result.alternates).contains(990002L));
        assertFalse(fdcIds(result.alternates).contains(990003L));

        // ...and the later SAFE ones are returned, in scan order.
        assertTrue(fdcIds(result.alternates).contains(990012L));
        assertTrue(fdcIds(result.alternates).contains(990013L));
        assertTrue(fdcIds(result.alternates).indexOf(990012L)
                < fdcIds(result.alternates).indexOf(990013L));

        // Every returned alternate is independently CLEAN ∧ SAFE.
        for (ProductResult alt : result.alternates) {
            assertTrue(alt.flagged == null || alt.flagged.isEmpty());
            assertNotNull(alt.nutritionAnalysis);
            assertEquals(NutritionStatus.SAFE, alt.nutritionAnalysis.status);
        }
    }

    @Test
    public void finalize_noneQualify_explicitEmptyNoSubstitution()
            throws Exception {
        NutritionConfig config = config();
        JSONArray foods = fixtureFoods();
        ProductResult primary = scannedProduct(
                fixtureFood(foods, 990001L), true, config);

        // Only CONCERNING clean candidates were scanned.
        List<ProductResult> scanned = Arrays.asList(
                scannedProduct(fixtureFood(foods, 990002L), false, config),
                scannedProduct(fixtureFood(foods, 990003L), false, config));

        AlternateSearchResult result = CleanAlternateFinder.finalizeScannedPool(
                primary, scanned, seedPeers(foods, config), CHIPS_CATEGORY,
                config, new AlternateSearchResult());

        assertTrue("no eligible alternate => explicit empty, never a "
                + "CONCERNING substitute", result.alternates.isEmpty());
        assertEquals(2, result.peerPool.size());
        assertNotNull(result.primaryAnalysis);
    }

    @Test
    public void finalize_dirtyButSafe_candidateDropped() throws Exception {
        NutritionConfig config = config();
        JSONArray foods = fixtureFoods();
        ProductResult primary = scannedProduct(
                fixtureFood(foods, 990001L), true, config);

        // 990013's nutrition is SAFE against the final cohort, but the
        // candidate is ingredient DIRTY: dropped on the ingredient side.
        ProductResult dirtySafe = scannedProduct(
                fixtureFood(foods, 990013L), true, config);

        AlternateSearchResult result = CleanAlternateFinder.finalizeScannedPool(
                primary, Collections.singletonList(dirtySafe),
                seedPeers(foods, config), CHIPS_CATEGORY, config,
                new AlternateSearchResult());

        assertNotNull(dirtySafe.nutritionAnalysis);
        assertEquals(NutritionStatus.SAFE, dirtySafe.nutritionAnalysis.status);
        assertTrue(result.alternates.isEmpty());
    }

    @Test
    public void finalize_noCohortPeers_candidateUnavailableDropped()
            throws Exception {
        NutritionConfig config = config();
        JSONArray foods = fixtureFoods();
        ProductResult primary = scannedProduct(
                fixtureFood(foods, 990001L), true, config);
        ProductResult clean = scannedProduct(
                fixtureFood(foods, 990013L), false, config);

        // No step-3 peers and the scanned pool alone cannot reach
        // minPeers: the candidate's analysis is UNAVAILABLE.
        AlternateSearchResult result = CleanAlternateFinder.finalizeScannedPool(
                primary, Collections.singletonList(clean),
                Collections.<CohortPeer>emptyList(), CHIPS_CATEGORY, config,
                new AlternateSearchResult());

        assertNotNull(clean.nutritionAnalysis);
        assertEquals(NutritionStatus.UNAVAILABLE,
                clean.nutritionAnalysis.status);
        assertTrue(result.alternates.isEmpty());
        assertEquals(1, result.peerPool.size());
    }

    @Test
    public void finalize_nullConfig_noAlternatesButPoolAndStatsKept()
            throws Exception {
        JSONArray foods = fixtureFoods();
        ProductResult primary = scannedProduct(
                fixtureFood(foods, 990001L), true, null);
        ProductResult clean = scannedProduct(
                fixtureFood(foods, 990013L), false, null);

        AlternateSearchResult result = new AlternateSearchResult();
        CleanAlternateFinder.recordBlockedCandidate(result,
                matchResult("Gluten", "wheat flour"));
        CleanAlternateFinder.finalizeScannedPool(primary,
                Collections.singletonList(clean),
                Collections.<CohortPeer>emptyList(), CHIPS_CATEGORY, null,
                result);

        // Config invalid => nutrition UNAVAILABLE => nothing eligible,
        // but the scanned pool and the block statistics survive.
        assertTrue(result.alternates.isEmpty());
        assertEquals(1, result.peerPool.size());
        assertEquals(Integer.valueOf(1), result.filterBlockCounts.get("Gluten"));
    }
}
