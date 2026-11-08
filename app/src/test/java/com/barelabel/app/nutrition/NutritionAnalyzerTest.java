package com.barelabel.app.nutrition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * NutritionAnalyzer: Contract §4.1/§4.2, PRD §7/§8.
 *
 * Judgment calls pinned by these tests (also documented on
 * {@link NutritionAnalyzer}):
 * <ul>
 *   <li>Stored raw/favorable percentiles are the DISPLAY values: when
 *       {@code peerCount < preferredPeers} they are rounded to the
 *       nearest {@code roundBelowPreferredToNearest}; quality/scoring
 *       always use the unrounded favorable percentile.</li>
 *   <li>{@code peerMedian} is exposed whenever the distribution count
 *       is &gt; 0; percentiles and quality require count &gt;=
 *       {@code minPeers}.</li>
 *   <li>Peer adequacy for SAFE means the cohort holds at least
 *       {@code minPeers} products (in addition to the coverage gate).</li>
 * </ul>
 */
public class NutritionAnalyzerTest {

    private static final double EPS = 0.001;
    private static final String CHIPS_CATEGORY = "Chips, Pretzels & Snacks";

    // ------------------------------------------------------------------
    // Real config through the real loader (same embedded §1.2 values as
    // PeerCohortTest — no mocked definitions).
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
    // Synthetic helpers: direct PeerCohort / profile construction.
    // ------------------------------------------------------------------

