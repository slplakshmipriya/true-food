package com.barelabel.app.news;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

/**
 * RssFeedParser: Contracts section 2.2 item shapes.
 *
 * Fixture /rss_strawberry.xml (NA-00): 5 items, every item carries a
 * source element. I1 outlet comes from its source element; I2's outlet
 * likewise comes from ITS source element (NBC News). pubDates are the
 * pinned November / September 2026 dates.
 */
public class RssFeedParserTest {

    private static long utc(int year, int month1Based, int day,
                            int hour, int minute) {
        GregorianCalendar cal =
                new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        cal.clear();
        cal.set(year, month1Based - 1, day, hour, minute, 0);
        return cal.getTimeInMillis();
    }

    private static String fixtureXml() throws Exception {
        InputStream is = RssFeedParserTest.class
                .getResourceAsStream("/rss_strawberry.xml");
        assertNotNull("fixture on test classpath", is);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        is.close();
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String feed(String items) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<rss version=\"2.0\"><channel>"
                + "<title>test feed</title>"
                + items
                + "</channel></rss>";
    }

    private static String item(String title, String link, String pubDate,
                               String sourceOrNull) {
        String source = sourceOrNull == null ? ""
                : "<source url=\"https://example.com\">" + sourceOrNull
                        + "</source>";
        return "<item><title>" + title + "</title>"
                + "<link>" + link + "</link>"
                + "<pubDate>" + pubDate + "</pubDate>"
                + source + "</item>";
    }

    // ------------------------------------------------------------------
    // Fixture (Contracts section 9)
    // ------------------------------------------------------------------

    @Test
    public void fixtureParsesFiveItems() throws Exception {
        List<RawNewsItem> items = new RssFeedParser().parse(fixtureXml());
        assertEquals(5, items.size());
    }

    @Test
    public void fixtureI1OutletFromSourceElement() throws Exception {
        List<RawNewsItem> items = new RssFeedParser().parse(fixtureXml());
        RawNewsItem i1 = items.get(0);
        assertEquals("FDA investigating hepatitis A outbreak linked to "
                + "fresh strawberries", i1.getHeadline());
        assertEquals("Food Safety News", i1.getOutlet());
        assertEquals("https://www.foodsafetynews.com/2026/11/"
                + "fda-investigating-hepatitis-a-outbreak-linked-to-fresh"
                + "-strawberries/", i1.getLink());
        assertEquals(utc(2026, 11, 6, 14, 30), i1.getPubDateEpochMillis());
    }

    @Test
    public void fixtureI2OutletFromItsSourceElement() throws Exception {
        List<RawNewsItem> items = new RssFeedParser().parse(fixtureXml());
        RawNewsItem i2 = items.get(1);
        assertEquals("FDA investigating hepatitis A outbreak linked to "
                + "strawberries, officials say", i2.getHeadline());
        assertEquals("NBC News", i2.getOutlet());
        assertEquals("https://www.nbcnews.com/health/health-news/"
                + "fda-investigating-hepatitis-outbreak-strawberries"
                + "-officials-say", i2.getLink());
        assertEquals(utc(2026, 11, 7, 8, 15), i2.getPubDateEpochMillis());
    }

    @Test
    public void fixtureRemainingItemsPinned() throws Exception {
        List<RawNewsItem> items = new RssFeedParser().parse(fixtureXml());

        RawNewsItem i3 = items.get(2);
        assertEquals("Strawberry prices rise as California harvest begins",
                i3.getHeadline());
        assertEquals("Fresh Produce Journal", i3.getOutlet());
        assertEquals(utc(2026, 11, 5, 11, 0), i3.getPubDateEpochMillis());

        RawNewsItem i4 = items.get(3);
        assertEquals("Salmonella outbreak linked to cucumbers sickens "
                + "dozens", i4.getHeadline());
        assertEquals("Food Safety News", i4.getOutlet());
        assertEquals(utc(2026, 11, 6, 16, 45), i4.getPubDateEpochMillis());

        RawNewsItem i5 = items.get(4);
        assertEquals("Strawberry recall expands to additional states",
                i5.getHeadline());
        assertEquals("Food Safety News", i5.getOutlet());
        assertEquals(utc(2026, 9, 24, 10, 20), i5.getPubDateEpochMillis());
    }

    // ------------------------------------------------------------------
    // Outlet resolution (Contracts section 2.2)
    // ------------------------------------------------------------------

    @Test
    public void outletFromTitleSuffixWhenNoSourceElement() {
        String xml = feed(item(
                "Strawberry recall widens - Daily Gazette",
                "https://example.com/a",
                "Sat, 07 Nov 2026 08:15:00 GMT", null));
        List<RawNewsItem> items = new RssFeedParser().parse(xml);
        assertEquals(1, items.size());
        assertEquals("Strawberry recall widens",
                items.get(0).getHeadline());
        assertEquals("Daily Gazette", items.get(0).getOutlet());
    }

