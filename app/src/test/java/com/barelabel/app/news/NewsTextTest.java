package com.barelabel.app.news;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Set;

/**
 * NewsText: Contract §3.1 normalization, plural folding, token sets
 * and §2.1 term variants.
 */
public class NewsTextTest {

    @Test
    public void normalize_lowercasesReplacesPunctuationAndCollapses() {
        assertEquals("fresh strawberries organic",
                NewsText.normalize("  Fresh,  STRAWBERRIES!! Organic\u2026 "));
        assertEquals("e coli o157 h7",
                NewsText.normalize("E. coli O157:H7"));
        assertEquals("", NewsText.normalize(null));
        assertEquals("", NewsText.normalize("   "));
        assertEquals("", NewsText.normalize("!!!"));
    }

    @Test
    public void tokens_areNormalizedInOrder() {
        List<String> tokens = NewsText.tokens("Fresh Breeze Farms, Organic");
        assertEquals(4, tokens.size());
        assertEquals("fresh", tokens.get(0));
        assertEquals("breeze", tokens.get(1));
        assertEquals("farms", tokens.get(2));
        assertEquals("organic", tokens.get(3));
    }

    @Test
    public void fold_iesBecomesY() {
        assertEquals("strawberry", NewsText.foldToken("strawberries"));
        assertEquals("berry", NewsText.foldToken("berries"));
    }

    @Test
    public void fold_trailingSStrippedButNotDoubleS() {
        assertEquals("egg", NewsText.foldToken("eggs"));
        assertEquals("glass", NewsText.foldToken("glass"));
        // "ss" ending is not folded at all.
        assertEquals("class", NewsText.foldToken("class"));
    }

    @Test
    public void fold_noTrailingSUnchanged() {
        assertEquals("chicken", NewsText.foldToken("chicken"));
        assertEquals("strawberry", NewsText.foldToken("strawberry"));
    }

    @Test
    public void tokenSet_containsBothRawAndFoldedForms() {
        Set<String> set = NewsText.tokenSet("Fresh Strawberries");
        assertTrue(set.contains("fresh"));
        assertTrue(set.contains("strawberries"));
        assertTrue(set.contains("strawberry"));
        assertEquals(3, set.size());
    }

    @Test
    public void tokenSet_glassKeepsOnlyRawForm() {
        Set<String> set = NewsText.tokenSet("glass");
        assertTrue(set.contains("glass"));
        assertEquals(1, set.size());
    }

    @Test
    public void queryTokens_matchesTokenSetSemantics() {
        Set<String> tokens = NewsText.queryTokens("strawberries");
        assertTrue(tokens.contains("strawberries"));
        assertTrue(tokens.contains("strawberry"));
        assertFalse(tokens.contains("berry"));
    }

    @Test
    public void queryTokens_nullAndEmptyAreEmpty() {
        assertTrue(NewsText.queryTokens(null).isEmpty());
        assertTrue(NewsText.queryTokens("").isEmpty());
        assertTrue(NewsText.queryTokens("  , ").isEmpty());
    }

    @Test
    public void termVariants_strawberry() {
        List<String> variants = NewsText.termVariants("strawberry");
        // Normalized + plural-expanded; the all-folded form dedupes
        // into the normalized form.
        assertEquals(2, variants.size());
        assertTrue(variants.contains("strawberry"));
        assertTrue(variants.contains("strawberries"));
    }

    @Test
    public void termVariants_chicken() {
        List<String> variants = NewsText.termVariants("chicken");
        assertEquals(2, variants.size());
        assertTrue(variants.contains("chicken"));
        assertTrue(variants.contains("chickens"));
    }

    @Test
    public void termVariants_pluralInputFoldsAndDedupes() {
        List<String> variants = NewsText.termVariants("strawberries");
        assertEquals(2, variants.size());
        assertTrue(variants.contains("strawberries"));
        assertTrue(variants.contains("strawberry"));
    }

    @Test
    public void termVariants_neverMoreThanThree() {
        // Multi-token: normalized, all-folded and last-token-expanded
        // are three distinct forms here.
        List<String> variants = NewsText.termVariants("eggs chicken");
        assertEquals(3, variants.size());
        assertTrue(variants.contains("eggs chicken"));
        assertTrue(variants.contains("egg chicken"));
        assertTrue(variants.contains("eggs chickens"));
    }

    @Test
    public void termVariants_emptyInputIsEmpty() {
        assertTrue(NewsText.termVariants(null).isEmpty());
        assertTrue(NewsText.termVariants("").isEmpty());
    }
}
