package com.barelabel.app.nutrition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * NutritionConfigLoader / NutritionConfig: Contract §1.
 *
 * A valid config (mirroring app/src/main/assets/nutrition_config.json) parses
 * into the exact §1.2 values, and every §1.3 rejection case throws
 * NutritionConfigException — the loader fails closed and never falls back to
 * hard-coded defaults.
 */
public class NutritionConfigTest {

    private static final double EPS = 0.0001;

    /**
     * Representative config JSON mirroring the real asset: same global values
     * and the same eleven §1.2 nutrients.
     */
    private static final String VALID_JSON = "{"
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

    /** Builds one nutrient object literal for {@link #VALID_JSON}. */
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

    private static NutritionConfig parseValid() throws Exception {
        return NutritionConfigLoader.parse(VALID_JSON);
    }

    /** Parses {@code json} expecting a §1.3 rejection. */
    private static void expectInvalid(String json) {
        try {
            NutritionConfigLoader.parse(json);
            fail("expected NutritionConfigException");
        } catch (NutritionConfigException expected) {
            // fail closed: expected path
        }
    }

    /** Returns a mutable copy of the valid config for one mutation. */
    private static JSONObject mutated() throws Exception {
        return new JSONObject(VALID_JSON);
    }

    private static JSONObject nutrientAt(JSONObject root, int index)
            throws Exception {
        return root.getJSONArray("nutrients").getJSONObject(index);
    }

    // ---- valid config -------------------------------------------------

    @Test
    public void validConfig_parsesGlobalValues() throws Exception {
        NutritionConfig config = parseValid();
        assertEquals(1, config.version);
        assertEquals(0.70, config.ingredientWeight, EPS);
        assertEquals(0.30, config.nutritionWeight, EPS);
        assertEquals(10, config.minPeers);
        assertEquals(20, config.preferredPeers);
        assertEquals(3, config.maxCategoryPages);
        assertEquals(5, config.roundBelowPreferredToNearest);
        assertEquals(0.50, config.minCoverage, EPS);
        assertEquals(0.65, config.relativeWeight, EPS);
        assertEquals(0.35, config.absoluteWeight, EPS);
        assertEquals(3, config.alternativesMaxPagesPerQuery);
        assertEquals(60, config.alternativesPoolCap);
    }

    @Test
    public void validConfig_parsesRankingProfiles() throws Exception {
        NutritionConfig config = parseValid();
        assertEquals(3, config.rankingProfiles.size());

        RankingProfile dirtyConcerning =
                config.rankingProfiles.get("dirty_concerning");
        assertNotNull(dirtyConcerning);
        assertEquals(0.40, dirtyConcerning.ingredientAdvantage, EPS);
        assertEquals(0.35, dirtyConcerning.focusedNutrition, EPS);
        assertEquals(0.15, dirtyConcerning.overallNutrition, EPS);
        assertEquals(0.10, dirtyConcerning.preferences, EPS);

        RankingProfile dirtyOnly = config.rankingProfiles.get("dirty_only");
        assertNotNull(dirtyOnly);
        assertEquals(0.75, dirtyOnly.ingredientAdvantage, EPS);
        assertEquals(0.00, dirtyOnly.focusedNutrition, EPS);
        assertEquals(0.15, dirtyOnly.overallNutrition, EPS);
        assertEquals(0.10, dirtyOnly.preferences, EPS);

        RankingProfile nutritionOnly =
                config.rankingProfiles.get("nutrition_only");
        assertNotNull(nutritionOnly);
        assertEquals(0.00, nutritionOnly.ingredientAdvantage, EPS);
        assertEquals(0.60, nutritionOnly.focusedNutrition, EPS);
        assertEquals(0.25, nutritionOnly.overallNutrition, EPS);
        assertEquals(0.15, nutritionOnly.preferences, EPS);
    }

