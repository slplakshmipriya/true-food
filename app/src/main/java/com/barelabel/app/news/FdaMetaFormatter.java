package com.barelabel.app.news;

/**
 * FDA meta line formatter (Contract §6.4):
 * {@code Class I · Reported Nov 2, 2026 · Ongoing} — classification
 * and status verbatim apart from whitespace trimming (NA-07,
 * NA-01 review M2: padded API values must not leak spaces into
 * the meta line), the date formatted from its YYYYMMDD int
 * with English month abbreviations. Missing parts are omitted
 * rather than rendered as blanks.
 */
public final class FdaMetaFormatter {

    private static final String[] MONTH_ABBR = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    private FdaMetaFormatter() {
    }

    public static String format(FdaRecord record) {
        if (record == null) {
            return "";
        }
        return format(record.classification, record.reportDateYyyymmdd,
                record.status);
    }

    public static String format(String classification,
                                int reportDateYyyymmdd, String status) {
        StringBuilder sb = new StringBuilder();
        if (classification != null && !classification.trim().isEmpty()) {
            sb.append(classification.trim());
        }
        String date = formatDate(reportDateYyyymmdd);
        if (!date.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" \u00b7 ");
            }
            sb.append("Reported ").append(date);
        }
        if (status != null && !status.trim().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" \u00b7 ");
            }
            sb.append(status.trim());
        }
        return sb.toString();
    }

    /** "Nov 2, 2026" for 20261102; "" for an invalid date. */
    public static String formatDate(int yyyymmdd) {
        if (!NewsDates.isValid(yyyymmdd)) {
            return "";
        }
        int month = NewsDates.monthOf(yyyymmdd);
        return MONTH_ABBR[month - 1] + " " + NewsDates.dayOf(yyyymmdd)
                + ", " + NewsDates.yearOf(yyyymmdd);
    }
}
