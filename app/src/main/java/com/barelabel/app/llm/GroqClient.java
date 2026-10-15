package com.barelabel.app.llm;

import com.barelabel.app.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal client for Groq's OpenAI-compatible chat completions API.
 *
 * <p>Call off the main thread; blocks on network I/O. Returns null when the
 * API key is missing (set GROQ_API_KEY in local.properties), on any HTTP
 * error, or on timeout — callers fall back to local behavior.
 */
public final class GroqClient {

    private static final String API_URL =
            "https://api.groq.com/openai/v1/chat/completions";
    /**
     * Validated 2026-10-15 on the free tier for both prompts: best
     * instruction-following per output token (qwen followed the 30-word cap
     * and filter-naming rules; gpt-oss-20b also worked but burns ~8x the
     * reasoning tokens; allam-2-7b ignored the filter rule).
     */
    public static final String DEFAULT_MODEL = "qwen/qwen3.8-27b";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private GroqClient() {
    }

    /** Chat with the default model. Returns the assistant content, or null. */
    public static String chat(String system, String user,
                              double temperature, int maxTokens) {
        return chat(DEFAULT_MODEL, system, user, temperature, maxTokens);
    }

    /** Chat with an explicit model. Returns the assistant content, or null. */
    public static String chat(String model, String system, String user,
                              double temperature, int maxTokens) {
        String key = BuildConfig.GROQ_API_KEY;
        if (key == null || key.isEmpty()
                || model == null || system == null || user == null) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            String body = "{\"model\":\"" + jsonEscape(model) + "\","
                    + "\"messages\":["
                    + "{\"role\":\"system\",\"content\":\""
                    + jsonEscape(system) + "\"},"
                    + "{\"role\":\"user\",\"content\":\""
                    + jsonEscape(user) + "\"}],"
                    + "\"temperature\":" + temperature + ","
                    + "\"max_tokens\":" + maxTokens + "}";
            conn = (HttpURLConnection) new URL(API_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("Content-Type",
                    "application/json; charset=utf-8");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() != 200) {
                return null;
            }
            String content = parseContent(readAll(conn.getInputStream()));
            return (content == null || content.isEmpty()) ? null : content;
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
     * Extracts choices[0].message.content from a chat-completions response.
     * Returns null when absent, JSON null, or malformed.
     * Package-visible for JVM tests.
     */
    static String parseContent(String json) {
        if (json == null) {
            return null;
        }
        int msg = json.indexOf("\"message\"");
        if (msg < 0) {
            return null;
        }
        int key = json.indexOf("\"content\"", msg);
        if (key < 0) {
            return null;
        }
        int colon = json.indexOf(':', key + 9);
        if (colon < 0) {
            return null;
        }
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        if (p >= json.length() || json.charAt(p) != '"') {
            return null; // JSON null or unexpected shape
        }
        StringBuilder sb = new StringBuilder();
        for (int i = p + 1; i < json.length(); i++) {
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
