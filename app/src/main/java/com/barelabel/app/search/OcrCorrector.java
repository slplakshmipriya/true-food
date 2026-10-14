package com.barelabel.app.search;

import java.util.ArrayList;
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
 * edit-distance threshold is 1 for tokens shorter than 6 chars, 2 otherwise;
 * only whole alphabetic tokens are replaced, punctuation and separators are
 * preserved verbatim.
 */
public final class OcrCorrector {

    private static final Pattern WORD = Pattern.compile("[A-Za-z]+");
    private static final int MIN_TOKEN_LENGTH = 3;
    private static final int LONG_TOKEN_LENGTH = 6;

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
            int bestDist = Integer.MAX_VALUE;
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
                int threshold = Math.max(2, term.length() / 4);
                if (Math.abs(cand.length() - term.length()) > threshold) {
                    continue;
                }
                int d = levenshteinBounded(cand.toString(), term, threshold);
                if (d <= 0) {
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
        int threshold = token.length() >= LONG_TOKEN_LENGTH ? 2 : 1;
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        int bestCount = 0;
        for (String cand : dict) {
            if (Math.abs(cand.length() - token.length()) > threshold) {
                continue;
            }
            int d = levenshteinBounded(token, cand, threshold);
            if (d < 0 || d > threshold) {
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
