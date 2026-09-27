package com.barelabel.app.network;

import static org.junit.Assert.assertEquals;

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
 * UsdaApiClient.majorityFoodCategory: query-level category consensus.
 *
 * User issue covered: searching "bread" titled the panel
 * "Clean choices in Cookies & Biscuits" because the single top hit (a
 * CONCHAS "BREAD") is filed by USDA under "Cookies & Biscuits". The vote
 * must outvote that one miscategorized record.
 */
public class MajorityCategoryTest {

    private static JSONObject food(String owner, String category) throws Exception {
        JSONObject o = new JSONObject();
        o.put("brandOwner", owner);
        o.put("description", "X");
        if (category != null) {
            o.put("foodCategory", category);
        }
        return o;
    }

    private static List<JSONObject> asList(JSONArray arr) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            out.add(arr.getJSONObject(i));
        }
        return out;
    }

    private static JSONArray fixtureFoods() throws Exception {
        InputStream is = MajorityCategoryTest.class
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
    public void issue_breadFixture_votesBreadsAndBuns() throws Exception {
        JSONArray foods = fixtureFoods();
        List<JSONObject> ranked = UsdaSpamFilter.rankedCandidates(foods, "bread");
        assertEquals("Breads & Buns", UsdaApiClient.majorityFoodCategory(ranked));
    }

    @Test
    public void singleCandidate_returnsItsCategory() throws Exception {
        List<JSONObject> ranked = new ArrayList<>();
        ranked.add(food("Bakery", "Dairy"));
        assertEquals("Dairy", UsdaApiClient.majorityFoodCategory(ranked));
    }

    @Test
    public void tie_breaksByRankOrder() throws Exception {
        List<JSONObject> ranked = new ArrayList<>();
        ranked.add(food("a", "X"));
        ranked.add(food("b", "Y"));
        assertEquals("X", UsdaApiClient.majorityFoodCategory(ranked));
    }

    @Test
    public void allEmpty_returnsEmpty() throws Exception {
        List<JSONObject> ranked = new ArrayList<>();
        ranked.add(food("a", ""));
        ranked.add(food("b", "  "));
        assertEquals("", UsdaApiClient.majorityFoodCategory(ranked));
    }

    @Test
    public void spamBrands_excludedFromVote() throws Exception {
        List<JSONObject> ranked = new ArrayList<>();
        ranked.add(food("Publishing Co", "SpamCat"));
        ranked.add(food("Real Foods", "RealCat"));
        assertEquals("RealCat", UsdaApiClient.majorityFoodCategory(ranked));
    }

    @Test
    public void spamOnly_returnsEmpty() throws Exception {
        List<JSONObject> ranked = new ArrayList<>();
        ranked.add(food("Publishing Co", "SpamCat"));
        assertEquals("", UsdaApiClient.majorityFoodCategory(ranked));
    }

    @Test
    public void emptyCategories_skipped() throws Exception {
        List<JSONObject> ranked = new ArrayList<>();
        ranked.add(food("a", ""));
        ranked.add(food("b", "Milk"));
        ranked.add(food("c", ""));
        assertEquals("Milk", UsdaApiClient.majorityFoodCategory(ranked));
    }

    @Test
    public void categoryWhitespace_trimmed() throws Exception {
        List<JSONObject> ranked = new ArrayList<>();
        ranked.add(food("a", "  Breads & Buns  "));
        assertEquals("Breads & Buns", UsdaApiClient.majorityFoodCategory(ranked));
    }

    @Test
    public void missingCategoryKey_treatedAsEmpty() throws Exception {
        List<JSONObject> ranked = new ArrayList<>();
        ranked.add(food("a", null));
        assertEquals("", UsdaApiClient.majorityFoodCategory(ranked));
    }

    @Test
    public void emptyList_returnsEmpty() {
        assertEquals("", UsdaApiClient.majorityFoodCategory(new ArrayList<JSONObject>()));
    }

    @Test
    public void cap_votesBeyond15Ignored() throws Exception {
        // 8xA + 7xB inside the first 15 -> A wins, even though 5 more B follow.
        List<JSONObject> ranked = new ArrayList<>();
        for (int i = 0; i < 8; i++) ranked.add(food("x", "A"));
        for (int i = 0; i < 7; i++) ranked.add(food("x", "B"));
        for (int i = 0; i < 5; i++) ranked.add(food("x", "B"));
        assertEquals("A", UsdaApiClient.majorityFoodCategory(ranked));
    }

    @Test
    public void cap_exactly15Considered() throws Exception {
        List<JSONObject> ranked = new ArrayList<>();
        for (int i = 0; i < 15; i++) ranked.add(food("x", "A"));
        for (int i = 0; i < 10; i++) ranked.add(food("x", "B"));
        assertEquals("A", UsdaApiClient.majorityFoodCategory(ranked));
    }
}
