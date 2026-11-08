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

/**
 * UsdaNutritionParser / NutritionProfile: Contract §2, §2.1, §3.
 *
 * The parser maps USDA branded foodNutrients through the real
 * NutritionConfig (built via NutritionConfigLoader.parse of an embedded
 * config mirroring the §1.2 asset values — no mocked definitions), converts
 * units to each definition's unit, keeps only reported nutrients (missing
 * is never 0.0), and never throws on malformed input.
 */
public class UsdaNutritionParserTest {

    private static final double EPS = 0.0001;

    // ------------------------------------------------------------------
    // Real config, built through the real loader (Contract §1 types).
    // Mirrors NutritionConfigTest's embedded config: same eleven §1.2
    // nutrients and global values as the shipped asset.
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
    // Fixture + synthetic-food helpers
    // ------------------------------------------------------------------

    private static JSONArray fixtureFoods() throws Exception {
        InputStream is = UsdaNutritionParserTest.class
                .getResourceAsStream("/usda_bread_2026-09-26.json");
        assertNotNull("bread fixture on test classpath", is);
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

    private static JSONObject fixtureFood(long fdcId) throws Exception {
        JSONArray foods = fixtureFoods();
        for (int i = 0; i < foods.length(); i++) {
            JSONObject food = foods.getJSONObject(i);
            if (food.optLong("fdcId", 0) == fdcId) {
                return food;
            }
        }
        fail("fixture has no food with fdcId " + fdcId);
        return null;
    }

    /** Flat USDA search shape: {nutrientId, value, unitName}. */
    private static JSONObject flatEntry(int nutrientId, double value,
                                        String unitName) throws Exception {
        JSONObject entry = new JSONObject();
        entry.put("nutrientId", nutrientId);
        entry.put("value", value);
        entry.put("unitName", unitName);
        return entry;
    }

    /** Nested USDA /food shape: {nutrient: {id, unitName}, amount}. */
    private static JSONObject nestedEntry(int nutrientId, double amount,
                                          String unitName) throws Exception {
        JSONObject nutrient = new JSONObject();
        nutrient.put("id", nutrientId);
        nutrient.put("unitName", unitName);
        JSONObject entry = new JSONObject();
        entry.put("nutrient", nutrient);
        entry.put("amount", amount);
        return entry;
    }

    private static JSONObject foodWith(JSONArray nutrients) throws Exception {
        JSONObject food = new JSONObject();
        food.put("fdcId", 424242);
        food.put("foodNutrients", nutrients);
        return food;
    }

    private static JSONArray nutrients(JSONObject... entries) {
        JSONArray arr = new JSONArray();
        for (JSONObject entry : entries) {
            arr.put(entry);
        }
        return arr;
    }

    // ------------------------------------------------------------------
    // Bread fixture (real USDA search response)
    // ------------------------------------------------------------------

    @Test
    public void breadFixture_firstFood_reportedSetAndValues()
            throws Exception {
        // fdcId 2058078 ("BREAD", JOHANN'S BAKERY): 14 foodNutrients, of
        // which 10 are configured (§1.2); calcium/iron/vitamins are not.
        NutritionProfile profile = UsdaNutritionParser.parse(
                fixtureFood(2058078L), config());

        assertEquals(2058078L, profile.fdcId);
        assertEquals("g", profile.basis);
        assertEquals(28.0, profile.servingSize.doubleValue(), EPS);
        assertEquals("g", profile.servingUnit);
        assertEquals("1 ONZ", profile.householdServing);

        assertTrue(profile.reported("protein"));
        assertTrue(profile.reported("total_fat"));
        assertTrue(profile.reported("carbohydrates"));
        assertTrue(profile.reported("energy"));
        assertTrue(profile.reported("total_sugars"));
        assertTrue(profile.reported("fiber"));
        assertTrue(profile.reported("sodium"));
        assertTrue(profile.reported("cholesterol"));
        assertTrue(profile.reported("trans_fat"));
        assertTrue(profile.reported("saturated_fat"));
        // The record does not report added sugars: absent, never 0.0.
        assertFalse(profile.reported("added_sugars"));
        assertNull(profile.per100.get("added_sugars"));
        // Exactly the 10 configured nutrients; unconfigured IDs ignored.
        assertEquals(10, profile.per100.size());

        assertEquals(3.57, profile.per100.get("protein").doubleValue(), EPS);
        assertEquals(354.0, profile.per100.get("sodium").doubleValue(), EPS);
        assertEquals(146.0, profile.per100.get("energy").doubleValue(), EPS);
        assertEquals(32.1,
                profile.per100.get("carbohydrates").doubleValue(), EPS);
        assertEquals(3.6, profile.per100.get("fiber").doubleValue(), EPS);
    }

    @Test
    public void breadFixture_firstFood_perServingConversion()
            throws Exception {
        // servingSize 28 with unit "g" == basis: per100 * 28/100 (§2.1).
        NutritionProfile profile = UsdaNutritionParser.parse(
                fixtureFood(2058078L), config());
        assertEquals(3.57 * 0.28,
                profile.perServing("protein").doubleValue(), EPS);
        assertEquals(354.0 * 0.28,
                profile.perServing("sodium").doubleValue(), EPS);
        // Not reported -> no per-serving value either.
        assertNull(profile.perServing("added_sugars"));
    }

    // ------------------------------------------------------------------
    // Both USDA entry shapes (§3)
    // ------------------------------------------------------------------

    @Test
    public void flatShape_parses() throws Exception {
        JSONObject food = foodWith(nutrients(
                flatEntry(1003, 3.57, "G"),
                flatEntry(1093, 354, "MG")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertEquals(424242L, profile.fdcId);
        assertEquals(3.57, profile.per100.get("protein").doubleValue(), EPS);
        assertEquals(354.0, profile.per100.get("sodium").doubleValue(), EPS);
    }

    @Test
    public void nestedShape_parses() throws Exception {
        JSONObject food = foodWith(nutrients(
                nestedEntry(1003, 3.57, "G"),
                nestedEntry(1093, 354, "MG")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertEquals(3.57, profile.per100.get("protein").doubleValue(), EPS);
        assertEquals(354.0, profile.per100.get("sodium").doubleValue(), EPS);
    }

    @Test
    public void mixedShapes_inOneFood_parse() throws Exception {
        JSONObject food = foodWith(nutrients(
                flatEntry(1003, 3.57, "G"),
                nestedEntry(1093, 354, "MG")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertTrue(profile.reported("protein"));
        assertTrue(profile.reported("sodium"));
        assertEquals(2, profile.per100.size());
    }

    // ------------------------------------------------------------------
    // Unit reconciliation (§3)
    // ------------------------------------------------------------------

    @Test
    public void milligrams_forGramDefinedNutrient_convert() throws Exception {
        // Protein is defined in g; 3570 MG is 3.57 g.
        JSONObject food = foodWith(nutrients(flatEntry(1003, 3570, "MG")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertEquals(3.57, profile.per100.get("protein").doubleValue(), EPS);
    }

    @Test
    public void grams_forMilligramDefinedNutrient_convert() throws Exception {
        // Sodium is defined in mg; 0.354 G is 354 mg.
        JSONObject food = foodWith(nutrients(flatEntry(1093, 0.354, "G")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertEquals(354.0, profile.per100.get("sodium").doubleValue(), EPS);
    }

    @Test
    public void kcal_matchesKcalDefinition() throws Exception {
        JSONObject food = foodWith(nutrients(flatEntry(1008, 146, "KCAL")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertEquals(146.0, profile.per100.get("energy").doubleValue(), EPS);
    }

    @Test
    public void unitNames_areCaseInsensitive() throws Exception {
        JSONObject food = foodWith(nutrients(
                flatEntry(1003, 3.57, "g"),
                flatEntry(1093, 354, "mg"),
                nestedEntry(1008, 146, "kcal")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertEquals(3.57, profile.per100.get("protein").doubleValue(), EPS);
        assertEquals(354.0, profile.per100.get("sodium").doubleValue(), EPS);
        assertEquals(146.0, profile.per100.get("energy").doubleValue(), EPS);
    }

    @Test
    public void irreconcilableUnit_iu_isDroppedNeverGuessed()
            throws Exception {
        JSONObject food = foodWith(nutrients(flatEntry(1003, 500, "IU")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertFalse(profile.reported("protein"));
        assertTrue(profile.per100.isEmpty());
    }

    @Test
    public void mismatchedDimension_kcalForGramNutrient_isDropped()
            throws Exception {
        JSONObject food = foodWith(nutrients(flatEntry(1003, 146, "KCAL")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertFalse(profile.reported("protein"));
    }

    @Test
    public void mismatchedDimension_gramsForKcalNutrient_isDropped()
            throws Exception {
        JSONObject food = foodWith(nutrients(flatEntry(1008, 12, "G")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertFalse(profile.reported("energy"));
    }

    @Test
    public void missingUnit_isDropped() throws Exception {
        JSONObject entry = new JSONObject();
        entry.put("nutrientId", 1003);
        entry.put("value", 3.57);
        NutritionProfile profile = UsdaNutritionParser.parse(
                foodWith(nutrients(entry)), config());
        assertFalse(profile.reported("protein"));
    }

    @Test
    public void unconfiguredNutrient_isIgnored() throws Exception {
        // 1087 (calcium) is not in the §1.2 configuration.
        JSONObject food = foodWith(nutrients(
                flatEntry(1087, 87, "MG"),
                flatEntry(1003, 3.57, "G")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertEquals(1, profile.per100.size());
        assertTrue(profile.reported("protein"));
    }

    // ------------------------------------------------------------------
    // Duplicates, missing values (§3, §2)
    // ------------------------------------------------------------------

    @Test
    public void duplicateEntries_firstReportedWins() throws Exception {
        JSONObject food = foodWith(nutrients(
                flatEntry(1003, 5.0, "G"),
                flatEntry(1003, 9.0, "G")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertEquals(5.0, profile.per100.get("protein").doubleValue(), EPS);
        assertEquals(1, profile.per100.size());
    }

    @Test
    public void duplicateEntries_acrossShapes_firstReportedWins()
            throws Exception {
        JSONObject food = foodWith(nutrients(
                nestedEntry(1093, 300, "MG"),
                flatEntry(1093, 900, "MG")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertEquals(300.0, profile.per100.get("sodium").doubleValue(), EPS);
    }

    @Test
    public void missingNutrients_stayAbsent_neverZero() throws Exception {
        JSONObject food = foodWith(nutrients(flatEntry(1003, 3.57, "G")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertFalse(profile.reported("sodium"));
        assertFalse(profile.per100.containsKey("sodium"));
        assertNull(profile.per100.get("sodium"));
        assertNull(profile.perServing("sodium"));
    }

    @Test
    public void reportedZero_isARealReportedValue() throws Exception {
        // A reported 0.0 is data (e.g. total_fat 0.0 in the bread fixture);
        // only *missing* nutrients are absent.
        JSONObject food = foodWith(nutrients(flatEntry(1004, 0.0, "G")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertTrue(profile.reported("total_fat"));
        assertEquals(0.0, profile.per100.get("total_fat").doubleValue(), EPS);
    }

    // ------------------------------------------------------------------
    // Serving conversion (§2.1)
    // ------------------------------------------------------------------

    @Test
    public void servingConversion_gramsServing() throws Exception {
        JSONObject food = foodWith(nutrients(flatEntry(1003, 10.0, "G")));
        food.put("servingSize", 28);
        food.put("servingSizeUnit", "g");
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertEquals(28.0, profile.servingSize.doubleValue(), EPS);
        assertEquals(10.0 * 0.28,
                profile.perServing("protein").doubleValue(), EPS);
    }

    @Test
    public void servingConversion_longFormGramsNormalizes() throws Exception {
        JSONObject food = foodWith(nutrients(flatEntry(1003, 10.0, "G")));
        food.put("servingSize", 50);
        food.put("servingSizeUnit", "grams");
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertEquals("g", profile.basis);
        assertEquals(5.0, profile.perServing("protein").doubleValue(), EPS);
    }

    @Test
    public void servingConversion_millilitersBasis() throws Exception {
        JSONObject food = foodWith(nutrients(flatEntry(1093, 100, "MG")));
        food.put("servingSize", 240);
        food.put("servingSizeUnit", "ml");
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertEquals("ml", profile.basis);
        assertEquals(240.0,
                profile.perServing("sodium").doubleValue(), EPS);
    }

    @Test
    public void servingConversion_ouncesUnit_perServingNull()
            throws Exception {
        // "oz" does not normalize to the basis: per-100 only (§2.1).
        JSONObject food = foodWith(nutrients(flatEntry(1003, 10.0, "G")));
        food.put("servingSize", 28);
        food.put("servingSizeUnit", "oz");
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertEquals("g", profile.basis);
        assertNull(profile.perServing("protein"));
        // The per-100 value itself is unaffected.
        assertEquals(10.0, profile.per100.get("protein").doubleValue(), EPS);
    }

    @Test
    public void servingConversion_householdOnly_perServingNull()
            throws Exception {
        // Household text but no numeric servingSize: nothing to scale by.
        JSONObject food = foodWith(nutrients(flatEntry(1003, 10.0, "G")));
        food.put("householdServingFullText", "1 slice");
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertEquals("1 slice", profile.householdServing);
        assertNull(profile.servingSize);
        assertNull(profile.perServing("protein"));
    }

    @Test
    public void servingConversion_noServingInfo_perServingNull()
            throws Exception {
        JSONObject food = foodWith(nutrients(flatEntry(1003, 10.0, "G")));
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertNull(profile.servingSize);
        assertEquals("", profile.servingUnit);
        assertEquals("", profile.householdServing);
        assertNull(profile.perServing("protein"));
    }

    // ------------------------------------------------------------------
    // Malformed input (§3: never throws)
    // ------------------------------------------------------------------

    @Test
    public void malformed_noFoodNutrients_emptyProfile() throws Exception {
        JSONObject food = new JSONObject();
        food.put("fdcId", 777);
        food.put("description", "MYSTERY FOOD");
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertNotNull(profile);
        assertEquals(777L, profile.fdcId);
        assertTrue(profile.per100.isEmpty());
        assertFalse(profile.reported("protein"));
    }

    @Test
    public void malformed_emptyFoodObject_emptyProfile() throws Exception {
        NutritionProfile profile =
                UsdaNutritionParser.parse(new JSONObject(), config());
        assertNotNull(profile);
        assertEquals(0L, profile.fdcId);
        assertEquals("g", profile.basis);
        assertTrue(profile.per100.isEmpty());
        assertNull(profile.servingSize);
    }

    @Test
    public void malformed_nullNutrientFields_entriesSkipped()
            throws Exception {
        JSONArray entries = new JSONArray();
        entries.put(new JSONObject());                       // no fields at all
        JSONObject noValue = new JSONObject();
        noValue.put("nutrientId", 1003);                     // id but no value
        entries.put(noValue);
        JSONObject nullValue = new JSONObject();
        nullValue.put("nutrientId", 1093);
        nullValue.put("value", JSONObject.NULL);
        nullValue.put("unitName", "MG");
        entries.put(nullValue);
        JSONObject emptyNested = new JSONObject();
        emptyNested.put("nutrient", new JSONObject());       // nested, no id
        entries.put(emptyNested);
        entries.put(flatEntry(1003, 3.57, "G"));             // one good entry

        NutritionProfile profile = UsdaNutritionParser.parse(
                foodWith(entries), config());
        assertEquals(1, profile.per100.size());
        assertEquals(3.57, profile.per100.get("protein").doubleValue(), EPS);
    }

    @Test
    public void malformed_nullFoodNutrientsValue_emptyProfile()
            throws Exception {
        JSONObject food = new JSONObject();
        food.put("fdcId", 555);
        food.put("foodNutrients", JSONObject.NULL);
        NutritionProfile profile =
                UsdaNutritionParser.parse(food, config());
        assertEquals(555L, profile.fdcId);
        assertTrue(profile.per100.isEmpty());
    }

    @Test
    public void malformed_nullFood_emptyProfile() throws Exception {
        NutritionProfile profile =
                UsdaNutritionParser.parse(null, config());
        assertNotNull(profile);
        assertEquals(0L, profile.fdcId);
        assertTrue(profile.per100.isEmpty());
    }

    // ------------------------------------------------------------------
    // Profile invariants (§2)
    // ------------------------------------------------------------------

    @Test
    public void per100Map_isUnmodifiable() throws Exception {
        NutritionProfile profile = UsdaNutritionParser.parse(
                foodWith(nutrients(flatEntry(1003, 3.57, "G"))), config());
        try {
            profile.per100.put("sodium", 1.0);
            fail("per100 must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // immutable per Contract §2
        }
    }
}
