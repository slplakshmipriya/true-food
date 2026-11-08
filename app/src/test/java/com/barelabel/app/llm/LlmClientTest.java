package com.barelabel.app.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import com.barelabel.app.model.ProductResult;
import com.barelabel.app.nutrition.NutrientComparison;
import com.barelabel.app.nutrition.NutrientDefinition;
import com.barelabel.app.nutrition.NutritionAnalysis;
import com.barelabel.app.nutrition.NutritionConfig;
import com.barelabel.app.nutrition.NutritionStatus;
import com.barelabel.app.nutrition.RankingProfile;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM tests for the llm package: response parsing and payload building.
 * Network calls themselves are not tested (null-safe by contract).
 */
public class LlmClientTest {

    @Test
    public void parseContentExtractsAssistantText() {
        String json = "{\"id\":\"chatcmpl-1\",\"choices\":[{\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"Hello \\\"there\\\"\"}}]}";
        assertEquals("Hello \"there\"", GroqClient.parseContent(json));
    }

    @Test
    public void parseContentHandlesNullAndMalformed() {
        assertNull(GroqClient.parseContent(null));
        assertNull(GroqClient.parseContent("{}"));
        assertNull(GroqClient.parseContent(
                "{\"choices\":[{\"message\":{\"content\":null}}]}"));
    }

    @Test
    public void jsonEscapeHandlesQuotesAndNewlines() {
        assertEquals("a\\\"b\\\\c\\nd", GroqClient.jsonEscape("a\"b\\c\nd"));
    }

    @Test
    public void buildPayloadHasExpectedShape() {
        String payload = VerdictExplainer.buildPayload(false, 79,
                Arrays.asList(new VerdictExplainer.Flag("all purpose flour",
                        "Gluten")),
                "all purpose flour, yogurt",
                Arrays.asList("gluten", "dairy"),
                Collections.emptyList(),
                "processed near wheat");
        assertTrue(payload.contains("\"verdict\":\"DIRTY\""));
        assertTrue(payload.contains("\"clean_score\":79"));
        assertTrue(payload.contains(
                "\"ingredient\":\"all purpose flour\",\"category\":\"Gluten\""));
        assertTrue(payload.contains("\"filters\":[\"gluten\",\"dairy\"]"));
        assertTrue(payload.contains(
                "\"allergen_advice\":\"processed near wheat\""));
    }

    @Test
    public void buildPayloadToleratesNulls() {
        String payload = VerdictExplainer.buildPayload(true, 95, null, null,
                null, null, null);
        assertTrue(payload.contains("\"verdict\":\"CLEAN\""));
        assertTrue(payload.contains("\"flagged\":[]"));
    }

    @Test
    public void promptsAreNonEmpty() {
        assertTrue(LlmPrompts.EXPLAIN_SYSTEM.length() > 100);
        assertTrue(LlmPrompts.SPELLCHECK_SYSTEM.length() > 100);
    }

    // ---- NUT-10: nutrition-aware payload v2 (Contract §8) -------------

    private static NutrientDefinition nutrientDef(String key, String label,
                                                  String unit) {
        return new NutrientDefinition(key,
                Collections.singletonList(9999), label, unit, "higher_bad",
                true, true, true, 0.10, key, 0.0, 5000.0, 0.0, 100.0,
                85.0, 10.0, 500.0, null, null, null, null);
    }

    private static NutritionConfig nutritionConfig(double ingredientWeight,
            double nutritionWeight, NutrientDefinition... defs) {
        Map<String, NutrientDefinition> byKey = new LinkedHashMap<>();
        for (NutrientDefinition def : defs) {
            byKey.put(def.key, def);
        }
        return new NutritionConfig(1, ingredientWeight, nutritionWeight,
                10, 20, 3, 5, 0.50, 0.65, 0.35, 3, 60, byKey,
                Collections.<String, RankingProfile>emptyMap());
    }

