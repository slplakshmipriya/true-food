package com.barelabel.app.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One product's parsed nutrition facts (Contract §2): per-100 amounts for
 * the nutrients USDA actually reported — a nutrient that was not reported
 * is absent from {@link #per100}, never 0.0.
 *
 * <p>Immutable. Built by
 * {@code com.barelabel.app.nutrition.UsdaNutritionParser}, the single
 * parsing path (Contract §3).
 */
public final class NutritionProfile {

    /** USDA FoodData Central ID; 0 when unknown. */
    public final long fdcId;
    /** Per-100 basis: "g" or "ml". */
    public final String basis;
    /** Serving size in {@link #servingUnit}; null when unusable/absent. */
    public final Double servingSize;
    /** Raw USDA serving unit (e.g. "g", "oz"); "" when absent. */
    public final String servingUnit;
    /** Household serving text (e.g. "1 slice"); "" when absent. */
    public final String householdServing;
    /** Key -> amount per 100 basis units; reported nutrients only. */
    public final Map<String, Double> per100;

    public NutritionProfile(long fdcId, String basis, Double servingSize,
                            String servingUnit, String householdServing,
                            Map<String, Double> per100) {
        this.fdcId = fdcId;
        this.basis = "ml".equals(basis) ? "ml" : "g";
        this.servingSize = servingSize;
        this.servingUnit = servingUnit != null ? servingUnit : "";
        this.householdServing = householdServing != null ? householdServing : "";
        this.per100 = per100 == null
                ? Collections.<String, Double>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(per100));
    }

    /** An empty profile (no reported nutrients) for the given fdcId. */
    public static NutritionProfile empty(long fdcId) {
        return new NutritionProfile(fdcId, "g", null, "", "",
                Collections.<String, Double>emptyMap());
    }

    /** True when USDA reported this nutrient for the product. */
    public boolean reported(String key) {
        return key != null && per100.containsKey(key);
    }

    /**
     * Amount per serving (Contract §2.1): {@code per100 * servingSize / 100},
     * computed only when {@link #servingUnit} normalizes to this profile's
     * basis. Returns null when the nutrient was not reported, when there is
     * no usable serving size, or when the serving unit cannot be reconciled
     * with the basis (e.g. "oz", household-only) — the UI then shows the
     * per-100 value only.
     */
    public Double perServing(String key) {
        Double value = key == null ? null : per100.get(key);
        if (value == null || servingSize == null) {
            return null;
        }
        String normalized = normalizeServingUnit(servingUnit);
        if (normalized == null || !normalized.equals(basis)) {
            return null;
        }
        return value * servingSize / 100.0;
    }

    /**
     * Normalizes a serving-size unit to a basis (Contract §2.1):
     * "g"/"gram"/"grams" -> "g", "ml"/"milliliter"/"milliliters" -> "ml"
     * (case-insensitive, surrounding whitespace ignored). Returns null for
     * anything else (e.g. "oz", "", null).
     */
    public static String normalizeServingUnit(String unit) {
        if (unit == null) {
            return null;
        }
        String u = unit.trim().toLowerCase(Locale.US);
        if (u.equals("g") || u.equals("gram") || u.equals("grams")) {
            return "g";
        }
        if (u.equals("ml") || u.equals("milliliter")
                || u.equals("milliliters")) {
            return "ml";
        }
        return null;
    }
}
