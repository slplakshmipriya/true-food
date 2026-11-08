package com.barelabel.app.llm;

import com.barelabel.app.model.ProductResult;
import com.barelabel.app.nutrition.NutrientComparison;
import com.barelabel.app.nutrition.NutrientDefinition;
import com.barelabel.app.nutrition.NutritionAnalysis;
import com.barelabel.app.nutrition.NutritionConfig;
import com.barelabel.app.nutrition.NutritionStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM verdict explanations: why a product was marked CLEAN or DIRTY, in the
 * user's context. The verdict itself stays deterministic (rule engine); the
 * LLM only narrates it. Call off the main thread; returns null when the
 * LLM is unreachable or the key is missing so callers fall back to the
 * rule-based text.
 */
public final class VerdictExplainer {

    /** One flagged ingredient and the category that flagged it. */
    public static final class Flag {
        public final String ingredient;
        public final String category;

        public Flag(String ingredient, String category) {
            this.ingredient = ingredient == null ? "" : ingredient;
            this.category = category == null ? "" : category;
        }
    }

    private VerdictExplainer() {
    }

    /**
     * Returns the explanation, or null on any failure. All list arguments
     * tolerate null/empty.
     */
    public static String explain(boolean isClean, int cleanScore,
                                 List<Flag> flagged, String ingredients,
                                 List<String> filters,
                                 List<String> superiorIngredients,
                                 String allergenAdvice) {
        String payload = buildPayload(isClean, cleanScore, flagged,
                ingredients, filters, superiorIngredients, allergenAdvice);
        String out = GroqClient.chat(
                LlmPrompts.EXPLAIN_SYSTEM, payload, 0.3, 150);
        return (out == null || out.trim().isEmpty()) ? null : out.trim();
    }

