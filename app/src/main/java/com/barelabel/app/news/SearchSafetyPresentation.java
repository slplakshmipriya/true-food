package com.barelabel.app.news;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure presentation decisions for the search-results safety
 * surface (NA-05; Contracts sections 6.2, 6.3 and 6.5). No Android,
 * no I/O: the views in MainActivity and
 * {@code SearchSafetyBinder} only render what these methods decide.
 *
 * <p>Section 6.2 count line: present iff the combined alert count
 * (FDA + NEWS, section 4) is at least 1, with the exact copy
 * {@code <N> safety report(s) for "<term>" — tap a flagged item to
 * see them} — singular "report" iff N == 1, and the term shown
 * verbatim (the user's raw search text, never normalized).
 *
 * <p>Section 6.3 row badges: a row shows the badge iff the NA-04
 * badge rule says so — this class delegates to
 * {@link SafetyAlertService#hasFdaBadge} (the same rule behind the
 * service's {@code badgeForProduct}) and never re-derives it, so
 * NEWS alerts can never badge a row.
 */
public final class SearchSafetyPresentation {

    /** Section 6.3 badge copy, verbatim. */
    public static final String BADGE_TEXT = "⚠ recall";

    private SearchSafetyPresentation() {
    }

    /** One search-result row's product identity, for badge decisions. */
    public static final class RowProduct {
        public final String brand;
        public final String name;

        public RowProduct(String brand, String name) {
            this.brand = brand;
            this.name = name;
        }
    }

    // ------------------------------------------------------------------
    // Section 6.2 count line
    // ------------------------------------------------------------------

    /**
     * The count-line text for {@code alertCount} combined alerts
     * and the raw {@code rawSearchTerm}, or null when no line may
     * be shown (count below 1, or a null/blank term).
     */
    public static String countLineText(int alertCount,
                                       String rawSearchTerm) {
        if (alertCount < 1 || rawSearchTerm == null
                || rawSearchTerm.trim().isEmpty()) {
            return null;
        }
        return alertCount
                + (alertCount == 1
                        ? " safety report for \""
                        : " safety reports for \"")
                + rawSearchTerm
                + "\" \u2014 tap a flagged item to see them";
    }

    /** Section 6.2: the line is present iff the count is at least 1. */
    public static boolean isCountLineVisible(int alertCount) {
        return alertCount >= 1;
    }

    /** Combined alert count (section 4): null reads as zero. */
    public static int combinedCount(List<SafetyAlert> alerts) {
        return alerts == null ? 0 : alerts.size();
    }

    // ------------------------------------------------------------------
    // Section 6.3 row badges
    // ------------------------------------------------------------------

    /**
     * Badge decision for one row, delegated unchanged to the NA-04
     * rule ({@link SafetyAlertService#hasFdaBadge}): an FDA alert
     * whose product description carries both a brand token and
     * another name token of the row's product. NEWS alerts in the
     * combined list are ignored by that rule.
     */
    public static boolean badgeVisible(String brand, String productName,
                                       List<SafetyAlert> alerts) {
        return SafetyAlertService.hasFdaBadge(brand, productName, alerts);
    }

    /**
     * Badge decisions for a list of rows, aligned with the input
     * order: entry i is the decision for row i. A null row list
     * yields an empty result; a null row entry yields false.
     */
    public static List<Boolean> badgeVisibilityForRows(
            List<RowProduct> rows, List<SafetyAlert> alerts) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<Boolean> out = new ArrayList<>();
        for (RowProduct row : rows) {
            out.add(row != null
                    && badgeVisible(row.brand, row.name, alerts));
        }
        return Collections.unmodifiableList(out);
    }
}