    @Test
    public void validConfig_parsesNutrientDefinitions() throws Exception {
        NutritionConfig config = parseValid();
        assertEquals(11, config.byKey.size());

        NutrientDefinition sodium = config.byKey.get("sodium");
        assertNotNull(sodium);
        assertEquals("Sodium", sodium.label);
        assertEquals("mg", sodium.unit);
        assertEquals("higher_bad", sodium.direction);
        assertTrue(sodium.display);
        assertTrue(sodium.scoreEligible);
        assertTrue(sodium.flagEligible);
        assertEquals(0.16, sodium.weight, EPS);
        assertEquals("sodium", sodium.scoringGroup);
        assertEquals(0, sodium.plausibleMin, EPS);
        assertEquals(5000, sodium.plausibleMax, EPS);
        assertEquals(40, sodium.goodAt, EPS);
        assertEquals(1200, sodium.badAt, EPS);
        assertEquals(85, sodium.flagPercentile.doubleValue(), EPS);
        assertEquals(600, sodium.flagFloor.doubleValue(), EPS);
        assertEquals(2000, sodium.severeAt.doubleValue(), EPS);
        assertNull(sodium.fallbackKey);

        NutrientDefinition energy = config.byKey.get("energy");
        assertNotNull(energy);
        assertEquals("kcal", energy.unit);
        assertEquals(150, energy.goodAt, EPS);
        assertEquals(650, energy.badAt, EPS);
        assertEquals(750, energy.severeAt.doubleValue(), EPS);
    }

    @Test
    public void protein_isNotFlagEligible() throws Exception {
        NutrientDefinition protein = parseValid().byKey.get("protein");
        assertNotNull(protein);
        assertFalse(protein.flagEligible);
        assertEquals("higher_good", protein.direction);
        assertNull(protein.flagPercentile);
        assertNull(protein.lowFlagPercentile);
    }

    @Test
    public void fiber_usesHigherGoodLowFlagRule() throws Exception {
        NutrientDefinition fiber = parseValid().byKey.get("fiber");
        assertNotNull(fiber);
        assertEquals("higher_good", fiber.direction);
        assertTrue(fiber.flagEligible);
        // pct <= 15 & <= 3 g (Contract §1.2)
        assertEquals(15, fiber.lowFlagPercentile.doubleValue(), EPS);
        assertEquals(3, fiber.lowFlagAtOrBelow.doubleValue(), EPS);
        assertNull(fiber.flagPercentile);
        assertNull(fiber.severeAt);
    }

    @Test
    public void changedBlendWeights_parseWithoutCodeChange() throws Exception {
        JSONObject root = mutated();
        root.getJSONObject("blend").put("ingredientWeight", 0.60);
        root.getJSONObject("blend").put("nutritionWeight", 0.40);
        NutritionConfig config =
                NutritionConfigLoader.parse(root.toString());
        assertEquals(0.60, config.ingredientWeight, EPS);
        assertEquals(0.40, config.nutritionWeight, EPS);
    }

    // ---- §1.2 USDA ID coverage ----------------------------------------

    @Test
    public void byUsdaId_coversEverySection12Id() throws Exception {
        NutritionConfig config = parseValid();
        int[] ids = {1008, 1003, 1004, 1005, 2000, 1235, 1079, 1093, 1258,
                1257, 1253};
        String[] keys = {"energy", "protein", "total_fat", "carbohydrates",
                "total_sugars", "added_sugars", "fiber", "sodium",
                "saturated_fat", "trans_fat", "cholesterol"};
        assertEquals(ids.length, config.byUsdaId.size());
        for (int i = 0; i < ids.length; i++) {
            NutrientDefinition def = config.byUsdaId.get(ids[i]);
            assertNotNull("missing USDA ID " + ids[i], def);
            assertEquals(keys[i], def.key);
            assertSame(config.byKey.get(keys[i]), def);
        }
    }

    // ---- sugar group wiring -------------------------------------------

    @Test
    public void sugarGroup_addedAndTotalShareOneGroup() throws Exception {
        NutritionConfig config = parseValid();
        NutrientDefinition added = config.byKey.get("added_sugars");
        NutrientDefinition total = config.byKey.get("total_sugars");
        assertNotNull(added);
        assertNotNull(total);
        assertEquals("sugars", added.scoringGroup);
        assertEquals("sugars", total.scoringGroup);
        assertEquals(added.scoringGroup, total.scoringGroup);
    }