    private static NutrientComparison comparison(String key, Double per100,
            int peerCount, Double peerMedian, Double rawPercentile,
            Double favorablePercentile, boolean flagged, String flagReason) {
        return new NutrientComparison(key, per100, null, peerCount,
                peerMedian, rawPercentile, favorablePercentile, null, null,
                flagged, flagReason);
    }

    private static NutritionAnalysis analysis(NutritionStatus status,
            Integer nutritionScore,
            Map<String, NutrientComparison> comparisons,
            List<String> flaggedNutrients) {
        return new NutritionAnalysis(status, nutritionScore, 0.9, "Snacks",
                21, comparisons, flaggedNutrients);
    }

    private static ProductResult productWithFlagged(String... flagged) {
        ProductResult product = new ProductResult();
        product.found = true;
        product.name = "Potato Chips";
        product.flagged.addAll(Arrays.asList(flagged));
        return product;
    }

    /** Contract §8 example shape: DIRTY ingredients, CONCERNING nutrition. */
    private static NutritionAnalysis concerningAnalysis() {
        Map<String, NutrientComparison> comparisons = new LinkedHashMap<>();
        comparisons.put("sodium", comparison("sodium", 680.0, 21, 410.0,
                85.0, 15.0, true, "relative_floor"));
        comparisons.put("fiber", comparison("fiber", 7.0, 21, 3.2,
                90.0, 90.0, false, ""));
        return analysis(NutritionStatus.CONCERNING, 38, comparisons,
                Collections.singletonList("sodium"));
    }

    private static NutritionConfig sodiumFiberConfig() {
        return nutritionConfig(0.70, 0.30,
                nutrientDef("sodium", "Sodium", "mg"),
                nutrientDef("fiber", "Fiber", "g"));
    }

    @Test
    public void buildNutritionPayloadHasV2Shape() throws Exception {
        String payload = VerdictExplainer.buildNutritionPayload(
                productWithFlagged("soy lecithin"), 67, concerningAnalysis(),
                58,
                Arrays.asList(new VerdictExplainer.Flag("soy lecithin",
                        "Emulsifiers")),
                "potato, soy lecithin", Arrays.asList("additives"),
                Collections.<String>emptyList(), "", sodiumFiberConfig());
        JSONObject root = new JSONObject(payload);
        assertEquals("DIRTY", root.getString("ingredient_verdict"));
        assertEquals(67, root.getInt("ingredient_score"));
        assertEquals("CONCERNING", root.getString("nutrition_status"));
        assertEquals(38, root.getInt("nutrition_score"));
        assertEquals(58, root.getInt("overall_score"));
        JSONObject blend = root.getJSONObject("blend");
        assertEquals(0.70, blend.getDouble("ingredient_weight"), 0.0001);
        assertEquals(0.30, blend.getDouble("nutrition_weight"), 0.0001);
        JSONArray flagged = root.getJSONArray("flagged");
        assertEquals(1, flagged.length());
        assertEquals("soy lecithin",
                flagged.getJSONObject(0).getString("ingredient"));
        assertEquals("Emulsifiers",
                flagged.getJSONObject(0).getString("category"));
        JSONArray flaggedNutrients = root.getJSONArray("flagged_nutrients");
        assertEquals(1, flaggedNutrients.length());
        JSONObject sodium = flaggedNutrients.getJSONObject(0);
        assertEquals("sodium", sodium.getString("key"));
        assertEquals("Sodium", sodium.getString("label"));
        assertEquals(680.0, sodium.getDouble("amount_per_100"), 0.0001);
        assertEquals("mg", sodium.getString("unit"));
        assertEquals(85.0, sodium.getDouble("raw_percentile"), 0.0001);
        assertEquals(15.0, sodium.getDouble("favorable_percentile"), 0.0001);
        assertEquals(21, sodium.getInt("peer_count"));
        assertEquals(410.0, sodium.getDouble("peer_median"), 0.0001);
        assertEquals("relative_floor", sodium.getString("flag_reason"));
        assertEquals("potato, soy lecithin", root.getString("ingredients"));
        assertEquals("additives",
                root.getJSONArray("filters").getString(0));
    }

