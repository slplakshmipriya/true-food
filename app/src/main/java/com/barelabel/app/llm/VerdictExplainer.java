package com.barelabel.app.llm;

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
