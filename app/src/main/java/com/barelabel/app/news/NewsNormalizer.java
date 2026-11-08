package com.barelabel.app.news;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Contracts section 3.1 normalization, NEWS-side copy.
 *
 * Lowercase; punctuation replaced with spaces; whitespace collapsed;
 * tokenize. Plural fold: a token ending "ies" becomes "y"; a token
 * ending "s" (but not "ss") loses one trailing "s". Token SETS built
 * here contain both the raw and the folded form of every token.
 *
 * NA-01 owns the shared section 3.1 implementation on its branch; this
 * small package-private copy exists so NA-02 is self-contained, and
 * the coordinator reconciles the duplication at merge time.
 */
final class NewsNormalizer {

    private NewsNormalizer() {
    }

    /** Normalized tokens of {@code text}, in order, unfolded. */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String spaced = text.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ");
        for (String token : spaced.split(" ")) {
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    /** Plural fold of a single normalized token (section 3.1). */
    static String fold(String token) {
        if (token == null) {
            return "";
        }
        if (token.length() > 3 && token.endsWith("ies")) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.length() > 1 && token.endsWith("s")
                && !token.endsWith("ss")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    /** Folded form of every token, in order. */
    static List<String> foldedTokens(String text) {
        List<String> raw = tokens(text);
        List<String> out = new ArrayList<>(raw.size());
        for (String token : raw) {
            out.add(fold(token));
        }
        return out;
    }

    /** Token set containing each token's raw AND folded form. */
    static Set<String> tokenSet(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String token : tokens(text)) {
            out.add(token);
            out.add(fold(token));
        }
        return out;
    }

    /** Raw + folded expansion of an already-tokenized query set. */
    static Set<String> expandTokens(Set<String> queryTokens) {
        Set<String> out = new LinkedHashSet<>();
        if (queryTokens == null) {
            return out;
        }
        for (String queryToken : queryTokens) {
            out.addAll(tokenSet(queryToken));
        }
        return out;
    }
}