    @Test
    public void buildNutritionPayloadBlendWeightsComeFromConfig()
            throws Exception {
        NutritionConfig config = nutritionConfig(0.60, 0.40,
                nutrientDef("sodium", "Sodium", "mg"),
                nutrientDef("fiber", "Fiber", "g"));
        String payload = VerdictExplainer.buildNutritionPayload(
                productWithFlagged(), 90, concerningAnalysis(), 85,
                Collections.<VerdictExplainer.Flag>emptyList(), "oats",
                Collections.<String>emptyList(),
                Collections.<String>emptyList(), "", config);
        JSONObject blend = new JSONObject(payload).getJSONObject("blend");
        assertEquals(0.60, blend.getDouble("ingredient_weight"), 0.0001);
        assertEquals(0.40, blend.getDouble("nutrition_weight"), 0.0001);
    }

    @Test
    public void buildNutritionPayloadSafeStatus() throws Exception {
        Map<String, NutrientComparison> comparisons = new LinkedHashMap<>();
        comparisons.put("sodium", comparison("sodium", 140.0, 21, 410.0,
                5.0, 95.0, false, ""));
        NutritionAnalysis safe = analysis(NutritionStatus.SAFE, 82,
                comparisons, Collections.<String>emptyList());
        String payload = VerdictExplainer.buildNutritionPayload(
                productWithFlagged(), 95, safe, 91,
                Collections.<VerdictExplainer.Flag>emptyList(), "lentils",
                Collections.<String>emptyList(),
                Collections.<String>emptyList(), "", sodiumFiberConfig());
        JSONObject root = new JSONObject(payload);
        assertEquals("CLEAN", root.getString("ingredient_verdict"));
        assertEquals("SAFE", root.getString("nutrition_status"));
        assertEquals(82, root.getInt("nutrition_score"));
        assertEquals(0, root.getJSONArray("flagged_nutrients").length());
    }

    @Test
    public void buildNutritionPayloadHighlightsCappedAtFour()
            throws Exception {
        Map<String, NutrientComparison> comparisons = new LinkedHashMap<>();
        String[] keys = {"n1", "n2", "n3", "n4", "n5", "n6"};
        double[] favorables = {10.0, 20.0, 25.0, 75.0, 80.0, 90.0};
        NutrientDefinition[] defs = new NutrientDefinition[keys.length + 1];
        for (int i = 0; i < keys.length; i++) {
            comparisons.put(keys[i], comparison(keys[i], 10.0, 21, 5.0,
                    favorables[i], favorables[i], false, ""));
            defs[i] = nutrientDef(keys[i], "Label " + keys[i], "g");
        }
        comparisons.put("mid", comparison("mid", 10.0, 21, 5.0, 50.0, 50.0,
                false, ""));
        defs[keys.length] = nutrientDef("mid", "Mid", "g");
        NutritionAnalysis a = analysis(NutritionStatus.CONCERNING, 40,
                comparisons, Collections.<String>emptyList());
        String payload = VerdictExplainer.buildNutritionPayload(
                productWithFlagged(), 80, a, 68,
                Collections.<VerdictExplainer.Flag>emptyList(), "oats",
                Collections.<String>emptyList(),
                Collections.<String>emptyList(), "",
                nutritionConfig(0.70, 0.30, defs));
        JSONArray highlights =
                new JSONObject(payload).getJSONArray("nutrient_highlights");
        assertEquals(4, highlights.length());
        for (int i = 0; i < 4; i++) {
            assertEquals(keys[i], highlights.getJSONObject(i).getString("key"));
        }
    }

