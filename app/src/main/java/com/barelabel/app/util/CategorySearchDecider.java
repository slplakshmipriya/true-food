package com.barelabel.app.util;

import com.barelabel.app.model.ProductResult;

import java.util.Set;

/**
 * Pure, Android-free brand-vs-category query decision (unit-testable).
 *
 * Extracted from MainActivity.isCategorySearch so the rule can run in JVM
 * unit tests: MainActivity delegates with a GenericWords-backed lookup.
 */
public final class CategorySearchDecider {

    /** Decides whether a token is a generic food word. */
    public interface GenericWordLookup {
        boolean isGeneric(String token);
    }

    private CategorySearchDecider() {
    }

    /**
     * True when the query does NOT name the product's brand (category search).
     * A query token counts as naming the brand only when it is distinctive:
     * length >= 4, present in the brand tokens, and NOT a generic food word.
     * Empty query, null/unfound product, or empty brand tokens behave exactly
     * as MainActivity.isCategorySearch always has.
     */
    public static boolean isCategorySearch(String query, ProductResult product,
                                           GenericWordLookup lookup) {
        if (query == null || query.isEmpty() || product == null || !product.found) {
            return false;
        }
        Set<String> brandTokens =
                StringNormalizer.wordTokens(product.brandName + " " + product.brandOwner);
        // No brand on the hit -> can't be a branded match for the query,
        // so treat it as a category search (hides the misleading verdict card).
        if (brandTokens.isEmpty()) {
            return true;
        }
        for (String token : StringNormalizer.wordTokens(query)) {
            // Only a *distinctive* brand token counts as naming the brand:
            // generic food words a human would type ("bread", "peanut butter")
            // must not match e.g. the "bread" in "The Bread Factory Inc."
            if (token.length() >= 4 && brandTokens.contains(token)
                    && !lookup.isGeneric(token)) {
                return false; // query names the brand -> branded product search
            }
        }
        return true;
    }

    /**
     * True when a category search should hide the primary verdict card:
     * only when the clean-choices card has at least one alternate to
     * display in its place. A category search whose clean choices come
     * back empty must fall back to the primary verdict — hiding the
     * card then leaves the user with no result at all (the produce
     * bug: generic queries like "broccoli" are category searches, and
     * their scattered USDA categories can yield zero displayable
     * alternates even though the search itself found products).
     */
    public static boolean hidePrimaryCard(boolean categoryIntent,
                                          int displayableAlternates) {
        return categoryIntent && displayableAlternates > 0;
    }
}
