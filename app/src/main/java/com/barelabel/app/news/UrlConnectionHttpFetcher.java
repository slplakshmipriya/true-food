package com.barelabel.app.news;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Production {@link HttpFetcher} over {@link HttpURLConnection}
 * (the transport the app's USDA client already uses). Contract
 * section 7: 10 s connect and read timeouts per request; any
 * non-200 status is an {@link IOException}. No URL is ever logged —
 * URLs carry the search term (Contract section 8).
 */
public final class UrlConnectionHttpFetcher implements HttpFetcher {

    /** Contract section 7: 10 s per request. */
    public static final int CONNECT_TIMEOUT_MS = 10000;
    public static final int READ_TIMEOUT_MS = 10000;

    private static final String USER_AGENT =
            "BareLabel/1.0 (Android food ingredient screening app)";

    @Override
    public String fetch(String url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                // Status only: the URL (and its search term) is
                // never put into messages or logs (section 8).
                throw new IOException("HTTP " + code);
            }
            InputStream in = connection.getInputStream();
            try {
                return readAll(in);
            } finally {
                in.close();
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }
}