    @Test
    public void buildNutritionPayloadHighlightsOmittedWhenNoneQualify()
            throws Exception {
        Map<String, NutrientComparison> comparisons = new LinkedHashMap<>();
        comparisons.put("sodium", comparison("sodium", 400.0, 21, 410.0,
                50.0, 50.0, false, ""));
        NutritionAnalysis a = analysis(NutritionStatus.SAFE, 70, comparisons,
                Collections.<String>emptyList());
        String payload = VerdictExplainer.buildNutritionPayload(
                productWithFlagged(), 90, a, 84,
                Collections.<VerdictExplainer.Flag>emptyList(), "oats",
                Collections.<String>emptyList(),
                Collections.<String>emptyList(), "", sodiumFiberConfig());
        assertFalse(payload.contains("nutrient_highlights"));
    }

    @Test
    public void buildNutritionPayloadUnavailableFallsBackToV1() {
        List<VerdictExplainer.Flag> flags =
                Collections.<VerdictExplainer.Flag>emptyList();
        NutritionAnalysis unavailable = analysis(
                NutritionStatus.UNAVAILABLE, null,
                Collections.<String, NutrientComparison>emptyMap(),
                Collections.<String>emptyList());
        String payload = VerdictExplainer.buildNutritionPayload(
                productWithFlagged(), 95, unavailable, 95, flags, "oats",
                Arrays.asList("gluten"), Collections.<String>emptyList(),
                "", sodiumFiberConfig());
        String v1 = VerdictExplainer.buildPayload(true, 95, flags, "oats",
                Arrays.asList("gluten"), Collections.<String>emptyList(),
                "");
        assertEquals(v1, payload);
        assertFalse(payload.contains("nutrition_status"));
        assertFalse(payload.contains("flagged_nutrients"));
        assertFalse(payload.contains("blend"));
    }

    @Test
    public void buildNutritionPayloadNullAnalysisFallsBackToV1() {
        List<VerdictExplainer.Flag> flags = Arrays.asList(
                new VerdictExplainer.Flag("soy lecithin", "Emulsifiers"));
        String payload = VerdictExplainer.buildNutritionPayload(
                productWithFlagged("soy lecithin"), 67, null, 67, flags,
                "potato, soy lecithin", Collections.<String>emptyList(),
                Collections.<String>emptyList(), "", sodiumFiberConfig());
        String v1 = VerdictExplainer.buildPayload(false, 67, flags,
                "potato, soy lecithin", Collections.<String>emptyList(),
                Collections.<String>emptyList(), "");
        assertEquals(v1, payload);
        assertFalse(payload.contains("nutrition_status"));
    }

    @Test
    public void buildNutritionPayloadNullConfigFallsBackToV1() {
        List<VerdictExplainer.Flag> flags = Arrays.asList(
                new VerdictExplainer.Flag("soy lecithin", "Emulsifiers"));
        String payload = VerdictExplainer.buildNutritionPayload(
                productWithFlagged("soy lecithin"), 67, concerningAnalysis(),
                58, flags, "potato, soy lecithin",
                Collections.<String>emptyList(),
                Collections.<String>emptyList(), "", null);
        String v1 = VerdictExplainer.buildPayload(false, 67, flags,
                "potato, soy lecithin", Collections.<String>emptyList(),
                Collections.<String>emptyList(), "");
        assertEquals(v1, payload);
        assertFalse(payload.contains("nutrition_status"));
    }

    @Test
    public void buildNutritionPayloadSevereFlagOmitsPercentilePeerFields()
            throws Exception {
        Map<String, NutrientComparison> comparisons = new LinkedHashMap<>();
        comparisons.put("sodium", comparison("sodium", 2500.0, 2, null,
                null, null, true, "severe"));
        NutritionAnalysis a = analysis(NutritionStatus.CONCERNING, null,
                comparisons, Collections.singletonList("sodium"));
        String payload = VerdictExplainer.buildNutritionPayload(
                productWithFlagged(), 80, a, 80,
                Collections.<VerdictExplainer.Flag>emptyList(), "chips",
                Collections.<String>emptyList(),
                Collections.<String>emptyList(), "", sodiumFiberConfig());
        JSONObject root = new JSONObject(payload);
        assertFalse(root.has("nutrition_score"));
        JSONObject sodium =
                root.getJSONArray("flagged_nutrients").getJSONObject(0);
        assertEquals(2500.0, sodium.getDouble("amount_per_100"), 0.0001);
        assertEquals("severe", sodium.getString("flag_reason"));
        assertFalse(sodium.has("raw_percentile"));
        assertFalse(sodium.has("favorable_percentile"));
        assertFalse(sodium.has("peer_count"));
        assertFalse(sodium.has("peer_median"));
    }

