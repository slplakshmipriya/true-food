package com.barelabel.app.news;

import android.content.Context;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Google News RSS client (Contract section 2.2).
 *
 * <p>Builds the section 2.2 search URL — the normalized term plus
 * the pinned hazard-keyword group — fetches the feed through the
 * {@link HttpFetcher} seam, and hands the raw feed text to NA-02's
 * {@link RssFeedParser}; this class does no XML parsing of its own.
 * The raw feed is cached per term via {@link NewsResponseCache}
 * (section 5).
 *
 * <p>Failure behavior (section 7): any network error, non-200,
 * timeout, or malformed feed yields an empty list for this source
 * only, silently; {@link #fetchNews} never throws.
 */
public final class GoogleNewsClient {

    /** Contract section 2.2 endpoint. */
    static final String BASE_URL = "https://news.google.com/rss/search";

    /** Contract section 2.2 hazard-keyword query group, verbatim. */
    private static final String QUERY_SUFFIX =
            " (recall OR outbreak OR contamination OR salmonella"
            + " OR listeria OR \"e. coli\" OR hepatitis)";

    private final HttpFetcher fetcher;
    private final File cacheDir;
    private final RssFeedParser parser = new RssFeedParser();

    /**
     * @param fetcher network seam (production:
     *        {@link UrlConnectionHttpFetcher})
     * @param cacheDir base cache dir (the app cache dir on device);
     *        {@link NewsResponseCache} adds its own sub-directory
     */
    public GoogleNewsClient(HttpFetcher fetcher, File cacheDir) {
        this.fetcher = fetcher;
        this.cacheDir = cacheDir;
    }

    /** Production constructor: real transport, app cache dir. */
    public GoogleNewsClient(Context context) {
        this(new UrlConnectionHttpFetcher(),
                context == null ? null : context.getCacheDir());
    }

    /**
     * The section 2.2 URL for {@code term}: the normalized term
     * followed by the hazard-keyword group, the whole query
     * URL-encoded, with the hl / gl / ceid parameters appended
     * literally as the contract spells them.
     */
    public static String buildUrl(String term) {
        String query = NewsText.normalize(term) + QUERY_SUFFIX;
        return BASE_URL + "?q=" + urlEncode(query)
                + "&hl=en-US&gl=US&ceid=US:en";
    }

    /**
     * Fetches the raw RSS items for {@code term} (a raw user search
     * term), parsed by {@link RssFeedParser}. Serves the section 5
     * cache first. Returns an empty list on any failure; never
     * throws. Matching/filtering is NA-02's {@link NewsMatcher},
     * applied later at display time.
     */
    public List<RawNewsItem> fetchNews(String term) {
        try {
            if (term == null || NewsText.normalize(term).isEmpty()
                    || fetcher == null) {
                return Collections.emptyList();
            }
            List<String> cached = NewsResponseCache.get(
                    cacheDir, NewsResponseCache.SOURCE_NEWS, term);
            if (cached != null && !cached.isEmpty()) {
                return parser.parse(cached.get(0));
            }
            String body = fetcher.fetch(buildUrl(term));
            if (body == null || body.isEmpty()) {
                return Collections.emptyList();
            }
            List<String> bodies = new ArrayList<>();
            bodies.add(body);
            NewsResponseCache.put(cacheDir,
                    NewsResponseCache.SOURCE_NEWS, term, bodies);
            return parser.parse(body);
        } catch (Throwable t) {
            // Section 7: the client boundary never throws.
            return Collections.emptyList();
        }
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
