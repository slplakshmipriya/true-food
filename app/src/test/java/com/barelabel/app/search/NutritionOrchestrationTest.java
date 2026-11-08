package com.barelabel.app.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.barelabel.app.model.NutritionProfile;
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

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * NutritionCohortService orchestration: Contract §5, PRD §9.1 / §12 / §13.
 *
 * Two pure seams are pinned here:
 * <ul>
 *   <li>The sourcing decision {@code shouldSource(ingredientClean, status,
 *       categoryIntent)} — all six PRD §9.1 rows, plus the rule that a
 *       category-intent search always sources.</li>
 *   <li>Cohort top-up against a fake {@link
 *       NutritionCohortService.FoodsPageSource}: seeds that already give
 *       every displayed nutrient {@code minPeers} valid peers fetch no
 *       pages; otherwise pages are fetched until the minimum is met or
 *       the {@code maxCategoryPages} budget runs out; a failing page
 *       source degrades to the partial cohort instead of failing the
 *       search. Page peers are parsed with the real {@link
 *       UsdaNutritionParser} and screened for spam brands; dedupe keys
 *       are GTIN/UPC when present, else normalized
 *       brand+description+serving.</li>
 * </ul>
 *
 * The config is the real loader parsing an embedded copy of the §1.2
 * values (same pattern as PeerCohortTest), with the peer knobs
 * (minPeers / maxCategoryPages) shrunk so the top-up scenarios stay
 * small.
 */
public class NutritionOrchestrationTest {

    private static final String CATEGORY = "Snacks";

    // ------------------------------------------------------------------
    // Real config through the real loader; only the peer knobs vary.
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

    private static String configJson(int minPeers, int maxCategoryPages) {
        return "{"
                + "\"version\": 1,"
                + "\"blend\": {\"ingredientWeight\": 0.70, \"nutritionWeight\": 0.30},"
                + "\"peers\": {\"minPeers\": " + minPeers + ", \"preferredPeers\": 20,"
                + " \"maxCategoryPages\": " + maxCategoryPages
                + ", \"roundBelowPreferredToNearest\": 5},"
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
    }

    private static NutritionConfig config(int minPeers, int maxCategoryPages)
            throws Exception {
        return NutritionConfigLoader.parse(configJson(minPeers, maxCategoryPages));
    }

    // ------------------------------------------------------------------
    // USDA food JSON builders (search shape: {nutrientId, value, unitName})
    // ------------------------------------------------------------------

    /** One foodNutrients entry in the USDA search shape. */
    private static JSONObject nutrientEntry(int usdaId, double value, String unit)
            throws Exception {
        JSONObject entry = new JSONObject();
        entry.put("nutrientId", usdaId);
        entry.put("value", value);
        entry.put("unitName", unit);
        return entry;
    }

    /**
     * A branded food reporting every configured nutrient with plausible,
     * flag-free values, except sodium which varies per peer (so the MAD
     * fence has a spread to work with). {@code skipUsdaId} omits one
     * nutrient (0 = omit none).
     */
    private static JSONObject fullFood(long fdcId, String description,
                                       String brandName, String brandOwner,
                                       String gtin, double sodiumMg,
                                       int skipUsdaId) throws Exception {
        JSONObject food = new JSONObject();
        food.put("fdcId", fdcId);
        food.put("description", description);
        food.put("brandName", brandName);
        food.put("brandOwner", brandOwner);
        food.put("foodCategory", CATEGORY);
        if (gtin != null) {
            food.put("gtinUpc", gtin);
        }
        food.put("servingSize", 28.0);
        food.put("servingSizeUnit", "g");
        food.put("ingredients", "POTATOES, OIL, SALT.");
        JSONArray nutrients = new JSONArray();
        Object[][] values = {
                {1008, 500.0, "KCAL"},
                {1003, 6.0, "G"},
                {1004, 30.0, "G"},
                {1005, 50.0, "G"},
                {2000, 2.0, "G"},
                {1235, 1.0, "G"},
                {1079, 4.0, "G"},
                {1093, sodiumMg, "MG"},
                {1258, 5.0, "G"},
                {1257, 0.0, "G"},
                {1253, 5.0, "MG"},
        };
        for (Object[] row : values) {
            int id = (Integer) row[0];
            if (id == skipUsdaId) {
                continue;
            }
            nutrients.put(nutrientEntry(id, (Double) row[1], (String) row[2]));
        }
        food.put("foodNutrients", nutrients);
        return food;
    }