    @Test
    public void buildNutritionPayloadEscapesQuotesAndBackslashes()
            throws Exception {
        String weirdLabel = "Sodium \"Salt\" \\ Mix";
        NutritionConfig config = nutritionConfig(0.70, 0.30,
                nutrientDef("sodium", weirdLabel, "mg"));
        Map<String, NutrientComparison> comparisons = new LinkedHashMap<>();
        comparisons.put("sodium", comparison("sodium", 680.0, 21, 410.0,
                85.0, 15.0, true, "relative_floor"));
        NutritionAnalysis a = analysis(NutritionStatus.CONCERNING, 38,
                comparisons, Collections.singletonList("sodium"));
        String weirdIngredient = "soy \"lecithin\" \\ x";
        String payload = VerdictExplainer.buildNutritionPayload(
                productWithFlagged(weirdIngredient), 67, a, 58,
                Arrays.asList(new VerdictExplainer.Flag(weirdIngredient,
                        "Emulsifiers")),
                "potato", Collections.<String>emptyList(),
                Collections.<String>emptyList(), "", config);
        JSONObject root = new JSONObject(payload);
        assertEquals(weirdLabel, root.getJSONArray("flagged_nutrients")
                .getJSONObject(0).getString("label"));
        assertEquals(weirdIngredient, root.getJSONArray("flagged")
                .getJSONObject(0).getString("ingredient"));
    }

    @Test
    public void buildNutritionPayloadToleratesAllNulls() {
        String payload = VerdictExplainer.buildNutritionPayload(null, 95,
                null, 95, null, null, null, null, null, null);
        assertEquals(VerdictExplainer.buildPayload(true, 95, null, null,
                null, null, null), payload);
        assertTrue(payload.contains("\"verdict\":\"CLEAN\""));
        assertTrue(payload.contains("\"flagged\":[]"));
    }

    @Test
    public void buildNutritionPayloadNullFlaggedListWithValidAnalysis()
            throws Exception {
        Map<String, NutrientComparison> comparisons = new LinkedHashMap<>();
        comparisons.put("sodium", comparison("sodium", 140.0, 21, 410.0,
                5.0, 95.0, false, ""));
        NutritionAnalysis safe = analysis(NutritionStatus.SAFE, 82,
                comparisons, Collections.<String>emptyList());
        String payload = VerdictExplainer.buildNutritionPayload(
                productWithFlagged(), 95, safe, 91, null, null, null, null,
                null, sodiumFiberConfig());
        JSONObject root = new JSONObject(payload);
        assertEquals("CLEAN", root.getString("ingredient_verdict"));
        assertEquals("SAFE", root.getString("nutrition_status"));
        assertEquals(0, root.getJSONArray("flagged").length());
    }

    @Test
    public void nutritionPromptCarriesContractPhrases() {
        assertTrue(LlmPrompts.EXPLAIN_NUTRITION_SYSTEM
                .contains("two deterministic verdicts"));
        assertTrue(LlmPrompts.EXPLAIN_NUTRITION_SYSTEM
                .contains("under 110 words"));
        assertTrue(LlmPrompts.EXPLAIN_NUTRITION_SYSTEM
                .contains("Never infer a missing value"));
        assertTrue(LlmPrompts.EXPLAIN_NUTRITION_SYSTEM
                .contains("Make no medical or disease claims"));
    }
}
