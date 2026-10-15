package com.barelabel.app.ocr;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Server-side BERT spellcheck for OCR'd ingredient text.
 *
 * The premium OCR pipeline calls this first: the extracted ingredient text
 * is POSTed to our spellcheck backend, which reranks edit-distance
 * candidates with a BERT masked-LM (context the on-device dictionary
 * corrector cannot see). Returns null when the server is unreachable,
 * times out, or errors — the caller falls back to the local OcrCorrector.
 *
 * <p>Call off the main thread; this blocks on network I/O.
 */
public final class ServerSpellChecker {

    /**
     * TODO: update after deploying spellcheck/ to Cloud Run.
     * Expected: https://barelabel-spellcheck-<project#>.us-east1.run.app/spellcheck
     */
    private static final String SPELLCHECK_URL =
            "https://barelabel-spellcheck-615466230528.us-east1.run.app/spellcheck";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_TEXT_CHARS = 2000;

    private ServerSpellChecker() {
    }

    /**
     * Returns the server-corrected text, or null on any failure.
     * Must be called off the main thread.
     */
    public static String correct(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String capped = text.length() > MAX_TEXT_CHARS
                ? text.substring(0, MAX_TEXT_CHARS) : text;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(SPELLCHECK_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            byte[] body = ("{\"text\":\"" + jsonEscape(capped) + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            if (conn.getResponseCode() != 200) {
                return null;
            }
            String corrected = parseCorrected(readAll(conn.getInputStream()));
            return (corrected == null || corrected.isEmpty()) ? null : corrected;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Minimal JSON string escaper for the request body. */
    static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Extracts the "corrected" string from the spellcheck JSON response.
     * Returns null when absent or malformed. Package-visible for JVM tests.
     */
    static String parseCorrected(String json) {
        if (json == null) {
            return null;
        }
        int key = json.indexOf("\"corrected\"");
        if (key < 0) {
            return null;
        }
        int colon = json.indexOf(':', key + 11);
        if (colon < 0) {
            return null;
        }
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = q1 + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                switch (n) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 5 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(
                                        json.substring(i + 2, i + 6), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append(n);
                            }
                        } else {
                            sb.append(n);
                        }
                        break;
                    default:
                        sb.append(n);
                        break;
                }
                i++;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }
}
