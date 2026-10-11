package com.barelabel.app.sharing;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Pure builders and parsers for BareLabel product share links.
 *
 * <p>Android-free so it stays JVM-testable (see ShareLinksTest). The share URL
 * doubles as the App Links deep link: https://barelabel.app/p/{gtin} opens the
 * product in the app when installed, and the same URL served by the share
 * backend renders Open Graph tags for rich chat previews.
 */
public final class ShareLinks {

    /** Public host for share/deep links. Change in one place if the domain moves. */
    public static final String SHARE_HOST = "barelabel.app";

    /** Play Store listing (placeholder until the app is published). */
    public static final String PLAY_STORE_URL =
            "https://play.google.com/store/apps/details?id=com.example.barelabel";

    private ShareLinks() {}

    /** Canonical deep link for a product: https://barelabel.app/p/{gtin} */
    public static String productUrl(String gtin) {
        return "https://" + SHARE_HOST + "/p/" + validatedGtin(gtin);
    }

    /**
     * Share URL carrying Open Graph hints as query params for the share
     * backend's preview renderer. Query params do not affect App Links
     * matching (intent-filter matches on path prefix only).
     *
     * @param isClean     verdict to show in the preview
     * @param score       Clean Score 0-100, or -1 when unavailable
     * @param flaggedCount number of flagged ingredients
     * @param name        product display name (may be null; ALL-CAPS names
     *                    are title-cased for the preview title)
     * @param imageUrl    product image URL (may be null)
     * @param merchants   retailer names carrying the item, cheapest first
     *                    (may be null; backend renders "Available at" chips)
     */
    public static String productUrl(String gtin, boolean isClean, int score,
                                    int flaggedCount, String name,
                                    String imageUrl, java.util.List<String> merchants) {
        StringBuilder sb = new StringBuilder(productUrl(gtin));
        sb.append("?v=").append(isClean ? "clean" : "dirty");
        sb.append("&score=").append(score);
        sb.append("&flagged=").append(Math.max(0, flaggedCount));
        if (name != null && !name.isEmpty()) {
            sb.append("&n=").append(encode(displayName(name)));
        }
        if (imageUrl != null && !imageUrl.isEmpty()) {
            sb.append("&img=").append(encode(imageUrl));
        }
        if (merchants != null && !merchants.isEmpty()) {
            StringBuilder joined = new StringBuilder();
            for (String m : merchants) {
                if (m == null) continue;
                String t = m.trim();
                if (t.isEmpty() || t.contains(",")) continue;
                if (joined.length() > 0) joined.append(',');
                joined.append(t);
            }
            if (joined.length() > 0) {
                sb.append("&m=").append(encode(joined.toString()));
            }
        }
        return sb.toString();
    }

    /** Variant without retailer hints. */
    public static String productUrl(String gtin, boolean isClean, int score,
                                    int flaggedCount, String name,
                                    String imageUrl) {
        return productUrl(gtin, isClean, score, flaggedCount, name, imageUrl,
                null);
    }

    /**
     * Display-friendly product name: title-cases ALL-CAPS names (the common
     * USDA/OFF form) and leaves mixed-case names untouched, so "McDonald's"
     * never becomes "Mcdonald's". Null/empty becomes "Unknown product".
     */
    public static String displayName(String name) {
        if (name == null || name.isEmpty()) return "Unknown product";
        boolean allCaps = true;
        boolean hasLetter = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (Character.isLowerCase(c)) {
                    allCaps = false;
                    break;
                }
            }
        }
        if (!allCaps || !hasLetter) return name;
        StringBuilder sb = new StringBuilder(name.length());
        boolean capNext = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetter(c)) {
                sb.append(capNext ? Character.toUpperCase(c)
                        : Character.toLowerCase(c));
                capNext = false;
            } else {
                sb.append(c);
                capNext = true;
            }
        }
        return sb.toString();
    }

    /**
     * Human-readable share message for the system share sheet. Compact and
     * scannable: bold product name (WhatsApp *...*), one context line
     * (brand · verdict · score), then the link. Reads well even where link
     * previews don't render; "via BareLabel" is dropped as redundant with
     * the barelabel.app domain in the URL.
     */
    public static String shareText(String name, String brand, boolean isClean,
                                   int score, int flaggedCount, String url) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(displayName(name)).append('*').append('\n');
        if (brand != null && !brand.isEmpty()) {
            sb.append(displayName(brand)).append(" \u00b7 ");
        }
        if (isClean) {
            sb.append("\u2713 CLEAN");
        } else {
            sb.append("\u26a0 DIRTY \u00b7 ").append(flaggedCount)
              .append(flaggedCount == 1 ? " flagged ingredient"
                      : " flagged ingredients");
        }
        if (score >= 0) {
            sb.append(" \u00b7 ").append(score).append("/100");
        }
        sb.append("\n\n").append(url);
        return sb.toString();
    }

    /**
     * Extracts the GTIN from a deep link. Returns null unless the host is the
     * share host and the path is exactly /p/{gtin} with a numeric GTIN.
     */
    public static String extractGtin(String host, String path) {
        if (host == null || path == null) return null;
        if (!SHARE_HOST.equalsIgnoreCase(host.trim())) return null;
        String p = path.trim();
        if (!p.startsWith("/p/")) return null;
        String gtin = p.substring(3);
        // Reject trailing segments: /p/{gtin} only.
        if (gtin.contains("/")) return null;
        if (!gtin.matches("[0-9]{8,14}")) return null;
        return gtin;
    }

    private static String validatedGtin(String gtin) {
        if (gtin == null || !gtin.matches("[0-9]{8,14}")) {
            throw new IllegalArgumentException("GTIN must be 8-14 digits: " + gtin);
        }
        return gtin;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is always present.
            throw new AssertionError(e);
        }
    }
}
