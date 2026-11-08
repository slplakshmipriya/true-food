package com.barelabel.app.nutrition;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Loads and validates the nutrition configuration (Contract §1).
 *
 * <p>{@link #parse(String)} is pure (org.json only) and unit-testable: it
 * returns a validated {@link NutritionConfig} or throws
 * {@link NutritionConfigException} on any §1.3 violation — malformed JSON,
 * duplicate nutrient keys, unknown direction, non-positive weight,
 * {@code plausibleMin >= plausibleMax}, anchors equal or outside the
 * plausible range, blend or ranking-profile weights not summing to 1.0
 * (±0.001), {@code minPeers < 1}, or coverage outside (0, 1].
 *
 * <p>{@link #load(Context)} reads the bundled asset
 * {@code nutrition_config.json} once and caches the result as a singleton.
 * Failure handling (chosen for NUT-01, per Contract §1.3): on any failure
 * {@code load} logs the error once and returns {@code null}; callers must
 * treat a {@code null} config as nutrition UNAVAILABLE. The loader never
 * falls back to hard-coded defaults.
 */
public final class NutritionConfigLoader {

    private static final String TAG = "NutritionConfigLoader";
    private static final String ASSET_NAME = "nutrition_config.json";
    private static final double SUM_TOLERANCE = 0.001;

    private static NutritionConfig cached;
    private static boolean loadFailed;

    private NutritionConfigLoader() {
    }

    /**
     * Returns the cached config, loading the asset on first call. Returns
     * {@code null} when the asset cannot be read or fails validation —
     * callers treat {@code null} as nutrition UNAVAILABLE.
     */
    public static synchronized NutritionConfig load(Context context) {
        if (cached != null) {
            return cached;
        }
        if (loadFailed) {
            return null;
        }
        try {
            String json = readAsset(context);
            cached = parse(json);
            return cached;
        } catch (Exception e) {
            // Fail closed: log once, no hard-coded defaults.
            loadFailed = true;
            Log.e(TAG, "Nutrition config unavailable; "
                    + "nutrition treated as UNAVAILABLE", e);
            return null;
        }
    }

    private static String readAsset(Context context) throws IOException {
        InputStream in = context.getApplicationContext().getAssets()
                .open(ASSET_NAME);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    /**
     * Parses and validates a nutrition configuration.
     *
     * @throws NutritionConfigException on malformed JSON or any §1.3
     *                                  validation violation
     */
    public static NutritionConfig parse(String json)
            throws NutritionConfigException {
        if (json == null) {
            throw new NutritionConfigException("null nutrition config JSON");
        }
        final JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            throw new NutritionConfigException(
                    "malformed nutrition config JSON", e);
        }
        try {
            return parseRoot(root);
        } catch (JSONException e) {
            throw new NutritionConfigException(
                    "malformed nutrition config JSON", e);
        }
    }

    private static NutritionConfig parseRoot(JSONObject root)
            throws JSONException, NutritionConfigException {
        int version = requireInt(root, "version");

        JSONObject blend = requireObject(root, "blend");
        double ingredientWeight = requireDouble(blend, "ingredientWeight");
        double nutritionWeight = requireDouble(blend, "nutritionWeight");
        if (Math.abs(ingredientWeight + nutritionWeight - 1.0)
                > SUM_TOLERANCE) {
            throw new NutritionConfigException(
                    "blend weights do not sum to 1.0: " + ingredientWeight
                            + " + " + nutritionWeight);
        }

        JSONObject peers = requireObject(root, "peers");
        int minPeers = requireInt(peers, "minPeers");
        if (minPeers < 1) {
            throw new NutritionConfigException("minPeers < 1: " + minPeers);
        }
        int preferredPeers = requireInt(peers, "preferredPeers");
        int maxCategoryPages = requireInt(peers, "maxCategoryPages");
        int roundBelowPreferredToNearest =
                requireInt(peers, "roundBelowPreferredToNearest");

        JSONObject scoring = requireObject(root, "scoring");
        double minCoverage = requireDouble(scoring, "minCoverage");
        if (minCoverage <= 0 || minCoverage > 1.0) {
            throw new NutritionConfigException(
                    "minCoverage outside (0, 1]: " + minCoverage);
        }
        double relativeWeight = requireDouble(scoring, "relativeWeight");
        double absoluteWeight = requireDouble(scoring, "absoluteWeight");

        JSONObject alternatives = requireObject(root, "alternatives");
        int alternativesMaxPagesPerQuery =
                requireInt(alternatives, "maxPagesPerQuery");
        int alternativesPoolCap = requireInt(alternatives, "poolCap");

        Map<String, RankingProfile> rankingProfiles =
                parseRankingProfiles(requireObject(root, "rankingProfiles"));

        Map<String, NutrientDefinition> byKey =
                parseNutrients(requireArray(root, "nutrients"));
        // Fallback keys must resolve to a configured nutrient.
        for (NutrientDefinition def : byKey.values()) {
            if (def.fallbackKey != null && !byKey.containsKey(def.fallbackKey)) {
                throw new NutritionConfigException("nutrient " + def.key
                        + " has unknown fallbackKey " + def.fallbackKey);
            }
        }

        return new NutritionConfig(version, ingredientWeight, nutritionWeight,
                minPeers, preferredPeers, maxCategoryPages,
                roundBelowPreferredToNearest, minCoverage, relativeWeight,
                absoluteWeight, alternativesMaxPagesPerQuery,
                alternativesPoolCap, byKey, rankingProfiles);
    }

    private static Map<String, RankingProfile> parseRankingProfiles(
            JSONObject profiles) throws JSONException,
            NutritionConfigException {
        Map<String, RankingProfile> result = new LinkedHashMap<>();
        Iterator<String> keys = profiles.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            JSONObject profile = requireObject(profiles, name);
            double ingredientAdvantage =
                    requireDouble(profile, "ingredientAdvantage");
            double focusedNutrition =
                    requireDouble(profile, "focusedNutrition");
            double overallNutrition =
                    requireDouble(profile, "overallNutrition");
            double preferences = requireDouble(profile, "preferences");
            double sum = ingredientAdvantage + focusedNutrition
                    + overallNutrition + preferences;
            if (Math.abs(sum - 1.0) > SUM_TOLERANCE) {
                throw new NutritionConfigException("ranking profile " + name
                        + " weights do not sum to 1.0: " + sum);
            }
            result.put(name, new RankingProfile(ingredientAdvantage,
                    focusedNutrition, overallNutrition, preferences));
        }
        String[] required = {"dirty_concerning", "dirty_only",
                "nutrition_only"};
        for (String name : required) {
            if (!result.containsKey(name)) {
                throw new NutritionConfigException(
                        "missing ranking profile: " + name);
            }
        }
        return result;
    }

    private static Map<String, NutrientDefinition> parseNutrients(
            JSONArray nutrients) throws JSONException,
            NutritionConfigException {
        Map<String, NutrientDefinition> byKey = new LinkedHashMap<>();
        for (int i = 0; i < nutrients.length(); i++) {
            JSONObject item = nutrients.getJSONObject(i);
            NutrientDefinition def = parseNutrient(item);
            if (byKey.containsKey(def.key)) {
                throw new NutritionConfigException(
                        "duplicate nutrient key: " + def.key);
            }
            byKey.put(def.key, def);
        }
        if (byKey.isEmpty()) {
            throw new NutritionConfigException("no nutrients configured");
        }
        return byKey;
    }

    private static NutrientDefinition parseNutrient(JSONObject item)
            throws JSONException, NutritionConfigException {
        String key = requireString(item, "key");

        JSONArray ids = requireArray(item, "usdaIds");
        if (ids.length() == 0) {
            throw new NutritionConfigException(
                    "nutrient " + key + " has no usdaIds");
        }
        List<Integer> usdaIds = new ArrayList<>();
        for (int i = 0; i < ids.length(); i++) {
            usdaIds.add(ids.getInt(i));
        }

        String direction = requireString(item, "direction");
        if (!"higher_good".equals(direction) && !"higher_bad".equals(direction)) {
            throw new NutritionConfigException("nutrient " + key
                    + " has unknown direction: " + direction);
        }

        double weight = requireDouble(item, "weight");
        if (weight <= 0) {
            throw new NutritionConfigException("nutrient " + key
                    + " has non-positive weight: " + weight);
        }

        double plausibleMin = requireDouble(item, "plausibleMin");
        double plausibleMax = requireDouble(item, "plausibleMax");
        if (plausibleMin >= plausibleMax) {
            throw new NutritionConfigException("nutrient " + key
                    + " has plausibleMin >= plausibleMax: " + plausibleMin
                    + " >= " + plausibleMax);
        }

        double goodAt = requireDouble(item, "goodAt");
        double badAt = requireDouble(item, "badAt");
        if (goodAt == badAt) {
            throw new NutritionConfigException("nutrient " + key
                    + " has equal anchors: " + goodAt);
        }
        if (goodAt < plausibleMin || goodAt > plausibleMax
                || badAt < plausibleMin || badAt > plausibleMax) {
            throw new NutritionConfigException("nutrient " + key
                    + " has an anchor outside the plausible range ["
                    + plausibleMin + ", " + plausibleMax + "]: goodAt="
                    + goodAt + ", badAt=" + badAt);
        }

        String fallbackKey = item.has("fallbackKey")
                && !item.isNull("fallbackKey")
                ? item.getString("fallbackKey") : null;

        return new NutrientDefinition(key, usdaIds,
                requireString(item, "label"), requireString(item, "unit"),
                direction, requireBoolean(item, "display"),
                requireBoolean(item, "scoreEligible"),
                requireBoolean(item, "flagEligible"), weight,
                requireString(item, "scoringGroup"), plausibleMin,
                plausibleMax, goodAt, badAt,
                optionalDouble(item, "flagPercentile"),
                optionalDouble(item, "flagFloor"),
                optionalDouble(item, "severeAt"),
                optionalDouble(item, "lowFlagPercentile"),
                optionalDouble(item, "lowFlagAtOrBelow"), fallbackKey,
                optionalDouble(item, "fallbackWeightFactor"));
    }

    private static JSONObject requireObject(JSONObject parent, String name)
            throws JSONException, NutritionConfigException {
        if (!parent.has(name) || parent.isNull(name)) {
            throw new NutritionConfigException("missing object: " + name);
        }
        return parent.getJSONObject(name);
    }

    private static JSONArray requireArray(JSONObject parent, String name)
            throws JSONException, NutritionConfigException {
        if (!parent.has(name) || parent.isNull(name)) {
            throw new NutritionConfigException("missing array: " + name);
        }
        return parent.getJSONArray(name);
    }

    private static String requireString(JSONObject parent, String name)
            throws JSONException, NutritionConfigException {
        if (!parent.has(name) || parent.isNull(name)) {
            throw new NutritionConfigException("missing string: " + name);
        }
        return parent.getString(name);
    }

    private static boolean requireBoolean(JSONObject parent, String name)
            throws JSONException, NutritionConfigException {
        if (!parent.has(name) || parent.isNull(name)) {
            throw new NutritionConfigException("missing boolean: " + name);
        }
        return parent.getBoolean(name);
    }

    private static int requireInt(JSONObject parent, String name)
            throws JSONException, NutritionConfigException {
        if (!parent.has(name) || parent.isNull(name)) {
            throw new NutritionConfigException("missing int: " + name);
        }
        return parent.getInt(name);
    }

    private static double requireDouble(JSONObject parent, String name)
            throws JSONException, NutritionConfigException {
        if (!parent.has(name) || parent.isNull(name)) {
            throw new NutritionConfigException("missing number: " + name);
        }
        return parent.getDouble(name);
    }

    private static Double optionalDouble(JSONObject parent, String name)
            throws JSONException {
        if (!parent.has(name) || parent.isNull(name)) {
            return null;
        }
        return parent.getDouble(name);
    }
}
