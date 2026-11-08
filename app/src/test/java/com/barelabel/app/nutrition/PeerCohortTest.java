package com.barelabel.app.nutrition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.barelabel.app.model.NutritionProfile;

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
import java.util.Map;

/**
 * PeerCohort / PeerDistribution: Contract §4, PRD §6.
 *
 * Cohort rules under test: candidate excluded from its own cohort;
 * deduplication by FDC ID, then GTIN/UPC key, then normalized
 * brand+description+serving key (the caller precomputes the key on
 * {@link CohortPeer}); category and per-100 basis must match the
 * candidate; values outside the configured plausible range are dropped;
 * a median/MAD fence (median ± 3.5 × 1.4826 × MAD) drops the rest, except
 * that MAD == 0 keeps every plausible value. Percentiles use the PRD
 * §6.5 half-credit tie formula.
 *
 * Fixture expectations are hand-computed in the docstring of
 * tools/verify_nutrition.py and reproduced by a Python mirror of the
 * cohort math over usda_chips_nutrition.json (see the NUT-03 report).
 * One documented-value nuance: for candidate 990007 the docstring's
 * fiber percentile (75.0) is the pre-fence figure; the MAD fence drops
 * the 7.0 g peer first, so the cohort assertion below uses the
 * fence-filtered value (see madFence_chipsFiber_...).
 */
public class PeerCohortTest {

    private static final double EPS = 0.0001;
    private static final String CHIPS_CATEGORY = "Chips, Pretzels & Snacks";
    private static final String BREAD_CATEGORY = "Breads & Buns";

    // ------------------------------------------------------------------
    // Real config through the real loader (same embedded §1.2 values as
    // UsdaNutritionParserTest — no mocked definitions).
    // ------------------------------------------------------------------

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

    /** Builds one nutrient object literal for {@link #CONFIG_JSON}. */
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

    private static NutritionConfig config() throws Exception {
        return NutritionConfigLoader.parse(CONFIG_JSON);
    }

    // ------------------------------------------------------------------
    // Synthetic profile / peer helpers
    // ------------------------------------------------------------------

