package com.barelabel.app.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * UsdaSpamFilter: brand-spam demotion + whole-word ranking.
 *
 * Guards the fixes that keep fake non-food brand owners (publishing /
 * entertainment companies filing "BREAD") out of the top results, and that
 * rank whole-word description matches above substring matches.
 */
public class SpamFilterTest {

    private static JSONObject food(String owner, String desc) throws Exception {
        JSONObject o = new JSONObject();
        o.put("brandOwner", owner);
        o.put("description", desc);
        return o;
    }

    private static List<String> owners(List<JSONObject> ranked) throws Exception {
        List<String> out = new ArrayList<>();
        for (JSONObject f : ranked) {
            out.add(f.optString("brandOwner"));
        }
        return out;
    }

    @Test
    public void publishing_isSpam() {
        assertTrue(UsdaSpamFilter.isSpamBrand("Multicom Publishing Incorporated"));
    }

    @Test
    public void entertainment_isSpam() {
        assertTrue(UsdaSpamFilter.isSpamBrand("Bang Brothers Entertainment, Inc"));
    }

    @Test
    public void software_isSpam() {
        assertTrue(UsdaSpamFilter.isSpamBrand("Acme Software LLC"));
    }

    @Test
    public void media_isSpam() {
        assertTrue(UsdaSpamFilter.isSpamBrand("Fresh Media Group"));
    }

    @Test
    public void films_isSpam() {
        assertTrue(UsdaSpamFilter.isSpamBrand("Sunset Films"));
    }

    @Test
    public void detection_isCaseInsensitive() {
        assertTrue(UsdaSpamFilter.isSpamBrand("PUBLISHING HOUSE"));
    }

    @Test
    public void breadFactory_isNotSpam() {
        assertFalse(UsdaSpamFilter.isSpamBrand("The Bread Factory Inc."));
    }

    @Test
    public void emptyAndNullOwner_isNotSpam() {
        assertFalse(UsdaSpamFilter.isSpamBrand(""));
        assertFalse(UsdaSpamFilter.isSpamBrand(null));
    }

    @Test
    public void partialWord_doesNotMatch() {
        // "Publisher" contains no whole word "publishing".
        assertFalse(UsdaSpamFilter.isSpamBrand("The Publisher Group"));
    }

    @Test
    public void wholeWordMatch_ciabattaBread() {
        assertTrue(UsdaSpamFilter.isWholeWordMatch("CIABATTA BREAD", "bread"));
    }

    @Test
    public void substringNotWholeWord_breadedCalamari() {
        assertFalse(UsdaSpamFilter.isWholeWordMatch("BREADED CALAMARI, BREADED", "bread"));
    }

    @Test
    public void emptyQuery_neverWholeMatches() {
        assertFalse(UsdaSpamFilter.isWholeWordMatch("BREAD", ""));
    }

    @Test
    public void ranked_wholeWordNonSpamFirst() throws Exception {
        JSONArray foods = new JSONArray();
        foods.put(food("Multicom Publishing Incorporated", "BREAD"));
        foods.put(food("Bay Seafood Inc", "BREADED CALAMARI, BREADED"));
        foods.put(food("The Bread Factory Inc.", "BREAD"));
        foods.put(food("Bang Brothers Entertainment, Inc", "BREAD"));
        List<JSONObject> ranked = UsdaSpamFilter.rankedCandidates(foods, "bread");
        assertEquals("The Bread Factory Inc.", ranked.get(0).optString("brandOwner"));
    }

    @Test
    public void ranked_nonSpamSubstringSecond() throws Exception {
        JSONArray foods = new JSONArray();
        foods.put(food("Multicom Publishing Incorporated", "BREAD"));
        foods.put(food("Bay Seafood Inc", "BREADED CALAMARI, BREADED"));
        foods.put(food("The Bread Factory Inc.", "BREAD"));
        foods.put(food("Bang Brothers Entertainment, Inc", "BREAD"));
        List<JSONObject> ranked = UsdaSpamFilter.rankedCandidates(foods, "bread");
        assertEquals("Bay Seafood Inc", ranked.get(1).optString("brandOwner"));
    }

    @Test
    public void ranked_spamTailKeepsOriginalOrder() throws Exception {
        JSONArray foods = new JSONArray();
        foods.put(food("Multicom Publishing Incorporated", "BREAD"));
        foods.put(food("Bay Seafood Inc", "BREADED CALAMARI, BREADED"));
        foods.put(food("The Bread Factory Inc.", "BREAD"));
        foods.put(food("Bang Brothers Entertainment, Inc", "BREAD"));
        List<JSONObject> ranked = UsdaSpamFilter.rankedCandidates(foods, "bread");
        assertEquals("Multicom Publishing Incorporated",
                ranked.get(2).optString("brandOwner"));
        assertEquals("Bang Brothers Entertainment, Inc",
                ranked.get(3).optString("brandOwner"));
    }

    @Test
    public void ranked_nullFoods_emptyList() {
        assertTrue(UsdaSpamFilter.rankedCandidates(null, "bread").isEmpty());
    }
}
