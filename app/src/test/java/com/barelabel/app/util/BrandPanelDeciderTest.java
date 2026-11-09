package com.barelabel.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.barelabel.app.AlternateRanker;
import com.barelabel.app.model.ProductResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * BrandPanelDecider: the brand panel ("Brands of &lt;Product&gt;",
 * Option C) replaces the Exact matches product stack only for a
 * category search whose exact matches are one product type offered
 * by 2+ brands, each with a scoreable representative.
 */
public class BrandPanelDeciderTest {

    private static AlternateRanker.RankedProduct ranked(
            String name, String brand, String category,
            Integer overall, int rank) {
        ProductResult p = new ProductResult();
        p.found = true;
        p.name = name;
        p.brand = brand;
        p.foodCategory = category;
        return new AlternateRanker.RankedProduct(
                p, rank, 1, 0, false, false, false, 0.0,
                overall, null,
                Collections.<String>emptyList(),
                Collections.<com.barelabel.app.NutrientDelta>emptyList());
    }

    /** The device-observed broccoli exact-match shape (2026-11-08). */
    private static List<AlternateRanker.RankedProduct> broccoliSet() {
        return Arrays.asList(
                ranked("BROCCOLI", "MAMBO", "Frozen Vegetables", 91, 0),
                ranked("Steam-In-Bag Broccoli Cuts", "Good & Gather",
                        "Frozen Vegetables", 88, 1),
                ranked("Broccoli Florets", "Birds Eye",
                        "Frozen Vegetables", 84, 2));
    }

    @Test
    public void broccoliSet_fires_spotlightIsHighestOverall() {
        BrandPanelDecider.BrandPanelModel model =
                BrandPanelDecider.decide(true, "BROCCOLI", broccoliSet());
        assertNotNull(model);
        assertEquals("Brands of Broccoli", model.title);
        assertEquals(3, model.brands.size());
        assertEquals("MAMBO", model.brands.get(0).brand);
        assertEquals(91, model.brands.get(0).overall);
        assertEquals(88, model.brands.get(1).overall);
        assertEquals(84, model.brands.get(2).overall);
    }

    @Test
    public void brandedSearch_doesNotFire() {
        assertNull(BrandPanelDecider.decide(false, "mambo", broccoliSet()));
    }

    @Test
    public void singleBrand_doesNotFire() {
        List<AlternateRanker.RankedProduct> one = Arrays.asList(
                ranked("BROCCOLI", "MAMBO", "Frozen Vegetables", 91, 0),
                ranked("Broccoli Florets", "Mambo", "Frozen Vegetables",
                        85, 1));
        assertNull(BrandPanelDecider.decide(true, "broccoli", one));
    }

    @Test
    public void mixedCategories_doNotFire() {
        List<AlternateRanker.RankedProduct> mixed = Arrays.asList(
                ranked("BROCCOLI", "MAMBO", "Frozen Vegetables", 91, 0),
                ranked("Broccoli Cheddar Soup", "Panera Bread",
                        "Prepared Soups", 70, 1));
        assertNull(BrandPanelDecider.decide(true, "broccoli", mixed));
    }

    @Test
    public void categoryCaseAndWhitespaceStillOneType() {
        List<AlternateRanker.RankedProduct> sloppy = Arrays.asList(
                ranked("BROCCOLI", "MAMBO", "Frozen Vegetables", 91, 0),
                ranked("Broccoli Cuts", "Birds Eye",
                        " frozen  vegetables ", 84, 1));
        assertNotNull(BrandPanelDecider.decide(true, "broccoli", sloppy));
    }

    @Test
    public void brandWithoutScoreableProduct_suppressesPanel() {
        List<AlternateRanker.RankedProduct> unscored = Arrays.asList(
                ranked("BROCCOLI", "MAMBO", "Frozen Vegetables", 91, 0),
                ranked("Broccoli Cuts", "Birds Eye",
                        "Frozen Vegetables", null, 1));
        assertNull(BrandPanelDecider.decide(true, "broccoli", unscored));
    }

    @Test
    public void representative_isHighestOverallVariant() {
        List<AlternateRanker.RankedProduct> multi = Arrays.asList(
                ranked("Broccoli Cuts", "Birds Eye",
                        "Frozen Vegetables", 80, 0),
                ranked("Broccoli Florets", "Birds Eye",
                        "Frozen Vegetables", 95, 1),
                ranked("BROCCOLI", "MAMBO", "Frozen Vegetables", 91, 2));
        BrandPanelDecider.BrandPanelModel model =
                BrandPanelDecider.decide(true, "broccoli", multi);
        assertNotNull(model);
        assertEquals(2, model.brands.size());
        // Birds Eye's 95 variant represents it and takes the spotlight.
        assertEquals("Birds Eye", model.brands.get(0).brand);
        assertEquals(95, model.brands.get(0).overall);
        assertEquals(2, model.brands.get(0).productCount);
        assertEquals("Broccoli Florets",
                model.brands.get(0).representative.product.name);
    }

    @Test
    public void emptyBrandProducts_areIgnoredNotCounted() {
        List<AlternateRanker.RankedProduct> withBlank = Arrays.asList(
                ranked("BROCCOLI", "MAMBO", "Frozen Vegetables", 91, 0),
                ranked("Broccoli Cuts", "Birds Eye",
                        "Frozen Vegetables", 84, 1),
                ranked("Broccoli, raw", "", "Frozen Vegetables", 99, 2));
        BrandPanelDecider.BrandPanelModel model =
                BrandPanelDecider.decide(true, "broccoli", withBlank);
        assertNotNull(model);
        assertEquals(2, model.brands.size());
    }

    @Test
    public void blankLabelOrEmptySet_doesNotFire() {
        assertNull(BrandPanelDecider.decide(true, "  ", broccoliSet()));
        assertNull(BrandPanelDecider.decide(true, null, broccoliSet()));
        assertNull(BrandPanelDecider.decide(true, "broccoli",
                Collections.<AlternateRanker.RankedProduct>emptyList()));
        assertNull(BrandPanelDecider.decide(true, "broccoli", null));
    }

    @Test
    public void allBrandsKept_beyondInitialDisplayCount() {
        List<AlternateRanker.RankedProduct> many = new ArrayList<>();
        int total = BrandPanelDecider.INITIAL_BRANDS + 2;
        for (int i = 0; i < total; i++) {
            many.add(ranked("Broccoli " + i, "Brand" + i,
                    "Frozen Vegetables", 50 + i, i));
        }
        BrandPanelDecider.BrandPanelModel model =
                BrandPanelDecider.decide(true, "broccoli", many);
        assertNotNull(model);
        // No brand is cut from the model: the panel's "show all"
        // expansion renders past INITIAL_BRANDS from this same list.
        assertEquals(total, model.brands.size());
        // Highest overall still leads (the spotlight).
        assertEquals(50 + total - 1, model.brands.get(0).overall);
        assertEquals(50, model.brands.get(total - 1).overall);
    }

    @Test
    public void productLabel_titleCasesWords() {
        assertEquals("Broccoli",
                BrandPanelDecider.productLabel("BROCCOLI"));
        assertEquals("Peanut Butter",
                BrandPanelDecider.productLabel("peanut butter"));
        assertEquals("Greek Yogurt",
                BrandPanelDecider.productLabel("  greek   yogurt "));
        assertEquals("", BrandPanelDecider.productLabel("   "));
        assertEquals("", BrandPanelDecider.productLabel(null));
    }
}
