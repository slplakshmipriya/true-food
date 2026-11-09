package com.barelabel.app.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Brand+product query planning (pure, Android-free, unit-testable).
 *
 * <p>USDA's text search effectively ignores a brand token in a
 * multi-token query: "driscoll Strawberries" returns the same 14,927
 * hits as "strawberries" alone, the first page is topped by
 * token-dense yogurt records, and the one Driscoll's record
 * (fdcId 534514, "STRAWBERRIES FRAISES", owner DRISCOLL'S) is buried
 * beyond any useful page. The API's {@code brandOwner} search field
 * does find it — but loosely (it token-matches, so "New Seasons"
 * also surfaces SEAL THE SEASONS), which is why every brand-filtered
 * response is post-filtered here by exact token containment.
 *
 * <p>An <em>attempt</em> reads the query's leading tokens as a brand
 * (longest prefix first, at most {@link #MAX_BRAND_TOKENS} tokens)
 * and the rest as the product. Attempts exist only when a product
 * part remains — a lone brand token needs no fallback, because the
 * plain search matches a single brand token against brandOwner
 * already ("driscoll" alone returns exactly the Driscoll's record).
 */
public final class BrandQueryDecider {

    /** Most leading tokens treated as a brand ("New Seasons" = 2). */
    public static final int MAX_BRAND_TOKENS = 2;

    private BrandQueryDecider() {
    }

    /** One brand-filtered search to try. */
    public static final class BrandAttempt {
        /** Brand string for the API's brandOwner field. */
        public final String brandOwner;
        /** Remaining query tokens, space-joined (the product part). */
        public final String productQuery;
        /** Normalized brand tokens, for post-filter matching. */
        public final List<String> brandTokens;

        BrandAttempt(String brandOwner, String productQuery,
                     List<String> brandTokens) {
            this.brandOwner = brandOwner;
            this.productQuery = productQuery;
            this.brandTokens = brandTokens;
        }
    }

    /**
     * Attempts for a query, longest brand prefix first. Empty when
     * the query has fewer than 2 usable tokens or no product part
     * would remain. Brand tokens shorter than 2 characters never
     * start a brand (a stray "s" from a possessive is not a brand),
     * and the product part must be at least 2 characters.
     */
    public static List<BrandAttempt> attempts(String query) {
        List<String> tokens = tokens(query);
        if (tokens.size() < 2) {
            return Collections.emptyList();
        }
        List<BrandAttempt> out = new ArrayList<>();
        int maxPrefix = Math.min(MAX_BRAND_TOKENS, tokens.size() - 1);
        for (int prefix = maxPrefix; prefix >= 1; prefix--) {
            List<String> brandTokens = tokens.subList(0, prefix);
            if (brandTokens.get(0).length() < 2) {
                continue;
            }
            String product = join(tokens.subList(prefix, tokens.size()));
            if (product.length() < 2) {
                continue;
            }
            out.add(new BrandAttempt(join(brandTokens), product,
                    new ArrayList<>(brandTokens)));
        }
        return out;
    }

    /**
     * True when {@code brandText} (a record's label brand or owner)
     * contains EVERY token of {@code brandTokens}. Tokenization
     * splits on punctuation, so "DRISCOLL'S" yields {driscoll, s}
     * and matches the brand tokens {driscoll}; "SEAL THE SEASONS"
     * does not match {new, seasons} because "new" is absent.
     */
    public static boolean brandMatches(String brandText,
                                       List<String> brandTokens) {
        if (brandText == null || brandTokens == null
                || brandTokens.isEmpty()) {
            return false;
        }
        Set<String> have = new LinkedHashSet<>(tokens(brandText));
        for (String token : brandTokens) {
            if (!have.contains(token)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The fallback's fire condition: true when NONE of the candidate
     * brand texts matches ANY attempt's brand — i.e. the plain
     * search demonstrably failed to surface the brand the user
     * named, so a brand-filtered retry is worth its request.
     */
    public static boolean noBrandSurfaced(List<String> candidateBrands,
                                          List<BrandAttempt> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            return false;
        }
        if (candidateBrands == null) {
            return true;
        }
        for (BrandAttempt attempt : attempts) {
            for (String brand : candidateBrands) {
                if (brandMatches(brand, attempt.brandTokens)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Normalized tokens of a text, in order (lowercase alphanumerics). */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String lowered = text.toLowerCase(Locale.US);
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
