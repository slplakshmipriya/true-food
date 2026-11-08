package com.barelabel.app.nutrition;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        final String json;
        try {
            json = readAsset(context);
        } catch (Exception e) {
            // Not a config failure (e.g. a null-context NPE or a transient
            // asset read error): do NOT latch loadFailed — a later call may
            // succeed. Still fail closed for this call.
            Log.e(TAG, "Nutrition config could not be read; "
                    + "nutrition treated as UNAVAILABLE", e);
            return null;
        }
        try {
            cached = parse(json);
            return cached;
        } catch (NutritionConfigException e) {
            // Real config validation/parse failure (parse wraps any
            // JSONException from parsing the asset into this type): latch
            // so the failure is logged once and never retried, and never
            // fall back to hard-coded defaults.
            loadFailed = true;
            Log.e(TAG, "Nutrition config unavailable; "
                    + "nutrition treated as UNAVAILABLE", e);
            return null;
        }
    }

    /**
     * True only once a load attempt has failed validation/parsing — a
     * permanent failure that {@link #load} latches and never retries.
     * A transient asset-read failure returns {@code null} from
     * {@link #load} WITHOUT setting this, so callers that cache the
     * {@code load} result can use this to tell the two apart: latch
     * their own cache only on success or when this returns true, and
     * leave it unset after a transient failure so a later call retries.
     * (Strictly necessary for NUT-05 review Minor 2: {@code load}'s
     * {@code null} return alone does not distinguish the two kinds.)
     */
    public static synchronized boolean isPermanentlyFailed() {
        return loadFailed;
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
        // org.json collapses duplicate object keys (last wins), so the
        // §1.3 duplicate-key rejection must run on the raw token stream
        // before JSONObject ever sees the text.
        new RawDuplicateKeyChecker(json).check();
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
        if (ingredientWeight < 0 || nutritionWeight < 0) {
            throw new NutritionConfigException(
                    "blend weights must not be negative: " + ingredientWeight
                            + ", " + nutritionWeight);
        }
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
            if (ingredientAdvantage < 0 || focusedNutrition < 0
                    || overallNutrition < 0 || preferences < 0) {
                throw new NutritionConfigException("ranking profile " + name
                        + " has a negative weight");
            }
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
        Set<Integer> seenUsdaIds = new HashSet<>();
        for (int i = 0; i < nutrients.length(); i++) {
            JSONObject item = nutrients.getJSONObject(i);
            NutrientDefinition def = parseNutrient(item);
            if (byKey.containsKey(def.key)) {
                throw new NutritionConfigException(
                        "duplicate nutrient key: " + def.key);
            }
            // A USDA ID may belong to exactly one nutrient: otherwise
            // NutritionConfig.byUsdaId would silently overwrite.
            for (Integer usdaId : def.usdaIds) {
                if (!seenUsdaIds.add(usdaId)) {
                    throw new NutritionConfigException("duplicate USDA ID "
                            + usdaId + " (nutrient " + def.key + ")");
                }
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

        String unit = requireString(item, "unit");
        if (!"g".equals(unit) && !"mg".equals(unit) && !"kcal".equals(unit)) {
            throw new NutritionConfigException("nutrient " + key
                    + " has unknown unit: " + unit);
        }

        boolean flagEligible = requireBoolean(item, "flagEligible");
        Double flagPercentile = optionalDouble(item, "flagPercentile");
        Double flagFloor = optionalDouble(item, "flagFloor");
        Double severeAt = optionalDouble(item, "severeAt");
        Double lowFlagPercentile = optionalDouble(item, "lowFlagPercentile");
        Double lowFlagAtOrBelow = optionalDouble(item, "lowFlagAtOrBelow");
        if (flagEligible) {
            // A flag-eligible nutrient must carry the flag rule for its
            // direction, or its flags could never fire.
            if ("higher_bad".equals(direction)) {
                boolean relativeRule = flagPercentile != null
                        && flagFloor != null;
                if (!relativeRule && severeAt == null) {
                    throw new NutritionConfigException("nutrient " + key
                            + " is flagEligible but has no higher_bad flag"
                            + " rule (needs flagPercentile + flagFloor,"
                            + " or severeAt)");
                }
            } else if (lowFlagPercentile == null || lowFlagAtOrBelow == null) {
                throw new NutritionConfigException("nutrient " + key
                        + " is flagEligible but has no higher_good low"
                        + " flag rule (needs lowFlagPercentile +"
                        + " lowFlagAtOrBelow)");
            }
        }

        String fallbackKey = item.has("fallbackKey")
                && !item.isNull("fallbackKey")
                ? item.getString("fallbackKey") : null;

        return new NutrientDefinition(key, usdaIds,
                requireString(item, "label"), unit,
                direction, requireBoolean(item, "display"),
                requireBoolean(item, "scoreEligible"),
                flagEligible, weight,
                requireString(item, "scoringGroup"), plausibleMin,
                plausibleMax, goodAt, badAt,
                flagPercentile, flagFloor, severeAt,
                lowFlagPercentile, lowFlagAtOrBelow, fallbackKey,
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

    /**
     * Strict duplicate-key scan over the raw JSON text. org.json collapses
     * duplicate keys within one object (last wins) before any validation
     * could see them, so §1.3's duplicate rejection needs this pass: it
     * walks the token stream recursively, keeps a key set per object
     * nesting level, decodes string escapes (so a key written with a
     * {@code \\uXXXX} escape still compares equal to its plain form), and
     * throws on the second occurrence of a key within the same object.
     * The same key name in different objects is fine. Structural problems
     * found along the way are reported as malformed JSON — the subsequent
     * org.json parse would reject the same text.
     */
    private static final class RawDuplicateKeyChecker {

        private final String text;
        private int pos;

        RawDuplicateKeyChecker(String text) {
            this.text = text;
        }

        void check() throws NutritionConfigException {
            skipWhitespace();
            parseValue();
            skipWhitespace();
            if (pos != text.length()) {
                throw malformed("trailing content at offset " + pos);
            }
        }

        private void parseValue() throws NutritionConfigException {
            skipWhitespace();
            if (pos >= text.length()) {
                throw malformed("unexpected end of input");
            }
            char c = text.charAt(pos);
            if (c == '{') {
                parseObject();
            } else if (c == '[') {
                parseArray();
            } else if (c == '"') {
                parseString();
            } else {
                parseLiteral();
            }
        }

        private void parseObject() throws NutritionConfigException {
            pos++; // consume '{'
            Set<String> keys = new HashSet<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw malformed("expected object key at offset " + pos);
                }
                String key = parseString();
                if (!keys.add(key)) {
                    throw new NutritionConfigException(
                            "duplicate key in JSON object: \"" + key + "\"");
                }
                skipWhitespace();
                if (!peek(':')) {
                    throw malformed("expected ':' at offset " + pos);
                }
                pos++;
                parseValue();
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek('}')) {
                    pos++;
                    return;
                } else {
                    throw malformed("expected ',' or '}' at offset " + pos);
                }
            }
        }

        private void parseArray() throws NutritionConfigException {
            pos++; // consume '['
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return;
            }
            while (true) {
                parseValue();
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek(']')) {
                    pos++;
                    return;
                } else {
                    throw malformed("expected ',' or ']' at offset " + pos);
                }
            }
        }

        /** Parses a string literal and returns its decoded value. */
        private String parseString() throws NutritionConfigException {
            pos++; // consume opening '"'
            StringBuilder decoded = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return decoded.toString();
                }
                if (c == '\\') {
                    decoded.append(parseEscape());
                } else {
                    decoded.append(c);
                }
            }
            throw malformed("unterminated string");
        }

        private char parseEscape() throws NutritionConfigException {
            if (pos >= text.length()) {
                throw malformed("unterminated escape");
            }
            char e = text.charAt(pos++);
            switch (e) {
                case '"':
                    return '"';
                case '\\':
                    return '\\';
                case '/':
                    return '/';
                case 'b':
                    return '\b';
                case 'f':
                    return '\f';
                case 'n':
                    return '\n';
                case 'r':
                    return '\r';
                case 't':
                    return '\t';
                case 'u':
                    if (pos + 4 > text.length()) {
                        throw malformed("truncated unicode escape");
                    }
                    try {
                        int code = Integer.parseInt(
                                text.substring(pos, pos + 4), 16);
                        pos += 4;
                        return (char) code;
                    } catch (NumberFormatException ex) {
                        throw malformed("invalid unicode escape");
                    }
                default:
                    throw malformed("invalid escape: \\" + e);
            }
        }

        private void parseLiteral() throws NutritionConfigException {
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ',' || c == '}' || c == ']' || c == ' '
                        || c == '\t' || c == '\n' || c == '\r') {
                    break;
                }
                pos++;
            }
            if (pos == start) {
                throw malformed("expected a value at offset " + pos);
            }
        }

        private boolean peek(char expected) {
            return pos < text.length() && text.charAt(pos) == expected;
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        private NutritionConfigException malformed(String detail) {
            return new NutritionConfigException(
                    "malformed nutrition config JSON: " + detail);
        }
    }
}
