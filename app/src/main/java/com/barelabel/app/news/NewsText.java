package com.barelabel.app.news;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared text normalization (Contract §3.1). Pure JVM, static only.
 *
 * <p>Normalization: lowercase, punctuation replaced with spaces,
 * whitespace collapsed, then tokenized. Plural fold: a token ending
 * "ies" becomes "y"; a token ending "s" (not "ss") loses one "s".
 * Token sets keep BOTH the raw and the folded form of every token,
 * so matching is insensitive to singular/plural on either side.
 */
public final class NewsText {

    private NewsText() {
    }

    /** Normalized form of {@code text}: "" for null/blank input. */
    public static String normalize(String text) {
        List<String> tokens = tokens(text);
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(token);
        }
        return sb.toString();
    }

    /** Normalized tokens of {@code text}, in order; empty for null. */
    public static List<String> tokens(String text) {
        if (text == null) {
            return Collections.emptyList();
        }
        String lowered = text.toLowerCase(Locale.US);
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < lowered.length(); i++) {
            char c = lowered.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                current.append(c);
            } else if (current.length() > 0) {
                out.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /**
     * Plural fold of one normalized token (Contract §3.1):
     * "ies" -&gt; "y", else a trailing "s" that is not "ss" is
     * stripped once. Tokens too short to fold are returned as-is.
     */
    public static String foldToken(String token) {
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

    /**
     * Token set for any text: every normalized token plus its folded
     * form (Contract §3.1, "both kept in the token set").
     */
    public static Set<String> tokenSet(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String token : tokens(text)) {
            out.add(token);
            out.add(foldToken(token));
        }
        return Collections.unmodifiableSet(out);
    }

    /** Query token set (Contract §3.1): same semantics as tokenSet. */
    public static Set<String> queryTokens(String text) {
        return tokenSet(text);
    }

    /**
     * Term variants for the openFDA queries (Contract §2.1):
     * the normalized term, the all-tokens-folded term, and the
     * plural-expanded term, deduplicated in that order (max 3).
     * The plural expansion touches only the last token: a trailing
     * "y" becomes "ies", otherwise "s" is appended when the token
     * does not already end in "s".
     */
    public static List<String> termVariants(String text) {
        List<String> rawTokens = tokens(text);
        if (rawTokens.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> variants = new LinkedHashSet<>();
        variants.add(join(rawTokens));

        List<String> folded = new ArrayList<>();
        for (String token : rawTokens) {
            folded.add(foldToken(token));
        }
        variants.add(join(folded));

        List<String> expanded = new ArrayList<>(rawTokens);
        int lastIndex = expanded.size() - 1;
        expanded.set(lastIndex, expandToken(expanded.get(lastIndex)));
        variants.add(join(expanded));

        return Collections.unmodifiableList(new ArrayList<>(variants));
    }

    private static String expandToken(String token) {
        if (token.endsWith("y")) {
            return token.substring(0, token.length() - 1) + "ies";
        }
        if (!token.endsWith("s")) {
            return token + "s";
        }
        return token;
    }

    private static String join(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(token);
        }
        return sb.toString();
    }
}