    private static JSONObject fullFood(long fdcId, String description,
                                       double sodiumMg) throws Exception {
        return fullFood(fdcId, description, "ACME", "Acme Foods, Inc.",
                "gtin-" + fdcId, sodiumMg, 0);
    }

    /** A primary product whose profile is parsed from the given food JSON. */
    private static ProductResult primaryFor(JSONObject food, NutritionConfig config) {
        ProductResult primary = new ProductResult(true,
                food.optString("description", ""), "ACME",
                food.optString("ingredients", ""), null);
        primary.nutritionProfile = UsdaNutritionParser.parse(food, config);
        primary.foodCategory = food.optString("foodCategory", "");
        return primary;
    }

    /** A mid-range primary: no nutrient crosses any flag rule vs full peers. */
    private static ProductResult midPrimary(NutritionConfig config) throws Exception {
        JSONObject food = fullFood(900L, "Primary Snack", "ACME",
                "Acme Foods, Inc.", "gtin-primary", 450.0, 0);
        // Fiber above every peer's 4.0 so the low rule cannot fire.
        JSONArray nutrients = food.getJSONArray("foodNutrients");
        for (int i = 0; i < nutrients.length(); i++) {
            JSONObject entry = nutrients.getJSONObject(i);
            if (entry.getInt("nutrientId") == 1079) {
                entry.put("value", 5.0);
            }
        }
        return primaryFor(food, config);
    }

    // ------------------------------------------------------------------
    // Fake page source
    // ------------------------------------------------------------------

    private static final class FakePageSource
            implements NutritionCohortService.FoodsPageSource {
        final List<List<JSONObject>> pages = new ArrayList<>();
        final List<Integer> requestedPages = new ArrayList<>();
        boolean throwOnFetch;

        @Override
        public List<JSONObject> fetchPage(String category, int page) throws Exception {
            requestedPages.add(page);
            if (throwOnFetch) {
                throw new Exception("simulated page failure");
            }
            if (page < 1 || page > pages.size()) {
                return Collections.emptyList();
            }
            return pages.get(page - 1);
        }
    }

    private static List<JSONObject> pageOf(JSONObject... foods) {
        return new ArrayList<>(Arrays.asList(foods));
    }

    // ------------------------------------------------------------------
    // Sourcing decision: the six PRD §9.1 rows
    // ------------------------------------------------------------------

    @Test
    public void shouldSource_cleanSafe_noSource() {
        assertFalse(NutritionCohortService.shouldSource(
                true, NutritionStatus.SAFE, false));
    }

    @Test
    public void shouldSource_cleanConcerning_sources() {
        assertTrue(NutritionCohortService.shouldSource(
                true, NutritionStatus.CONCERNING, false));
    }

    @Test
    public void shouldSource_dirtySafe_sources() {
        assertTrue(NutritionCohortService.shouldSource(
                false, NutritionStatus.SAFE, false));
    }

    @Test
    public void shouldSource_dirtyConcerning_sources() {
        assertTrue(NutritionCohortService.shouldSource(
                false, NutritionStatus.CONCERNING, false));
    }

    @Test
    public void shouldSource_dirtyUnavailable_sources() {
        assertTrue(NutritionCohortService.shouldSource(
                false, NutritionStatus.UNAVAILABLE, false));
    }

    @Test
    public void shouldSource_cleanUnavailable_noSource() {
        assertFalse(NutritionCohortService.shouldSource(
                true, NutritionStatus.UNAVAILABLE, false));
    }