    @Test
    public void sugarGroup_totalSugarsIsFallbackOfAddedSugars()
            throws Exception {
        NutritionConfig config = parseValid();
        NutrientDefinition added = config.byKey.get("added_sugars");
        assertNotNull(added);
        assertEquals("total_sugars", added.fallbackKey);
        assertEquals(1.0, added.fallbackWeightFactor.doubleValue(), EPS);
        // The fallback key resolves to the total_sugars definition itself.
        assertSame(config.byKey.get("total_sugars"),
                config.byKey.get(added.fallbackKey));
    }

    @Test
    public void groupWeights_sumToOne() throws Exception {
        NutritionConfig config = parseValid();
        // One weight per scoring group (the sugars group contributes once).
        Map<String, Double> groupWeights = new LinkedHashMap<>();
        for (NutrientDefinition def : config.byKey.values()) {
            if (!groupWeights.containsKey(def.scoringGroup)) {
                groupWeights.put(def.scoringGroup, def.weight);
            }
        }
        double sum = 0;
        for (double weight : groupWeights.values()) {
            sum += weight;
        }
        assertEquals(1.00, sum, 0.001);
    }

    // ---- §1.3 rejection cases ------------------------------------------

    @Test
    public void malformedJson_throws() {
        expectInvalid("{not json");
        expectInvalid("");
        expectInvalid("[]");
    }

    @Test
    public void duplicateNutrientKey_throws() throws Exception {
        JSONObject root = mutated();
        JSONArray nutrients = root.getJSONArray("nutrients");
        JSONObject duplicate =
                new JSONObject(nutrients.getJSONObject(0).toString());
        nutrients.put(duplicate);
        expectInvalid(root.toString());
    }

    @Test
    public void unknownDirection_throws() throws Exception {
        JSONObject root = mutated();
        nutrientAt(root, 0).put("direction", "sideways");
        expectInvalid(root.toString());
    }

    @Test
    public void nonPositiveWeight_throws() throws Exception {
        JSONObject zero = mutated();
        nutrientAt(zero, 0).put("weight", 0);
        expectInvalid(zero.toString());

        JSONObject negative = mutated();
        nutrientAt(negative, 0).put("weight", -0.5);
        expectInvalid(negative.toString());
    }

    @Test
    public void plausibleMinAtOrAboveMax_throws() throws Exception {
        JSONObject equal = mutated();
        nutrientAt(equal, 0).put("plausibleMin", 900);
        expectInvalid(equal.toString());

        JSONObject inverted = mutated();
        nutrientAt(inverted, 0).put("plausibleMin", 950);
        expectInvalid(inverted.toString());
    }

    @Test
    public void equalAnchors_throw() throws Exception {
        JSONObject root = mutated();
        nutrientAt(root, 0).put("goodAt", 650);
        nutrientAt(root, 0).put("badAt", 650);
        expectInvalid(root.toString());
    }

    @Test
    public void anchorOutsidePlausibleRange_throws() throws Exception {
        JSONObject above = mutated();
        nutrientAt(above, 0).put("goodAt", 901);
        expectInvalid(above.toString());

        JSONObject below = mutated();
        nutrientAt(below, 0).put("badAt", -1);
        expectInvalid(below.toString());
    }

    @Test
    public void blendWeightsNotSummingToOne_throw() throws Exception {
        JSONObject root = mutated();
        root.getJSONObject("blend").put("ingredientWeight", 0.60);
        // nutritionWeight stays 0.30: sum is 0.90.
        expectInvalid(root.toString());
    }

    @Test
    public void rankingProfileWeightsNotSummingToOne_throw()
            throws Exception {
        JSONObject root = mutated();
        root.getJSONObject("rankingProfiles").getJSONObject("dirty_only")
                .put("ingredientAdvantage", 0.50);
        // dirty_only now sums to 0.75.
        expectInvalid(root.toString());
    }

    @Test
    public void minPeersBelowOne_throws() throws Exception {
        JSONObject root = mutated();
        root.getJSONObject("peers").put("minPeers", 0);
        expectInvalid(root.toString());
    }

    @Test
    public void coverageOutsideOpenClosedInterval_throws() throws Exception {
        JSONObject zero = mutated();
        zero.getJSONObject("scoring").put("minCoverage", 0);
        expectInvalid(zero.toString());

        JSONObject aboveOne = mutated();
        aboveOne.getJSONObject("scoring").put("minCoverage", 1.5);
        expectInvalid(aboveOne.toString());
    }
}