    @Test
    public void outletUsesFinalDashWhenTitleHasSeveral() {
        String xml = feed(item(
                "Recall - update - Strawberry batch pulled - Wire Service",
                "https://example.com/a",
                "Sat, 07 Nov 2026 08:15:00 GMT", null));
        List<RawNewsItem> items = new RssFeedParser().parse(xml);
        assertEquals(1, items.size());
        assertEquals("Recall - update - Strawberry batch pulled",
                items.get(0).getHeadline());
        assertEquals("Wire Service", items.get(0).getOutlet());
    }

    @Test
    public void sourceElementStripsMatchingTitleSuffix() {
        String xml = feed(item(
                "Strawberry recall widens - Daily Gazette",
                "https://example.com/a",
                "Sat, 07 Nov 2026 08:15:00 GMT", "Daily Gazette"));
        List<RawNewsItem> items = new RssFeedParser().parse(xml);
        assertEquals(1, items.size());
        assertEquals("Strawberry recall widens",
                items.get(0).getHeadline());
        assertEquals("Daily Gazette", items.get(0).getOutlet());
    }

    @Test
    public void sourceElementWithoutSuffixLeavesTitleAlone() {
        String xml = feed(item(
                "Strawberry recall widens",
                "https://example.com/a",
                "Sat, 07 Nov 2026 08:15:00 GMT", "Daily Gazette"));
        List<RawNewsItem> items = new RssFeedParser().parse(xml);
        assertEquals(1, items.size());
        assertEquals("Strawberry recall widens",
                items.get(0).getHeadline());
        assertEquals("Daily Gazette", items.get(0).getOutlet());
    }

    @Test
    public void noSourceNoSuffixMeansEmptyOutlet() {
        String xml = feed(item(
                "Strawberry recall widens",
                "https://example.com/a",
                "Sat, 07 Nov 2026 08:15:00 GMT", null));
        List<RawNewsItem> items = new RssFeedParser().parse(xml);
        assertEquals(1, items.size());
        assertEquals("Strawberry recall widens",
                items.get(0).getHeadline());
        assertEquals("", items.get(0).getOutlet());
    }

    // ------------------------------------------------------------------
    // pubDate handling
    // ------------------------------------------------------------------

    @Test
    public void numericOffsetPubDateParsesToSameInstantAsGmt() {
        String xml = feed(item(
                "Strawberry recall widens",
                "https://example.com/a",
                "Sat, 07 Nov 2026 03:15:00 -0500", null));
        List<RawNewsItem> items = new RssFeedParser().parse(xml);
        assertEquals(1, items.size());
        assertEquals(utc(2026, 11, 7, 8, 15),
                items.get(0).getPubDateEpochMillis());
    }

    @Test
    public void unparseablePubDateSkipsOnlyThatItem() {
        String xml = feed(
                item("Strawberry recall widens", "https://example.com/a",
                        "not a date", null)
                + item("Strawberry recall widens again",
                        "https://example.com/b",
                        "Sat, 07 Nov 2026 08:15:00 GMT", null));
        List<RawNewsItem> items = new RssFeedParser().parse(xml);
        assertEquals(1, items.size());
        assertEquals("Strawberry recall widens again",
                items.get(0).getHeadline());
    }

    // ------------------------------------------------------------------
    // Failure behaviour: malformed input never throws (Contracts section 7)
    // ------------------------------------------------------------------

    @Test
    public void malformedXmlReturnsEmptyList() {
        assertTrue(new RssFeedParser().parse("<rss><channel><item>")
                .isEmpty());
        assertTrue(new RssFeedParser().parse("this is not xml").isEmpty());
        assertTrue(new RssFeedParser().parse("").isEmpty());
        assertTrue(new RssFeedParser().parse(null).isEmpty());
    }

    @Test
    public void doctypeWithExternalEntityIsNotExpanded() {
        String xml = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE rss [<!ENTITY xxe SYSTEM "
                + "\"file:///etc/passwd\">]>"
                + "<rss version=\"2.0\"><channel>"
                + "<item><title>&xxe;</title>"
                + "<link>https://example.com/a</link>"
                + "<pubDate>Sat, 07 Nov 2026 08:15:00 GMT</pubDate>"
                + "</item></channel></rss>";
        // Hardened factory: either the document is rejected outright
        // (empty list) or the entity is never expanded into the title.
        List<RawNewsItem> items = new RssFeedParser().parse(xml);
        for (RawNewsItem parsed : items) {
            assertTrue("external entity must not be expanded",
                    !parsed.getHeadline().contains("root:"));
        }
    }

    @Test
    public void rawNewsItemValueSemantics() {
        RawNewsItem a = new RawNewsItem("T", "O", "L", 42L);
        RawNewsItem b = new RawNewsItem("T", "O", "L", 42L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(a.toString().contains("T"));
    }
}