    @Test
    public void shouldSource_categoryIntent_alwaysSources() {
        for (boolean clean : new boolean[]{true, false}) {
            for (NutritionStatus status : NutritionStatus.values()) {
                assertTrue("categoryIntent must source (clean=" + clean
                                + ", status=" + status + ")",
                        NutritionCohortService.shouldSource(clean, status, true));
            }
            assertTrue(NutritionCohortService.shouldSource(clean, null, true));
        }
    }

    @Test
    public void shouldSource_nullStatus_treatedAsUnavailable() {
        // No analysis yet (e.g. a null nutritionAnalysis) reads as
        // UNAVAILABLE: dirty still sources, clean does not.
        assertTrue(NutritionCohortService.shouldSource(false, null, false));
        assertFalse(NutritionCohortService.shouldSource(true, null, false));
    }

    // ------------------------------------------------------------------
    // Seed peers from search candidates
    // ------------------------------------------------------------------

    @Test
    public void seedPeers_parsesProfiles_skipsSpam_dedupeKeys() throws Exception {
        NutritionConfig config = config(3, 2);
        JSONObject withGtin = fullFood(1L, "Chips One", 300.0);
        JSONObject spam = fullFood(2L, "Chips Spam", "JUNK",
                "Multicom Publishing Incorporated", "gtin-spam", 100.0, 0);
        JSONObject noGtin = fullFood(3L, "Chips Two", "ACME",
                "Acme Foods, Inc.", null, 400.0, 0);

        List<CohortPeer> seeds = NutritionCohortService.seedPeersFromCandidates(
                Arrays.asList(withGtin, spam, noGtin), config);

        assertEquals(2, seeds.size());
        // GTIN/UPC wins as the dedupe key when present.
        assertEquals("gtin-1", seeds.get(0).dedupeKey);
        assertEquals(CATEGORY, seeds.get(0).category);
        assertTrue(seeds.get(0).profile.reported("sodium"));
        // No GTIN: normalized brand+description+serving key.
        assertTrue(seeds.get(1).dedupeKey.contains("acme"));
        assertTrue(seeds.get(1).dedupeKey.contains("chips two"));
        assertTrue(seeds.get(1).profile.reported("sodium"));
    }

    @Test
    public void seedPeers_householdServingCasingAndPunctuation_dedupeToOnePeer()
            throws Exception {
        // Regression (NUT-05 review, Minor 1): with no GTIN and no
        // numeric servingSize, the dedupe key falls back to
        // householdServingFullText — that component must be normalized
        // like brand and description, or the same product written with
        // different household-text casing/punctuation would not dedupe.
        NutritionConfig config = config(1, 2);
        JSONObject first = fullFood(11L, "Household Chips", "ACME",
                "Acme Foods, Inc.", null, 300.0, 0);
        first.remove("servingSize");
        first.put("householdServingFullText", "1 Slice");
        JSONObject second = fullFood(12L, "Household Chips", "ACME",
                "Acme Foods, Inc.", null, 400.0, 0);
        second.remove("servingSize");
        second.put("householdServingFullText", "1 SLICE!");

        List<CohortPeer> seeds = NutritionCohortService.seedPeersFromCandidates(
                Arrays.asList(first, second), config);

        assertEquals(2, seeds.size());
        assertEquals(seeds.get(0).dedupeKey, seeds.get(1).dedupeKey);

        FakePageSource source = new FakePageSource();
        NutritionCohortService service = new NutritionCohortService(config, source);
        NutritionAnalysis analysis = service.analyzePrimary(
                midPrimary(config), seeds, CATEGORY);

        // The two records are one product: exactly one peer survives.
        assertEquals(1, analysis.cohortPeerCount);
        assertTrue(source.requestedPages.isEmpty());
    }

    @Test
    public void seedPeers_nullConfigOrFoods_empty() throws Exception {
        assertTrue(NutritionCohortService.seedPeersFromCandidates(
                null, config(3, 2)).isEmpty());
        assertTrue(NutritionCohortService.seedPeersFromCandidates(
                Arrays.asList(fullFood(1L, "Chips One", 300.0)), null).isEmpty());
    }

