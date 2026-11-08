package com.barelabel.app.news;

import org.json.JSONObject;

/**
 * Converts one openFDA enforcement {@code results} object into an
 * {@link FdaRecord}. Tolerates missing/null fields: strings default
 * to "" and an absent or unparseable {@code report_date} defaults
 * to 0. Never throws.
 */
public final class FdaRecordParser {

    private FdaRecordParser() {
    }

    public static FdaRecord parse(JSONObject result) {
        if (result == null) {
            return new FdaRecord("", "", "", "", "", 0, "", "");
        }
        try {
            return new FdaRecord(
                    stringField(result, "recalling_firm"),
                    stringField(result, "product_description"),
                    stringField(result, "reason_for_recall"),
                    stringField(result, "classification"),
                    stringField(result, "status"),
                    dateField(result, "report_date"),
                    stringField(result, "event_id"),
                    stringField(result, "recall_number"));
        } catch (Exception e) {
            return new FdaRecord("", "", "", "", "", 0, "", "");
        }
    }

    private static String stringField(JSONObject obj, String key) {
        try {
            if (!obj.has(key) || obj.isNull(key)) {
                return "";
            }
            String value = obj.optString(key, "");
            return value != null ? value : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static int dateField(JSONObject obj, String key) {
        try {
            if (!obj.has(key) || obj.isNull(key)) {
                return 0;
            }
            Object raw = obj.opt(key);
            if (raw instanceof Number) {
                return ((Number) raw).intValue();
            }
            String text = raw != null ? raw.toString().trim() : "";
            if (text.isEmpty()) {
                return 0;
            }
            return Integer.parseInt(text);
        } catch (Exception e) {
            return 0;
        }
    }
}
