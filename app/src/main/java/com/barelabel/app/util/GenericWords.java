package com.barelabel.app.util;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Generic food words a human would type when searching ("bread", "peanut butter",
 * "ice cream"). Used by MainActivity.isCategorySearch: a query token only counts
 * as "naming the brand" when it is NOT generic, so a query for "bread" never
 * matches the brand token in "The Bread Factory Inc." while "panera" still does.
 *
 * Loaded once per process from assets/generic_words.txt (one human-typed phrase
 * per line, # comments); phrases are tokenized with StringNormalizer.wordTokens
 * so multi-word entries contribute their individual tokens.
 */
public final class GenericWords {
    private static final String TAG = "GenericWords";
    private static final String ASSET_FILE = "generic_words.txt";
    private static final Object LOCK = new Object();
    private static volatile Set<String> cachedTokens = null;

    private GenericWords() {
    }

    private static Set<String> load(Context context) {
        Set<String> hit = cachedTokens;
        if (hit != null) return hit;
        synchronized (LOCK) {
            if (cachedTokens != null) return cachedTokens;
            Set<String> out = new HashSet<>();
            try {
                if (context != null && context.getAssets() != null) {
                    try (InputStream is = context.getAssets().open(ASSET_FILE);
                         BufferedReader reader = new BufferedReader(
                                 new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String trimmed = line.trim();
                            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                            out.addAll(StringNormalizer.wordTokens(trimmed));
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load " + ASSET_FILE + " from assets", e);
            }
            cachedTokens = out;
            return out;
        }
    }

    /**
     * True when the token is a generic food word. Tokens from
     * StringNormalizer.wordTokens are already lowercase; null-safe.
     */
    public static boolean isGeneric(Context context, String token) {
        if (token == null || token.isEmpty()) return false;
        return load(context).contains(token.toLowerCase(Locale.US));
    }

    /** Number of distinct generic tokens loaded (for diagnostics/tests). */
    public static int count(Context context) {
        return load(context).size();
    }
}
