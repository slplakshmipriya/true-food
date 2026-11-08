package com.barelabel.app.news;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * openFDA food/enforcement client (Contract section 2.1).
 *
 * <p>One GET per term variant from {@link NewsText#termVariants}
 * (normalized term, plural-folded form, plural-expanded form;
 * deduplicated, max 3): multi-token variants are searched as a
 * quoted phrase, single tokens bare, always with
 * sort=report_date:desc and limit=100. The per-variant responses
 * are merged and deduped by event_id + recall_number BEFORE any
 * matching — matching itself is NA-01's {@link FdaRecallMatcher},
 * which consumes the {@link FdaRecord} list this client returns.
 *
 * <p>Raw responses go through {@link NewsResponseCache} (section
 * 5): one entry per term holding every variant body. Failure
 * behavior (section 7): if ANY variant request fails or returns a
 * malformed body, the whole FDA source yields an empty list,
 * nothing is cached, and nothing ever throws out of
 * {@link #fetchRecalls} — the news source is unaffected.
 */
public final class OpenFdaClient {

    /** Contract section 2.1 endpoint (no API key). */
    static final String BASE_URL =
            "https://api.fda.gov/food/enforcement.json";

    private final HttpFetcher fetcher;
    private final File cacheDir;

    /**
     * @param fetcher network seam (production:
     *        {@link UrlConnectionHttpFetcher})
     * @param cacheDir base cache dir (the app cache dir on device);
     *        {@link NewsResponseCache} adds its own sub-directory
     */
    public OpenFdaClient(HttpFetcher fetcher, File cacheDir) {
        this.fetcher = fetcher;
        this.cacheDir = cacheDir;
    }

    /** Production constructor: real transport, app cache dir. */
    public OpenFdaClient(Context context) {
        this(new UrlConnectionHttpFetcher(),
                context == null ? null : context.getCacheDir());
    }

    /**
     * The section 2.1 URL for one term variant: quoted
     * product_description phrase for multi-token variants, bare
     * token for single-token variants, plus sort and limit. The
     * search and sort values are URL-encoded as a whole.
     */
    public static String buildUrl(String variant) {
        String v = variant == null ? "" : variant;
        String searchValue;
        if (v.indexOf(' ') >= 0) {
            searchValue = "product_description:\"" + v + "\"";
        } else {
            searchValue = "product_description:" + v;
        }
        return BASE_URL + "?search=" + urlEncode(searchValue)
                + "&sort=" + urlEncode("report_date:desc")
                + "&limit=100";
    }

    /**
     * Fetches the merged, deduped raw record set for {@code term}
     * (a raw user search term; normalization and variants are
     * handled here). Serves the section 5 cache first. Returns an
     * empty list on any failure; never throws.
     */
    public List<FdaRecord> fetchRecalls(String term) {
        try {
            List<String> variants = NewsText.termVariants(term);
            if (variants.isEmpty() || fetcher == null) {
                return Collections.emptyList();
            }
            List<String> cached = NewsResponseCache.get(
                    cacheDir, NewsResponseCache.SOURCE_FDA, term);
            if (cached != null) {
                List<FdaRecord> fromCache = parseBodies(cached);
                if (fromCache != null) {
                    return fromCache;
                }
                // Malformed cache entry: fall through to the network
                // (a successful fetch overwrites it).
            }
            List<String> bodies = new ArrayList<>();
            List<FdaRecord> all = new ArrayList<>();
            for (String variant : variants) {
                String body = fetcher.fetch(buildUrl(variant));
                if (body == null) {
                    return Collections.emptyList();
                }
                List<FdaRecord> parsed = parseBody(body);
                if (parsed == null) {
                    // Malformed response: the whole source fails
                    // (section 7) and nothing is cached.
                    return Collections.emptyList();
                }
                bodies.add(body);
                all.addAll(parsed);
            }
            NewsResponseCache.put(cacheDir,
                    NewsResponseCache.SOURCE_FDA, term, bodies);
            return mergeDedupe(all);
        } catch (Throwable t) {
            // Section 7: the client boundary never throws.
            return Collections.emptyList();
        }
    }

    /**
     * Parses one openFDA response body into records via
     * {@link FdaRecordParser}. Returns null when the body is
     * malformed (not JSON, or no results array) — deliberately
     * distinct from a valid body with zero results, which returns
     * an empty list.
     */
    static List<FdaRecord> parseBody(String body) {
        if (body == null) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(body);
            if (!root.has("results") || root.isNull("results")) {
                return null;
            }
            JSONArray results = root.getJSONArray("results");
            List<FdaRecord> out = new ArrayList<>();
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) {
                    return null;
                }
                out.add(FdaRecordParser.parse(item));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Parses and merges several bodies; null if any is malformed. */
    private static List<FdaRecord> parseBodies(List<String> bodies) {
        List<FdaRecord> all = new ArrayList<>();
        for (String body : bodies) {
            List<FdaRecord> parsed = parseBody(body);
            if (parsed == null) {
                return null;
            }
            all.addAll(parsed);
        }
        return mergeDedupe(all);
    }

    /**
     * Section 2.1 dedupe: records are the same when BOTH event_id
     * and recall_number agree; the first occurrence wins and order
     * is preserved. Records carrying neither id cannot be proven
     * duplicates and are all kept.
     */
    static List<FdaRecord> mergeDedupe(List<FdaRecord> records) {
        List<FdaRecord> out = new ArrayList<>();
        if (records == null) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        int anonymousCount = 0;
        for (FdaRecord record : records) {
            if (record == null) {
                continue;
            }
            String key;
            if (record.eventId.isEmpty() && record.recallNumber.isEmpty()) {
                key = "\u0000anonymous-" + anonymousCount;
                anonymousCount++;
            } else {
                key = record.eventId + "\u0001" + record.recallNumber;
            }
            if (seen.add(key)) {
                out.add(record);
            }
        }
        return out;
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