    // ------------------------------------------------------------------
    // Top-up stop conditions
    // ------------------------------------------------------------------

    @Test
    public void topUp_seedsSuffice_noPagesFetched() throws Exception {
        NutritionConfig config = config(3, 2);
        ProductResult primary = midPrimary(config);
        List<CohortPeer> seeds = NutritionCohortService.seedPeersFromCandidates(
                Arrays.asList(
                        fullFood(1L, "Peer One", 300.0),
                        fullFood(2L, "Peer Two", 400.0),
                        fullFood(3L, "Peer Three", 500.0)), config);
        FakePageSource source = new FakePageSource();
        source.pages.add(pageOf(fullFood(4L, "Peer Four", 350.0)));

        NutritionCohortService service = new NutritionCohortService(config, source);
        NutritionAnalysis analysis = service.analyzePrimary(primary, seeds, CATEGORY);

        assertTrue("seeds already meet minPeers for every displayed nutrient",
                source.requestedPages.isEmpty());
        assertSame(analysis, primary.nutritionAnalysis);
        assertEquals(3, analysis.comparisons.get("sodium").peerCount);
        assertEquals(3, analysis.comparisons.get("fiber").peerCount);
        assertEquals(3, analysis.cohortPeerCount);
        assertEquals(CATEGORY, analysis.cohortCategory);
        // Full coverage, adequate cohort, no flags: SAFE end to end.
        assertEquals(NutritionStatus.SAFE, analysis.status);
        assertNotNull(analysis.nutritionScore);
    }

    @Test
    public void topUp_fetchesUntilMinPeersMet() throws Exception {
        NutritionConfig config = config(3, 3);
        ProductResult primary = midPrimary(config);
        List<CohortPeer> seeds = NutritionCohortService.seedPeersFromCandidates(
                Collections.singletonList(fullFood(1L, "Peer One", 300.0)), config);
        FakePageSource source = new FakePageSource();
        source.pages.add(pageOf(fullFood(2L, "Peer Two", 400.0)));
        source.pages.add(pageOf(fullFood(3L, "Peer Three", 500.0)));
        source.pages.add(pageOf(fullFood(4L, "Peer Four", 350.0)));

        NutritionCohortService service = new NutritionCohortService(config, source);
        NutritionAnalysis analysis = service.analyzePrimary(primary, seeds, CATEGORY);

        // Two pages close the 1 -> 3 gap; the third page is never fetched.
        assertEquals(Arrays.asList(1, 2), source.requestedPages);
        assertEquals(3, analysis.comparisons.get("sodium").peerCount);
        assertEquals(NutritionStatus.SAFE, analysis.status);
    }

    @Test
    public void topUp_stopsAtPageBudget() throws Exception {
        NutritionConfig config = config(3, 2);
        ProductResult primary = midPrimary(config);
        FakePageSource source = new FakePageSource();
        source.pages.add(pageOf(fullFood(1L, "Peer One", 300.0)));
        source.pages.add(pageOf(fullFood(2L, "Peer Two", 400.0)));
        source.pages.add(pageOf(fullFood(3L, "Peer Three", 500.0)));

        NutritionCohortService service = new NutritionCohortService(config, source);
        NutritionAnalysis analysis = service.analyzePrimary(
                primary, Collections.<CohortPeer>emptyList(), CATEGORY);

        assertEquals(Arrays.asList(1, 2), source.requestedPages);
        assertEquals(2, analysis.comparisons.get("sodium").peerCount);
        // 2 peers < minPeers 3: percentiles suppressed, status UNAVAILABLE,
        // but the analysis exists and the search did not fail.
        assertEquals(NutritionStatus.UNAVAILABLE, analysis.status);
        assertSame(analysis, primary.nutritionAnalysis);
    }

