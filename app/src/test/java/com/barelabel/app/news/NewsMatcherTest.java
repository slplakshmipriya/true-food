package com.barelabel.app.news;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * NewsMatcher + NewsMetaFormatter: Contracts sections 3.3, 4 and 6.4
 * (NEWS half).
 *
 * Fixture reference instant: 2026-11-08T12:00:00Z. For the query
 * "strawberry" the fixture pipeline leaves exactly ONE survivor: I2,
 * the newest copy of the duplicated outbreak story (Contracts
 * section 9).
 */
public class NewsMatcherTest {

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    /** 2026-11-08T12:00:00Z. */
    private static final long REFERENCE = utc(2026, 11, 8, 12, 0);

    private static long utc(int year, int month1Based, int day,
                            int hour, int minute) {
        GregorianCalendar cal =
                new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        cal.clear();
        cal.set(year, month1Based - 1, day, hour, minute, 0);
        return cal.getTimeInMillis();
    }

    private static Set<String> query(String... tokens) {
        return new HashSet<>(Arrays.asList(tokens));
    }

    private static RawNewsItem item(String headline, String outlet,
                                    long pubDateEpochMillis) {
        return new RawNewsItem(headline, outlet,
                "https://example.com/story", pubDateEpochMillis);
    }

    private static List<RawNewsItem> fixtureItems() throws Exception {
        InputStream is = NewsMatcherTest.class
                .getResourceAsStream("/rss_strawberry.xml");
        assertNotNull("fixture on test classpath", is);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        is.close();
        String xml = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        return new RssFeedParser().parse(xml);
    }

    // ------------------------------------------------------------------
    // Fixture pipeline (Contracts sections 3.3, 4, 9)
    // ------------------------------------------------------------------

    @Test
    public void fixtureLeavesExactlyOneSurvivor() throws Exception {
        List<NewsStory> stories = new NewsMatcher().match(
                fixtureItems(), query("strawberry"), REFERENCE);
        assertEquals(1, stories.size());

        NewsStory survivor = stories.get(0);
        // I2: the duplicate kept because it is the newest.
        assertEquals("FDA investigating hepatitis A outbreak linked to "
                + "strawberries, officials say", survivor.getHeadline());
        assertEquals("NBC News", survivor.getOutlet());
        assertEquals("https://www.nbcnews.com/health/health-news/"
                + "fda-investigating-hepatitis-outbreak-strawberries"
                + "-officials-say", survivor.getLink());
        assertEquals(utc(2026, 11, 7, 8, 15),
                survivor.getPubDateEpochMillis());
        assertEquals("News report", survivor.getSourceLabel());
        // Nov 7 against a Nov 8 reference is "yesterday" (UTC days).
        assertEquals("NBC News · yesterday", survivor.getMetaLine());
    }

    @Test
    public void fixtureDropsAreForThePinnedReasons() throws Exception {
        List<RawNewsItem> items = fixtureItems();
        NewsMatcher matcher = new NewsMatcher();

        // I3: same food, no hazard keyword -> dropped.
        assertTrue(matcher.match(Collections.singletonList(items.get(2)),
                query("strawberry"), REFERENCE).isEmpty());
        // I4: hazard keyword, different food (cucumbers) -> dropped.
        assertTrue(matcher.match(Collections.singletonList(items.get(3)),
                query("strawberry"), REFERENCE).isEmpty());
        // I5: food token + hazard keyword, 45 days old -> dropped.
        assertTrue(matcher.match(Collections.singletonList(items.get(4)),
                query("strawberry"), REFERENCE).isEmpty());
        // I1 alone matches (it is only dropped by dedupe in the full set).
        assertEquals(1, matcher.match(
                Collections.singletonList(items.get(0)),
                query("strawberry"), REFERENCE).size());
    }

    // ------------------------------------------------------------------
    // Hazard keywords (Contracts section 3.3)
    // ------------------------------------------------------------------

