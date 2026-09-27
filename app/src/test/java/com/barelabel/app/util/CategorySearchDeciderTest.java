package com.barelabel.app.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.barelabel.app.model.ProductResult;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * CategorySearchDecider (pure logic extracted from
 * MainActivity.isCategorySearch): generic query vs branded query decisions.
 *
 * User issues covered:
 *  - "bread" showed a DIRTY verdict for a random hit with an empty brand
 *  - "bread" matched the brand token in "The Bread Factory Inc."
 */
public class CategorySearchDeciderTest {

    // Stand-in for the curated generic-words list (the real asset is covered
    // by GenericWordsTest); only the lookup contract matters here.
    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
            "bread", "peanut", "butter", "ice", "cream", "mac", "cheese",
            "yogurt", "greek", "cookies", "chips", "crackers", "cereal", "soda"));

    private static boolean decides(String query, String brandName, String brandOwner) {
        ProductResult product = new ProductResult();
        product.found = true;
        product.brandName = brandName;
        product.brandOwner = brandOwner;
        return CategorySearchDecider.isCategorySearch(
                query, product, token -> GENERIC.contains(token));
    }

    // --- user issue: empty brand -> category search, verdict hidden ---
    @Test
    public void issue_emptyBrand_isCategorySearch() {
        assertTrue(decides("bread", "", ""));
    }

    @Test
    public void issue_blankBrand_isCategorySearch() {
        assertTrue(decides("bread", "  ", " "));
    }

    // --- user issue: generic word overlap must NOT count as naming brand ---
    @Test
    public void issue_breadVsBreadFactory_isCategorySearch() {
        assertTrue(decides("bread", "CONCHAS", "The Bread Factory Inc."));
    }

    @Test
    public void breadVsOwnerOnly_isCategorySearch() {
        assertTrue(decides("bread", "", "The Bread Factory Inc."));
    }

    @Test
    public void uppercaseBread_isCategorySearch() {
        assertTrue(decides("BREAD", "CONCHAS", "The Bread Factory Inc."));
    }

    // --- genuinely branded queries still detected ---
    @Test
    public void oreo_isBranded() {
        assertFalse(decides("oreo", "OREO", "Mondelez Global LLC"));
    }

    @Test
    public void paneraBread_isBranded() {
        assertFalse(decides("panera bread", "PANERA BREAD", "Panera Bread Company"));
    }

    @Test
    public void chobaniYogurt_isBranded() {
        assertFalse(decides("chobani yogurt", "CHOBANI GREEK YOGURT", "Chobani, LLC"));
    }

    @Test
    public void cocaCola_isBranded() {
        assertFalse(decides("coca cola", "COCA-COLA", "Coca-Cola Company"));
    }

    @Test
    public void dietCoke_isBranded() {
        assertFalse(decides("diet coke", "DIET COKE", "Coca-Cola Company"));
    }

    @Test
    public void kindBar_isBranded() {
        assertFalse(decides("kind bar", "KIND BAR", "Kind LLC"));
    }

    @Test
    public void kraftMacAndCheese_isBranded() {
        assertFalse(decides("kraft mac and cheese", "KRAFT MAC & CHEESE", "Kraft Heinz"));
    }

    // --- generic queries with no brand overlap at all ---
    @Test
    public void macAndCheeseVsKraft_isCategorySearch_queryNeverNamesKraft() {
        assertTrue(decides("mac and cheese", "KRAFT MAC & CHEESE", "Kraft Heinz"));
    }

    @Test
    public void peanutButterVsSkippy_isCategorySearch() {
        assertTrue(decides("peanut butter", "SKIPPY PEANUT BUTTER", "Hormel Foods"));
    }

    @Test
    public void greekYogurtVsFage_isCategorySearch() {
        assertTrue(decides("greek yogurt", "FAGE TOTAL GREEK YOGURT", "Fage USA Dairy"));
    }

    @Test
    public void iceCreamVsBenAndJerrys_isCategorySearch() {
        assertTrue(decides("ice cream", "BEN & JERRY'S ICE CREAM", "Unilever"));
    }

    // --- known limitation: short brand names (<4 chars) are ignored ---
    @Test
    public void limitation_jifTooShort_isCategorySearch() {
        assertTrue(decides("jif peanut butter", "JIF PEANUT BUTTER",
                "The J.M. Smucker Company"));
    }

    // --- guard clauses (same as the original MainActivity method) ---
    @Test
    public void nullQuery_isNotCategorySearch() {
        ProductResult product = new ProductResult();
        product.found = true;
        assertFalse(CategorySearchDecider.isCategorySearch(
                null, product, token -> false));
    }

    @Test
    public void emptyQuery_isNotCategorySearch() {
        ProductResult product = new ProductResult();
        product.found = true;
        assertFalse(CategorySearchDecider.isCategorySearch(
                "", product, token -> false));
    }

    @Test
    public void nullProduct_isNotCategorySearch() {
        assertFalse(CategorySearchDecider.isCategorySearch(
                "bread", null, token -> false));
    }

    @Test
    public void unfoundProduct_isNotCategorySearch() {
        ProductResult product = new ProductResult(); // found defaults false
        product.brandName = "";
        product.brandOwner = "";
        assertFalse(CategorySearchDecider.isCategorySearch(
                "bread", product, token -> false));
    }
}
