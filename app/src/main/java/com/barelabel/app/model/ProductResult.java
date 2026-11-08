package com.barelabel.app.model;

import com.barelabel.app.FlaggedIngredientManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One USDA branded product with its analyzed ingredient flags. */
public class ProductResult {
    public boolean found;
    public String name, brand, ingredients;
    public String foodCategory = "";
    /**
     * Majority-vote foodCategory over the top results for the query; set only by
     * searchPrimary. Used for category searches so one miscategorized record
     * can't retitle and refilter the whole result set.
     */
    public String queryCategory = "";
    public String gtinUpc = "";
    public String brandName = "";
    public String brandOwner = "";
    /** USDA FoodData Central ID of the source record; 0 when unknown. */
    public long fdcId;
    /**
     * Parsed nutrition facts for this product (Contract §2), attached by
     * the USDA parser at build time; null when no profile was attached
     * (e.g. products built outside the USDA path).
     */
    public NutritionProfile nutritionProfile;
    // Note: the NutritionAnalysis field from Contract §2 is added by
    // NUT-04 together with its real type; it is deliberately not declared
    // here yet.
    public MatchQuality matchQuality;
    public FlaggedIngredientManager.MatchResult matchResult;
    public Set<String> flagged = new HashSet<>();

    public ProductResult() {
    }

    public ProductResult(boolean found, String name, String brand, String ingredients,
                         FlaggedIngredientManager.MatchResult matchResult, MatchQuality matchQuality) {
        this.found = found;
        this.name = name;
        this.brand = brand;
        this.ingredients = ingredients;
        this.matchResult = matchResult;
        this.matchQuality = matchQuality;

        // Reset and strictly populate only active matches
        this.flagged.clear();
        if (matchResult != null && matchResult.hasMatches()) {
            for (List<String> items : matchResult.categoryMap.values()) {
                this.flagged.addAll(items);
            }
        }
    }

    public ProductResult(boolean found, String name, String brand, String ingredients,
                         FlaggedIngredientManager.MatchResult matchResult) {
        this(found, name, brand, ingredients, matchResult, MatchQuality.NONE);
    }

    public static ProductResult notFound() {
        return new ProductResult(false, "", "", "", new FlaggedIngredientManager.MatchResult(), MatchQuality.NONE);
    }
}
