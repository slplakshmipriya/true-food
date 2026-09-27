package com.barelabel.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * RuleBasedFoodClassifier.classify: fallback category from description.
 *
 * Covers the keyword map, brand stripping, paren stripping, the secondary
 * (inverted-order) pass, and the null fallback that triggers the ML layer.
 */
public class FoodClassifierTest {

    @Test
    public void bread_mapsToBread() {
        assertEquals("Bread", new RuleBasedFoodClassifier().classify("BREAD"));
    }

    @Test
    public void greekYogurt_mapsToYogurt() {
        assertEquals("Yogurt", new RuleBasedFoodClassifier().classify("Greek Yogurt"));
    }

    @Test
    public void cheddarCheese_mapsToCheese() {
        assertEquals("Cheese", new RuleBasedFoodClassifier().classify("Cheddar Cheese"));
    }

    @Test
    public void invertedOrder_secondaryPassFindsYogurt() {
        assertEquals("Yogurt",
                new RuleBasedFoodClassifier().classify("Chocolate Syrup, Greek Yogurt"));
    }

    @Test
    public void leadingBrand_strippedBeforeMatch() {
        assertEquals("Cheese",
                new RuleBasedFoodClassifier().classify("KRAFT, Cheddar Cheese"));
    }

    @Test
    public void parens_strippedBeforeMatch() {
        assertEquals("Bread",
                new RuleBasedFoodClassifier().classify("Bread (Whole Wheat)"));
    }

    @Test
    public void potatoChips_mapsToChipsAndCrisps() {
        assertEquals("Chips & Crisps",
                new RuleBasedFoodClassifier().classify("Potato Chips, Salted"));
    }

    @Test
    public void miniPretzels_mapsToPretzels() {
        assertEquals("Pretzels",
                new RuleBasedFoodClassifier().classify("Mini Pretzels"));
    }

    @Test
    public void saltedButter_mapsToButterAndSpreads() {
        assertEquals("Butter & Spreads",
                new RuleBasedFoodClassifier().classify("Salted Butter"));
    }

    @Test
    public void punctuation_strippedBeforeMatch() {
        assertEquals("Cereal",
                new RuleBasedFoodClassifier().classify("Cereal - Honey Oat"));
    }

    @Test
    public void iceCream_noKeyword_returnsNull() {
        assertNull(new RuleBasedFoodClassifier().classify("Ice Cream (Vanilla)"));
    }

    @Test
    public void unknownDescription_returnsNull() {
        assertNull(new RuleBasedFoodClassifier().classify("Quinoa Salad"));
    }

    @Test
    public void nullDescription_returnsNull() {
        assertNull(new RuleBasedFoodClassifier().classify(null));
    }

    @Test
    public void emptyDescription_returnsNull() {
        assertNull(new RuleBasedFoodClassifier().classify(""));
    }

    @Test
    public void wholeMilkGreekYogurt_primaryPassWins() {
        // Documents actual behavior: "milk" matches in the primary segment, so
        // the secondary pass never runs. (The class's own doc comment suggests
        // "Yogurt" for this input, which is aspirational, not actual.)
        assertEquals("Milk",
                new RuleBasedFoodClassifier().classify("Whole Milk, Greek Yogurt"));
    }
}
