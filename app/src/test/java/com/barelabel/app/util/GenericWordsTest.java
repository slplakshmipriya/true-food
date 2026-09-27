package com.barelabel.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * GenericWords asset: the curated list itself is under test.
 *
 * Loads the REAL app/src/main/assets/generic_words.txt, so these cases guard
 * the curation: human-typed terms must be present, brand names must never
 * leak in (a leaked brand would break isCategorySearch for that brand).
 */
public class GenericWordsTest {

    private static Set<String> loadRealAsset() throws Exception {
        File asset = new File("src/main/assets/generic_words.txt");
        assertTrue("asset not found at " + asset.getAbsolutePath(), asset.isFile());
        try (BufferedReader r =
                     Files.newBufferedReader(asset.toPath(), StandardCharsets.UTF_8)) {
            return GenericWords.parseLines(r);
        }
    }

    @Test
    public void parseLines_skipsCommentsAndBlanks() throws Exception {
        String input = "# a comment\n\nbread\npeanut butter\n   \n# another\n";
        Set<String> tokens = GenericWords.parseLines(
                new BufferedReader(new StringReader(input)));
        assertEquals(new HashSet<>(Arrays.asList("bread", "peanut", "butter")), tokens);
    }

    @Test
    public void realAsset_loadsLargeList() throws Exception {
        assertTrue(loadRealAsset().size() > 500);
    }

    @Test
    public void realAsset_containsHumanTypedTerms() throws Exception {
        Set<String> tokens = loadRealAsset();
        for (String w : new String[]{"bread", "peanut", "butter", "ice", "cream",
                "mac", "cheese", "yogurt", "cereal", "soda", "cookies", "chips"}) {
            assertTrue("expected generic word missing: " + w, tokens.contains(w));
        }
    }

    @Test
    public void realAsset_hasNoBrandNameLeaks() throws Exception {
        Set<String> tokens = loadRealAsset();
        String[] brands = {"oreo", "chobani", "panera", "fage", "kind", "jif",
                "skippy", "coca", "coke", "kraft", "yoplait", "dannon",
                "kellogg", "quaker", "nestle", "pepsi", "nutella",
                "cheetos", "doritos", "lays"};
        for (String b : brands) {
            assertFalse("brand name leaked into generic list: " + b,
                    tokens.contains(b));
        }
    }

    @Test
    public void realAsset_commentOnlyWordsExcluded() throws Exception {
        // "cheetos"/"coke" appear only in '#' comment lines by design.
        Set<String> tokens = loadRealAsset();
        assertFalse(tokens.contains("cheetos"));
        assertFalse(tokens.contains("coke"));
    }

    @Test
    public void realAsset_punctuationAloneIsNotAToken() throws Exception {
        assertFalse(loadRealAsset().contains("&"));
    }

    @Test
    public void realAsset_singleLettersComeOnlyFromRealPhrases() throws Exception {
        // "a" is generic only because of the real phrase "ants on a log".
        assertTrue(loadRealAsset().contains("a"));
    }
}
