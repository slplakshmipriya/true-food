package com.barelabel.app.util;

import com.barelabel.app.AlternateRanker;
import com.barelabel.app.model.ProductResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides whether a category (non-brand) search's Exact matches are
 * presented as a brand panel ("Brands of &lt;Product&gt;", Option C —
 * top brand spotlight) instead of the product-card stack, and builds
 * the panel's model when they are. Pure and Android-free so the
 * trigger and grouping rules are unit-testable.
 *
 * <p>The panel fires only when ALL of these hold (brand panel spec,
 * 2026-11-08):
 * <ul>
 *   <li>the search is a category search;</li>
 *   <li>the exact matches carry 2 or more distinct brands (products
 *       with an empty brand are ignored — they cannot form a row);</li>
 *   <li>every exact match shares the same USDA food category
 *       (normalized), so the set is one product type;</li>
 *   <li>every brand has at least one product with a computed Overall
 *       score — the panel promises a rating per brand, so a brand
 *       with no scoreable representative suppresses the panel rather
 *       than being silently dropped.</li>
 * </ul>
 * A brand's representative is its highest-Overall exact match (ties
 * go to the better rank). Brands are ordered by representative
 * Overall descending (ties by rank), capped at {@link #MAX_BRANDS};
 * the first entry is the spotlight.
 */
public final class BrandPanelDecider {

    /** Most brands the panel lists. */
    public static final int MAX_BRANDS = 8;

    private BrandPanelDecider() {
    }

    /** One brand row of the panel. */
    public static final class BrandEntry {
        /** Display brand (the representative's brand, trimmed). */
        public final String brand;
        /** The brand's representative exact match (tap target). */
        public final AlternateRanker.RankedProduct representative;
        /** The representative's Overall score. */
        public final int overall;
        /** How many exact matches this brand has in the set. */
        public final int productCount;

        public BrandEntry(String brand,
                          AlternateRanker.RankedProduct representative,
                          int overall, int productCount) {
            this.brand = brand;
            this.representative = representative;
            this.overall = overall;
            this.productCount = productCount;
        }
    }

    /** The panel model: title plus the ordered brand entries. */
    public static final class BrandPanelModel {
        /** Panel title, e.g. "Brands of Broccoli". */
        public final String title;
        /** Title-cased product label, e.g. "Broccoli". */
        public final String productLabel;
        /** Brands by Overall descending; index 0 is the spotlight. */
        public final List<BrandEntry> brands;

        public BrandPanelModel(String title, String productLabel,
                               List<BrandEntry> brands) {
            this.title = title;
            this.productLabel = productLabel;
            this.brands = brands;
        }
    }

    /**
     * Builds the brand panel model for a search's exact matches, or
     * null when the panel must not replace the product stack.
     *
     * @param categoryIntent whether the search is a category search
     * @param queryLabel     the raw search query (title-cased into
     *                       the panel title); blank suppresses
     * @param exactRanked    the Exact matches stack's ranked products
     */
    public static BrandPanelModel decide(boolean categoryIntent,
                                         String queryLabel,
                                         List<AlternateRanker.RankedProduct> exactRanked) {
        if (!categoryIntent || exactRanked == null || exactRanked.isEmpty()) {
            return null;
        }
        String label = productLabel(queryLabel);
        if (label.isEmpty()) {
            return null;
        }
        String category = null;
        Map<String, List<AlternateRanker.RankedProduct>> byBrand =
                new LinkedHashMap<>();
        for (AlternateRanker.RankedProduct rp : exactRanked) {
            if (rp == null || rp.product == null) {
                continue;
            }
            ProductResult p = rp.product;
            String cat = normalizeCategory(p.foodCategory);
            if (category == null) {
                category = cat;
            } else if (!category.equals(cat)) {
                return null;
            }
            String brand = p.brand == null ? "" : p.brand.trim();
            if (brand.isEmpty()) {
                continue;
            }
            String key = brand.toLowerCase(Locale.US);
            List<AlternateRanker.RankedProduct> group = byBrand.get(key);
            if (group == null) {
                group = new ArrayList<>();
                byBrand.put(key, group);
            }
            group.add(rp);
        }
        if (byBrand.size() < 2) {
            return null;
        }
        List<BrandEntry> entries = new ArrayList<>();
        for (List<AlternateRanker.RankedProduct> group : byBrand.values()) {
            AlternateRanker.RankedProduct rep = null;
            for (AlternateRanker.RankedProduct rp : group) {
                if (rp.overallScore == null) {
                    continue;
                }
                if (rep == null
                        || rp.overallScore.intValue() > rep.overallScore.intValue()
                        || (rp.overallScore.equals(rep.overallScore)
                            && rp.rank < rep.rank)) {
                    rep = rp;
                }
            }
            if (rep == null) {
                // A brand with no scoreable product: the panel cannot
                // keep its per-brand rating promise — fall back to
                // the product stack for the whole set.
                return null;
            }
            entries.add(new BrandEntry(
                    rep.product.brand.trim(), rep,
                    rep.overallScore.intValue(), group.size()));
        }
        Collections.sort(entries, new Comparator<BrandEntry>() {
            @Override
            public int compare(BrandEntry a, BrandEntry b) {
                if (a.overall != b.overall) {
                    return b.overall - a.overall;
                }
                return a.representative.rank - b.representative.rank;
            }
        });
        if (entries.size() > MAX_BRANDS) {
            entries = new ArrayList<>(entries.subList(0, MAX_BRANDS));
        }
        return new BrandPanelModel("Brands of " + label, label,
                Collections.unmodifiableList(entries));
    }

    /**
     * Title-cases a raw query into the panel's product label:
     * whitespace collapsed, each word's first letter upper-cased and
     * the rest lower-cased ("BROCCOLI" → "Broccoli",
     * "peanut butter" → "Peanut Butter"). Empty in, empty out.
     */
    public static String productLabel(String query) {
        if (query == null) {
            return "";
        }
        String trimmed = query.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] words = trimmed.split(" ");
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                out.append(' ');
            }
            String w = words[i];
            if (w.isEmpty()) {
                continue;
            }
            out.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) {
                out.append(w.substring(1).toLowerCase(Locale.US));
            }
        }
        return out.toString();
    }

    private static String normalizeCategory(String foodCategory) {
        if (foodCategory == null) {
            return "";
        }
        return foodCategory.trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.US);
    }
}