    @Test
    public void eColiPhraseKeywordMatches() {
        RawNewsItem story = item(
                "E. coli outbreak linked to spinach sickens dozens",
                "Food Safety News", REFERENCE - DAY_MS);
        List<NewsStory> stories = new NewsMatcher().match(
                Collections.singletonList(story), query("spinach"),
                REFERENCE);
        assertEquals(1, stories.size());
    }

    @Test
    public void multiTokenKeywordsNeedConsecutiveTokens() {
        // "e" and "coli" present but not consecutive: no phrase match,
        // and no other hazard keyword applies.
        RawNewsItem story = item(
                "E department report mentions coli counts in spinach",
                "Food Safety News", REFERENCE - DAY_MS);
        assertTrue(new NewsMatcher().match(
                Collections.singletonList(story), query("spinach"),
                REFERENCE).isEmpty());
    }

    @Test
    public void doNotEatPhraseKeywordMatches() {
        RawNewsItem story = item(
                "Officials say do not eat the recalled strawberries",
                "Wire", REFERENCE - DAY_MS);
        assertEquals(1, new NewsMatcher().match(
                Collections.singletonList(story), query("strawberry"),
                REFERENCE).size());
    }

    @Test
    public void queryTokenMustBeWholeAndPluralFoldApplies() {
        // "strawberries" folds to "strawberry" (Contracts section 3.1).
        RawNewsItem plural = item(
                "Strawberries under recall after salmonella scare",
                "Wire", REFERENCE - DAY_MS);
        assertEquals(1, new NewsMatcher().match(
                Collections.singletonList(plural), query("strawberry"),
                REFERENCE).size());
        // A longer token merely containing the query does not match.
        RawNewsItem substring = item("Strawberryfield recall expands",
                "Wire", REFERENCE - DAY_MS);
        assertTrue(new NewsMatcher().match(
                Collections.singletonList(substring), query("strawberry"),
                REFERENCE).isEmpty());
    }

    // ------------------------------------------------------------------
    // Dedupe, window, ordering, cap (Contracts sections 3.3, 4)
    // ------------------------------------------------------------------

    @Test
    public void dedupeKeepsNewestOfSameStory() {
        RawNewsItem older = item(
                "FDA investigating hepatitis A outbreak linked to fresh "
                        + "strawberries", "Food Safety News",
                REFERENCE - 2 * DAY_MS);
        RawNewsItem newer = item(
                "FDA investigating hepatitis A outbreak linked to "
                        + "strawberries, officials say", "NBC News",
                REFERENCE - DAY_MS);
        List<NewsStory> stories = new NewsMatcher().match(
                Arrays.asList(older, newer), query("strawberry"),
                REFERENCE);
        assertEquals(1, stories.size());
        assertEquals("NBC News", stories.get(0).getOutlet());
        assertEquals(REFERENCE - DAY_MS,
                stories.get(0).getPubDateEpochMillis());
    }

    @Test
    public void windowBoundaryThirtyDaysKeptThirtyOneDropped() {
        RawNewsItem at30 = item("Strawberry recall alpha begins",
                "Wire", REFERENCE - 30 * DAY_MS);
        RawNewsItem at31 = item("Strawberry recall beta begins",
                "Wire", REFERENCE - 31 * DAY_MS);
        List<NewsStory> stories = new NewsMatcher().match(
                Arrays.asList(at30, at31), query("strawberry"), REFERENCE);
        assertEquals(1, stories.size());
        assertEquals("Strawberry recall alpha begins",
                stories.get(0).getHeadline());
    }

    @Test
    public void futureDatedItemIsKeptAsAgeZero() {
        RawNewsItem future = item("Strawberry recall just announced",
                "Wire", REFERENCE + 3 * 60L * 60L * 1000L);
        List<NewsStory> stories = new NewsMatcher().match(
                Collections.singletonList(future), query("strawberry"),
                REFERENCE);
        assertEquals(1, stories.size());
        assertEquals("Wire · today", stories.get(0).getMetaLine());
    }

