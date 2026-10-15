package com.barelabel.app.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android-free dictionary spellcheck for OCR'd ingredient text. Kept out of
 * MainActivity so it stays unit-testable on the JVM (same split as
 * BarcodeSeed / TitleMatchScorer).
 *
 * <p>The dictionary is the set of words from the flagged-ingredient terms:
 * an OCR-mangled flagged word ("suger", "preservatlve") is the failure that
 * flips a verdict, so those are the words worth correcting. Correction is
 * deliberately conservative — a wrong auto-correction is worse than a missed
 * one — so a token is fixed only when exactly one dictionary word sits at
 * the minimum edit distance.
 *
 * <p>Rules: lowercase compare; tokens already in the dictionary pass through
 * untouched (case preserved); tokens shorter than 3 chars are never touched;
 * distance threshold is 1 for tokens shorter than 6 chars, 2 otherwise;
 * only whole alphabetic tokens are replaced, punctuation and separators are
 * preserved verbatim.
 *
 * <p>Distance is confusion-aware: substitutions the OCR engine makes far
 * more often than random typos ("0" for "O", "1" for "l") cost 0.5 instead
 * of 1, and merged/split glyphs ("m" read as "ll", turning "All" into "AM")
 * cost 0.6 via 1-to-2 character alignments.
 */
public final class OcrCorrector {

    private static final Pattern WORD = Pattern.compile("[A-Za-z]+");
    private static final int MIN_TOKEN_LENGTH = 3;
    private static final int LONG_TOKEN_LENGTH = 6;

    /**
     * Single-character OCR confusions: substitutions the recognizer makes
     * far more often than random typos ("c" for "e" in small print).
     * Stored as ordered pairs; lookup is symmetric by construction (both
     * orders are listed). Letter-only: the WORD tokenizer ([A-Za-z]+)
     * never passes digits to the corrector.
     */
    private static final double CONFUSION_COST = 0.5;
    private static final Set<String> CONFUSION_PAIRS = new HashSet<>(Arrays.asList(
            "ec", "ce"));

    /**
     * Merged/split glyphs: one character read as two or vice versa ("All"
     * read as "AM" when "ll" merges into "M"). Each entry is
     * "single|pair"; matching is tried in both directions.
     */
    private static final double MERGE_COST = 0.6;
    private static final Set<String> CONFUSION_MERGES = new HashSet<>(Arrays.asList(
            "m|ll", "m|rn", "m|nn",
            "w|vv", "d|cl"));

    private OcrCorrector() {
    }

