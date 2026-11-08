package com.barelabel.app.news;

import static org.junit.Assert.assertEquals;
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
 * GoogleNewsClient: Contract §2.2 URL construction (exact query
 * string, hazard-keyword group, hl/gl/ceid parameters), fetching for
 * NA-02's RssFeedParser, §5 cache use, and §7 failure behavior
 * (any error ⇒ empty, silently, never throwing).
 */
public class GoogleNewsClientTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    // ------------------------------------------------------------------
    // Fake network seam (no network, no device)
    // ------------------------------------------------------------------

    private static final class FakeFetcher implements HttpFetcher {
        final Map<String, String> bodiesByUrl = new HashMap<>();
        final List<String> requestedUrls = new ArrayList<>();
        final Set<String> timeoutUrls = new HashSet<>();
        boolean failAll;
        String defaultBody;

        @Override
        public String fetch(String url) throws IOException {
            requestedUrls.add(url);
            if (failAll) {
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

    private static final String SAMPLE_RSS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<rss version=\"2.0\"><channel>"
            + "<title>strawberry news</title>"
            + "<item>"
            + "<title>Strawberry recall expands to additional states"
            + " - Example News</title>"
            + "<link>https://example.com/articles/strawberry-recall</link>"
            + "<pubDate>Sat, 07 Nov 2026 09:00:00 GMT</pubDate>"
            + "<source url=\"https://example.com\">Example News</source>"
            + "</item>"
            + "<item>"
            + "<title>Salmonella outbreak linked to cucumbers"
            + " - Wire Daily</title>"
            + "<link>https://example.com/articles/cucumber-outbreak</link>"
            + "<pubDate>Fri, 06 Nov 2026 12:00:00 GMT</pubDate>"
            + "</item>"
            + "</channel></rss>";

    private GoogleNewsClient client(FakeFetcher fetcher) throws Exception {
        return new GoogleNewsClient(fetcher, temp.newFolder());
    }

    // ------------------------------------------------------------------
    // §2.2 URL construction (exact strings)
    // ------------------------------------------------------------------

    @Test
    public void buildUrl_strawberryExact() {
        assertEquals("https://news.google.com/rss/search"
                        + "?q=strawberry+%28recall+OR+outbreak+OR+contamination"
                        + "+OR+salmonella+OR+listeria+OR+%22e.+coli%22"
                        + "+OR+hepatitis%29"
                        + "&hl=en-US&gl=US&ceid=US:en",
                GoogleNewsClient.buildUrl("strawberry"));
    }

    @Test
    public void buildUrl_multiWordTermExact() {
        assertEquals("https://news.google.com/rss/search"
                        + "?q=ice+cream+%28recall+OR+outbreak+OR+contamination"
                        + "+OR+salmonella+OR+listeria+OR+%22e.+coli%22"
                        + "+OR+hepatitis%29"
                        + "&hl=en-US&gl=US&ceid=US:en",
                GoogleNewsClient.buildUrl("ice cream"));
    }

    @Test
    public void buildUrl_termIsNormalized() {
        assertEquals(GoogleNewsClient.buildUrl("strawberry"),
                GoogleNewsClient.buildUrl("  Strawberry!! "));
    }

    // ------------------------------------------------------------------
    // Fetch + parse via NA-02's RssFeedParser
    // ------------------------------------------------------------------

    @Test
    public void fetch_parsesItemsThroughRssFeedParser() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.defaultBody = SAMPLE_RSS;
        List<RawNewsItem> items = client(fetcher).fetchNews("strawberry");
        assertEquals(1, fetcher.requestedUrls.size());
        assertEquals(GoogleNewsClient.buildUrl("strawberry"),
                fetcher.requestedUrls.get(0));
        assertEquals(2, items.size());
        RawNewsItem first = items.get(0);
        // Outlet suffix split off by the parser (source element wins).
        assertEquals("Strawberry recall expands to additional states",
                first.getHeadline());
        assertEquals("Example News", first.getOutlet());
        assertEquals("https://example.com/articles/strawberry-recall",
                first.getLink());
        assertTrue(first.getPubDateEpochMillis() > 0);
        RawNewsItem second = items.get(1);
        assertEquals("Salmonella outbreak linked to cucumbers",
                second.getHeadline());
        assertEquals("Wire Daily", second.getOutlet());
    }

    // ------------------------------------------------------------------
    // §7 failure behavior: empty for this source, never throws
    // ------------------------------------------------------------------

    @Test
    public void fetch_networkErrorReturnsEmpty() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.failAll = true;
        assertTrue(client(fetcher).fetchNews("strawberry").isEmpty());
    }

    @Test
    public void fetch_timeoutReturnsEmpty() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.timeoutUrls.add(GoogleNewsClient.buildUrl("strawberry"));
        assertTrue(client(fetcher).fetchNews("strawberry").isEmpty());
    }

    @Test
    public void fetch_malformedXmlReturnsEmpty() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.defaultBody = "this is not xml at all";
        assertTrue(client(fetcher).fetchNews("strawberry").isEmpty());
        fetcher.defaultBody = "<rss><channel><item><title>truncated";
        assertTrue(client(fetcher).fetchNews("blueberry").isEmpty());
    }

    @Test
    public void fetch_nullBodyReturnsEmpty() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.defaultBody = null;
        assertTrue(client(fetcher).fetchNews("strawberry").isEmpty());
    }

    @Test
    public void fetch_blankTermReturnsEmptyAndMakesNoRequests()
            throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.defaultBody = SAMPLE_RSS;
        GoogleNewsClient client = client(fetcher);
        assertTrue(client.fetchNews(null).isEmpty());
        assertTrue(client.fetchNews("").isEmpty());
        assertTrue(client.fetchNews("   ").isEmpty());
        assertTrue(fetcher.requestedUrls.isEmpty());
    }

    // ------------------------------------------------------------------
    // §5 cache: second fetch is served without network
    // ------------------------------------------------------------------

    @Test
    public void fetch_secondCallServedFromCache() throws Exception {
        File base = temp.newFolder();
        FakeFetcher fetcher = new FakeFetcher();
        fetcher.defaultBody = SAMPLE_RSS;
        GoogleNewsClient client = new GoogleNewsClient(fetcher, base);

        List<RawNewsItem> first = client.fetchNews("strawberry");
        assertEquals(2, first.size());
        assertEquals(1, fetcher.requestedUrls.size());

        fetcher.failAll = true;
        List<RawNewsItem> second = client.fetchNews("strawberry");
        assertEquals(2, second.size());
        assertEquals("Strawberry recall expands to additional states",
                second.get(0).getHeadline());
        assertEquals(1, fetcher.requestedUrls.size());
    }
}