    @Test
    public void capThreeWithNewestFirstOrder() {
        List<RawNewsItem> items = new ArrayList<>();
        items.add(item("Strawberry recall alpha expands", "Wire",
                REFERENCE - 4 * DAY_MS));
        items.add(item("Strawberry outbreak beta widens", "Wire",
                REFERENCE - DAY_MS));
        items.add(item("Strawberry contamination gamma found", "Wire",
                REFERENCE - 3 * DAY_MS));
        items.add(item("Strawberry salmonella delta confirmed", "Wire",
                REFERENCE - 2 * DAY_MS));
        List<NewsStory> stories = new NewsMatcher().match(
                items, query("strawberry"), REFERENCE);
        assertEquals(3, stories.size());
        assertEquals("Strawberry outbreak beta widens",
                stories.get(0).getHeadline());
        assertEquals("Strawberry salmonella delta confirmed",
                stories.get(1).getHeadline());
        assertEquals("Strawberry contamination gamma found",
                stories.get(2).getHeadline());
    }

    @Test
    public void emptyAndNullInputsReturnEmpty() {
        NewsMatcher matcher = new NewsMatcher();
        assertTrue(matcher.match(Collections.<RawNewsItem>emptyList(),
                query("strawberry"), REFERENCE).isEmpty());
        assertTrue(matcher.match(null, query("strawberry"), REFERENCE)
                .isEmpty());
        assertTrue(matcher.match(
                Collections.singletonList(item("Strawberry recall x",
                        "Wire", REFERENCE)), null, REFERENCE).isEmpty());
    }

    // ------------------------------------------------------------------
    // Meta line / relative dates (Contracts section 6.4, NEWS half)
    // ------------------------------------------------------------------

    @Test
    public void relativeDateFormatter() {
        // Same UTC calendar day as the reference.
        assertEquals("Wire · today", NewsMetaFormatter.format(
                "Wire", utc(2026, 11, 8, 1, 0), REFERENCE));
        // Previous UTC calendar day.
        assertEquals("Wire · yesterday", NewsMetaFormatter.format(
                "Wire", utc(2026, 11, 7, 23, 0), REFERENCE));
        // 2..30 days.
        assertEquals("Wire · 5 days ago", NewsMetaFormatter.format(
                "Wire", utc(2026, 11, 3, 12, 0), REFERENCE));
        assertEquals("Wire · 30 days ago", NewsMetaFormatter.format(
                "Wire", utc(2026, 10, 9, 12, 0), REFERENCE));
        // Past 30 days: absolute date (formatter-complete; unreachable
        // through the section 4 window).
        assertEquals("Wire · Sep 24, 2026", NewsMetaFormatter.format(
                "Wire", utc(2026, 9, 24, 10, 20), REFERENCE));
    }

    @Test
    public void emptyOutletMetaLineHasNoSeparator() {
        assertEquals("today", NewsMetaFormatter.format(
                "", REFERENCE - 60_000L, REFERENCE));
        assertEquals("yesterday", NewsMetaFormatter.format(
                null, REFERENCE - DAY_MS, REFERENCE));
    }

    @Test
    public void noSourceNoSuffixStoryMetaHasNoSeparator() {
        RawNewsItem story = item("Strawberry recall expands statewide",
                "", REFERENCE - 2 * DAY_MS);
        List<NewsStory> stories = new NewsMatcher().match(
                Collections.singletonList(story), query("strawberry"),
                REFERENCE);
        assertEquals(1, stories.size());
        assertEquals("", stories.get(0).getOutlet());
        assertEquals("2 days ago", stories.get(0).getMetaLine());
        assertFalse(stories.get(0).getMetaLine().contains("·"));
    }

    @Test
    public void newsStoryValueSemanticsAndLabel() {
        NewsStory a = new NewsStory("H", "O", "L", 7L, "O · today");
        NewsStory b = new NewsStory("H", "O", "L", 7L, "O · today");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals("News report", a.getSourceLabel());
        assertEquals(NewsStory.SOURCE_LABEL, a.getSourceLabel());
        assertTrue(a.toString().contains("H"));
    }
}
