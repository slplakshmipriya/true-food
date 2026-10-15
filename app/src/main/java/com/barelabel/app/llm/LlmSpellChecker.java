package com.barelabel.app.llm;

/**
 * LLM contextual spellcheck for OCR'd ingredient labels: the final step of
 * the OCR -&gt; rudimentary spellcheck -&gt; LLM spellcheck pipeline.
 *
 * <p>The local {@code OcrCorrector} handles cheap deterministic fixes first
 * (phrase correction, confusion-aware edit distance); this fixes the
 * residual misspellings with full label context. Conservative by design:
 * ambiguous words such as brand names pass through untouched.
 *
 * <p>Call off the main thread; returns null on any failure so the caller
 * keeps the locally-corrected text.
 */
public final class LlmSpellChecker {

    private LlmSpellChecker() {
    }

    /** Returns the LLM-corrected text, or null on any failure. */
    public static String correct(String ocrText) {
        if (ocrText == null || ocrText.trim().isEmpty()) {
            return null;
        }
        String out = GroqClient.chat(
                LlmPrompts.SPELLCHECK_SYSTEM, ocrText, 0.0, 600);
        return (out == null || out.trim().isEmpty()) ? null : out.trim();
    }
}