    /**
     * Returns ocrText with unambiguous OCR misspellings corrected against
     * the dictionary. Never null; returns the input unchanged when there is
     * nothing to correct.
     */
    public static String correct(String ocrText, Set<String> dictionary) {
        if (ocrText == null || ocrText.isEmpty()
                || dictionary == null || dictionary.isEmpty()) {
            return ocrText == null ? "" : ocrText;
        }
        Set<String> dict = new HashSet<>();
        for (String w : dictionary) {
            if (w != null && !w.isEmpty()) {
                dict.add(w.toLowerCase(Locale.US));
            }
        }
        if (dict.isEmpty()) {
            return ocrText;
        }
        StringBuffer out = new StringBuffer(ocrText.length());
        Matcher m = WORD.matcher(ocrText);
        while (m.find()) {
            String token = m.group();
            String lower = token.toLowerCase(Locale.US);
            String replacement = token;
            if (!dict.contains(lower) && lower.length() >= MIN_TOKEN_LENGTH) {
                String best = uniqueBest(lower, dict);
                if (best != null) {
                    replacement = matchCase(token, best);
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Phrase-level correction for multi-word flagged terms ("all purpose
     * flour", "high fructose corn syrup"). Token-level correction cannot fix
     * phrases where several tokens are mangled at once ("AM purpose Fou":
     * "AM" is too short to touch, "Fou" is 2 edits from "flour").
     *
     * <p>For each window of words, the unique best term within a
     * length-scaled threshold (max(2, term length / 4), roughly 25%
     * normalized edit distance) replaces the whole span with the canonical
     * term. Same conservatism as {@link #correct}: tied matches are left
     * alone, exact matches are preserved verbatim (casing included), and
     * punctuation outside the word spans is untouched.
     */
    public static String correctPhrases(String text, Set<String> multiWordTerms) {
        if (text == null || text.isEmpty()
                || multiWordTerms == null || multiWordTerms.isEmpty()) {
            return text == null ? "" : text;
        }
        List<String> terms = new ArrayList<>();
        List<Integer> termWords = new ArrayList<>();
        for (String t : multiWordTerms) {
            if (t == null) {
                continue;
            }
            String norm = t.trim().toLowerCase(Locale.US).replaceAll("\\s+", " ");
            if (norm.contains(" ")) {
                terms.add(norm);
                termWords.add(norm.split(" ").length);
            }
        }
        if (terms.isEmpty()) {
            return text;
        }
        Matcher m = WORD.matcher(text);
        List<String> words = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        while (m.find()) {
            words.add(m.group().toLowerCase(Locale.US));
            spans.add(new int[]{m.start(), m.end()});
        }
        int n = words.size();
        if (n == 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int pos = 0;
        int i = 0;
        while (i < n) {
            String bestTerm = null;
            int bestK = 0;
            double bestDist = Double.MAX_VALUE;
            int bestCount = 0;
            for (int ti = 0; ti < terms.size(); ti++) {
                String term = terms.get(ti);
                int k = termWords.get(ti);
                if (i + k > n) {
                    continue;
                }
                StringBuilder cand = new StringBuilder();
                for (int j = 0; j < k; j++) {
                    if (j > 0) {
                        cand.append(' ');
                    }
                    cand.append(words.get(i + j));
                }
                int threshold = (int) Math.ceil(Math.max(2.0, term.length() / 4.0));
                if (Math.abs(cand.length() - term.length()) > threshold + 1) {
                    continue;
                }
                double d = confusionDistance(cand.toString(), term, threshold);
                if (d < 0 || d == 0) {
                    continue; // exact match or over threshold
                }
                if (d < bestDist) {
                    bestDist = d;
                    bestTerm = term;
                    bestK = k;
                    bestCount = 1;
                } else if (d == bestDist) {
                    bestCount++;
                }
            }
            if (bestTerm != null && bestCount == 1) {
                int start = spans.get(i)[0];
                int end = spans.get(i + bestK - 1)[1];
                out.append(text, pos, start);
                out.append(bestTerm);
                pos = end;
                i += bestK;
            } else {
                i++;
            }
        }
        out.append(text, pos, text.length());
        return out.toString();
    }

    /**
     * The single dictionary word at the minimum edit distance from token,
     * or null when there is no candidate within threshold or the minimum
     * distance is shared by two or more candidates (ambiguous).
     */
    private static String uniqueBest(String token, Set<String> dict) {
        double threshold = token.length() >= LONG_TOKEN_LENGTH ? 2.0 : 1.0;
        String best = null;
        double bestDist = Double.MAX_VALUE;
        int bestCount = 0;
        for (String cand : dict) {
            if (Math.abs(cand.length() - token.length()) > threshold + 1) {
                continue;
            }
            double d = confusionDistance(token, cand, threshold);
            if (d < 0) {
                continue;
            }
            if (d < bestDist) {
                bestDist = d;
                best = cand;
                bestCount = 1;
            } else if (d == bestDist) {
                bestCount++;
            }
        }
        return bestCount == 1 ? best : null;
    }

    /**
     * Weighted edit distance where known OCR confusions are cheaper than
     * random edits (single-char confusion: {@link #CONFUSION_COST};
     * merged/split glyph like "m" <-> "ll": {@link #MERGE_COST}; any other
     * substitution/insertion/deletion: 1). Supports 1-to-2 and 2-to-1
     * character alignments for the merge cases. Returns -1 when the
     * distance exceeds max (early exit on row minima).
     *
     * <p>Inputs are expected lowercased; the confusion tables are defined
     * for both cases so mixed input still works.
     */
    static double confusionDistance(String a, String b, double max) {
        int n = a.length();
        int m = b.length();
        double[][] dp = new double[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            double rowMin = dp[i][0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                char cb = b.charAt(j - 1);
                double best = Math.min(
                        dp[i - 1][j - 1] + subCost(ca, cb),
                        Math.min(dp[i - 1][j] + 1.0, dp[i][j - 1] + 1.0));
                if (j >= 2 && isMerge(ca, b.charAt(j - 2), cb)) {
                    best = Math.min(best, dp[i - 1][j - 2] + MERGE_COST);
                }
                if (i >= 2 && isMerge(cb, a.charAt(i - 2), ca)) {
                    best = Math.min(best, dp[i - 2][j - 1] + MERGE_COST);
                }
                dp[i][j] = best;
                rowMin = Math.min(rowMin, best);
            }
            if (rowMin > max) {
                return -1;
            }
        }
        return dp[n][m] <= max ? dp[n][m] : -1;
    }

    private static double subCost(char a, char b) {
        if (a == b) {
            return 0.0;
        }
        if (CONFUSION_PAIRS.contains("" + a + b)) {
            return CONFUSION_COST;
        }
        return 1.0;
    }

    private static boolean isMerge(char single, char first, char second) {
        return CONFUSION_MERGES.contains(single + "|" + first + second);
    }

    /** Levenshtein distance, or -1 when it exceeds max (early exit). Package-visible for reuse. */
    static int levenshteinBounded(String a, String b, int max) {
        int n = a.length();
        int m = b.length();
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1),
                        prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > max) {
                return -1;
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m] <= max ? prev[m] : -1;
    }

    private static String matchCase(String original, String corrected) {
        if (original.equals(original.toUpperCase(Locale.US))) {
            return corrected.toUpperCase(Locale.US);
        }
        return corrected;
    }
}
