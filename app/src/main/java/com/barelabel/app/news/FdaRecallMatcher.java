package com.barelabel.app.news;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FDA matcher (Contract §3.2), filter/order/cap (§4) and alert
 * construction (§1, §6.4). Pure JVM, no network.
 *
 * <p>Match: any (expanded) query token appears as a WHOLE token in
 * the record's normalized product_description or reason_for_recall
 * token set. Filter, literally: keep iff the status is "Ongoing"
 * (case-insensitive) OR the report date is within 90 days before
 * the reference date (0 &lt;= refDay - reportDay &lt;= 90).
 */
public final class FdaRecallMatcher {

    /** FDA-only category keyword map (Contract §3.2, exhaustive v1). */
    public static final Map<String, String> CATEGORY_MAP;

    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("chicken", "poultry");
        CATEGORY_MAP = Collections.unmodifiableMap(map);
    }

    private static final int MAX_ALERTS = 5;
    private static final int RECENT_WINDOW_DAYS = 90;
    private static final int MAX_TEXT_LENGTH = 220;
    private static final String ELLIPSIS = "\u2026";
    private static final String FDA_RECALLS_URL =
            "https://www.fda.gov/safety/recalls-market-withdrawals-safety-alerts";

    private FdaRecallMatcher() {
    }

    public static List<SafetyAlert> match(List<FdaRecord> records,
                                          Set<String> queryTokens,
                                          int referenceDateYyyymmdd) {
        if (records == null || records.isEmpty()
                || queryTokens == null || queryTokens.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> effective = effectiveQueryTokens(queryTokens);
        if (effective.isEmpty()) {
            return Collections.emptyList();
        }
        int referenceDay = NewsDates.epochDayFromYyyymmdd(
                referenceDateYyyymmdd);

        List<FdaRecord> kept = new ArrayList<>();
        for (FdaRecord record : records) {
            if (record == null) {
                continue;
            }
            if (!matches(record, effective)) {
                continue;
            }
            if (!passesFilter(record, referenceDay)) {
                continue;
            }
            kept.add(record);
        }

        Collections.sort(kept, new Comparator<FdaRecord>() {
            @Override
            public int compare(FdaRecord a, FdaRecord b) {
                int byClass = Integer.compare(
                        SafetyAlert.classRank(a.classification),
                        SafetyAlert.classRank(b.classification));
                if (byClass != 0) {
                    return byClass;
                }
                // report_date desc: YYYYMMDD ints order chronologically.
                return Integer.compare(b.reportDateYyyymmdd,
                        a.reportDateYyyymmdd);
            }
        });

        List<SafetyAlert> alerts = new ArrayList<>();
        for (FdaRecord record : kept) {
            if (alerts.size() >= MAX_ALERTS) {
                break;
            }
            alerts.add(toAlert(record));
        }
        return alerts;
    }

    /**
     * Query tokens plus their folds and the §3.2 category-map
     * expansions (the map applies to the raw and the folded form,
     * and the mapped value is folded too).
     */
    static Set<String> effectiveQueryTokens(Set<String> queryTokens) {
        Set<String> out = new LinkedHashSet<>();
        for (String token : queryTokens) {
            if (token == null || token.isEmpty()) {
                continue;
            }
            out.add(token);
            String folded = NewsText.foldToken(token);
            out.add(folded);
            String mapped = CATEGORY_MAP.get(token);
            if (mapped == null) {
                mapped = CATEGORY_MAP.get(folded);
            }
            if (mapped != null) {
                out.add(mapped);
                out.add(NewsText.foldToken(mapped));
            }
        }
        return out;
    }

    private static boolean matches(FdaRecord record,
                                   Set<String> effectiveTokens) {
        Set<String> haystack = new LinkedHashSet<>();
        haystack.addAll(NewsText.tokenSet(record.productDescription));
        haystack.addAll(NewsText.tokenSet(record.reasonForRecall));
        for (String token : effectiveTokens) {
            if (haystack.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean passesFilter(FdaRecord record,
                                        int referenceDay) {
        if (record.status != null
                && "Ongoing".equalsIgnoreCase(record.status.trim())) {
            return true;
        }
        if (!NewsDates.isValid(record.reportDateYyyymmdd)) {
            return false;
        }
        int age = referenceDay
                - NewsDates.epochDayFromYyyymmdd(record.reportDateYyyymmdd);
        return age >= 0 && age <= RECENT_WINDOW_DAYS;
    }

    private static SafetyAlert toAlert(FdaRecord record) {
        String link = FDA_RECALLS_URL + "?search_api_fulltext="
                + urlEncode(record.recallNumber);
        return new SafetyAlert(
                SafetyAlert.Tier.FDA,
                record.recallingFirm,
                trimTo220(record.productDescription),
                trimTo220(record.reasonForRecall),
                FdaMetaFormatter.format(record),
                "FDA recall",
                link,
                nullIfEmpty(record.classification),
                nullIfEmpty(record.status),
                nullIfEmpty(record.eventId),
                nullIfEmpty(record.recallNumber),
                record.reportDateYyyymmdd);
    }

    /**
     * §6.4 trim: at most 220 chars including the trailing ellipsis
     * when cut; cuts at a word boundary when one is available in
     * range, otherwise hard-cuts. Null stays null; the input is
     * whitespace-trimmed first.
     */
    public static String trimTo220(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() <= MAX_TEXT_LENGTH) {
            return trimmed;
        }
        int limit = MAX_TEXT_LENGTH - ELLIPSIS.length();
        int cut = trimmed.lastIndexOf(' ', limit);
        // A word boundary is only practical when it is near the
        // limit; an early space (one very long token) hard-cuts.
        if (cut <= 0 || cut < limit - 40) {
            cut = limit;
        }
        return trimmed.substring(0, cut) + ELLIPSIS;
    }

    private static String nullIfEmpty(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value;
    }

    private static String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
