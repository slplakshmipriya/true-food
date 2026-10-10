package com.barelabel.app;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Plain-English "why it matters" text for each flagged category, shown when
 * a flagged-ingredient row is expanded on the verdict card. Pure lookup
 * (unit-testable); unknown categories get a neutral fallback.
 */
public final class IngredientExplainer {

    private static final String FALLBACK =
            "You flagged this category in your avoid list.";

    private static final Map<String, String> REASONS;

    static {
        Map<String, String> m = new HashMap<>();
        m.put("Dairy",
                "Milk-derived ingredients. You chose to flag dairy.");
        m.put("Gluten",
                "Wheat or other gluten sources. Often avoided for celiac "
                        + "disease or gluten sensitivity.");
        m.put("Non-Vegetarian",
                "Meat, gelatin, or other animal-derived ingredients.");
        m.put("Non-Vegan",
                "Animal-derived ingredients such as milk, egg, or honey.");
        m.put("Artificial Colors",
                "Synthetic dyes added only for appearance; they carry no nutrition.");
        m.put("Preservatives",
                "Chemical preservatives that extend shelf life. A common avoid "
                        + "for people eating minimally processed food.");
        m.put("Artificial Sweeteners",
                "Zero-calorie sweeteners. Heavily processed and a frequent "
                        + "clean-eating avoid.");
        m.put("Emulsifiers & Thickening Agents",
                "Texture additives such as gums and lecithins. Often a sign of "
                        + "heavy processing.");
        m.put("Flavor Enhancers & Additives",
                "Added flavor systems (for example MSG or yeast extract). A "
                        + "broad marker of ultra-processed food.");
        m.put("Fats & Hydrogenated Oils",
                "Hydrogenated or interesterified oils. A marker of industrial "
                        + "processing and added trans-fat risk.");
        m.put("Processed Sweeteners & Fibers",
                "Refined sweeteners and isolated fibers added back to foods. "
                        + "Usually heavily processed.");
        REASONS = Collections.unmodifiableMap(m);
    }

    private IngredientExplainer() {
    }

    /** Reason text for a flagged category; never null. */
    public static String reasonFor(String category) {
        if (category == null) return FALLBACK;
        String reason = REASONS.get(category.trim());
        return reason != null ? reason : FALLBACK;
    }

    /** Categories with curated reason text (for tests/onboarding copy). */
    public static Map<String, String> allReasons() {
        return REASONS;
    }
}