    @Test
    public void topUp_throwingSource_degradesToPartialCohort() throws Exception {
        NutritionConfig config = config(3, 2);
        ProductResult primary = midPrimary(config);
        List<CohortPeer> seeds = NutritionCohortService.seedPeersFromCandidates(
                Collections.singletonList(fullFood(1L, "Peer One", 300.0)), config);
        FakePageSource source = new FakePageSource();
        source.throwOnFetch = true;

        NutritionCohortService service = new NutritionCohortService(config, source);
        // Must not throw: a failed top-up page is a partial cohort,
        // never a failed search (Contract §5 error cases).
        NutritionAnalysis analysis = service.analyzePrimary(primary, seeds, CATEGORY);

        assertEquals(Collections.singletonList(1), source.requestedPages);
        assertEquals(1, analysis.comparisons.get("sodium").peerCount);
        assertEquals(NutritionStatus.UNAVAILABLE, analysis.status);
        assertSame(analysis, primary.nutritionAnalysis);
    }

    @Test
    public void topUp_anyDisplayedNutrientShort_keepsFetching() throws Exception {
        // Seeds cover every displayed nutrient except fiber; the top-up
        // condition is per-nutrient, so one short nutrient still fetches.
        NutritionConfig config = config(2, 2);
        ProductResult primary = midPrimary(config);
        List<CohortPeer> seeds = NutritionCohortService.seedPeersFromCandidates(
                Arrays.asList(
                        fullFood(1L, "Peer One", "ACME", "Acme Foods, Inc.",
                                "gtin-1", 300.0, 1079),
                        fullFood(2L, "Peer Two", "ACME", "Acme Foods, Inc.",
                                "gtin-2", 400.0, 1079)), config);
        FakePageSource source = new FakePageSource();
        source.pages.add(pageOf(
                fullFood(3L, "Peer Three", 350.0),
                fullFood(4L, "Peer Four", 450.0)));

        NutritionCohortService service = new NutritionCohortService(config, source);
        NutritionAnalysis analysis = service.analyzePrimary(primary, seeds, CATEGORY);

        assertEquals(Collections.singletonList(1), source.requestedPages);
        assertEquals(2, analysis.comparisons.get("fiber").peerCount);
        assertEquals(4, analysis.comparisons.get("sodium").peerCount);
    }

    @Test
    public void topUp_validCountShrinkage_fenceDroppedPeers_triggerFetch()
            throws Exception {
        // Gap test (NUT-11): the top-up condition reads VALID peer
        // counts (post plausible-range + MAD fence), not raw seed
        // counts. Five seeds report sodium, but the fence drops the
        // 4900 mg outlier (median 340, MAD 20, fence ~[236, 444]), so
        // sodium has only 4 valid peers < minPeers 5 and the top-up
        // must fire even though five seed products are present.
        NutritionConfig config = config(5, 2);
        ProductResult primary = midPrimary(config);
        List<CohortPeer> seeds = NutritionCohortService.seedPeersFromCandidates(
                Arrays.asList(
                        fullFood(1L, "Peer One", 300.0),
                        fullFood(2L, "Peer Two", 320.0),
                        fullFood(3L, "Peer Three", 340.0),
                        fullFood(4L, "Peer Four", 360.0),
                        fullFood(5L, "Peer Five", 4900.0)), config);
        FakePageSource source = new FakePageSource();
        source.pages.add(pageOf(fullFood(6L, "Peer Six", 350.0)));

        NutritionCohortService service = new NutritionCohortService(config, source);
        NutritionAnalysis analysis = service.analyzePrimary(primary, seeds, CATEGORY);

        // One page closes the valid-count gap (4900 stays fenced out,
        // the page peer counts): no second page is fetched.
        assertEquals(Collections.singletonList(1), source.requestedPages);
        assertEquals(5, analysis.comparisons.get("sodium").peerCount);
        // All six products survive as cohort members; only the sodium
        // distribution lost the outlier.
        assertEquals(6, analysis.cohortPeerCount);
    }