    private static NutritionProfile candidate(Object... keyValues) {
        Map<String, Double> per100 = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            per100.put((String) keyValues[i], (Double) keyValues[i + 1]);
        }
        return new NutritionProfile(999L, "g", null, "", "", per100);
    }

    private static PeerDistribution dist(double... values) {
        List<Double> list = new ArrayList<>();
        for (double v : values) {
            list.add(v);
        }
        return new PeerDistribution(list);
    }

    /** Distribution of {@code n} copies of {@code value}. */
    private static PeerDistribution distN(double value, int n) {
        List<Double> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(value);
        }
        return new PeerDistribution(list);
    }

    private static PeerCohort cohort(int peerCount, Object... keyDists) {
        Map<String, PeerDistribution> byNutrient = new LinkedHashMap<>();
        for (int i = 0; i < keyDists.length; i += 2) {
            byNutrient.put((String) keyDists[i], (PeerDistribution) keyDists[i + 1]);
        }
        return new PeerCohort("Snacks", "g", byNutrient, peerCount);
    }

    private static NutrientComparison sodium(NutritionAnalysis a) {
        return a.comparisons.get("sodium");
    }

    // ------------------------------------------------------------------
    // Flags: severe rule (Contract §4.2, PRD §7.1)
    // ------------------------------------------------------------------

    @Test
    public void severeRule_flagsAloneWithTinyPeerSet() throws Exception {
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 2100.0),
                cohort(2, "sodium", dist(300.0, 400.0)), config());
        assertTrue(sodium(a).flagged);
        assertEquals("severe", sodium(a).flagReason);
        assertEquals(NutritionStatus.CONCERNING, a.status);
    }

    @Test
    public void severeRule_flagsEvenWhenPercentileBelowThreshold() throws Exception {
        // Every peer is saltier, so the raw percentile is 0 — the severe
        // rule must still fire (PRD §7.1: a nutrient cannot escape a flag
        // merely because the whole category is unhealthy).
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 2100.0),
                cohort(10, "sodium", distN(3000.0, 10)), config());
        assertTrue(sodium(a).flagged);
        assertEquals("severe", sodium(a).flagReason);
    }

    @Test
    public void severeFlagWithInadequatePeers_statusConcerning() throws Exception {
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 2500.0),
                cohort(0), config());
        assertTrue(sodium(a).flagged);
        assertEquals("severe", sodium(a).flagReason);
        assertEquals(NutritionStatus.CONCERNING, a.status);
    }

    // ------------------------------------------------------------------
    // Flags: relative + floor rule (higher_bad)
    // ------------------------------------------------------------------

    @Test
    public void relativeFloor_flagsWhenPercentileAndFloorBothCrossed() throws Exception {
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 700.0),
                cohort(10, "sodium", distN(100.0, 10)), config());
        assertTrue(sodium(a).flagged);
        assertEquals("relative_floor", sodium(a).flagReason);
    }

    @Test
    public void relativeFloor_percentileCrossedButBelowFloor_notFlagged() throws Exception {
        // Raw percentile 100 (>= 85) but 500 mg is below the 600 mg floor.
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 500.0),
                cohort(10, "sodium", distN(100.0, 10)), config());
        assertFalse(sodium(a).flagged);
        assertEquals("", sodium(a).flagReason);
    }

    @Test
    public void relativeFloor_floorCrossedButPercentileBelow_notFlagged() throws Exception {
        // 700 mg clears the floor but every peer is saltier (percentile 0)
        // and 700 is below the severe threshold.
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 700.0),
                cohort(10, "sodium", distN(3000.0, 10)), config());
        assertFalse(sodium(a).flagged);
        assertEquals("", sodium(a).flagReason);
    }

    // ------------------------------------------------------------------
    // Flags: low rule (higher_good)
    // ------------------------------------------------------------------

    @Test
    public void fiberLow_flagsWhenPercentileAndAmountBothLow() throws Exception {
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("fiber", 2.0),
                cohort(10, "fiber", distN(5.0, 10)), config());
        NutrientComparison fiber = a.comparisons.get("fiber");
        assertTrue(fiber.flagged);
        assertEquals("low", fiber.flagReason);
    }

    @Test
    public void fiberLow_percentileLowButAmountAboveThreshold_notFlagged() throws Exception {
        // Raw percentile 0 (<= 15) but 4 g is above the 3 g ceiling.
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("fiber", 4.0),
                cohort(10, "fiber", distN(10.0, 10)), config());
        assertFalse(a.comparisons.get("fiber").flagged);
        assertEquals("", a.comparisons.get("fiber").flagReason);
    }

    @Test
    public void protein_neverFlagged_flagEligibleFalse() throws Exception {
        // Protein sits at raw percentile 0 among richer peers, but its
        // definition is not flag-eligible (and carries no low rule).
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("protein", 0.5),
                cohort(10, "protein", distN(20.0, 10)), config());
        assertFalse(a.comparisons.get("protein").flagged);
        assertEquals("", a.comparisons.get("protein").flagReason);
    }

    @Test
    public void customHigherGood_usesItsOwnLowRuleFields() throws Exception {
        NutritionConfig cfg = directConfig(10, 20,
                higherGoodDef("potassium_like", 0.20, "potassium_like",
                        20.0, 0.0, 15.0, 5.0, true));
        NutritionAnalysis flagged = NutritionAnalyzer.analyze(
                candidate("potassium_like", 3.0),
                cohort(10, "potassium_like", distN(10.0, 10)), cfg);
        assertTrue(flagged.comparisons.get("potassium_like").flagged);
        assertEquals("low", flagged.comparisons.get("potassium_like").flagReason);

        NutritionAnalysis notFlagged = NutritionAnalyzer.analyze(
                candidate("potassium_like", 8.0),
                cohort(10, "potassium_like", distN(10.0, 10)), cfg);
        assertFalse(notFlagged.comparisons.get("potassium_like").flagged);
    }

    /** Minimal config built directly (no JSON) for custom-definition tests. */
    private static NutritionConfig directConfig(int minPeers, int preferredPeers,
                                                NutrientDefinition... defs) {
        Map<String, NutrientDefinition> byKey = new LinkedHashMap<>();
        for (NutrientDefinition def : defs) {
            byKey.put(def.key, def);
        }
        return new NutritionConfig(1, 0.70, 0.30, minPeers, preferredPeers, 3, 5,
                0.50, 0.65, 0.35, 3, 60, byKey,
                Collections.<String, RankingProfile>emptyMap());
    }

    private static NutrientDefinition higherGoodDef(String key, double weight,
                                                    String group, double goodAt,
                                                    double badAt,
                                                    Double lowPct, Double lowAt,
                                                    boolean flagEligible) {
        return new NutrientDefinition(key, Collections.singletonList(9999),
                key, "g", "higher_good", true, true, flagEligible, weight,
                group, 0.0, 5000.0, goodAt, badAt, null, null, null,
                lowPct, lowAt, null, null);
    }

    // ------------------------------------------------------------------
    // Status derivation (PRD §7.3)
    // ------------------------------------------------------------------

    /** Candidate reporting every nutrient at a benign mid-cohort value. */
    private static NutritionProfile benignCandidate() {
        return candidate(
                "energy", 400.0, "protein", 10.0, "total_fat", 10.0,
                "carbohydrates", 30.0, "total_sugars", 5.0,
                "added_sugars", 2.0, "fiber", 6.0, "sodium", 400.0,
                "saturated_fat", 3.0, "trans_fat", 0.0, "cholesterol", 50.0);
    }

    /** Cohort where every nutrient has {@code peers} copies of the value. */
    private static PeerCohort benignCohort(int peersPerNutrient, int peerCount) {
        return cohort(peerCount,
                "energy", distN(400.0, peersPerNutrient),
                "protein", distN(10.0, peersPerNutrient),
                "total_fat", distN(10.0, peersPerNutrient),
                "carbohydrates", distN(30.0, peersPerNutrient),
                "total_sugars", distN(5.0, peersPerNutrient),
                "added_sugars", distN(2.0, peersPerNutrient),
                "fiber", distN(6.0, peersPerNutrient),
                "sodium", distN(400.0, peersPerNutrient),
                "saturated_fat", distN(3.0, peersPerNutrient),
                "trans_fat", distN(0.0, peersPerNutrient),
                "cholesterol", distN(50.0, peersPerNutrient));
    }

    @Test
    public void status_safeWhenNoFlagsCoverageAndPeersAdequate() throws Exception {
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                benignCandidate(), benignCohort(12, 12), config());
        assertTrue(a.flaggedNutrients.isEmpty());
        assertEquals(1.0, a.coverage, EPS);
        assertNotNull(a.nutritionScore);
        assertEquals(NutritionStatus.SAFE, a.status);
    }

    @Test
    public void status_unavailableWhenCoverageBelowMinimum() throws Exception {
        // Only sodium (group weight 0.16) has a quality: coverage 0.16.
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 400.0),
                cohort(12, "sodium", distN(400.0, 12)), config());
        assertEquals(0.16, a.coverage, EPS);
        assertNull(a.nutritionScore);
        assertEquals(NutritionStatus.UNAVAILABLE, a.status);
    }

    @Test
    public void status_unavailableWhenPeersInadequate() throws Exception {
        // Every nutrient reported, but only 3 peers each (< minPeers 10):
        // no valid percentiles, no qualities, coverage 0.
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                benignCandidate(), benignCohort(3, 3), config());
        assertTrue(a.flaggedNutrients.isEmpty());
        assertEquals(0.0, a.coverage, EPS);
        assertNull(a.nutritionScore);
        assertEquals(NutritionStatus.UNAVAILABLE, a.status);
    }

    // ------------------------------------------------------------------
    // Absolute quality (Contract §4.1): direction-aware interpolation
    // ------------------------------------------------------------------

    @Test
    public void absoluteQuality_higherBad_interpolatesAndClamps() throws Exception {
        // Sodium anchors: goodAt 40 -> 100, badAt 1200 -> 0.
        NutritionAnalysis mid = NutritionAnalyzer.analyze(
                candidate("sodium", 620.0), cohort(0), config());
        assertEquals(50.0, sodium(mid).absoluteQuality, EPS);

        NutritionAnalysis belowGood = NutritionAnalyzer.analyze(
                candidate("sodium", 10.0), cohort(0), config());
        assertEquals(100.0, sodium(belowGood).absoluteQuality, EPS);

        NutritionAnalysis aboveBad = NutritionAnalyzer.analyze(
                candidate("sodium", 2000.0), cohort(0), config());
        assertEquals(0.0, sodium(aboveBad).absoluteQuality, EPS);
    }

    @Test
    public void absoluteQuality_higherGood_interpolates() throws Exception {
        // Fiber anchors: goodAt 12 -> 100, badAt 0 -> 0.
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("fiber", 6.0), cohort(0), config());
        assertEquals(50.0, a.comparisons.get("fiber").absoluteQuality, EPS);
        NutritionAnalysis top = NutritionAnalyzer.analyze(
                candidate("fiber", 12.0), cohort(0), config());
        assertEquals(100.0, top.comparisons.get("fiber").absoluteQuality, EPS);
    }

    @Test
    public void absoluteQuality_presentWithoutValidPercentile_butNoQuality()
            throws Exception {
        // A nutrient with no valid percentile still carries an absolute
        // quality and a severe flag, but contributes no combined quality.
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 2100.0),
                cohort(3, "sodium", dist(300.0, 400.0, 500.0)), config());
        assertEquals(2100.0, sodium(a).per100, EPS);
        assertNotNull(sodium(a).absoluteQuality);
        assertNull(sodium(a).quality);
        assertNull(sodium(a).rawPercentile);
        assertNull(sodium(a).favorablePercentile);
        assertTrue(sodium(a).flagged);
    }

    // ------------------------------------------------------------------
    // Percentiles, favorable direction, display rounding (Contract §4.1)
    // ------------------------------------------------------------------

    @Test
    public void favorablePercentile_directionAware() throws Exception {
        // Candidate is the highest of its peers for both nutrients:
        // raw 100; higher_bad favorable = 0, higher_good favorable = 100.
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 900.0, "fiber", 9.0),
                cohort(10, "sodium", distN(100.0, 10),
                        "fiber", distN(1.0, 10)), config());
        assertEquals(100.0, sodium(a).rawPercentile, EPS);
        assertEquals(0.0, sodium(a).favorablePercentile, EPS);
        assertEquals(100.0, a.comparisons.get("fiber").rawPercentile, EPS);
        assertEquals(100.0, a.comparisons.get("fiber").favorablePercentile, EPS);
    }

    @Test
    public void allEqualPeers_favorableIsNeutral50() throws Exception {
        // PeerDistribution's half-credit tie formula yields raw 50 when
        // the candidate equals every peer; the analyzer uses it as-is.
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 500.0),
                cohort(10, "sodium", distN(500.0, 10)), config());
        assertEquals(50.0, sodium(a).rawPercentile, EPS);
        assertEquals(50.0, sodium(a).favorablePercentile, EPS);
    }

    @Test
    public void quality_combinesRelativeAndAbsoluteWithConfigWeights()
            throws Exception {
        // Sodium 620: favorable 50 (all peers equal), absolute 50 ->
        // quality = 0.65*50 + 0.35*50 = 50.
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 620.0),
                cohort(10, "sodium", distN(620.0, 10)), config());
        assertEquals(50.0, sodium(a).quality, EPS);
    }

    @Test
    public void displayRounding_belowPreferred_storedRounded_scoringUnrounded()
            throws Exception {
        // 12 peers (< preferred 20): 10 below, 2 above the candidate.
        // Unrounded raw = 83.333, favorable = 16.667; stored display
        // values round to the nearest 5 (85 / 15), but quality must use
        // the unrounded favorable: 0.65*16.667 + 0.35*43.103 = 25.92,
        // not 0.65*15 + 0.35*43.103 = 24.84.
        List<Double> peers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            peers.add(100.0);
        }
        peers.add(800.0);
        peers.add(900.0);
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 700.0),
                cohort(12, "sodium", new PeerDistribution(peers)), config());
        assertEquals(85.0, sodium(a).rawPercentile, EPS);
        assertEquals(15.0, sodium(a).favorablePercentile, EPS);
        assertEquals(25.92, sodium(a).quality, 0.01);
    }

    @Test
    public void displayRounding_atOrAbovePreferred_notRounded() throws Exception {
        // 20 peers (= preferred): 17 below, 1 equal, 2 above ->
        // raw = (17 + 0.5)/20 * 100 = 87.5, stored unrounded.
        List<Double> peers = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            peers.add(100.0);
        }
        peers.add(700.0);
        peers.add(800.0);
        peers.add(900.0);
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 700.0),
                cohort(20, "sodium", new PeerDistribution(peers)), config());
        assertEquals(87.5, sodium(a).rawPercentile, EPS);
        assertEquals(12.5, sodium(a).favorablePercentile, EPS);
    }

    @Test
    public void medianExposedBelowMinPeers_percentilesNull() throws Exception {
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 450.0),
                cohort(3, "sodium", dist(300.0, 400.0, 500.0)), config());
        assertEquals(3, sodium(a).peerCount);
        assertEquals(400.0, sodium(a).peerMedian, EPS);
        assertNull(sodium(a).rawPercentile);
        assertNull(sodium(a).favorablePercentile);
        assertNull(sodium(a).quality);
    }

    @Test
    public void noPeers_medianAndPercentilesNull() throws Exception {
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 450.0), cohort(0), config());
        assertEquals(0, sodium(a).peerCount);
        assertNull(sodium(a).peerMedian);
        assertNull(sodium(a).rawPercentile);
    }

    @Test
    public void unreportedNutrient_nullAmountsButPeerStatsPresent() throws Exception {
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("fiber", 6.0),
                cohort(12, "sodium", distN(400.0, 12)), config());
        assertNull(sodium(a).per100);
        assertNull(sodium(a).perServing);
        assertEquals(12, sodium(a).peerCount);
        assertEquals(400.0, sodium(a).peerMedian, EPS);
        assertNull(sodium(a).rawPercentile);
        assertNull(sodium(a).absoluteQuality);
        assertFalse(sodium(a).flagged);
    }

    @Test
    public void perServing_derivedFromProfile() throws Exception {
        Map<String, Double> per100 = new LinkedHashMap<>();
        per100.put("sodium", 600.0);
        NutritionProfile withServing = new NutritionProfile(
                999L, "g", 30.0, "g", "", per100);
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                withServing, cohort(0), config());
        assertEquals(600.0, sodium(a).per100, EPS);
        assertEquals(180.0, sodium(a).perServing, EPS);
    }

    // ------------------------------------------------------------------
    // Sugar group (Contract §1.2): one contribution, added preferred
    // ------------------------------------------------------------------

    /**
     * Candidate + all-equal peers (raw 50 everywhere) over five groups —
     * energy, protein, fiber, sodium, sugars — weight 0.68 total. Every
     * non-sugar group quality is 50 except as noted: energy 400, protein
     * 10 and fiber 6 all sit at absolute quality 50; sodium 620 likewise.
     * Added sugars 0 -> absolute 100 -> quality 67.5; total sugars 35 ->
     * absolute 0 -> quality 32.5. Peers equal the candidate per nutrient.
     */
    private static NutritionProfile sugarCandidate(boolean withAdded,
                                                   boolean withTotal) {
        Map<String, Double> per100 = new LinkedHashMap<>();
        per100.put("energy", 400.0);
        per100.put("protein", 10.0);
        per100.put("fiber", 6.0);
        per100.put("sodium", 620.0);
        if (withTotal) {
            per100.put("total_sugars", 35.0);
        }
        if (withAdded) {
            per100.put("added_sugars", 0.0);
        }
        return new NutritionProfile(999L, "g", null, "", "", per100);
    }

    private static PeerCohort sugarCohort(boolean withAdded, boolean withTotal) {
        Map<String, PeerDistribution> byNutrient = new LinkedHashMap<>();
        byNutrient.put("energy", distN(400.0, 10));
        byNutrient.put("protein", distN(10.0, 10));
        byNutrient.put("fiber", distN(6.0, 10));
        byNutrient.put("sodium", distN(620.0, 10));
        if (withTotal) {
            byNutrient.put("total_sugars", distN(35.0, 10));
        }
        if (withAdded) {
            byNutrient.put("added_sugars", distN(0.0, 10));
        }
        return new PeerCohort("Snacks", "g", byNutrient, 10);
    }

    @Test
    public void sugarGroup_addedPreferredWhenBothHaveQuality() throws Exception {
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                sugarCandidate(true, true), sugarCohort(true, true), config());
        // Group counted once: coverage = .10+.12+.14+.16+.16 = 0.68.
        assertEquals(0.68, a.coverage, EPS);
        // Sugar group quality from added (67.5):
        // score = (0.52*50 + 0.16*67.5) / 0.68 = 54.11 -> 54.
        // (Using total sugars' 32.5 instead would give 46.)
        assertEquals(Integer.valueOf(54), a.nutritionScore);
    }

    @Test
    public void sugarGroup_totalFallbackWhenAddedUnreported() throws Exception {
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                sugarCandidate(false, true), sugarCohort(false, true), config());
        assertEquals(0.68, a.coverage, EPS);
        // Sugar group quality from total (32.5):
        // score = (0.52*50 + 0.16*32.5) / 0.68 = 45.88 -> 46.
        assertEquals(Integer.valueOf(46), a.nutritionScore);
    }

    @Test
    public void sugarGroup_bothSugarsIndividuallyFlagged() throws Exception {
        // Flagging stays per-nutrient even though scoring groups them:
        // total 55 >= severe 50 and added 40 >= severe 35.
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("total_sugars", 55.0, "added_sugars", 40.0),
                cohort(0), config());
        assertTrue(a.comparisons.get("total_sugars").flagged);
        assertEquals("severe", a.comparisons.get("total_sugars").flagReason);
        assertTrue(a.comparisons.get("added_sugars").flagged);
        assertEquals("severe", a.comparisons.get("added_sugars").flagReason);
        // Config order: total_sugars precedes added_sugars.
        assertEquals(Arrays.asList("total_sugars", "added_sugars"),
                a.flaggedNutrients);
        assertEquals(NutritionStatus.CONCERNING, a.status);
    }

    // ------------------------------------------------------------------
    // Coverage and Nutrition Score (Contract §4.1, PRD §8.3)
    // ------------------------------------------------------------------

    @Test
    public void score_renormalizedOverAvailableGroups_missingNeverZero()
            throws Exception {
        // Groups present: energy .10, protein .12, fiber .14, sodium .16
        // (coverage 0.52). Qualities: 50, 50, 50, and sodium at 40 mg ->
        // absolute 100, favorable 50 -> quality 67.5.
        // Score = (5 + 6 + 7 + 10.8) / 0.52 = 55.38 -> 55.
        // If missing groups counted as zero the score would be 29.
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("energy", 400.0, "protein", 10.0, "fiber", 6.0,
                        "sodium", 40.0),
                cohort(10,
                        "energy", distN(400.0, 10),
                        "protein", distN(10.0, 10),
                        "fiber", distN(6.0, 10),
                        "sodium", distN(40.0, 10)), config());
        assertEquals(0.52, a.coverage, EPS);
        assertEquals(Integer.valueOf(55), a.nutritionScore);
        assertEquals(NutritionStatus.SAFE, a.status);
    }

    @Test
    public void score_nullWhenCoverageBelowMinimum() throws Exception {
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("energy", 400.0),
                cohort(10, "energy", distN(400.0, 10)), config());
        assertEquals(0.10, a.coverage, EPS);
        assertNull(a.nutritionScore);
    }

    // ------------------------------------------------------------------
    // Analysis shape: cohort fields, config order, full comparison set
    // ------------------------------------------------------------------

    @Test
    public void analysis_carriesCohortCategoryAndPeerCount() throws Exception {
        PeerCohort c = new PeerCohort(CHIPS_CATEGORY, "g",
                Collections.<String, PeerDistribution>emptyMap(), 12);
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 100.0), c, config());
        assertEquals(CHIPS_CATEGORY, a.cohortCategory);
        assertEquals(12, a.cohortPeerCount);
    }

    @Test
    public void comparisons_coverEveryDisplayedNutrient() throws Exception {
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("sodium", 100.0), cohort(0), config());
        assertEquals(11, a.comparisons.size());
        for (NutrientDefinition def : config().byKey.values()) {
            if (def.display) {
                assertTrue("comparison for " + def.key,
                        a.comparisons.containsKey(def.key));
            }
        }
    }

    @Test
    public void flaggedNutrients_inConfigOrder() throws Exception {
        // Energy (severe 800 >= 750), fiber (low among rich peers) and
        // sodium (severe 2100) all flag; config order is energy, fiber,
        // sodium regardless of evaluation discovery order.
        NutritionAnalysis a = NutritionAnalyzer.analyze(
                candidate("energy", 800.0, "fiber", 1.0, "sodium", 2100.0),
                cohort(10, "fiber", distN(5.0, 10)), config());
        assertEquals(Arrays.asList("energy", "fiber", "sodium"),
                a.flaggedNutrients);
    }

    // ------------------------------------------------------------------
    // Mockup scenario over the chips fixture (real parser + cohort)
    // ------------------------------------------------------------------

    private static JSONArray fixtureFoods(String resource) throws Exception {
        InputStream is = NutritionAnalyzerTest.class.getResourceAsStream(resource);
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

    private static List<CohortPeer> fixturePool(String resource) throws Exception {
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

    @Test
    public void mockup_990001_concerningWithSodiumFlagged() throws Exception {
        String resource = "/usda_chips_nutrition.json";
        NutritionProfile cand = fixtureCandidate(resource, 990001L);
        PeerCohort cohort = PeerCohort.build(
                cand, CHIPS_CATEGORY, fixturePool(resource), config());
        NutritionAnalysis a = NutritionAnalyzer.analyze(cand, cohort, config());
        assertEquals(NutritionStatus.CONCERNING, a.status);
        assertTrue(a.comparisons.get("sodium").flagged);
        assertEquals("relative_floor", a.comparisons.get("sodium").flagReason);
        assertTrue(a.flaggedNutrients.contains("sodium"));
        assertTrue(a.flaggedNutrients.contains("fiber"));
        assertEquals("low", a.comparisons.get("fiber").flagReason);
    }

    @Test
    public void mockup_990013_safe() throws Exception {
        String resource = "/usda_chips_nutrition.json";
        NutritionProfile cand = fixtureCandidate(resource, 990013L);
        PeerCohort cohort = PeerCohort.build(
                cand, CHIPS_CATEGORY, fixturePool(resource), config());
        NutritionAnalysis a = NutritionAnalyzer.analyze(cand, cohort, config());
        assertTrue("flagged: " + a.flaggedNutrients,
                a.flaggedNutrients.isEmpty());
        assertTrue("coverage " + a.coverage, a.coverage >= 0.50);
        assertNotNull(a.nutritionScore);
        assertEquals(NutritionStatus.SAFE, a.status);
    }
}
