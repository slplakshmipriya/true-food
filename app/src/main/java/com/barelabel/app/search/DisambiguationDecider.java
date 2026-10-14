package com.barelabel.app.search;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Android-free decision: which USDA candidates, if any, to offer as a
 * disambiguation list for a text search. Kept out of MainActivity so it
 * stays unit-testable on the JVM (same split as
 * {@link com.barelabel.app.util.CategorySearchDecider}).
 *
 * <p>Rules: score every candidate's description against the query with
 * {@link TitleMatchScorer}. A perfect 100 means the product is unambiguous,
 * so no list (the caller shows the verdict directly). When the best score is
 * strictly above {@link SearchConfig#DISAMBIGUATION_SCORE_THRESHOLD}, return
 * the top {@link SearchConfig#DISAMBIGUATION_MAX_OPTIONS} candidates by
 * score (ties keep USDA rank order). Otherwise return an empty list and the
 * caller keeps the old single-pick behavior.
 */
public final class DisambiguationDecider {

    private DisambiguationDecider() {
    }

    public static List<JSONObject> disambiguate(String query,
                                                List<JSONObject> candidates) {
        List<JSONObject> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()
                || candidates == null || candidates.size() < 2) {
            return out;
        }
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            JSONObject c = candidates.get(i);
            int s = TitleMatchScorer.score(
                    query, c != null ? c.optString("description", "") : "");
            scored.add(new Scored(c, s, i));
        }
        int best = 0;
        for (Scored s : scored) {
            best = Math.max(best, s.score);
        }
        if (best >= 100 || best <= SearchConfig.DISAMBIGUATION_SCORE_THRESHOLD) {
            return out;
        }
        Collections.sort(scored, new Comparator<Scored>() {
            @Override
            public int compare(Scored a, Scored b) {
                if (a.score != b.score) {
                    return b.score - a.score;
                }
                return a.rank - b.rank;
            }
        });
        int n = Math.min(scored.size(), SearchConfig.DISAMBIGUATION_MAX_OPTIONS);
        for (int i = 0; i < n; i++) {
            out.add(scored.get(i).candidate);
        }
        return out;
    }

    private static class Scored {
        final JSONObject candidate;
        final int score;
        final int rank;

        Scored(JSONObject candidate, int score, int rank) {
            this.candidate = candidate;
            this.score = score;
            this.rank = rank;
        }
    }
}
