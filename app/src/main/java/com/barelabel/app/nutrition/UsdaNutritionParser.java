package com.barelabel.app.nutrition;

import com.barelabel.app.model.NutritionProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parses one USDA branded food object into a {@link NutritionProfile}
 * (Contract §3). This is the single parsing path: every ProductResult
 * builder attaches profiles through here.
 *
 * <p>Rules: never throws — malformed input yields an empty profile;
 * accepts both USDA entry shapes ({@code {nutrientId, value, unitName}}
 * from search and {@code {nutrient: {id, unitName}, amount}} from the
 * /food endpoint); entries map through {@code config.byUsdaId} and
 * unconfigured nutrients are ignored; values are converted to the
 * definition's unit and a value whose unit cannot be reconciled with it
 * is dropped, never guessed; duplicate entries for one key keep the
 * first reported value.
 */
public final class UsdaNutritionParser {

    private UsdaNutritionParser() {
    }

    public static NutritionProfile parse(JSONObject food,
                                         NutritionConfig config) {
        long fdcId = 0;
        try {
            if (food == null || config == null) {
                return NutritionProfile.empty(
                        food == null ? 0 : safeFdcId(food));
            }
            fdcId = safeFdcId(food);

            String servingUnit = food.optString("servingSizeUnit", "");
            String basis = "ml".equals(
                    NutritionProfile.normalizeServingUnit(servingUnit))
                    ? "ml" : "g";
            Double servingSize = null;
            if (food.has("servingSize") && !food.isNull("servingSize")) {
                double size = food.optDouble("servingSize", Double.NaN);
                // Contract §2: servingSize is null when unusable — zero,
                // negative, or non-finite sizes cannot scale per-100 values.
                if (Double.isFinite(size) && size > 0) {
                    servingSize = size;
                }
            }
            String household =
                    food.optString("householdServingFullText", "");

            Map<String, Double> per100 = new LinkedHashMap<>();
            JSONArray entries = food.optJSONArray("foodNutrients");
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    try {
                        parseEntry(entries.optJSONObject(i), config, per100);
                    } catch (Exception ignored) {
                        // One malformed entry must not lose the rest.
                    }
                }
            }
            return new NutritionProfile(fdcId, basis, servingSize,
                    servingUnit, household, per100);
        } catch (Exception e) {
            // Never throws (Contract §3): fall back to an empty profile.
            return NutritionProfile.empty(fdcId);
        }
    }

    private static long safeFdcId(JSONObject food) {
        try {
            return food.optLong("fdcId", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Parses one foodNutrients entry into {@code per100}, keyed by the
     * configured nutrient key. Skips unconfigured nutrients, entries with
     * no usable value, unit-irreconcilable values, and duplicates (the
     * first reported value for a key wins).
     */
    private static void parseEntry(JSONObject entry, NutritionConfig config,
                                   Map<String, Double> per100) {
        if (entry == null) {
            return;
        }
        final int usdaId;
        final double rawValue;
        final String unitName;

        JSONObject nested = entry.optJSONObject("nutrient");
        if (nested != null && nested.has("id")) {
            // /food shape: {nutrient: {id, unitName}, amount}
            usdaId = nested.optInt("id", -1);
            if (!entry.has("amount") || entry.isNull("amount")) {
                return;
            }
            rawValue = entry.optDouble("amount", Double.NaN);
            unitName = nested.optString("unitName", "");
        } else if (entry.has("nutrientId")) {
            // Search shape: {nutrientId, value, unitName}
            usdaId = entry.optInt("nutrientId", -1);
            if (!entry.has("value") || entry.isNull("value")) {
                return;
            }
            rawValue = entry.optDouble("value", Double.NaN);
            unitName = entry.optString("unitName", "");
        } else {
            return;
        }
        if (usdaId < 0 || Double.isNaN(rawValue)) {
            return;
        }

        NutrientDefinition def = config.byUsdaId.get(usdaId);
        if (def == null) {
            return; // unconfigured nutrient: ignored
        }
        if (per100.containsKey(def.key)) {
            return; // duplicate: first reported value wins
        }
        Double converted = convertToDefinitionUnit(rawValue, unitName,
                def.unit);
        if (converted == null) {
            return; // irreconcilable unit: dropped, never guessed
        }
        per100.put(def.key, converted);
    }

    /**
     * Converts {@code value} from the entry's unit to the definition's
     * unit. Mass units interconvert (g &lt;-&gt; mg, factor 1000); kcal
     * only matches kcal. Returns null when the units cannot be reconciled
     * (e.g. IU, a mass value for a kcal nutrient, a missing unit).
     */
    static Double convertToDefinitionUnit(double value, String entryUnit,
                                          String definitionUnit) {
        String from = normalizeNutrientUnit(entryUnit);
        String to = normalizeNutrientUnit(definitionUnit);
        if (from == null || to == null) {
            return null;
        }
        if (from.equals(to)) {
            return value;
        }
        if ("g".equals(from) && "mg".equals(to)) {
            return value * 1000.0;
        }
        if ("mg".equals(from) && "g".equals(to)) {
            return value / 1000.0;
        }
        return null;
    }

    /**
     * Normalizes a nutrient unit to "g", "mg", or "kcal"
     * (case-insensitive). Returns null for anything else.
     */
    private static String normalizeNutrientUnit(String unit) {
        if (unit == null) {
            return null;
        }
        String u = unit.trim().toLowerCase(Locale.US);
        if (u.equals("g") || u.equals("gram") || u.equals("grams")) {
            return "g";
        }
        if (u.equals("mg") || u.equals("milligram")
                || u.equals("milligrams")) {
            return "mg";
        }
        if (u.equals("kcal") || u.equals("kilocalorie")
                || u.equals("kilocalories")) {
            return "kcal";
        }
        return null;
    }
}
