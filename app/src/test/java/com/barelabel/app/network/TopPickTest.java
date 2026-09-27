package com.barelabel.app.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.barelabel.app.search.UsdaSpamFilter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * UsdaApiClient.selectTopPick (pure selection extracted from searchPrimary):
 * first ranked non-spam hit with non-blank ingredients, else first ranked
 * non-spam hit, else the raw first hit.
 */
public class TopPickTest {

    /** ingredients == null means the key is absent (treated as empty). */
    private static JSONObject food(String owner, String desc, String ingredients)
            throws Exception {
        JSONObject o = new JSONObject();
        o.put("brandOwner", owner);
        o.put("description", desc);
        if (ingredients != null) {
            o.put("ingredients", ingredients);
        }
        return o;
    }

    private static JSONObject pick(JSONArray foods, String query) {
        return UsdaApiClient.selectTopPick(
                foods, UsdaSpamFilter.rankedCandidates(foods, query));
    }

    private static JSONArray fixtureFoods() throws Exception {
        InputStream is = TopPickTest.class
                .getResourceAsStream("/usda_bread_2026-09-26.json");
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

    @Test
    public void fixtureBread_pickIsSane() throws Exception {
        JSONArray foods = fixtureFoods();
        JSONObject p = pick(foods, "bread");
        assertFalse(UsdaSpamFilter.isSpamBrand(p.optString("brandOwner", "")));
        assertTrue(!p.optString("ingredients", "").trim().isEmpty());
        assertTrue((" " + p.optString("description", "").toLowerCase() + " ")
                .contains(" bread "));
    }

    @Test
    public void spamWithIngredients_skippedForCleanWithIngredients()
            throws Exception {
        JSONArray foods = new JSONArray();
        foods.put(food("Multicom Publishing Incorporated", "BREAD", "WHEAT FLOUR, WATER"));
        foods.put(food("Real Bakery", "BREAD", "WHEAT FLOUR, WATER"));
        assertEquals("Real Bakery", pick(foods, "bread").optString("brandOwner"));
    }

    @Test
    public void noCleanWithIngredients_firstCleanHit() throws Exception {
        JSONArray foods = new JSONArray();
        foods.put(food("Multicom Publishing Incorporated", "BREAD", "WHEAT FLOUR"));
        foods.put(food("Real Bakery", "BREAD", "   "));
        assertEquals("Real Bakery", pick(foods, "bread").optString("brandOwner"));
    }

    @Test
    public void allSpam_rawFirstHit() throws Exception {
        JSONArray foods = new JSONArray();
        foods.put(food("Multicom Publishing Incorporated", "BREAD", "FLOUR"));
        foods.put(food("Acme Software LLC", "BREAD", "FLOUR"));
        assertEquals("Multicom Publishing Incorporated",
                pick(foods, "bread").optString("brandOwner"));
    }

    @Test
    public void wholeWordBeatsEarlierSubstring() throws Exception {
        JSONArray foods = new JSONArray();
        foods.put(food("Seafood Co", "BREADED SHRIMP", "FLOUR"));
        foods.put(food("Bakery Co", "RYE BREAD", "FLOUR"));
        assertEquals("RYE BREAD", pick(foods, "bread").optString("description"));
    }

    @Test
    public void singleFood_returnsIt() throws Exception {
        JSONArray foods = new JSONArray();
        foods.put(food("Real Bakery", "BREAD", "FLOUR"));
        assertEquals("Real Bakery", pick(foods, "bread").optString("brandOwner"));
    }

    @Test
    public void whitespaceIngredients_treatedAsEmpty() throws Exception {
        JSONArray foods = new JSONArray();
        foods.put(food("Bakery Co", "BREAD A", "   "));
        foods.put(food("Bakery Co", "BREAD B", "FLOUR"));
        assertEquals("BREAD B", pick(foods, "bread").optString("description"));
    }

    @Test
    public void missingIngredientsKey_treatedAsEmpty() throws Exception {
        JSONArray foods = new JSONArray();
        foods.put(food("A", "BREAD", null));
        foods.put(food("Real Bakery", "BREAD", "FLOUR"));
        assertEquals("Real Bakery", pick(foods, "bread").optString("brandOwner"));
    }

    @Test
    public void emptyFoods_returnsNull() {
        assertNull(UsdaApiClient.selectTopPick(
                new JSONArray(), new ArrayList<JSONObject>()));
    }

    @Test
    public void nullFoods_returnsNull() {
        assertNull(UsdaApiClient.selectTopPick(null, new ArrayList<JSONObject>()));
    }

    @Test
    public void missingOwner_notSpamSoEligible() throws Exception {
        JSONArray foods = new JSONArray();
        JSONObject noOwner = new JSONObject();
        noOwner.put("description", "BREAD");
        noOwner.put("ingredients", "FLOUR");
        foods.put(noOwner);
        assertEquals("BREAD", pick(foods, "bread").optString("description"));
    }

    @Test
    public void twoWholeWordClean_firstRankedWins() throws Exception {
        JSONArray foods = new JSONArray();
        foods.put(food("First Bakery", "BREAD ONE", "FLOUR"));
        foods.put(food("Second Bakery", "BREAD TWO", "FLOUR"));
        assertEquals("First Bakery", pick(foods, "bread").optString("brandOwner"));
    }
}