    /** Profile with the given per-100 values: profile(id, basis, "sodium", 1.0, ...). */
    private static NutritionProfile profile(long fdcId, String basis,
                                            Object... keyValues) {
        Map<String, Double> per100 = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            per100.put((String) keyValues[i], (Double) keyValues[i + 1]);
        }
        return new NutritionProfile(fdcId, basis, null, "", "", per100);
    }

    private static CohortPeer peer(NutritionProfile profile, String dedupeKey,
                                   String category) {
        return new CohortPeer(profile, dedupeKey, category);
    }

    /** Candidate in category "Snacks", basis g, fdcId 999. */
    private static NutritionProfile snackCandidate() {
        return profile(999L, "g", "sodium", 500.0);
    }

    // ------------------------------------------------------------------
    // Fixture helpers (pattern follows UsdaNutritionParserTest)
    // ------------------------------------------------------------------

    private static JSONArray fixtureFoods(String resource) throws Exception {
        InputStream is = PeerCohortTest.class.getResourceAsStream(resource);
        assertNotNull("fixture on test classpath: " + resource, is);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        is.close();
        String body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        return new JSONObject(body).getJSONArray("foods");
    }

    /**
     * Parses every fixture food through the single parser path and wraps
     * it as a CohortPeer: dedupe key is the GTIN/UPC when present, else a
     * normalized brand+description+serving key; category is the food's
     * USDA foodCategory.
     */
    private static List<CohortPeer> fixturePool(String resource)
            throws Exception {
        NutritionConfig config = config();
        JSONArray foods = fixtureFoods(resource);
        List<CohortPeer> pool = new ArrayList<>();
        for (int i = 0; i < foods.length(); i++) {
            JSONObject food = foods.getJSONObject(i);
            NutritionProfile profile = UsdaNutritionParser.parse(food, config);
            String gtin = food.optString("gtinUpc", "");
            String key;
            if (!gtin.isEmpty()) {
                key = "gtin:" + gtin;
            } else {
                key = "name:" + food.optString("brandName", "").trim().toLowerCase()
                        + "|" + food.optString("description", "").trim().toLowerCase()
                        + "|" + food.optDouble("servingSize", 0);
            }
            pool.add(new CohortPeer(profile, key,
                    food.optString("foodCategory", "")));
        }
        return pool;
    }

    private static NutritionProfile fixtureCandidate(String resource, long fdcId)
            throws Exception {
        JSONArray foods = fixtureFoods(resource);
        for (int i = 0; i < foods.length(); i++) {
            JSONObject food = foods.getJSONObject(i);
            if (food.optLong("fdcId", 0) == fdcId) {
                return UsdaNutritionParser.parse(food, config());
            }
        }
        fail("fixture has no food with fdcId " + fdcId);
        return null;
    }

    // ------------------------------------------------------------------
    // Deduplication (PRD §6.1: FDC ID -> GTIN/UPC -> brand+desc+serving)
    // ------------------------------------------------------------------

    @Test
    public void dedupe_byFdcId_firstWins() throws Exception {
        List<CohortPeer> pool = Arrays.asList(
                peer(profile(111L, "g", "sodium", 300.0), "gtin:aaa", "Snacks"),
                peer(profile(111L, "g", "sodium", 999.0), "gtin:bbb", "Snacks"),
                peer(profile(112L, "g", "sodium", 400.0), "gtin:ccc", "Snacks"));
        PeerCohort cohort = PeerCohort.build(
                snackCandidate(), "Snacks", pool, config());
        PeerDistribution sodium = cohort.byNutrient.get("sodium");
        assertEquals(Arrays.asList(300.0, 400.0), sodium.values);
        assertEquals(2, cohort.peerCount);
    }

    @Test
    public void dedupe_byGtinKey_differentFdcIds_firstWins() throws Exception {
        // Same GTIN/UPC under two FDC IDs: the GTIN rule drops the second.
        List<CohortPeer> pool = Arrays.asList(
                peer(profile(201L, "g", "sodium", 300.0), "gtin:0123", "Snacks"),
                peer(profile(202L, "g", "sodium", 999.0), "gtin:0123", "Snacks"),
                peer(profile(203L, "g", "sodium", 400.0), "gtin:0456", "Snacks"));
        PeerCohort cohort = PeerCohort.build(
                snackCandidate(), "Snacks", pool, config());
        assertEquals(Arrays.asList(300.0, 400.0),
                cohort.byNutrient.get("sodium").values);
        assertEquals(2, cohort.peerCount);
    }

    @Test
    public void dedupe_byNormalizedNameKey_unknownFdcIds_firstWins()
            throws Exception {
        // No FDC ID and no GTIN: the normalized brand+description+serving
        // key the caller precomputed is the dedupe key.
        String nameKey = "name:acme|potato chips|28.0";
        List<CohortPeer> pool = Arrays.asList(
                peer(profile(0L, "g", "sodium", 300.0), nameKey, "Snacks"),
                peer(profile(0L, "g", "sodium", 999.0), nameKey, "Snacks"),
                peer(profile(0L, "g", "sodium", 400.0),
                        "name:acme|tortilla chips|28.0", "Snacks"));
        PeerCohort cohort = PeerCohort.build(
                snackCandidate(), "Snacks", pool, config());
        assertEquals(Arrays.asList(300.0, 400.0),
                cohort.byNutrient.get("sodium").values);
        assertEquals(2, cohort.peerCount);
    }

    @Test
    public void dedupe_fdcIdZeroPeersWithDistinctKeys_allKept()
            throws Exception {
        // fdcId 0 means "unknown": it must never collapse distinct peers.
        List<CohortPeer> pool = Arrays.asList(
                peer(profile(0L, "g", "sodium", 300.0), "gtin:1", "Snacks"),
                peer(profile(0L, "g", "sodium", 400.0), "gtin:2", "Snacks"),
                peer(profile(0L, "g", "sodium", 500.0), "gtin:3", "Snacks"));
        PeerCohort cohort = PeerCohort.build(
                snackCandidate(), "Snacks", pool, config());
        assertEquals(3, cohort.peerCount);
        assertEquals(3, cohort.byNutrient.get("sodium").count());
    }

    // ------------------------------------------------------------------
    // Category and basis filtering (PRD §6.1, §6.3)
    // ------------------------------------------------------------------

    @Test
    public void categoryMismatch_excluded() throws Exception {
        List<CohortPeer> pool = Arrays.asList(
                peer(profile(1L, "g", "sodium", 300.0), "gtin:1", "Snacks"),
                peer(profile(2L, "g", "sodium", 999.0), "gtin:2", "Cookies"),
                peer(profile(3L, "g", "sodium", 888.0), "gtin:3", ""));
        PeerCohort cohort = PeerCohort.build(
                snackCandidate(), "Snacks", pool, config());
        assertEquals(Collections.singletonList(300.0),
                cohort.byNutrient.get("sodium").values);
        assertEquals(1, cohort.peerCount);
    }

    @Test
    public void categoryMatch_caseInsensitiveAndTrimmed() throws Exception {
        List<CohortPeer> pool = Arrays.asList(
                peer(profile(1L, "g", "sodium", 300.0), "gtin:1", "  snacks "),
                peer(profile(2L, "g", "sodium", 400.0), "gtin:2", "SNACKS"));
        PeerCohort cohort = PeerCohort.build(
                snackCandidate(), " Snacks ", pool, config());
        assertEquals(2, cohort.peerCount);
        // The cohort records the category actually used, as supplied.
        assertEquals(" Snacks ", cohort.category);
    }

    @Test
    public void basisMismatch_mlCandidate_excludesGramPeers() throws Exception {
        NutritionProfile candidate = profile(999L, "ml", "sodium", 500.0);
        List<CohortPeer> pool = Arrays.asList(
                peer(profile(1L, "g", "sodium", 300.0), "gtin:1", "Drinks"),
                peer(profile(2L, "ml", "sodium", 400.0), "gtin:2", "Drinks"));
        PeerCohort cohort = PeerCohort.build(
                candidate, "Drinks", pool, config());
        assertEquals("ml", cohort.basis);
        assertEquals(Collections.singletonList(400.0),
                cohort.byNutrient.get("sodium").values);
        assertEquals(1, cohort.peerCount);
    }

    @Test
    public void basisMismatch_gramCandidate_excludesMlPeers() throws Exception {
        List<CohortPeer> pool = Arrays.asList(
                peer(profile(1L, "ml", "sodium", 300.0), "gtin:1", "Snacks"),
                peer(profile(2L, "g", "sodium", 400.0), "gtin:2", "Snacks"));
        PeerCohort cohort = PeerCohort.build(
                snackCandidate(), "Snacks", pool, config());
        assertEquals("g", cohort.basis);
        assertEquals(Collections.singletonList(400.0),
                cohort.byNutrient.get("sodium").values);
    }

    // ------------------------------------------------------------------
    // Candidate exclusion (PRD §6.1: never in its own denominator)
    // ------------------------------------------------------------------

    @Test
    public void candidateExcluded_sameFdcIdInPool() throws Exception {
        NutritionProfile candidate = profile(42L, "g", "sodium", 700.0);
        List<CohortPeer> pool = Arrays.asList(
                // The candidate itself, re-keyed: fdcId match excludes it.
                peer(profile(42L, "g", "sodium", 700.0), "gtin:other", "Snacks"),
                peer(profile(43L, "g", "sodium", 300.0), "gtin:1", "Snacks"));
        PeerCohort cohort = PeerCohort.build(
                candidate, "Snacks", pool, config());
        assertEquals(Collections.singletonList(300.0),
                cohort.byNutrient.get("sodium").values);
        assertEquals(1, cohort.peerCount);
    }

    @Test
    public void candidateExcluded_sameInstanceWhenFdcIdUnknown()
            throws Exception {
        NutritionProfile candidate = profile(0L, "g", "sodium", 700.0);
        List<CohortPeer> pool = Arrays.asList(
                peer(candidate, "gtin:self", "Snacks"),
                peer(profile(0L, "g", "sodium", 300.0), "gtin:1", "Snacks"));
        PeerCohort cohort = PeerCohort.build(
                candidate, "Snacks", pool, config());
        assertEquals(Collections.singletonList(300.0),
                cohort.byNutrient.get("sodium").values);
    }

    // ------------------------------------------------------------------
    // Plausible range (Contract §1.2 ranges; PRD §6.4 step 1)
    // ------------------------------------------------------------------

    @Test
    public void plausibleRange_syntheticOutliersDropped() throws Exception {
        List<CohortPeer> pool = Arrays.asList(
                peer(profile(1L, "g", "protein", 270.0, "sodium", 15300.0),
                        "gtin:1", "Snacks"),
                peer(profile(2L, "g", "protein", -5.0, "sodium", 300.0),
                        "gtin:2", "Snacks"),
                peer(profile(3L, "g", "protein", 10.0, "sodium", 400.0),
                        "gtin:3", "Snacks"));
        PeerCohort cohort = PeerCohort.build(
                snackCandidate(), "Snacks", pool, config());
        // Protein plausible range is 0-100 g: 270 and -5 are dropped.
        assertEquals(Collections.singletonList(10.0),
                cohort.byNutrient.get("protein").values);
        // Sodium plausible range is 0-5000 mg: 15,300 is dropped.
        assertEquals(Arrays.asList(300.0, 400.0),
                cohort.byNutrient.get("sodium").values);
    }

    @Test
    public void plausibleRange_breadFixtureOutlierDropped() throws Exception {
        // Bread fixture fdcId 2157209 reports protein 270 g and sodium
        // 15,300 mg per 100 g — both outside the §1.2 plausible ranges.
        List<CohortPeer> pool = fixturePool("/usda_bread_2026-09-26.json");
        NutritionProfile candidate =
                fixtureCandidate("/usda_bread_2026-09-26.json", 2058078L);
        PeerCohort cohort = PeerCohort.build(
                candidate, BREAD_CATEGORY, pool, config());
        PeerDistribution protein = cohort.byNutrient.get("protein");
        assertFalse(protein.values.contains(270.0));
        for (double v : protein.values) {
            assertTrue("protein within plausible range: " + v, v <= 100.0);
        }
        PeerDistribution sodium = cohort.byNutrient.get("sodium");
        assertFalse(sodium.values.contains(15300.0));
        for (double v : sodium.values) {
            assertTrue("sodium within plausible range: " + v, v <= 5000.0);
        }
        assertTrue(protein.count() > 0);
        assertTrue(sodium.count() > 0);
    }

    // ------------------------------------------------------------------
    // MAD fence (PRD §6.4 step 2)
    // ------------------------------------------------------------------

    @Test
    public void madFence_dropsPlausibleButExtremeValue() throws Exception {
        // Sodium clustered 100..190 (median 150, MAD 30, fence upper
        // bound ~305.7): 2000 mg is plausible (<= 5000) but fenced out.
        List<CohortPeer> pool = new ArrayList<>();
        double[] clustered = {100, 110, 120, 130, 140, 150, 160, 170, 180, 190};
        for (int i = 0; i < clustered.length; i++) {
            pool.add(peer(profile(100L + i, "g", "sodium", clustered[i]),
                    "gtin:c" + i, "Snacks"));
        }
        pool.add(peer(profile(200L, "g", "sodium", 2000.0),
                "gtin:outlier", "Snacks"));
        PeerCohort cohort = PeerCohort.build(
                snackCandidate(), "Snacks", pool, config());
        PeerDistribution sodium = cohort.byNutrient.get("sodium");
        assertEquals(10, sodium.count());
        assertFalse(sodium.values.contains(2000.0));
        assertEquals(150.0, sodium.median, EPS);
    }

    @Test
    public void madZero_keepsAllPlausibleValues() throws Exception {
        // Ten peers at 400 mg and one at 900 mg: median 400, MAD 0, so
        // the fence is disabled and every plausible value is kept.
        List<CohortPeer> pool = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pool.add(peer(profile(100L + i, "g", "sodium", 400.0),
                    "gtin:z" + i, "Snacks"));
        }
        pool.add(peer(profile(200L, "g", "sodium", 900.0),
                "gtin:z-out", "Snacks"));
        PeerCohort cohort = PeerCohort.build(
                snackCandidate(), "Snacks", pool, config());
        PeerDistribution sodium = cohort.byNutrient.get("sodium");
        assertEquals(11, sodium.count());
        assertTrue(sodium.values.contains(900.0));
        assertEquals(400.0, sodium.median, EPS);
    }

    @Test
    public void madFence_chipsFiber_dropsExtremePeer() throws Exception {
        // Candidate 990007 (fiber 4.0 g): plausible peers are
        // [1.0, 2.0, 2.5, 2.8, 3.0, 3.0, 3.2, 3.5, 3.8, 4.5, 6.0, 7.0],
        // median 3.1, MAD 0.65, fence upper ~6.47 — the 7.0 g peer is
        // fenced out, leaving 11 values, so the candidate's raw
        // percentile is 9/11 = 81.82, not the pre-fence 75.0 quoted in
        // the verify script docstring.
        List<CohortPeer> pool = fixturePool("/usda_chips_nutrition.json");
        NutritionProfile candidate =
                fixtureCandidate("/usda_chips_nutrition.json", 990007L);
        PeerCohort cohort = PeerCohort.build(
                candidate, CHIPS_CATEGORY, pool, config());
        PeerDistribution fiber = cohort.byNutrient.get("fiber");
        assertEquals(11, fiber.count());
        assertFalse(fiber.values.contains(7.0));
        assertEquals(81.8182, fiber.rawPercentile(4.0), 0.001);
    }

    // ------------------------------------------------------------------
    // Hand-computed chips fixture percentiles (verify script docstring)
    // ------------------------------------------------------------------

    @Test
    public void chipsSodium_saltedCandidate_percentile100_median430()
            throws Exception {
        // Candidate 990001 (sodium 920 mg) excluded: peer sodium values
        // [140, 180, 320, 350, 380, 410, 450, 480, 540, 580, 620, 680],
        // median (410+450)/2 = 430; no MAD exclusions (MAD 110, fence
        // upper ~1000.8). All 12 peers below the candidate -> 100.0.
        List<CohortPeer> pool = fixturePool("/usda_chips_nutrition.json");
        NutritionProfile candidate =
                fixtureCandidate("/usda_chips_nutrition.json", 990001L);
        PeerCohort cohort = PeerCohort.build(
                candidate, CHIPS_CATEGORY, pool, config());
        assertEquals(12, cohort.peerCount);
        PeerDistribution sodium = cohort.byNutrient.get("sodium");
        assertEquals(12, sodium.count());
        assertEquals(430.0, sodium.median, EPS);
        assertEquals(100.0, sodium.rawPercentile(920.0), EPS);
        // A peer's own value against the distribution: 6 below + 1 equal.
        assertEquals(100.0 * (6 + 0.5) / 12, sodium.rawPercentile(450.0), EPS);
        // Candidate fiber (1.0 g) is below every peer -> 0.0.
        assertEquals(0.0, cohort.byNutrient.get("fiber")
                .rawPercentile(1.0), EPS);
    }

    @Test
    public void chipsSodium_medianCandidate_percentile50() throws Exception {
        // Candidate 990007 (sodium 450 mg): peers are the other twelve,
        // median (410+480)/2 = 445; 6 of 12 peers below 450 -> 50.0,
        // exactly the docstring's hand-computed value.
        List<CohortPeer> pool = fixturePool("/usda_chips_nutrition.json");
        NutritionProfile candidate =
                fixtureCandidate("/usda_chips_nutrition.json", 990007L);
        PeerCohort cohort = PeerCohort.build(
                candidate, CHIPS_CATEGORY, pool, config());
        PeerDistribution sodium = cohort.byNutrient.get("sodium");
        assertEquals(12, sodium.count());
        assertEquals(445.0, sodium.median, EPS);
        assertEquals(50.0, sodium.rawPercentile(450.0), EPS);
    }

    @Test
    public void chipsCarbs_halfCreditTie_percentile83Point33()
            throws Exception {
        // Candidate 990012 (carbohydrates 62.0 g): peers hold a two-way
        // tie at 62.0 (990003, 990010) with 9 values below:
        // 100 * (9 + 0.5 * 2) / 12 = 83.33 (docstring, §1.2 flag note).
        List<CohortPeer> pool = fixturePool("/usda_chips_nutrition.json");
        NutritionProfile candidate =
                fixtureCandidate("/usda_chips_nutrition.json", 990012L);
        PeerCohort cohort = PeerCohort.build(
                candidate, CHIPS_CATEGORY, pool, config());
        PeerDistribution carbs = cohort.byNutrient.get("carbohydrates");
        assertEquals(12, carbs.count());
        assertEquals(100.0 * (9 + 0.5 * 2) / 12,
                carbs.rawPercentile(62.0), EPS);
    }

    @Test
    public void chipsFiber_cleanCandidate_percentile100() throws Exception {
        // Candidate 990013 (fiber 7.0 g, sodium 140 mg): both extremes
        // of the docstring's clean-and-safe scenario.
        List<CohortPeer> pool = fixturePool("/usda_chips_nutrition.json");
        NutritionProfile candidate =
                fixtureCandidate("/usda_chips_nutrition.json", 990013L);
        PeerCohort cohort = PeerCohort.build(
                candidate, CHIPS_CATEGORY, pool, config());
        assertEquals(100.0, cohort.byNutrient.get("fiber")
                .rawPercentile(7.0), EPS);
        assertEquals(0.0, cohort.byNutrient.get("sodium")
                .rawPercentile(140.0), EPS);
    }

    // ------------------------------------------------------------------
    // PeerDistribution math (PRD §6.5)
    // ------------------------------------------------------------------

    @Test
    public void allEqualPeerValues_rawPercentileIs50() {
        // (0 below + 0.5 * count equal) / count = 50: neutral, never
        // fabricated best/worst (PRD §6.5, §13).
        PeerDistribution dist = new PeerDistribution(
                Arrays.asList(500.0, 500.0, 500.0, 500.0));
        assertEquals(50.0, dist.rawPercentile(500.0), EPS);
        assertEquals(500.0, dist.median, EPS);
    }

    @Test
    public void allEqualPeerValues_viaCohort_rawPercentileIs50()
            throws Exception {
        List<CohortPeer> pool = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pool.add(peer(profile(100L + i, "g", "sodium", 500.0),
                    "gtin:e" + i, "Snacks"));
        }
        PeerCohort cohort = PeerCohort.build(
                snackCandidate(), "Snacks", pool, config());
        PeerDistribution sodium = cohort.byNutrient.get("sodium");
        assertEquals(10, sodium.count());
        assertEquals(50.0, sodium.rawPercentile(500.0), EPS);
    }

    @Test
    public void median_oddEvenAndSingleCounts() {
        assertEquals(2.0, new PeerDistribution(
                Arrays.asList(3.0, 1.0, 2.0)).median, EPS);
        assertEquals(2.5, new PeerDistribution(
                Arrays.asList(4.0, 1.0, 3.0, 2.0)).median, EPS);
        PeerDistribution single = new PeerDistribution(
                Collections.singletonList(7.0));
        assertEquals(7.0, single.median, EPS);
        assertEquals(50.0, single.rawPercentile(7.0), EPS);
        assertEquals(0.0, single.rawPercentile(6.0), EPS);
        assertEquals(100.0, single.rawPercentile(8.0), EPS);
    }

    @Test
    public void distributionValues_sortedAscending() {
        PeerDistribution dist = new PeerDistribution(
                Arrays.asList(9.0, 1.0, 5.0, 1.0, 3.0));
        assertEquals(Arrays.asList(1.0, 1.0, 3.0, 5.0, 9.0), dist.values);
        assertEquals(5, dist.count());
    }

    @Test
    public void emptyDistribution_nanPercentileAndMedian() {
        // count == 0: rawPercentile and median are NaN by definition;
        // the analyzer treats peerCount < minPeers as unavailable
        // before ever reading a percentile (Contract §4).
        PeerDistribution dist = new PeerDistribution(
                Collections.<Double>emptyList());
        assertEquals(0, dist.count());
        assertTrue(Double.isNaN(dist.median));
        assertTrue(Double.isNaN(dist.rawPercentile(123.0)));
    }

    @Test
    public void unreportedNutrient_emptyDistributionInCohort()
            throws Exception {
        List<CohortPeer> pool = Arrays.asList(
                peer(profile(1L, "g", "sodium", 300.0), "gtin:1", "Snacks"),
                peer(profile(2L, "g", "sodium", 400.0), "gtin:2", "Snacks"));
        PeerCohort cohort = PeerCohort.build(
                snackCandidate(), "Snacks", pool, config());
        // Every configured nutrient has a distribution; unreported ones
        // are empty (missing is never converted to a 0.0 peer value).
        assertEquals(config().byKey.size(), cohort.byNutrient.size());
        PeerDistribution protein = cohort.byNutrient.get("protein");
        assertNotNull(protein);
        assertEquals(0, protein.count());
        assertTrue(Double.isNaN(protein.rawPercentile(10.0)));
    }

    // ------------------------------------------------------------------
    // Immutability and determinism
    // ------------------------------------------------------------------

    @Test
    public void distributionValues_areUnmodifiable() {
        PeerDistribution dist = new PeerDistribution(
                Arrays.asList(1.0, 2.0));
        try {
            dist.values.add(3.0);
            fail("values must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // immutable per Contract §4
        }
    }

    @Test
    public void cohortByNutrient_isUnmodifiable() throws Exception {
        PeerCohort cohort = PeerCohort.build(snackCandidate(), "Snacks",
                Collections.singletonList(peer(
                        profile(1L, "g", "sodium", 300.0), "gtin:1", "Snacks")),
                config());
        try {
            cohort.byNutrient.put("x", null);
            fail("byNutrient must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // immutable per Contract §4
        }
    }

    @Test
    public void cohortRecordsCategoryAndBasisUsed() throws Exception {
        PeerCohort cohort = PeerCohort.build(snackCandidate(), "Snacks",
                Collections.singletonList(peer(
                        profile(1L, "g", "sodium", 300.0), "gtin:1", "Snacks")),
                config());
        assertEquals("Snacks", cohort.category);
        assertEquals("g", cohort.basis);
        assertEquals(1, cohort.peerCount);
    }

    @Test
    public void determinism_identicalAndReversedPools_sameDistributions()
            throws Exception {
        List<CohortPeer> pool = fixturePool("/usda_chips_nutrition.json");
        NutritionProfile candidate =
                fixtureCandidate("/usda_chips_nutrition.json", 990001L);
        PeerCohort first = PeerCohort.build(
                candidate, CHIPS_CATEGORY, pool, config());
        PeerCohort second = PeerCohort.build(
                candidate, CHIPS_CATEGORY, pool, config());
        List<CohortPeer> reversed = new ArrayList<>(pool);
        Collections.reverse(reversed);
        PeerCohort reversedCohort = PeerCohort.build(
                candidate, CHIPS_CATEGORY, reversed, config());
        // No duplicate keys in the fixture pool, so pool order cannot
        // change the outcome: identical inputs -> identical cohort.
        for (String key : first.byNutrient.keySet()) {
            assertEquals(first.byNutrient.get(key).values,
                    second.byNutrient.get(key).values);
            assertEquals(first.byNutrient.get(key).values,
                    reversedCohort.byNutrient.get(key).values);
            assertEquals(first.byNutrient.get(key).median,
                    reversedCohort.byNutrient.get(key).median, EPS);
        }
        assertEquals(first.peerCount, reversedCohort.peerCount);
    }
}