    /** Builds the JSON user message. Package-visible for JVM tests. */
    static String buildPayload(boolean isClean, int cleanScore,
                               List<Flag> flagged, String ingredients,
                               List<String> filters,
                               List<String> superiorIngredients,
                               String allergenAdvice) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"verdict\":\"").append(isClean ? "CLEAN" : "DIRTY")
                .append("\",\"clean_score\":").append(cleanScore)
                .append(",\"flagged\":[");
        if (flagged != null) {
            boolean first = true;
            for (Flag f : flagged) {
                if (f == null) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"ingredient\":\"")
                        .append(GroqClient.jsonEscape(f.ingredient))
                        .append("\",\"category\":\"")
                        .append(GroqClient.jsonEscape(f.category))
                        .append("\"}");
            }
        }
        sb.append("],\"ingredients\":\"")
                .append(GroqClient.jsonEscape(
                        ingredients == null ? "" : ingredients))
                .append("\",\"filters\":[");
        appendStrings(sb, filters);
        sb.append("],\"superior_ingredients\":[");
        appendStrings(sb, superiorIngredients);
        sb.append("],\"allergen_advice\":\"")
                .append(GroqClient.jsonEscape(
                        allergenAdvice == null ? "" : allergenAdvice))
                .append("\"}");
        return sb.toString();
    }

    private static void appendStrings(StringBuilder sb, List<String> items) {
        if (items == null) {
            return;
        }
        boolean first = true;
        for (String s : items) {
            if (s == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(GroqClient.jsonEscape(s)).append('"');
        }
    }

    // ---- Nutrition-aware explanation (Contract §8) --------------------

    /**
     * Nutrition-aware explanation: narrates both deterministic verdicts
     * (ingredient CLEAN/DIRTY and nutrition SAFE/CONCERNING) and the
     * blended overall score, using {@link LlmPrompts#EXPLAIN_NUTRITION_SYSTEM}
     * at temperature 0.3 with max tokens 220.
     *
     * <p>Fallback: when {@code analysis} is null, its status is
     * {@link NutritionStatus#UNAVAILABLE}, or {@code config} is null,
     * this delegates to the v1 {@link #explain(boolean, int, List,
     * String, List, List, String)} path. The v1 {@code cleanScore}
     * parameter receives the INGREDIENT score (not {@code overallScore}):
     * v1 semantics are ingredient-only, and {@code overallScore} may have
     * been blended with a nutrition score the v1 prompt knows nothing
     * about. Returns null on any LLM failure, exactly like v1.
     *
     * <p>Rule for downstream readers (NUT-11): the v1/v2 choice keys
     * off the analysis STATUS (and null analysis/config) — never off
     * score nullness. A CONCERNING analysis whose nutrition score is
     * null (e.g. a severe flag with coverage below the minimum) still
     * takes the v2 path; only the {@code nutrition_score} field inside
     * the v2 payload is omitted in that case (see
     * {@link #buildNutritionPayload}).
     */
    public static String explain(ProductResult product, int ingredientScore,
                                 NutritionAnalysis analysis, int overallScore,
                                 List<Flag> flagged, String ingredients,
                                 List<String> filters,
                                 List<String> superiorIngredients,
                                 String allergenAdvice,
                                 NutritionConfig config) {
        if (analysis == null
                || analysis.status == NutritionStatus.UNAVAILABLE
                || config == null) {
            return explain(isClean(product, flagged), ingredientScore,
                    flagged, ingredients, filters, superiorIngredients,
                    allergenAdvice);
        }
        String payload = buildNutritionPayload(product, ingredientScore,
                analysis, overallScore, flagged, ingredients, filters,
                superiorIngredients, allergenAdvice, config);
        String out = GroqClient.chat(
                LlmPrompts.EXPLAIN_NUTRITION_SYSTEM, payload, 0.3, 220);
        return (out == null || out.trim().isEmpty()) ? null : out.trim();
    }

    /**
     * Builds the JSON user message for the nutrition-aware explanation
     * (Contract §8 payload v2). Package-visible for JVM tests.
     *
     * <p>The ingredient verdict is CLEAN only when neither the product's
     * {@code flagged} set nor the {@code flagged} list carries an entry.
     * Percentile and peer values come from {@link NutrientComparison}
     * (display-rounded values, as in the contract example): a null
     * {@code peer_median} or percentile is omitted rather than emitted as
     * JSON null, and when both percentiles are null (e.g. a severe flag
     * without peers) {@code peer_count} is omitted too — a flagged
     * nutrient still always carries its amount and {@code flag_reason}.
     * {@code nutrient_highlights} holds the non-flagged comparisons whose
     * favorable percentile is ≤ 25 or ≥ 75, in config order, capped at 4,
     * and the key is omitted entirely when none qualify. A null
     * {@code nutritionScore} omits {@code nutrition_score}.
     *
     * <p>Fallback: when {@code analysis} is null, its status is
     * UNAVAILABLE, or {@code config} is null, this returns the v1 payload
     * string from {@link #buildPayload} with the ingredient score as its
     * {@code clean_score} — the same fallback {@link #explain} uses.
     */
    static String buildNutritionPayload(ProductResult product,
                                        int ingredientScore,
                                        NutritionAnalysis analysis,
                                        int overallScore,
                                        List<Flag> flagged,
                                        String ingredients,
                                        List<String> filters,
                                        List<String> superiorIngredients,
                                        String allergenAdvice,
                                        NutritionConfig config) {
        boolean clean = isClean(product, flagged);
        if (analysis == null
                || analysis.status == NutritionStatus.UNAVAILABLE
                || config == null) {
            return buildPayload(clean, ingredientScore, flagged,
                    ingredients, filters, superiorIngredients,
                    allergenAdvice);
        }
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"ingredient_verdict\":\"")
                .append(clean ? "CLEAN" : "DIRTY")
                .append("\",\"ingredient_score\":").append(ingredientScore)
                .append(",\"nutrition_status\":\"")
                .append(analysis.status.name()).append("\"");
        if (analysis.nutritionScore != null) {
            sb.append(",\"nutrition_score\":").append(analysis.nutritionScore);
        }
        sb.append(",\"overall_score\":").append(overallScore)
                .append(",\"blend\":{\"ingredient_weight\":")
                .append(formatNumber(config.ingredientWeight))
                .append(",\"nutrition_weight\":")
                .append(formatNumber(config.nutritionWeight))
                .append("},\"flagged\":[");
        appendFlags(sb, flagged);
        sb.append("],\"flagged_nutrients\":[");
        boolean firstNutrient = true;
        for (String key : analysis.flaggedNutrients) {
            if (key == null) {
                continue;
            }
            NutrientComparison comparison = analysis.comparisons.get(key);
            if (comparison == null) {
                continue;
            }
            if (!firstNutrient) {
                sb.append(',');
            }
            firstNutrient = false;
            appendNutrient(sb, comparison, config, true);
        }
        sb.append(']');
        List<NutrientComparison> highlights = new ArrayList<>();
        // Config order by construction: iterate the config's nutrient
        // definitions and look each comparison up, instead of relying on
        // the comparisons map's iteration order (which is the analyzer's
        // insertion order and only happens to match config order today).
        for (String key : config.byKey.keySet()) {
            NutrientComparison comparison = analysis.comparisons.get(key);
            if (comparison == null || comparison.flagged
                    || analysis.flaggedNutrients.contains(comparison.key)
                    || comparison.favorablePercentile == null) {
                continue;
            }
            double favorable = comparison.favorablePercentile;
            if (favorable <= 25.0 || favorable >= 75.0) {
                highlights.add(comparison);
                if (highlights.size() == 4) {
                    break;
                }
            }
        }
        if (!highlights.isEmpty()) {
            sb.append(",\"nutrient_highlights\":[");
            boolean firstHighlight = true;
            for (NutrientComparison comparison : highlights) {
                if (!firstHighlight) {
                    sb.append(',');
                }
                firstHighlight = false;
                appendNutrient(sb, comparison, config, false);
            }
            sb.append(']');
        }
        sb.append(",\"ingredients\":\"")
                .append(GroqClient.jsonEscape(
                        ingredients == null ? "" : ingredients))
                .append("\",\"filters\":[");
        appendStrings(sb, filters);
        sb.append("],\"superior_ingredients\":[");
        appendStrings(sb, superiorIngredients);
        sb.append("],\"allergen_advice\":\"")
                .append(GroqClient.jsonEscape(
                        allergenAdvice == null ? "" : allergenAdvice))
                .append("\"}");
        return sb.toString();
    }

    /**
     * CLEAN only when neither the product's flagged-ingredient set nor
     * the flagged list carries a (non-null) entry; a null product or
     * null set/list counts as empty on that side.
     */
    private static boolean isClean(ProductResult product,
                                   List<Flag> flagged) {
        if (product != null && product.flagged != null
                && !product.flagged.isEmpty()) {
            return false;
        }
        if (flagged != null) {
            for (Flag f : flagged) {
                if (f != null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The v1 flagged-ingredient array, shared by the v2 payload. */
    private static void appendFlags(StringBuilder sb, List<Flag> flagged) {
        if (flagged == null) {
            return;
        }
        boolean first = true;
        for (Flag f : flagged) {
            if (f == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"ingredient\":\"")
                    .append(GroqClient.jsonEscape(f.ingredient))
                    .append("\",\"category\":\"")
                    .append(GroqClient.jsonEscape(f.category))
                    .append("\"}");
        }
    }

    /**
     * One flagged-nutrient or highlight entry (Contract §8 shape). Null
     * amount/percentile/peer values are omitted, never emitted as JSON
     * null; {@code peer_count} is emitted only alongside a percentile.
     * Non-finite values (NaN, infinities) are treated exactly like null
     * and omitted — a bare {@code NaN}/{@code Infinity} token is not
     * valid JSON and would corrupt the payload.
     */
    private static void appendNutrient(StringBuilder sb,
                                       NutrientComparison comparison,
                                       NutritionConfig config,
                                       boolean includeFlagReason) {
        NutrientDefinition def = config.byKey.get(comparison.key);
        String label = def != null ? def.label : comparison.key;
        String unit = def != null ? def.unit : "";
        boolean hasRaw = isFinite(comparison.rawPercentile);
        boolean hasFavorable = isFinite(comparison.favorablePercentile);
        sb.append("{\"key\":\"")
                .append(GroqClient.jsonEscape(comparison.key))
                .append("\",\"label\":\"")
                .append(GroqClient.jsonEscape(label)).append("\"");
        if (isFinite(comparison.per100)) {
            sb.append(",\"amount_per_100\":")
                    .append(formatNumber(comparison.per100));
        }
        sb.append(",\"unit\":\"").append(GroqClient.jsonEscape(unit))
                .append("\"");
        if (hasRaw) {
            sb.append(",\"raw_percentile\":")
                    .append(formatNumber(comparison.rawPercentile));
        }
        if (hasFavorable) {
            sb.append(",\"favorable_percentile\":")
                    .append(formatNumber(comparison.favorablePercentile));
        }
        if (hasRaw || hasFavorable) {
            sb.append(",\"peer_count\":").append(comparison.peerCount);
        }
        if (isFinite(comparison.peerMedian)) {
            sb.append(",\"peer_median\":")
                    .append(formatNumber(comparison.peerMedian));
        }
        if (includeFlagReason) {
            sb.append(",\"flag_reason\":\"")
                    .append(GroqClient.jsonEscape(comparison.flagReason))
                    .append("\"");
        }
        sb.append('}');
    }

    /**
     * True when a nullable payload number is present and finite. NaN and
     * infinite values count as absent (see {@link #appendNutrient}).
     * Callers must guard with this before {@link #formatNumber(double)}.
     */
    private static boolean isFinite(Double value) {
        return value != null && Double.isFinite(value);
    }

    /**
     * JSON number formatting matching the Contract §8 example: integral
     * values render without a decimal point (680, 85, 410), others with
     * their natural decimal representation (0.7, 2.5). Callers guard
     * non-finite values with {@link #isFinite(Double)} — a bare NaN or
     * Infinity token is not valid JSON.
     */
    private static String formatNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1.0e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /** Convenience: flags from parallel ingredient/category lists. */
    public static List<Flag> flags(List<String> ingredients,
                                   List<String> categories) {
        List<Flag> out = new ArrayList<>();
        if (ingredients == null) {
            return out;
        }
        for (int i = 0; i < ingredients.size(); i++) {
            String cat = (categories != null && i < categories.size())
                    ? categories.get(i) : "";
            out.add(new Flag(ingredients.get(i), cat));
        }
        return out;
    }
}
