package com.barelabel.app.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * DisambiguationDecider: top-3 choice list only when the best title match is
 * strong (> threshold) but inexact (< 100).
 */
public class DisambiguationDeciderTest {

    private static JSONObject food(String description) throws Exception {
        return new JSONObject().put("description", description);
    }

    private static List<JSONObject> candidates(String... descriptions)
            throws Exception {
        List<JSONObject> out = new ArrayList<>();
        for (String d : descriptions) {
            out.add(food(d));
        }
        return out;
    }

    @Test
    public void strongInexactMatchReturnsTopThree() throws Exception {
        List<JSONObject> in = candidates(
                "World's Finest, Chocolate Covered Pecans",
                "World's Finest Chocolate Bar",
                "World's Finest Chocolate Covered Almonds",
                "Hershey's Milk Chocolate Bar");
        List<JSONObject> out =
                DisambiguationDecider.disambiguate("worlds finest chocolate", in);
        assertEquals(3, out.size());
        // Best score first.
        assertEquals("World's Finest Chocolate Bar",
                out.get(0).optString("description"));
    }

    @Test
    public void exactMatchMeansNoDisambiguation() throws Exception {
        List<JSONObject> in = candidates("Cheez-It", "Cheez-It White Cheddar");
        assertTrue(DisambiguationDecider.disambiguate("cheez-it", in).isEmpty());
    }

    @Test
    public void weakMatchMeansNoDisambiguation() throws Exception {
        List<JSONObject> in = candidates(
                "Quinoa Salad", "Kale Chips", "Lentil Soup");
        assertTrue(
                DisambiguationDecider.disambiguate("worlds finest chocolate", in)
                        .isEmpty());
    }

    @Test
    public void fewerThanTwoCandidatesMeansNoDisambiguation() throws Exception {
        assertTrue(DisambiguationDecider
                .disambiguate("worlds finest chocolate", candidates("World's Finest Chocolate Bar"))
                .isEmpty());
        assertTrue(DisambiguationDecider
                .disambiguate("worlds finest chocolate", null).isEmpty());
        assertTrue(DisambiguationDecider
                .disambiguate("worlds finest chocolate", new ArrayList<JSONObject>())
                .isEmpty());
    }

    @Test
    public void capsAtThreeEvenWithMoreStrongMatches() throws Exception {
        List<JSONObject> in = candidates(
                "World's Finest Chocolate Bar",
                "World's Finest Chocolate Covered Pecans",
                "World's Finest Chocolate Covered Almonds",
                "World's Finest Chocolate Mint",
                "World's Finest Dark Chocolate");
        assertEquals(3,
                DisambiguationDecider.disambiguate("worlds finest chocolate", in)
                        .size());
    }
}
