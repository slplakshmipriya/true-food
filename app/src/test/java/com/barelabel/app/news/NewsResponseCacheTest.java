package com.barelabel.app.news;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * NewsResponseCache: Contract §5 (one file per (source,
 * normalized-term-set hash), JSON payloads, 24 h TTL by file mtime,
 * cap 100 files LRU-evicted by mtime) and §8 (cache filenames are
 * hashed — never a raw search term).
 */
public class NewsResponseCacheTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File cacheBase() throws Exception {
        return temp.newFolder("cachebase");
    }

    private static List<String> bodies(String... bodies) {
        return Arrays.asList(bodies);
    }

    // ------------------------------------------------------------------
    // Key hashing (§5 / §8)
    // ------------------------------------------------------------------

    @Test
    public void fileName_sameTermSetSameKey() throws Exception {
        String base = NewsResponseCache.fileNameFor("fda", "Strawberry");
        assertEquals(base, NewsResponseCache.fileNameFor("fda", "strawberry"));
        assertEquals(base, NewsResponseCache.fileNameFor("fda", "  strawberry  "));
        // Token order is not part of the term SET.
        assertEquals(NewsResponseCache.fileNameFor("fda", "eggs chicken"),
                NewsResponseCache.fileNameFor("fda", "chicken eggs"));
        // Plural-folded equivalents share a key.
        assertEquals(NewsResponseCache.fileNameFor("fda", "strawberries"),
                NewsResponseCache.fileNameFor("fda", "strawberry"));
    }

    @Test
    public void fileName_differentTermOrSourceDifferentKey() throws Exception {
        String fdaStrawberry = NewsResponseCache.fileNameFor("fda", "strawberry");
        assertFalse(fdaStrawberry.equals(
                NewsResponseCache.fileNameFor("fda", "blueberry")));
        assertFalse(fdaStrawberry.equals(
                NewsResponseCache.fileNameFor("news", "strawberry")));
    }

    @Test
    public void fileName_neverContainsRawTerm() throws Exception {
        String name = NewsResponseCache.fileNameFor("fda", "strawberry");
        assertFalse(name.contains("strawberry"));
        assertFalse(name.contains("Strawberry"));
        assertTrue(name.startsWith("fda_"));
        assertTrue(name.endsWith(".json"));
        String newsName = NewsResponseCache.fileNameFor("news", "ice cream");
        assertFalse(newsName.contains("ice"));
        assertFalse(newsName.contains("cream"));
        assertTrue(newsName.startsWith("news_"));
    }

    // ------------------------------------------------------------------
    // Roundtrip / miss / malformed
    // ------------------------------------------------------------------

    @Test
    public void putThenGet_roundTripsRawBodies() throws Exception {
        File base = cacheBase();
        NewsResponseCache.put(base, "fda", "strawberry",
                bodies("{\"results\":[]}", "{\"results\":[1]}"));
        List<String> got = NewsResponseCache.get(base, "fda", "strawberry");
        assertNotNull(got);
        assertEquals(2, got.size());
        assertEquals("{\"results\":[]}", got.get(0));
        assertEquals("{\"results\":[1]}", got.get(1));
    }

    @Test
    public void get_missingEntryReturnsNull() throws Exception {
        File base = cacheBase();
        assertNull(NewsResponseCache.get(base, "fda", "strawberry"));
        assertNull(NewsResponseCache.get(base, "news", "strawberry"));
    }

    @Test
    public void get_sourcesAreIndependent() throws Exception {
        File base = cacheBase();
        NewsResponseCache.put(base, "fda", "strawberry", bodies("fda-body"));
        assertNull(NewsResponseCache.get(base, "news", "strawberry"));
        assertEquals(bodies("fda-body"),
                NewsResponseCache.get(base, "fda", "strawberry"));
    }

    @Test
    public void get_malformedCacheFileReturnsNull() throws Exception {
        File base = cacheBase();
        File file = NewsResponseCache.cacheFile(base, "fda", "strawberry");
        FileOutputStream fos = new FileOutputStream(file);
        fos.write("this is not a json array".getBytes(StandardCharsets.UTF_8));
        fos.close();
        assertNull(NewsResponseCache.get(base, "fda", "strawberry"));
    }

    // ------------------------------------------------------------------
    // TTL (§5: 24 h judged by file mtime)
    // ------------------------------------------------------------------

    @Test
    public void get_freshWithin24hReturnsBodies() throws Exception {
        File base = cacheBase();
        NewsResponseCache.put(base, "fda", "strawberry", bodies("body"));
        File file = NewsResponseCache.cacheFile(base, "fda", "strawberry");
        long twentyThreeHoursAgo =
                System.currentTimeMillis() - 23L * 60L * 60L * 1000L;
        assertTrue(file.setLastModified(twentyThreeHoursAgo));
        assertEquals(bodies("body"),
                NewsResponseCache.get(base, "fda", "strawberry"));
    }

    @Test
    public void get_olderThan24hReturnsNullAndDeletes() throws Exception {
        File base = cacheBase();
        NewsResponseCache.put(base, "fda", "strawberry", bodies("body"));
        File file = NewsResponseCache.cacheFile(base, "fda", "strawberry");
        long twentyFiveHoursAgo =
                System.currentTimeMillis() - 25L * 60L * 60L * 1000L;
        assertTrue(file.setLastModified(twentyFiveHoursAgo));
        assertNull(NewsResponseCache.get(base, "fda", "strawberry"));
        assertFalse(file.exists());
    }

    // ------------------------------------------------------------------
    // LRU cap (§5: 100 files, oldest mtime evicted)
    // ------------------------------------------------------------------

    @Test
    public void put_beyondCapEvictsOldestByMtime() throws Exception {
        File base = cacheBase();
        long baseTime = System.currentTimeMillis() - 1000L * 1000L;
        for (int i = 0; i < 100; i++) {
            String term = "term" + i;
            NewsResponseCache.put(base, "fda", term, bodies("body" + i));
            File file = NewsResponseCache.cacheFile(base, "fda", term);
            assertTrue(file.setLastModified(baseTime + i * 1000L));
        }
        // The 101st distinct term pushes past the cap.
        NewsResponseCache.put(base, "fda", "term100", bodies("body100"));

        File dir = NewsResponseCache.cacheFile(base, "fda", "term100")
                .getParentFile();
        File[] files = dir.listFiles();
        assertNotNull(files);
        assertEquals(100, files.length);
        // Oldest (term0) evicted; second-oldest and newest survive.
        assertFalse(NewsResponseCache.cacheFile(base, "fda", "term0").exists());
        assertTrue(NewsResponseCache.cacheFile(base, "fda", "term1").exists());
        assertTrue(NewsResponseCache.cacheFile(base, "fda", "term100").exists());
    }

    @Test
    public void constants_matchContract() {
        assertEquals(24L * 60L * 60L * 1000L, NewsResponseCache.TTL_MILLIS);
        assertEquals(100, NewsResponseCache.MAX_FILES);
        assertEquals("fda", NewsResponseCache.SOURCE_FDA);
        assertEquals("news", NewsResponseCache.SOURCE_NEWS);
    }
}
