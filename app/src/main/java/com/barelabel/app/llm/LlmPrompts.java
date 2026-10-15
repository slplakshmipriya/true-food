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
            "You are a food label assistant in the BareLabel app. You receive "
            + "a deterministic verdict (CLEAN or DIRTY), the flagged "
            + "ingredients with their categories, the full ingredient list, "
            + "the user's selected filters, any superior ingredients present, "
            + "and allergen advisory text. Explain in a friendly tone why "
            + "this product got its verdict. Name only the filters that "
            + "affected the verdict and the ingredients that triggered them; "
            + "do not list all the filters. If the verdict is CLEAN, confirm "
            + "briefly and highlight which superior ingredients are present "
            + "and why they are good. Mention the allergen advisory when it "
            + "is relevant. Never contradict the verdict. Never mention "
            + "ingredients not in the list. Keep it under 30 words. No "
            + "medical advice beyond what is on the label.";

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
