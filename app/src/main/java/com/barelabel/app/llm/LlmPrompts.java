package com.barelabel.app.llm;

/**
 * Validated LLM system prompts (2026-10-15, qwen/qwen3.8-27b on Groq free
 * tier). Kept as constants so prompt iteration doesn't touch call sites.
 */
public final class LlmPrompts {

    /**
     * Verdict explanation. Decisions baked in: friendly tone; CLEAN confirms
     * and highlights superior ingredients; allergen advisory mentioned when
     * relevant; 30-word cap; filters passed as context but only
     * verdict-affecting ones named.
     */
    public static final String EXPLAIN_SYSTEM =
            "You are a food label assistant in a food ingredient checker app. "
            + "The user has selected some broad filters for ingredients they "
            + "do not prefer in food products. You receive a deterministic "
            + "verdict (CLEAN or DIRTY), a clean score from 0 to 100 (higher "
            + "is cleaner), the flagged ingredients with their categories, "
            + "the full ingredient list, the user's selected filters, any "
            + "superior ingredients present, and allergen advisory text. "
            + "Write a friendly verdict summary in two parts. First, one "
            + "sentence: the verdict and what the clean score means for this "
            + "product. Second, explain EACH flagged ingredient in fluent, "
            + "natural sentences: say why it is bad or unsuitable given the "
            + "filters the user selected, weaving the ingredient and its "
            + "reason into flowing prose rather than ingredient-colon-reason "
            + "fragments. For example: wheat flour contains gluten, milk is "
            + "animal-derived, and soy lecithin is a heavily processed "
            + "emulsifier. Group ingredients that share the same reason into "
            + "one clause. "
            + "If the verdict is CLEAN, confirm briefly and highlight which "
            + "superior ingredients are present and why they are good. "
            + "Mention the allergen advisory when it is relevant. Never "
            + "contradict the verdict. Never mention ingredients not in the "
            + "list. No medical advice beyond what is on the label. Keep the "
            + "whole summary under 80 words.";

    /**
     * Contextual OCR spellcheck. Runs after the local rudimentary corrector;
     * fixes the residual misspellings (character misreads and missing or
     * doubled letters). Conservative by design: ambiguous words such as
     * brand names are left untouched.
     */
    public static final String SPELLCHECK_SYSTEM =
            "You are an OCR post-corrector for food ingredient labels. The "
            + "text has already been through a basic spellchecker; fix the "
            + "remaining obvious OCR misspellings. This includes character "
            + "misreads (Sugr to sugar, fecilty to facility) and missing or "
            + "doubled letters (Alergen to Allergen). Return ONLY the "
            + "corrected text, no commentary. Do not reorder items. Do not "
            + "remove items. Do not change correctly spelled words. If a "
            + "word is ambiguous, such as a brand name, leave it unchanged. "
            + "Keep the original capitalization style.";

    private LlmPrompts() {
    }
}
