package com.barelabel.app.search;

import com.barelabel.app.util.StringNormalizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Android-free 0-100 score for how well a USDA item title matches the user's
 * query. Used by {@link DisambiguationDecider} to decide whether a text
 * search should offer a choice list instead of auto-picking one verdict.
 *
 * <p>Scoring: 100 for normalized equality; 0 when no query token appears as a
 * whole word in the title; otherwise 100 * (matched query tokens / query
 * tokens) minus 2 points per extra title word, clamped to [0, 100].
 * Whole-word matching avoids the "breaded chicken" trap; the extra-word
 * penalty prefers the most specific title ("Cheez-It" beats
 * "Cheez-It Baked Snack Crackers" beats a 12-word description).
 *
 * <p>Apostrophes are dropped before normalizing so "world's" matches
 * "worlds" (StringNormalizer would otherwise split it into "world" + "s").
 * Null-safe: null/blank inputs score 0.
 */
public final class TitleMatchScorer {

    /** Points deducted per title word beyond the matched query tokens. */
    private static final int EXTRA_WORD_PENALTY = 2;

    private TitleMatchScorer() {
    }

    public static int score(String query, String title) {
        String nq = normalize(query);
        String nt = normalize(title);
        if (nq.isEmpty() || nt.isEmpty()) {
            return 0;
        }
        if (nq.equals(nt)) {
            return 100;
        }
        List<String> qTokens = tokens(nq);
        Set<String> tSet = new HashSet<>(tokens(nt));
        int matched = 0;
        for (String t : qTokens) {
            if (tSet.contains(t)) {
                matched++;
            }
        }
        if (matched == 0) {
            return 0;
        }
        int extra = tSet.size() - matched;
        int score = Math.round(100f * matched / qTokens.size())
                - EXTRA_WORD_PENALTY * extra;
        return Math.max(0, Math.min(100, score));
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return StringNormalizer.normalize(s.replace("'", ""));
    }

    private static List<String> tokens(String normalized) {
        List<String> out = new ArrayList<>();
        for (String t : normalized.split(" ")) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
