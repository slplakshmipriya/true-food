package com.barelabel.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * First-run "What do you avoid?" presets (pure mapping; unit-testable).
 * Each preset names the flagged categories it enables; categories not
 * known to the current flagged list are dropped defensively.
 */
public final class AvoidListPresets {

    public static final class Preset {
        public final String id;
        public final String label;
        public final String description;
        /** Categories enabled by this preset; null means "all of them". */
        public final List<String> categories;

        Preset(String id, String label, String description, List<String> categories) {
            this.id = id;
            this.label = label;
            this.description = description;
            this.categories = categories;
        }
    }

    private AvoidListPresets() {
    }

    /** Presets in display order (first = the default: everything on). */
    public static List<Preset> presets() {
        List<Preset> out = new ArrayList<>();
        out.add(new Preset("everything", "Everything BareLabel flags",
                "All flagged categories", null));
        out.add(new Preset("dairy_gluten", "Dairy + gluten",
                "Milk-derived ingredients and gluten sources",
                Arrays.asList("Dairy", "Gluten")));
        out.add(new Preset("plant_based", "Plant-based",
                "Meat and other animal-derived ingredients",
                Arrays.asList("Non-Vegetarian", "Non-Vegan")));
        out.add(new Preset("sweetener_watch", "Sweetener watch",
                "Artificial and processed sweeteners",
                Arrays.asList("Artificial Sweeteners",
                        "Processed Sweeteners & Fibers")));
        out.add(new Preset("ultra_processed", "Ultra-processed watch",
                "Preservatives, colors, emulsifiers, flavor additives, hydrogenated oils",
                Arrays.asList("Preservatives", "Artificial Colors",
                        "Emulsifiers & Thickening Agents",
                        "Flavor Enhancers & Additives",
                        "Fats & Hydrogenated Oils")));
        return out;
    }

    /** Categories to enable for {@code preset} out of {@code allCategories}. */
    public static Set<String> enabledFor(Preset preset, List<String> allCategories) {
        if (preset == null || preset.categories == null) {
            return new LinkedHashSet<>(allCategories);
        }
        Set<String> out = new LinkedHashSet<>();
        for (String category : preset.categories) {
            if (allCategories.contains(category)) {
                out.add(category);
            }
        }
        return out;
    }
}
