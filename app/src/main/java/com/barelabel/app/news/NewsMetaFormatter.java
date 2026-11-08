package com.barelabel.app.news;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * NEWS meta line (Contracts section 6.4): {@code <outlet> · <relative
 * date>}, or just the relative date when the outlet is empty.
 *
 * Relative dates are computed on UTC calendar days against the
 * reference instant: same day "today", previous day "yesterday",
 * 2-30 days "N days ago", anything older an absolute date such as
 * "Oct 9, 2026" (formatter-complete; the section 4 window makes the
 * absolute branch unreachable through the matcher). A publication
 * instant after the reference is treated as age 0 ("today"), matching
 * the matcher's future-dating rule.
 *
 * Pure JVM: java.text only, no java.time (minSdk 23).
 */
public final class NewsMetaFormatter {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private NewsMetaFormatter() {
    }

    /** Full meta line for one NEWS item. */
    public static String format(String outlet, long pubDateEpochMillis,
                                long referenceEpochMillis) {
        String relative = relativeDate(pubDateEpochMillis,
                referenceEpochMillis);
        if (outlet == null || outlet.isEmpty()) {
            return relative;
        }
        return outlet + " · " + relative;
    }

    /** Relative (or, past 30 days, absolute) date string on its own. */
    public static String relativeDate(long pubDateEpochMillis,
                                      long referenceEpochMillis) {
        long dayDiff = utcDayNumber(referenceEpochMillis)
                - utcDayNumber(pubDateEpochMillis);
        if (dayDiff <= 0) {
            return "today";
        }
        if (dayDiff == 1) {
            return "yesterday";
        }
        if (dayDiff <= 30) {
            return dayDiff + " days ago";
        }
        SimpleDateFormat absolute =
                new SimpleDateFormat("MMM d, yyyy", Locale.US);
        absolute.setTimeZone(UTC);
        return absolute.format(new Date(pubDateEpochMillis));
    }

    private static long utcDayNumber(long epochMillis) {
        // Floor division by hand: java.lang.Math.floorDiv only exists
        // on API 24+, and minSdk is 23.
        long days = epochMillis / DAY_MS;
        if (epochMillis % DAY_MS != 0 && epochMillis < 0) {
            days--;
        }
        return days;
    }
}
