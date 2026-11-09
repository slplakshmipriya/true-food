package com.barelabel.app.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Brand+product query planning: the Driscoll case (typed
 * "driscoll Strawberries" buried under yogurt results) and its
 * neighbours.
 */
public class BrandQueryDeciderTest {

    @Test
    public void attempts_singleBrandToken_plusProduct() {
        List<BrandQueryDecider.BrandAttempt> attempts =
                BrandQueryDecider.attempts("driscoll Strawberries");
        assertEquals(1, attempts.size());
        assertEquals("driscoll", attempts.get(0).brandOwner);
        assertEquals("strawberries", attempts.get(0).productQuery);
        assertEquals(Arrays.asList("driscoll"), attempts.get(0).brandTokens);
    }

    @Test
    public void attempts_twoWordBrand_longestPrefixFirst() {
        List<BrandQueryDecider.BrandAttempt> attempts =
                BrandQueryDecider.attempts("new seasons strawberries");
        assertEquals(2, attempts.size());
        assertEquals("new seasons", attempts.get(0).brandOwner);
        assertEquals("strawberries", attempts.get(0).productQuery);
        assertEquals("new", attempts.get(1).brandOwner);
        assertEquals("seasons strawberries", attempts.get(1).productQuery);
    }

    @Test
    public void attempts_noProductPart_noAttempts() {
        assertTrue(BrandQueryDecider.attempts("strawberries").isEmpty());
        assertTrue(BrandQueryDecider.attempts("driscoll").isEmpty());
        assertTrue(BrandQueryDecider.attempts("").isEmpty());
        assertTrue(BrandQueryDecider.attempts(null).isEmpty());
        assertTrue(BrandQueryDecider.attempts("  ").isEmpty());
    }

    @Test
    public void attempts_degenerateTokens_noAttempts() {
        // A one-letter leading token is not a brand; a one-letter
        // product part is not a product yet (still typing).
        assertTrue(BrandQueryDecider.attempts("a strawberries").isEmpty());
        assertTrue(BrandQueryDecider.attempts("driscoll s").isEmpty());
    }

    @Test
    public void brandMatches_apostropheBrand_byItsRealToken() {
        assertTrue(BrandQueryDecider.brandMatches(
                "DRISCOLL'S", Arrays.asList("driscoll")));
        assertTrue(BrandQueryDecider.brandMatches(
                "WELCH'S", Arrays.asList("welch")));
        assertTrue(BrandQueryDecider.brandMatches(
                "Jasper Wyman & Son", Arrays.asList("jasper", "wyman")));
    }

    @Test
    public void brandMatches_requiresEveryBrandToken() {
        assertFalse(BrandQueryDecider.brandMatches(
                "SEAL THE SEASONS", Arrays.asList("new", "seasons")));
        assertTrue(BrandQueryDecider.brandMatches(
                "NEW SEASONS", Arrays.asList("new", "seasons")));
        assertFalse(BrandQueryDecider.brandMatches(
                "NEW SEASONS", Arrays.asList("driscoll")));
    }

    @Test
    public void brandMatches_emptyInputs_false() {
        assertFalse(BrandQueryDecider.brandMatches(
                "", Arrays.asList("driscoll")));
        assertFalse(BrandQueryDecider.brandMatches(
                null, Arrays.asList("driscoll")));
        assertFalse(BrandQueryDecider.brandMatches(
                "DRISCOLL'S", Collections.<String>emptyList()));
        assertFalse(BrandQueryDecider.brandMatches("DRISCOLL'S", null));
    }

    @Test
    public void noBrandSurfaced_driscollAmongYogurts_true() {
        List<BrandQueryDecider.BrandAttempt> attempts =
                BrandQueryDecider.attempts("driscoll Strawberries");
        List<String> pageOneBrands = Arrays.asList(
                "N/A", "", "PAPA JOHN'S SALADS & PRODUCE",
                "RENAISSANCE FOOD GROUP", "PUBLIX PREMIUM");
        assertTrue(BrandQueryDecider.noBrandSurfaced(pageOneBrands, attempts));
    }

    @Test
    public void noBrandSurfaced_brandAlreadyOnPage_false() {
        List<BrandQueryDecider.BrandAttempt> attempts =
                BrandQueryDecider.attempts("driscoll Strawberries");
        assertFalse(BrandQueryDecider.noBrandSurfaced(
                Arrays.asList("DRISCOLL'S"), attempts));
        // No attempts -> never fires.
        assertFalse(BrandQueryDecider.noBrandSurfaced(
                Arrays.asList("DRISCOLL'S"),
                Collections.<BrandQueryDecider.BrandAttempt>emptyList()));
    }
}
