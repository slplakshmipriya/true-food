package com.barelabel.app.news;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenFdaClient: Contract §2.1 query construction (per term variant,
 * quoted multi-token / bare single-token, sort + limit), merge and
 * dedupe by event_id + recall_number across variant responses, §5
 * cache use, and §7 failure behavior (any error ⇒ empty, silently,
 * never throwing).
 */
public class OpenFdaClientTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    // ------------------------------------------------------------------
    // Fake network seam (no network, no device)
    // ------------------------------------------------------------------

    private static final class FakeFetcher implements HttpFetcher {
        final Map<String, String> bodiesByUrl = new HashMap<>();
        final Set<String> failingUrls = new HashSet<>();
        final Set<String> timeoutUrls = new HashSet<>();
        final List<String> requestedUrls = new ArrayList<>();
        boolean failAll;
        String defaultBody;

        @Override
        public String fetch(String url) throws IOException {
            requestedUrls.add(url);
            if (failAll || failingUrls.contains(url)) {
                throw new IOException("simulated HTTP failure");
            }
            if (timeoutUrls.contains(url)) {
                throw new SocketTimeoutException("simulated timeout");
            }
            if (bodiesByUrl.containsKey(url)) {
                return bodiesByUrl.get(url);
            }
            return defaultBody;
        }
    }

    private static String recordJson(String eventId, String recallNumber,
                                     String firm) {
        return "{\"event_id\":\"" + eventId + "\""
                + ",\"recall_number\":\"" + recallNumber + "\""
                + ",\"recalling_firm\":\"" + firm + "\""
                + ",\"product_description\":\"Fresh Strawberries, 16 oz\""
                + ",\"reason_for_recall\":\"Potential hepatitis A contamination\""
                + ",\"classification\":\"Class I\""
                + ",\"status\":\"Ongoing\""
                + ",\"report_date\":\"20261102\"}";
    }

    private static String fdaBody(String... records) {
        StringBuilder sb = new StringBuilder("{\"results\":[");
        for (int i = 0; i < records.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(records[i]);
        }
        sb.append("]}");
        return sb.toString();
    }

    private static Set<String> pairsOf(List<FdaRecord> records) {
        Set<String> pairs = new HashSet<>();
        for (FdaRecord r : records) {
            pairs.add(r.eventId + "/" + r.recallNumber);
        }
        return pairs;
    }

    private OpenFdaClient client(FakeFetcher fetcher) throws Exception {
        File base = temp.newFolder();
        return new OpenFdaClient(fetcher, base);
    }

    // ------------------------------------------------------------------
    // §2.1 URL construction
    // ------------------------------------------------------------------

    @Test
    public void buildUrl_singleTokenIsBare() {
        assertEquals("https://api.fda.gov/food/enforcement.json"
                        + "?search=product_description%3Astrawberry"
                        + "&sort=report_date%3Adesc&limit=100",
                OpenFdaClient.buildUrl("strawberry"));
    }

    @Test
    public void buildUrl_multiTokenIsQuoted() {
        assertEquals("https://api.fda.gov/food/enforcement.json"
                        + "?search=product_description%3A%22fresh+strawberries%22"
                        + "&sort=report_date%3Adesc&limit=100",
                OpenFdaClient.buildUrl("fresh strawberries"));
    }

    @Test
    public void fetch_strawberryIssuesTwoDedupedVariantRequests()
            throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.defaultBody = fdaBody();
        List<FdaRecord> records = client(fetcher).fetchRecalls("strawberry");
        assertTrue(records.isEmpty());
        // Variants of "strawberry": strawberry + strawberries (the
        // folded form dedupes into the normalized form).
        assertEquals(2, fetcher.requestedUrls.size());
        assertTrue(fetcher.requestedUrls.contains(
                OpenFdaClient.buildUrl("strawberry")));
        assertTrue(fetcher.requestedUrls.contains(
                OpenFdaClient.buildUrl("strawberries")));
    }

    @Test
    public void fetch_pluralTermIssuesFoldedAndNormalizedVariants()
            throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.defaultBody = fdaBody();
        client(fetcher).fetchRecalls("strawberries");
        assertEquals(2, fetcher.requestedUrls.size());
        assertTrue(fetcher.requestedUrls.contains(
                OpenFdaClient.buildUrl("strawberries")));
        assertTrue(fetcher.requestedUrls.contains(
                OpenFdaClient.buildUrl("strawberry")));
    }

    @Test
    public void fetch_multiTokenTermIssuesThreeQuotedRequests()
            throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.defaultBody = fdaBody();
        client(fetcher).fetchRecalls("eggs chicken");
        // Variants: "eggs chicken", "egg chicken", "eggs chickens".
        assertEquals(3, fetcher.requestedUrls.size());
        assertEquals("https://api.fda.gov/food/enforcement.json"
                        + "?search=product_description%3A%22eggs+chicken%22"
                        + "&sort=report_date%3Adesc&limit=100",
                fetcher.requestedUrls.get(0));
        for (String url : fetcher.requestedUrls) {
            assertTrue("multi-token variant must be quoted: " + url,
                    url.contains("%22"));
        }
    }

    // ------------------------------------------------------------------
    // Merge + dedupe by event_id + recall_number (§2.1)
    // ------------------------------------------------------------------

    @Test
    public void mergeDedupe_sameEventDifferentRecallBothKept() {
        List<FdaRecord> in = new ArrayList<>();
        in.add(new FdaRecord("Firm A", "Strawberries", "", "Class I",
                "Ongoing", 20261102, "E1", "R1"));
        in.add(new FdaRecord("Firm A", "Strawberries", "", "Class I",
                "Ongoing", 20261102, "E1", "R2"));
        List<FdaRecord> merged = OpenFdaClient.mergeDedupe(in);
        assertEquals(2, merged.size());
    }

    @Test
    public void mergeDedupe_sameRecallDifferentEventBothKept() {
        List<FdaRecord> in = new ArrayList<>();
        in.add(new FdaRecord("Firm A", "Strawberries", "", "Class I",
                "Ongoing", 20261102, "E1", "R1"));
        in.add(new FdaRecord("Firm B", "Strawberries", "", "Class I",
                "Ongoing", 20261102, "E2", "R1"));
        List<FdaRecord> merged = OpenFdaClient.mergeDedupe(in);
        assertEquals(2, merged.size());
    }

    @Test
    public void mergeDedupe_exactPairDedupedKeepingFirst() {
        List<FdaRecord> in = new ArrayList<>();
        in.add(new FdaRecord("Firm First", "Strawberries", "", "Class I",
                "Ongoing", 20261102, "E1", "R1"));
        in.add(new FdaRecord("Firm Second", "Strawberries", "", "Class I",
                "Ongoing", 20261102, "E1", "R1"));
        List<FdaRecord> merged = OpenFdaClient.mergeDedupe(in);
        assertEquals(1, merged.size());
        assertEquals("Firm First", merged.get(0).recallingFirm);
    }

    @Test
    public void fetch_mergesAndDedupesAcrossVariants() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.bodiesByUrl.put(OpenFdaClient.buildUrl("strawberry"),
                fdaBody(recordJson("E1", "R1", "Firm A"),
                        recordJson("E1", "R2", "Firm A"),
                        recordJson("E2", "R1", "Firm B")));
        fetcher.bodiesByUrl.put(OpenFdaClient.buildUrl("strawberries"),
                fdaBody(recordJson("E1", "R1", "Firm A"),
                        recordJson("E3", "R3", "Firm C")));
        List<FdaRecord> records = client(fetcher).fetchRecalls("strawberry");
        assertEquals(4, records.size());
        Set<String> pairs = pairsOf(records);
        assertTrue(pairs.contains("E1/R1"));
        assertTrue(pairs.contains("E1/R2"));
        assertTrue(pairs.contains("E2/R1"));
        assertTrue(pairs.contains("E3/R3"));
        // Fields survive the parse boundary into FdaRecord.
        for (FdaRecord r : records) {
            if ("E3".equals(r.eventId)) {
                assertEquals("Firm C", r.recallingFirm);
                assertEquals("Class I", r.classification);
                assertEquals(20261102, r.reportDateYyyymmdd);
            }
        }
    }

    // ------------------------------------------------------------------
    // Parse boundary: malformed vs valid-but-empty
    // ------------------------------------------------------------------

    @Test
    public void parseBody_validEmptyResultsIsEmptyNotNull() {
        List<FdaRecord> parsed = OpenFdaClient.parseBody("{\"results\":[]}");
        assertNotNull(parsed);
        assertTrue(parsed.isEmpty());
    }

    @Test
    public void parseBody_malformedReturnsNull() {
        assertNull(OpenFdaClient.parseBody("this is not json"));
        assertNull(OpenFdaClient.parseBody("{}"));
        assertNull(OpenFdaClient.parseBody("{\"error\":{\"code\":\"x\"}}"));
        assertNull(OpenFdaClient.parseBody(null));
        assertNull(OpenFdaClient.parseBody(""));
    }

    // ------------------------------------------------------------------
    // §7 failure behavior: empty for this source, never throws
    // ------------------------------------------------------------------

    @Test
    public void fetch_networkErrorReturnsEmpty() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.failAll = true;
        assertTrue(client(fetcher).fetchRecalls("strawberry").isEmpty());
    }

    @Test
    public void fetch_non200ReturnsEmpty() throws Exception {
        // The fetcher seam surfaces non-200 as IOException (the
        // production fetcher throws on any non-200); that must yield
        // an empty result, not a propagated error.
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.failingUrls.add(OpenFdaClient.buildUrl("strawberry"));
        fetcher.failingUrls.add(OpenFdaClient.buildUrl("strawberries"));
        assertTrue(client(fetcher).fetchRecalls("strawberry").isEmpty());
    }

    @Test
    public void fetch_timeoutReturnsEmpty() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.timeoutUrls.add(OpenFdaClient.buildUrl("strawberry"));
        fetcher.timeoutUrls.add(OpenFdaClient.buildUrl("strawberries"));
        assertTrue(client(fetcher).fetchRecalls("strawberry").isEmpty());
    }

    @Test
    public void fetch_malformedBodyReturnsEmpty() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.defaultBody = "<html>definitely not openFDA json</html>";
        assertTrue(client(fetcher).fetchRecalls("strawberry").isEmpty());
    }

    @Test
    public void fetch_nullBodyReturnsEmpty() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.defaultBody = null;
        assertTrue(client(fetcher).fetchRecalls("strawberry").isEmpty());
    }

    @Test
    public void fetch_oneVariantFailsWholeSourceEmptyAndNothingCached()
            throws Exception {
        File base = temp.newFolder();
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.defaultBody = fdaBody(recordJson("E1", "R1", "Firm A"));
        fetcher.failingUrls.add(OpenFdaClient.buildUrl("strawberries"));
        OpenFdaClient client = new OpenFdaClient(fetcher, base);
        assertTrue(client.fetchRecalls("strawberry").isEmpty());
        assertNull(NewsResponseCache.get(
                base, NewsResponseCache.SOURCE_FDA, "strawberry"));
    }

    @Test
    public void fetch_blankTermReturnsEmptyAndMakesNoRequests()
            throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.defaultBody = fdaBody(recordJson("E1", "R1", "Firm A"));
        OpenFdaClient client = client(fetcher);
        assertTrue(client.fetchRecalls(null).isEmpty());
        assertTrue(client.fetchRecalls("").isEmpty());
        assertTrue(client.fetchRecalls("   ").isEmpty());
        assertTrue(client.fetchRecalls("!!!").isEmpty());
        assertTrue(fetcher.requestedUrls.isEmpty());
    }

    // ------------------------------------------------------------------
    // §5 cache: second fetch is served without network
    // ------------------------------------------------------------------

    @Test
    public void fetch_secondCallServedFromCache() throws Exception {
        File base = temp.newFolder();
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.defaultBody = fdaBody(recordJson("E1", "R1", "Firm A"));
        OpenFdaClient client = new OpenFdaClient(fetcher, base);

        List<FdaRecord> first = client.fetchRecalls("strawberry");
        assertEquals(1, first.size());
        int callsAfterFirst = fetcher.requestedUrls.size();
        assertEquals(2, callsAfterFirst);

        fetcher.failAll = true;
        List<FdaRecord> second = client.fetchRecalls("strawberry");
        assertEquals(1, second.size());
        assertEquals("E1", second.get(0).eventId);
        assertEquals(callsAfterFirst, fetcher.requestedUrls.size());
    }

    @Test
    public void fetcherTimeouts_areTenSeconds() {
        assertEquals(10000, UrlConnectionHttpFetcher.CONNECT_TIMEOUT_MS);
        assertEquals(10000, UrlConnectionHttpFetcher.READ_TIMEOUT_MS);
    }
}
