package com.barelabel.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * AvoidListPresets.enabledFor: preset -> enabled category set.
 */
public class AvoidListPresetsTest {

    private static final List<String> ALL = Arrays.asList(
            "Dairy", "Gluten", "Non-Vegetarian", "Non-Vegan",
            "Artificial Colors", "Preservatives", "Artificial Sweeteners",
            "Emulsifiers & Thickening Agents", "Flavor Enhancers & Additives",
            "Fats & Hydrogenated Oils", "Processed Sweeteners & Fibers");

    private static AvoidListPresets.Preset byId(String id) {
        for (AvoidListPresets.Preset p : AvoidListPresets.presets()) {
            if (p.id.equals(id)) return p;
        }
        throw new AssertionError("missing preset " + id);
    }

    @Test
    public void everything_enablesAll() {
        Set<String> enabled = AvoidListPresets.enabledFor(byId("everything"), ALL);
        assertEquals(ALL.size(), enabled.size());
        assertTrue(enabled.containsAll(ALL));
    }

    @Test
    public void dairyGluten_enablesTwo() {
        Set<String> enabled = AvoidListPresets.enabledFor(byId("dairy_gluten"), ALL);
        assertEquals(2, enabled.size());
        assertTrue(enabled.contains("Dairy"));
        assertTrue(enabled.contains("Gluten"));
    }

    @Test
    public void plantBased_excludesDairy() {
        Set<String> enabled = AvoidListPresets.enabledFor(byId("plant_based"), ALL);
        assertTrue(enabled.contains("Non-Vegan"));
        assertTrue(!enabled.contains("Dairy"));
    }

    @Test
    public void ultraProcessed_enablesFive() {
        Set<String> enabled = AvoidListPresets.enabledFor(
                byId("ultra_processed"), ALL);
        assertEquals(5, enabled.size());
    }

    @Test
    public void nullPreset_enablesAll() {
        assertEquals(ALL.size(),
                AvoidListPresets.enabledFor(null, ALL).size());
    }

    @Test
    public void unknownCategoriesInList_dropped() {
        // A stale flagged list missing 'Gluten' must not resurrect it.
        List<String> partial = Arrays.asList("Dairy", "Preservatives");
        Set<String> enabled = AvoidListPresets.enabledFor(
                byId("dairy_gluten"), partial);
        assertEquals(1, enabled.size());
        assertTrue(enabled.contains("Dairy"));
    }
}
