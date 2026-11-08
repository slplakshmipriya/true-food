package com.barelabel.app.news;

import android.content.Context;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * On-device response cache for the news feature (Contract section
 * 5), mirroring the app's USDA response cache: files under a
 * dedicated sub-directory of the app cache dir, one file per
 * (source, normalized-term-set hash), hashed filenames so a raw
 * search term never appears in a filename (section 8).
 *
 * <p>Each file holds a JSON array of the response bodies for
 * that term (one per openFDA term variant; a single body for RSS),
 * so the cached payload is JSON for both sources. The bodies are
 * stored exactly as the fetcher returned them — which is the
 * fetcher's line-normalized text, not byte-for-byte raw response
 * bytes: {@code UrlConnectionHttpFetcher} reads line by line and
 * rejoins with '\n' (NA-07, NA-03 review NOTE 1). Matching always
 * runs later, at display time, on whatever the clients parse out
 * of these bodies.
 *
 * <p>TTL is 24 h judged by file mtime; an expired entry is deleted
 * and reported as a miss. A read does NOT refresh the mtime: the
 * TTL guarantees content freshness, so the mtime is the fetch time.
 * The directory is capped at {@link #MAX_FILES} files; writes evict
 * the oldest-mtime files first (LRU by mtime). Nothing here ever
 * throws and nothing is logged (section 8).
 */
public final class NewsResponseCache {

    /** Cache source id for openFDA enforcement responses. */
    public static final String SOURCE_FDA = "fda";
    /** Cache source id for Google News RSS responses. */
    public static final String SOURCE_NEWS = "news";

    /** Contract section 5: 24 h TTL, judged by file mtime. */
    public static final long TTL_MILLIS = 24L * 60L * 60L * 1000L;
    /** Contract section 5: at most 100 cache files. */
    public static final int MAX_FILES = 100;

    private static final String CACHE_SUBDIR = "news_http_cache";

    private NewsResponseCache() {
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    /**
     * Hashed cache filename for (source, term): the source id, an
     * underscore, the SHA-256 hex of the canonical term set, and a
     * ".json" suffix. Never contains the raw term (section 8).
     */
    public static String fileNameFor(String source, String term) {
        return safeSource(source) + "_"
                + sha256Hex(canonicalTermSet(term)) + ".json";
    }

    /** The cache file for (source, term) under {@code baseCacheDir}. */
    public static File cacheFile(File baseCacheDir, String source,
                                 String term) {
        File dir = new File(baseCacheDir, CACHE_SUBDIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, fileNameFor(source, term));
    }

    /**
     * Canonical form of the normalized term SET (section 3.1): the
     * plural-folded form of every normalized token, deduplicated and
     * sorted, joined with single spaces. Case, punctuation, token
     * order and singular/plural spelling therefore share one key.
     */
    static String canonicalTermSet(String term) {
        Set<String> folded = new LinkedHashSet<>();
        for (String token : NewsText.tokens(term)) {
            folded.add(NewsText.foldToken(token));
        }
        List<String> sorted = new ArrayList<>(folded);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (String token : sorted) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(token);
        }
        return sb.toString();
    }

    private static String safeSource(String source) {
        if (source == null || source.isEmpty()) {
            return "src";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            // SHA-256 is guaranteed on every Java runtime; this is
            // only a defensive fallback and is never expected.
            return Integer.toHexString(input.hashCode());
        }
    }

    // ------------------------------------------------------------------
    // Read / write (File-based core: unit-testable without Android)
    // ------------------------------------------------------------------

    /**
     * The cached raw bodies for (source, term), or null on a miss:
     * no entry, entry older than the TTL (deleted), or an unreadable
     * / malformed cache file. Never throws.
     */
    public static List<String> get(File baseCacheDir, String source,
                                   String term) {
        if (baseCacheDir == null) {
            return null;
        }
        try {
            File file = cacheFile(baseCacheDir, source, term);
            if (!file.isFile()) {
                return null;
            }
            long age = System.currentTimeMillis() - file.lastModified();
            if (age > TTL_MILLIS) {
                file.delete();
                return null;
            }
            JSONArray array = new JSONArray(readAll(file));
            List<String> bodies = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                bodies.add(array.getString(i));
            }
            return bodies;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Stores the raw bodies for (source, term) and trims the cache
     * directory to the cap. Never throws.
     */
    public static void put(File baseCacheDir, String source, String term,
                           List<String> bodies) {
        if (baseCacheDir == null || bodies == null) {
            return;
        }
        try {
            JSONArray array = new JSONArray();
            for (String body : bodies) {
                array.put(body != null ? body : "");
            }
            File file = cacheFile(baseCacheDir, source, term);
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(array.toString()
                        .getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            trim(file.getParentFile());
        } catch (Exception e) {
            // A cache write failure only costs a future re-fetch.
        }
    }

    /** Context convenience overload (app cache dir as the base). */
    public static List<String> get(Context context, String source,
                                   String term) {
        if (context == null) {
            return null;
        }
        return get(context.getCacheDir(), source, term);
    }

    /** Context convenience overload (app cache dir as the base). */
    public static void put(Context context, String source, String term,
                           List<String> bodies) {
        if (context == null) {
            return;
        }
        put(context.getCacheDir(), source, term, bodies);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Evicts oldest-mtime files until at most MAX_FILES remain. */
    private static void trim(File dir) {
        try {
            File[] files = dir == null ? null : dir.listFiles();
            if (files == null || files.length <= MAX_FILES) {
                return;
            }
            List<File> sorted = new ArrayList<>(Arrays.asList(files));
            Collections.sort(sorted, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (int i = 0; i < sorted.size() - MAX_FILES; i++) {
                sorted.get(i).delete();
            }
        } catch (Exception e) {
            // Trimming is best-effort; the cap is a hygiene bound.
        }
    }

    private static String readAll(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        FileInputStream in = new FileInputStream(file);
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } finally {
            in.close();
        }
    }
}