    @Test
    public void topUp_emptyPageMidLoop_stopsFetching() throws Exception {
        // Gap test (NUT-11): an empty page ends the top-up even when
        // the page budget is not exhausted and minPeers is still
        // unmet — the loop must break, not skip ahead to later pages.
        NutritionConfig config = config(3, 3);
        ProductResult primary = midPrimary(config);
        FakePageSource source = new FakePageSource();
        source.pages.add(pageOf(fullFood(1L, "Peer One", 300.0)));
        source.pages.add(pageOf());
        source.pages.add(pageOf(
                fullFood(2L, "Peer Two", 400.0),
                fullFood(3L, "Peer Three", 500.0)));

        NutritionCohortService service = new NutritionCohortService(config, source);
        NutritionAnalysis analysis = service.analyzePrimary(
                primary, Collections.<CohortPeer>emptyList(), CATEGORY);

        // Page 3 exists in the source but is never requested.
        assertEquals(Arrays.asList(1, 2), source.requestedPages);
        assertEquals(1, analysis.comparisons.get("sodium").peerCount);
        assertEquals(NutritionStatus.UNAVAILABLE, analysis.status);
    }

    @Test
    public void topUp_pagePeers_parsedScreenedAndDeduped() throws Exception {
        NutritionConfig config = config(2, 1);
        ProductResult primary = midPrimary(config);
        JSONObject peerA = fullFood(1L, "Peer A", 300.0);
        JSONObject peerB = fullFood(2L, "Peer B", 400.0);
        JSONObject spam = fullFood(3L, "Peer Spam", "JUNK",
                "Multicom Publishing Incorporated", "gtin-spam", 100.0, 0);
        // Same GTIN as peer A under a different FDC ID: deduped by key.
        JSONObject dupOfA = fullFood(4L, "Peer A Clone", "ACME",
                "Acme Foods, Inc.", "gtin-1", 999.0, 0);
        FakePageSource source = new FakePageSource();
        source.pages.add(pageOf(peerA, peerB, spam, dupOfA));

        NutritionCohortService service = new NutritionCohortService(config, source);
        NutritionAnalysis analysis = service.analyzePrimary(
                primary, Collections.<CohortPeer>emptyList(), CATEGORY);

        // Spam screened out by the service, the GTIN twin deduped by the
        // cohort: exactly peers A and B remain.
        assertEquals(2, analysis.cohortPeerCount);
        assertEquals(2, analysis.comparisons.get("sodium").peerCount);
        assertEquals(350.0, analysis.comparisons.get("sodium").peerMedian, 0.0001);
    }

    // ------------------------------------------------------------------
    // Degenerate inputs: UNAVAILABLE, never a crash (PRD §13)
    // ------------------------------------------------------------------

    @Test
    public void analyzePrimary_nullConfig_unavailableAndNoFetch() throws Exception {
        ProductResult primary = midPrimary(config(3, 2));
        FakePageSource source = new FakePageSource();

        NutritionCohortService service = new NutritionCohortService(null, source);
        NutritionAnalysis analysis = service.analyzePrimary(
                primary, Collections.<CohortPeer>emptyList(), CATEGORY);

        assertEquals(NutritionStatus.UNAVAILABLE, analysis.status);
        assertSame(analysis, primary.nutritionAnalysis);
        assertTrue(source.requestedPages.isEmpty());
    }

    @Test
    public void analyzePrimary_notFoundPrimary_noPagesFetchedAndUnavailable()
            throws Exception {
        // Regression (NUT-05 review, Minor 3): a not-found primary can
        // never produce an analysis, so the top-up must be skipped
        // entirely — no USDA pages may be requested for it.
        NutritionConfig config = config(3, 2);
        ProductResult primary = new ProductResult(false, "Missing Snack",
                "ACME", "", null);
        primary.nutritionProfile = null;
        FakePageSource source = new FakePageSource();
        source.pages.add(pageOf(fullFood(1L, "Peer One", 300.0)));

        NutritionCohortService service = new NutritionCohortService(config, source);
        NutritionAnalysis analysis = service.analyzePrimary(
                primary, Collections.<CohortPeer>emptyList(), CATEGORY);

        assertTrue(source.requestedPages.isEmpty());
        assertEquals(NutritionStatus.UNAVAILABLE, analysis.status);
        assertSame(analysis, primary.nutritionAnalysis);
    }

    @Test
    public void analyzePrimary_emptyProfilePrimary_noPagesFetchedAndUnavailable()
            throws Exception {
        // Regression (NUT-05 review, Minor 3): a found primary whose
        // profile reports none of the configured nutrients is likewise
        // provably UNAVAILABLE regardless of cohort — no top-up pages.
        NutritionConfig config = config(3, 2);
        ProductResult primary = new ProductResult(true, "Blank Snack",
                "ACME", "INGREDIENTS", null);
        primary.nutritionProfile = NutritionProfile.empty(42L);
        FakePageSource source = new FakePageSource();
        source.pages.add(pageOf(fullFood(1L, "Peer One", 300.0)));

        NutritionCohortService service = new NutritionCohortService(config, source);
        NutritionAnalysis analysis = service.analyzePrimary(
                primary, Collections.<CohortPeer>emptyList(), CATEGORY);

        assertTrue(source.requestedPages.isEmpty());
        assertEquals(NutritionStatus.UNAVAILABLE, analysis.status);
        assertSame(analysis, primary.nutritionAnalysis);
    }

    @Test
    public void analyzePrimary_analysisFailure_degradesToUnavailable()
            throws Exception {
        // Gap test (NUT-11): Contract §5 — "any other analysis failure
        // degrades that product's analysis to UNAVAILABLE". The
        // production seams are defensive by construction (the parser,
        // the cohort builder, and the analyzer all refuse to throw),
        // so a seed list whose first iteration throws stands in for an
        // analysis-stage failure and pins the service's catch: the
        // result is a stored UNAVAILABLE analysis, the caller's seeds
        // are handed back in the result, and no page is fetched. (The
        // list iterates normally afterwards so the fallback's own
        // defensive copy of the seeds can succeed.)
        NutritionConfig config = config(3, 2);
        ProductResult primary = midPrimary(config);
        final List<CohortPeer> backing = NutritionCohortService
                .seedPeersFromCandidates(Collections.singletonList(
                        fullFood(1L, "Peer One", 300.0)), config);
        List<CohortPeer> failOnceSeeds = new AbstractList<CohortPeer>() {
            private boolean armed = true;

            @Override
            public CohortPeer get(int index) {
                return backing.get(index);
            }

            @Override
            public int size() {
                return backing.size();
            }

            @Override
            public Iterator<CohortPeer> iterator() {
                if (armed) {
                    armed = false;
                    throw new IllegalStateException(
                            "simulated analysis failure");
                }
                return backing.iterator();
            }
        };
        FakePageSource source = new FakePageSource();

        NutritionCohortService service = new NutritionCohortService(config, source);
        NutritionCohortService.CohortResult result =
                service.analyzePrimaryWithCohort(primary, failOnceSeeds, CATEGORY);

        assertEquals(NutritionStatus.UNAVAILABLE, result.analysis.status);
        assertSame(result.analysis, primary.nutritionAnalysis);
        assertEquals(backing, result.peers);
        assertTrue(source.requestedPages.isEmpty());
    }

    @Test
    public void analyzePrimary_primaryWithoutProfile_unavailableNoCrash()
            throws Exception {
        NutritionConfig config = config(3, 2);
        ProductResult primary = new ProductResult(true, "Mystery Snack",
                "ACME", "INGREDIENTS", null);
        primary.nutritionProfile = null;
        FakePageSource source = new FakePageSource();

        NutritionCohortService service = new NutritionCohortService(config, source);
        NutritionAnalysis analysis = service.analyzePrimary(
                primary, Collections.<CohortPeer>emptyList(), CATEGORY);

        assertEquals(NutritionStatus.UNAVAILABLE, analysis.status);
        assertSame(analysis, primary.nutritionAnalysis);
    }
}
