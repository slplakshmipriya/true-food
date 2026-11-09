package com.barelabel.app.news;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SafetyAlertService (NA-04): Contract §3.1 token sets for search
 * terms vs products (name + USDA category), §4 combined ordering
 * (FDA block first, then NEWS, internal orders preserved) and the
 * combined count behind §6.2, the §6.3 badge rule, concurrent
 * fetching of both sources with a bounded overall wait, source
 * failure isolation, and the §7 Throwable-proof boundary (no service
 * method ever throws).
 *
 * All tests run against fake sources: no network, no Android.
 * Reference instant: 2026-11-08 (matching the NA-00 fixtures).
 */
public class SafetyAlertServiceTest {

    private static final int REF_DATE = 20261108;
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    /** 2026-11-08T12:00:00Z. */
    private static final long REF_EPOCH = utc(2026, 11, 8, 12, 0);

    private static long utc(int year, int month1Based, int day,
                            int hour, int minute) {
        GregorianCalendar cal =
                new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        cal.clear();
        cal.set(year, month1Based - 1, day, hour, minute, 0);
        return cal.getTimeInMillis();
    }

    // ------------------------------------------------------------------
    // Fake sources (the service's injectable seams)
    // ------------------------------------------------------------------

    private static final class FakeFdaSource
            implements SafetyAlertService.FdaSource {
        final List<FdaRecord> records = new ArrayList<>();
        final AtomicInteger invocations = new AtomicInteger();
        volatile String lastTerm;
        boolean throwOnFetch;
        long sleepMs;
        AtomicInteger inFlight;
        AtomicInteger maxInFlight;

        @Override
        public List<FdaRecord> fetchRecalls(String term) {
            invocations.incrementAndGet();
            lastTerm = term;
            if (inFlight != null) {
                int now = inFlight.incrementAndGet();
                recordMax(maxInFlight, now);
            }
            try {
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException e) {
                        // Cancelled by the service timeout: just return.
                    }
                }
                if (throwOnFetch) {
                    throw new RuntimeException("simulated FDA failure");
                }
                return records;
            } finally {
                if (inFlight != null) {
                    inFlight.decrementAndGet();
                }
            }
        }
    }

    private static final class FakeNewsSource
            implements SafetyAlertService.NewsSource {
        final List<RawNewsItem> items = new ArrayList<>();
        final AtomicInteger invocations = new AtomicInteger();
        volatile String lastTerm;
        boolean throwOnFetch;
        long sleepMs;
        AtomicInteger inFlight;
        AtomicInteger maxInFlight;

        @Override
        public List<RawNewsItem> fetchNews(String term) {
            invocations.incrementAndGet();
            lastTerm = term;
            if (inFlight != null) {
                int now = inFlight.incrementAndGet();
                recordMax(maxInFlight, now);
            }
            try {
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException e) {
                        // Cancelled by the service timeout: just return.
                    }
                }
                if (throwOnFetch) {
                    throw new RuntimeException("simulated news failure");
                }
                return items;
            } finally {
                if (inFlight != null) {
                    inFlight.decrementAndGet();
                }
            }
        }
    }

    /**
     * Term-sensitive FDA fake for the product path (fix round 1):
     * returns ONLY the records registered for the exact single-token
     * term it is queried with, and an empty list for every other
     * term. Unlike the canned fakes above, a service that passes a
     * raw multi-token product name (or never queries a category
     * token) retrieves nothing from this fake, so the product-path
     * tests below can actually catch a discarded token set.
     */
    private static final class TermSensitiveFakeFdaSource
            implements SafetyAlertService.FdaSource {
        final Map<String, List<FdaRecord>> byTerm = new HashMap<>();
        final Set<String> throwTerms = new HashSet<>();
        final List<String> termsSeen = Collections.synchronizedList(
                new ArrayList<String>());
        final AtomicInteger invocations = new AtomicInteger();
        long sleepMs;

        @Override
        public List<FdaRecord> fetchRecalls(String term) {
            invocations.incrementAndGet();
            termsSeen.add(term);
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    // Cancelled by the service deadline.
                    return Collections.emptyList();
                }
            }
            if (throwTerms.contains(term)) {
                throw new RuntimeException(
                        "simulated FDA failure for term: " + term);
            }
            List<FdaRecord> found = byTerm.get(term);
            return found == null
                    ? Collections.<FdaRecord>emptyList()
                    : new ArrayList<>(found);
        }
    }

    /** Term-sensitive news fake: same contract as the FDA one. */
    private static final class TermSensitiveFakeNewsSource
            implements SafetyAlertService.NewsSource {
        final Map<String, List<RawNewsItem>> byTerm = new HashMap<>();
        final Set<String> throwTerms = new HashSet<>();
        final List<String> termsSeen = Collections.synchronizedList(
                new ArrayList<String>());
        final AtomicInteger invocations = new AtomicInteger();
        long sleepMs;

        @Override
        public List<RawNewsItem> fetchNews(String term) {
            invocations.incrementAndGet();
            termsSeen.add(term);
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    // Cancelled by the service deadline.
                    return Collections.emptyList();
                }
            }
            if (throwTerms.contains(term)) {
                throw new RuntimeException(
                        "simulated news failure for term: " + term);
            }
            List<RawNewsItem> found = byTerm.get(term);
            return found == null
                    ? Collections.<RawNewsItem>emptyList()
                    : new ArrayList<>(found);
        }
    }

    private static void assertAllSingleTokens(List<String> terms) {
        synchronized (terms) {
            for (String term : terms) {
                assertFalse("fetch term must not be empty",
                        term == null || term.isEmpty());
                assertFalse("fetch term must be a single token: "
                        + term, term.contains(" "));
            }
        }
    }

    /** Strawberry FDA records, as in {@link #strawberryFda()}. */
    private static List<FdaRecord> strawberryRecords() {
        List<FdaRecord> records = new ArrayList<>();
        records.add(fdaRecord("Firm A", "Fresh strawberries, 16 oz",
                "Potential hepatitis A contamination", "Class II",
                "Ongoing", 20261105, "ev-a", "F-0002-2026"));
        records.add(fdaRecord("Firm B", "Strawberry jam",
                "Potential listeria contamination", "Class I",
                "Ongoing", 20261001, "ev-b", "F-0001-2026"));
        return records;
    }

    /**
     * Term-sensitive strawberry FDA fake: the fixture-style records
     * come back ONLY for "strawberry" / "strawberries".
     */
    private static TermSensitiveFakeFdaSource termSensitiveStrawberryFda() {
        TermSensitiveFakeFdaSource fda = new TermSensitiveFakeFdaSource();
        fda.byTerm.put("strawberry", strawberryRecords());
        fda.byTerm.put("strawberries", strawberryRecords());
        return fda;
    }

    /**
     * Term-sensitive strawberry news fake (distinct links per item,
     * as real RSS items have): the stories come back ONLY for
     * "strawberry" / "strawberries".
     */
    private static TermSensitiveFakeNewsSource termSensitiveStrawberryNews() {
        TermSensitiveFakeNewsSource news = new TermSensitiveFakeNewsSource();
        List<RawNewsItem> items = new ArrayList<>();
        items.add(new RawNewsItem(
                "Strawberry recall expands to more states",
                "Example News", "news-link-1", REF_EPOCH - DAY_MS));
        items.add(new RawNewsItem(
                "Strawberries outbreak sickens dozens",
                "Example News", "news-link-2", REF_EPOCH));
        news.byTerm.put("strawberry", items);
        news.byTerm.put("strawberries", items);
        return news;
    }

    /** The poultry FDA record: returned ONLY for chicken / poultry. */
    private static FdaRecord poultryRecord() {
        return fdaRecord("Poultry Co", "Frozen poultry strips",
                "Potential salmonella contamination", "Class I",
                "Ongoing", 20261101, "ev-p", "F-0003-2026");
    }

    private static void recordMax(AtomicInteger max, int candidate) {
        int prev;
        do {
            prev = max.get();
            if (candidate <= prev) {
                return;
            }
        } while (!max.compareAndSet(prev, candidate));
    }

    private static SafetyAlertService service(FakeFdaSource fda,
                                              FakeNewsSource news) {
        return new SafetyAlertService(fda, news);
    }

    private static FdaRecord fdaRecord(String firm, String product,
                                       String reason, String classification,
                                       String status, int reportDate,
                                       String eventId, String recallNumber) {
        return new FdaRecord(firm, product, reason, classification,
                status, reportDate, eventId, recallNumber);
    }

    private static RawNewsItem newsItem(String headline, long pubDate) {
        return new RawNewsItem(headline, "Example News", "story-link",
                pubDate);
    }

    /** FDA kept pair for "strawberry": Class II newer, Class I older. */
    private static FakeFdaSource strawberryFda() {
        FakeFdaSource fda = new FakeFdaSource();
        fda.records.add(fdaRecord("Firm A", "Fresh strawberries, 16 oz",
                "Potential hepatitis A contamination", "Class II",
                "Ongoing", 20261105, "ev-a", "F-0002-2026"));
        fda.records.add(fdaRecord("Firm B", "Strawberry jam",
                "Potential listeria contamination", "Class I",
                "Ongoing", 20261001, "ev-b", "F-0001-2026"));
        return fda;
    }

    /** NEWS kept pair for "strawberry": older first on purpose. */
    private static FakeNewsSource strawberryNews() {
        FakeNewsSource news = new FakeNewsSource();
        news.items.add(newsItem("Strawberry recall expands to more states",
                REF_EPOCH - DAY_MS));
        news.items.add(newsItem("Strawberries outbreak sickens dozens",
                REF_EPOCH));
        return news;
    }

    private static SafetyAlert fdaAlert(String productText) {
        return new SafetyAlert(SafetyAlert.Tier.FDA, "Some Firm",
                productText, "Some reason", "meta", "FDA recall",
                "report-link", "Class I", "Ongoing", "ev", "F-1",
                20261101);
    }

    // ------------------------------------------------------------------
    // §3.1 token-set construction: search vs product
    // ------------------------------------------------------------------

    @Test
    public void searchTokens_rawAndFoldedFormsKept() {
        Set<String> tokens =
                SafetyAlertService.queryTokensForSearch("Fresh Strawberries!");
        assertTrue(tokens.contains("fresh"));
        assertTrue(tokens.contains("strawberries"));
        assertTrue(tokens.contains("strawberry"));
        assertFalse(tokens.contains("chicken"));
    }

    @Test
    public void productTokens_includeUsdaCategoryTokens() {
        Set<String> tokens = SafetyAlertService.queryTokensForProduct(
                "Grilled Strips", "Chicken, broiler or fryers");
        assertTrue(tokens.contains("grilled"));
        assertTrue(tokens.contains("strips"));
        assertTrue(tokens.contains("strip"));
        // Category tokens are part of the product token set (§3.1).
        assertTrue(tokens.contains("chicken"));
        assertTrue(tokens.contains("broiler"));
    }

    @Test
    public void productTokens_nullCategoryFallsBackToNameOnly() {
        Set<String> tokens = SafetyAlertService.queryTokensForProduct(
                "Whole Milk", null);
        assertTrue(tokens.contains("milk"));
        assertEquals(2, tokens.size());
    }

    @Test
    public void searchMatching_usesCategoryMapExpansion() {
        // "chicken" matches a poultry-only FDA record only through
        // the §3.2 category expansion applied to the §3.1 token set.
        FakeFdaSource fda = new FakeFdaSource();
        fda.records.add(fdaRecord("Poultry Co", "Frozen poultry nuggets",
                "Potential salmonella contamination", "Class I",
                "Ongoing", 20261101, "ev-p", "F-0003-2026"));
        List<SafetyAlert> alerts = service(fda, new FakeNewsSource())
                .alertsForSearch("chicken", REF_DATE, REF_EPOCH);
        assertEquals(1, alerts.size());
        assertEquals(SafetyAlert.Tier.FDA, alerts.get(0).tier);
    }

    @Test
    public void productMatching_categoryTokenDrivesBothMatchers() {
        // Neither the FDA record nor the headline contains a product
        // NAME token; both match only via the category token
        // "chicken" (FDA side through the poultry expansion).
        // Term-sensitive fakes (fix round 1): the poultry record /
        // chicken story come back ONLY when a client is queried
        // with "chicken" or "poultry" — a raw product-name fetch
        // retrieves nothing, so the category token must actually
        // reach both clients as a fetch term.
        TermSensitiveFakeFdaSource fda = new TermSensitiveFakeFdaSource();
        fda.byTerm.put("chicken",
                Collections.singletonList(poultryRecord()));
        fda.byTerm.put("poultry",
                Collections.singletonList(poultryRecord()));
        TermSensitiveFakeNewsSource news = new TermSensitiveFakeNewsSource();
        List<RawNewsItem> chickenNews = Collections.singletonList(
                new RawNewsItem("Chicken recall hits grocery stores",
                        "Example News", "chicken-news-link", REF_EPOCH));
        news.byTerm.put("chicken", chickenNews);
        news.byTerm.put("poultry", chickenNews);
        List<SafetyAlert> alerts = new SafetyAlertService(fda, news)
                .alertsForProduct("Grilled Strips", "Chicken products",
                        REF_DATE, REF_EPOCH);
        assertEquals(2, alerts.size());
        assertEquals(SafetyAlert.Tier.FDA, alerts.get(0).tier);
        assertEquals(SafetyAlert.Tier.NEWS, alerts.get(1).tier);
        // Per-token fetch: the category token reached both clients,
        // and the raw product name was never sent as a fetch term.
        assertTrue(fda.termsSeen.contains("chicken"));
        assertTrue(news.termsSeen.contains("chicken"));
        assertFalse(fda.termsSeen.contains("Grilled Strips"));
        assertFalse(news.termsSeen.contains("Grilled Strips"));
        assertAllSingleTokens(fda.termsSeen);
        assertAllSingleTokens(news.termsSeen);
    }

    @Test
    public void productMatching_strawberryProduct_nonEmptyPerTokenFetch() {
        // Required scenario (fix round 1): a realistic multi-token
        // product name must still retrieve alerts, because fetching
        // happens per single folded token (bare-token query path),
        // not as one quoted phrase built from the raw name.
        TermSensitiveFakeFdaSource fda = termSensitiveStrawberryFda();
        TermSensitiveFakeNewsSource news = termSensitiveStrawberryNews();
        List<SafetyAlert> alerts = new SafetyAlertService(fda, news)
                .alertsForProduct(
                        "FreshHarvest Farms Organic Strawberries (16 Oz.)",
                        "Berries", REF_DATE, REF_EPOCH);
        assertEquals(4, alerts.size());
        assertEquals(SafetyAlert.Tier.FDA, alerts.get(0).tier);
        assertEquals(SafetyAlert.Tier.FDA, alerts.get(1).tier);
        assertEquals(SafetyAlert.Tier.NEWS, alerts.get(2).tier);
        assertEquals(SafetyAlert.Tier.NEWS, alerts.get(3).tier);
        // The folded strawberry token was fetched from both sources;
        // the raw name and the excluded numeric / unit tokens were
        // never fetch terms, and every fetch term is a single token.
        assertTrue(fda.termsSeen.contains("strawberry"));
        assertTrue(news.termsSeen.contains("strawberry"));
        assertFalse(fda.termsSeen.contains(
                "FreshHarvest Farms Organic Strawberries (16 Oz.)"));
        assertFalse(fda.termsSeen.contains("16"));
        assertFalse(fda.termsSeen.contains("oz"));
        assertFalse(news.termsSeen.contains("16"));
        assertFalse(news.termsSeen.contains("oz"));
        // Folded-distinct: the plural spelling is not fetched as a
        // second token once its folded form is the fetch token.
        assertFalse(fda.termsSeen.contains("strawberries"));
        assertFalse(fda.termsSeen.contains("farms"));
        assertTrue(fda.termsSeen.contains("farm"));
        assertAllSingleTokens(fda.termsSeen);
        assertAllSingleTokens(news.termsSeen);
    }

    @Test
    public void productDedupe_sameFdaRecordForTwoTokens_appearsOnce() {
        // The same record (same event_id + recall_number) is
        // returned for BOTH the "grilled" and the "strip" token
        // calls; the merged set must contain it exactly once.
        FdaRecord dup = fdaRecord("Grill Co", "Grilled strips, frozen",
                "Potential salmonella contamination", "Class I",
                "Ongoing", 20261101, "ev-dup", "F-0010-2026");
        TermSensitiveFakeFdaSource fda = new TermSensitiveFakeFdaSource();
        fda.byTerm.put("grilled", Collections.singletonList(dup));
        fda.byTerm.put("strip", Collections.singletonList(dup));
        List<SafetyAlert> alerts =
                new SafetyAlertService(fda, new TermSensitiveFakeNewsSource())
                        .alertsForProduct("Grilled Strips", null,
                                REF_DATE, REF_EPOCH);
        // Both token calls really happened (the duplicate came from
        // two calls, not one).
        assertTrue(fda.termsSeen.contains("grilled"));
        assertTrue(fda.termsSeen.contains("strip"));
        assertEquals(1, alerts.size());
        assertEquals(SafetyAlert.Tier.FDA, alerts.get(0).tier);
        assertEquals("Grill Co", alerts.get(0).headline);
    }

    @Test
    public void productDedupe_newsSameLinkForTwoTokens_appearsOnce() {
        // Two DIFFERENT matching stories sharing one link, returned
        // for two different token calls: news dedupe is by link
        // (first wins), so only the first survives to the matcher.
        // (The matcher's own title-prefix dedupe would keep both:
        // their first 6 tokens differ.)
        TermSensitiveFakeNewsSource news = new TermSensitiveFakeNewsSource();
        news.byTerm.put("grilled", Collections.singletonList(
                new RawNewsItem("Grilled strips recall hits grocery stores",
                        "Example News", "dup-link", REF_EPOCH)));
        news.byTerm.put("strip", Collections.singletonList(
                new RawNewsItem("Strip snack recall expands across state lines",
                        "Example News", "dup-link", REF_EPOCH - DAY_MS)));
        List<SafetyAlert> alerts =
                new SafetyAlertService(new TermSensitiveFakeFdaSource(), news)
                        .alertsForProduct("Grilled Strips", null,
                                REF_DATE, REF_EPOCH);
        assertEquals(1, alerts.size());
        assertEquals(SafetyAlert.Tier.NEWS, alerts.get(0).tier);
        assertEquals("Grilled strips recall hits grocery stores",
                alerts.get(0).headline);
    }

    @Test
    public void productFailure_oneTokenThrows_otherTokensStillReturn() {
        // Per-token isolation (fix round 1): the first token call
        // ("grilled") throws in BOTH sources, but that contributes
        // nothing for that token only — the later "chicken" token
        // call still yields its record / story for each source.
        TermSensitiveFakeFdaSource fda = new TermSensitiveFakeFdaSource();
        fda.throwTerms.add("grilled");
        fda.byTerm.put("chicken",
                Collections.singletonList(poultryRecord()));
        TermSensitiveFakeNewsSource news = new TermSensitiveFakeNewsSource();
        news.throwTerms.add("grilled");
        news.byTerm.put("chicken", Collections.singletonList(
                new RawNewsItem("Chicken recall hits grocery stores",
                        "Example News", "chicken-news-link", REF_EPOCH)));
        List<SafetyAlert> alerts = new SafetyAlertService(fda, news)
                .alertsForProduct("Grilled Strips", "Chicken products",
                        REF_DATE, REF_EPOCH);
        assertEquals(2, alerts.size());
        assertEquals(SafetyAlert.Tier.FDA, alerts.get(0).tier);
        assertEquals(SafetyAlert.Tier.NEWS, alerts.get(1).tier);
        // The failing token was attempted before the succeeding one.
        assertTrue(fda.termsSeen.indexOf("grilled")
                < fda.termsSeen.indexOf("chicken"));
    }

    @Test
    public void productDeadline_singleOverallDeadlineAcrossTokens() {
        // The FDA source stalls on its (per-token) calls; the one
        // overall deadline — not a fresh bound per token — cuts it
        // off and the news source's per-token results still return.
        TermSensitiveFakeFdaSource fda = termSensitiveStrawberryFda();
        fda.sleepMs = 30000;
        TermSensitiveFakeNewsSource news = termSensitiveStrawberryNews();
        SafetyAlertService svc = new SafetyAlertService(fda, news, 300);
        long start = System.currentTimeMillis();
        List<SafetyAlert> alerts = svc.alertsForProduct(
                "FreshHarvest Farms Organic Strawberries (16 Oz.)",
                "Berries", REF_DATE, REF_EPOCH);
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(2, alerts.size());
        assertEquals(SafetyAlert.Tier.NEWS, alerts.get(0).tier);
        assertTrue("bounded wait, elapsed=" + elapsed, elapsed < 10000);
    }

    @Test
    public void searchFetch_passesRawSearchTextToBothSources() {
        FakeFdaSource fda = new FakeFdaSource();
        FakeNewsSource news = new FakeNewsSource();
        service(fda, news).alertsForSearch("strawberry", REF_DATE, REF_EPOCH);
        assertEquals("strawberry", fda.lastTerm);
        assertEquals("strawberry", news.lastTerm);
    }

    // ------------------------------------------------------------------
    // §4 combined ordering + count (§6.2)
    // ------------------------------------------------------------------

    @Test
    public void combinedOrdering_fdaBlockBeforeNewsBlock_ordersPreserved() {
        List<SafetyAlert> alerts = service(strawberryFda(), strawberryNews())
                .alertsForSearch("strawberry", REF_DATE, REF_EPOCH);
        assertEquals(4, alerts.size());
        // FDA block, matcher order: Class I (Firm B) before Class II.
        assertEquals(SafetyAlert.Tier.FDA, alerts.get(0).tier);
        assertEquals("Firm B", alerts.get(0).headline);
        assertEquals(SafetyAlert.Tier.FDA, alerts.get(1).tier);
        assertEquals("Firm A", alerts.get(1).headline);
        // NEWS block, matcher order: pubDate desc.
        assertEquals(SafetyAlert.Tier.NEWS, alerts.get(2).tier);
        assertEquals("Strawberries outbreak sickens dozens",
                alerts.get(2).headline);
        assertEquals(SafetyAlert.Tier.NEWS, alerts.get(3).tier);
        assertEquals("Strawberry recall expands to more states",
                alerts.get(3).headline);
        // NEWS alerts carry no FDA-only fields (§1).
        assertEquals(null, alerts.get(2).productText);
        assertEquals(null, alerts.get(2).classification);
        assertEquals("News report", alerts.get(2).sourceLabel);
    }

    @Test
    public void combinedCount_matchesCombinedListSize() {
        SafetyAlertService svc =
                service(strawberryFda(), strawberryNews());
        assertEquals(4, svc.alertCountForSearch("strawberry",
                REF_DATE, REF_EPOCH));
        assertEquals(0, service(new FakeFdaSource(), new FakeNewsSource())
                .alertCountForSearch("strawberry", REF_DATE, REF_EPOCH));
    }

    // ------------------------------------------------------------------
    // §6.3 badge rule truth table (pure function)
    // ------------------------------------------------------------------

    @Test
    public void badge_brandAndNameTokenBothPresent_true() {
        List<SafetyAlert> alerts = Collections.singletonList(
                fdaAlert("Sunnyvale Organic Strawberries, 16 oz clamshell"));
        assertTrue(SafetyAlertService.hasFdaBadge(
                "Sunnyvale", "Sunnyvale Organic Strawberries", alerts));
    }

    @Test
    public void badge_brandOnly_false() {
        List<SafetyAlert> alerts = Collections.singletonList(
                fdaAlert("Sunnyvale apple juice, 64 oz"));
        assertFalse(SafetyAlertService.hasFdaBadge(
                "Sunnyvale", "Sunnyvale Organic Strawberries", alerts));
    }

    @Test
    public void badge_nameTokenOnly_false() {
        List<SafetyAlert> alerts = Collections.singletonList(
                fdaAlert("Organic strawberries grown in California"));
        assertFalse(SafetyAlertService.hasFdaBadge(
                "Sunnyvale", "Sunnyvale Organic Strawberries", alerts));
    }

    @Test
    public void badge_newsAlertsNeverBadge_false() {
        SafetyAlert news = new SafetyAlert(SafetyAlert.Tier.NEWS,
                "Sunnyvale strawberries recalled", null, null, "meta",
                "News report", "story-link", null, null, null, null,
                20261107);
        assertFalse(SafetyAlertService.hasFdaBadge("Sunnyvale",
                "Sunnyvale Organic Strawberries",
                Collections.singletonList(news)));
    }

    @Test
    public void badge_casePunctuationPluralVariants_true() {
        List<SafetyAlert> alerts = Collections.singletonList(
                fdaAlert("SUNNYVALE-brand STRAWBERRIES!! (fresh pack)"));
        assertTrue(SafetyAlertService.hasFdaBadge(
                "sunnyvale", "Sunnyvale Strawberry", alerts));
    }

    @Test
    public void badge_wholeTokensOnly_substringDoesNotCount() {
        List<SafetyAlert> alerts = Collections.singletonList(
                fdaAlert("Sunnyvalefarms strawberryfields forever mix"));
        assertFalse(SafetyAlertService.hasFdaBadge(
                "Sunnyvale", "Sunnyvale Strawberry", alerts));
    }

    @Test
    public void badge_nameIsBrandOnly_false() {
        List<SafetyAlert> alerts = Collections.singletonList(
                fdaAlert("Sunnyvale products assortment"));
        assertFalse(SafetyAlertService.hasFdaBadge(
                "Sunnyvale", "Sunnyvale", alerts));
    }

    @Test
    public void badge_nullAndEmptyInputs_false() {
        List<SafetyAlert> alerts = Collections.singletonList(
                fdaAlert("Sunnyvale Organic Strawberries"));
        assertFalse(SafetyAlertService.hasFdaBadge(
                null, "Sunnyvale Organic Strawberries", alerts));
        assertFalse(SafetyAlertService.hasFdaBadge(
                "Sunnyvale", null, alerts));
        assertFalse(SafetyAlertService.hasFdaBadge(
                "Sunnyvale", "Sunnyvale Organic Strawberries", null));
        assertFalse(SafetyAlertService.hasFdaBadge(
                "Sunnyvale", "Sunnyvale Organic Strawberries",
                Collections.<SafetyAlert>emptyList()));
        assertFalse(SafetyAlertService.hasFdaBadge(
                "", "", alerts));
    }

    // ------------------------------------------------------------------
    // Recall counting (brand panel): the counting twin of the badge rule
    // ------------------------------------------------------------------

    @Test
    public void count_twoMatchingFdaAlerts_countsTwo() {
        List<SafetyAlert> alerts = Arrays.asList(
                fdaAlert("Sunnyvale Organic Strawberries, 16 oz"),
                fdaAlert("SUNNYVALE STRAWBERRIES — expanded recall"),
                fdaAlert("Sunnyvale apple juice, 64 oz"),
                fdaAlert("Organic strawberries grown in California"));
        assertEquals(2, SafetyAlertService.countFdaMatches(
                "Sunnyvale", "Sunnyvale Organic Strawberries", alerts));
    }

    @Test
    public void count_newsAlertsNeverCount_zero() {
        SafetyAlert news = new SafetyAlert(SafetyAlert.Tier.NEWS,
                "Sunnyvale strawberries recalled", null, null, "meta",
                "News report", "story-link", null, null, null, null,
                20261107);
        assertEquals(0, SafetyAlertService.countFdaMatches("Sunnyvale",
                "Sunnyvale Organic Strawberries",
                Collections.singletonList(news)));
    }

    @Test
    public void count_nullAndEmptyInputs_zero() {
        List<SafetyAlert> alerts = Collections.singletonList(
                fdaAlert("Sunnyvale Organic Strawberries"));
        assertEquals(0, SafetyAlertService.countFdaMatches(
                null, "Sunnyvale Organic Strawberries", alerts));
        assertEquals(0, SafetyAlertService.countFdaMatches(
                "Sunnyvale", null, alerts));
        assertEquals(0, SafetyAlertService.countFdaMatches(
                "Sunnyvale", "Sunnyvale Organic Strawberries", null));
        assertEquals(0, SafetyAlertService.countFdaMatches(
                "Sunnyvale", "Sunnyvale Organic Strawberries",
                Collections.<SafetyAlert>emptyList()));
    }

    @Test
    public void count_agreesWithBadgeRule() {
        List<SafetyAlert> matching = Collections.singletonList(
                fdaAlert("Sunnyvale Organic Strawberries, 16 oz"));
        List<SafetyAlert> notMatching = Collections.singletonList(
                fdaAlert("Sunnyvale apple juice, 64 oz"));
        assertTrue(SafetyAlertService.countFdaMatches(
                "Sunnyvale", "Sunnyvale Organic Strawberries",
                matching) > 0);
        assertEquals(0, SafetyAlertService.countFdaMatches(
                "Sunnyvale", "Sunnyvale Organic Strawberries",
                notMatching));
    }

    // ------------------------------------------------------------------
    // Brand filtering (brand-focused verdict section): the filtering
    // twin of the counting rule — the section lists what the pill counted
    // ------------------------------------------------------------------

    @Test
    public void filter_returnsExactlyTheCountedAlerts_inOrder() {
        SafetyAlert first = fdaAlert("Sunnyvale Organic Strawberries, 16 oz");
        SafetyAlert other = fdaAlert("Organic strawberries grown in California");
        SafetyAlert second = fdaAlert("SUNNYVALE STRAWBERRIES — expanded recall");
        SafetyAlert wrongProduct = fdaAlert("Sunnyvale apple juice, 64 oz");
        List<SafetyAlert> alerts = Arrays.asList(first, other, second, wrongProduct);
        List<SafetyAlert> filtered = SafetyAlertService.filterBrandMatches(
                "Sunnyvale", "Sunnyvale Organic Strawberries", alerts);
        assertEquals(2, filtered.size());
        assertSame(first, filtered.get(0));
        assertSame(second, filtered.get(1));
        // The filter and the count are the same rule, so they agree.
        assertEquals(filtered.size(), SafetyAlertService.countFdaMatches(
                "Sunnyvale", "Sunnyvale Organic Strawberries", alerts));
    }

    @Test
    public void filter_newsAlertsNeverReturned() {
        SafetyAlert news = new SafetyAlert(SafetyAlert.Tier.NEWS,
                "Sunnyvale strawberries recalled", null, null, "meta",
                "News report", "story-link", null, null, null, null,
                20261107);
        assertTrue(SafetyAlertService.filterBrandMatches("Sunnyvale",
                "Sunnyvale Organic Strawberries",
                Collections.singletonList(news)).isEmpty());
    }

    @Test
    public void filter_noBrandMatch_empty() {
        // The Wyman's case in reverse: a brand with no badge-rule
        // match gets an empty section even when the product type has
        // loose reports — its pill said "No recalls".
        List<SafetyAlert> alerts = Arrays.asList(
                fdaAlert("Organic strawberries grown in California"),
                fdaAlert("EAK Distribution sprouting seeds for strawberries"));
        assertTrue(SafetyAlertService.filterBrandMatches("New Seasons",
                "New Seasons Organic Strawberries", alerts).isEmpty());
    }

    @Test
    public void filter_nullAndEmptyInputs_empty() {
        List<SafetyAlert> alerts = Collections.singletonList(
                fdaAlert("Sunnyvale Organic Strawberries"));
        assertTrue(SafetyAlertService.filterBrandMatches(
                null, "Sunnyvale Organic Strawberries", alerts).isEmpty());
        assertTrue(SafetyAlertService.filterBrandMatches(
                "Sunnyvale", null, alerts).isEmpty());
        assertTrue(SafetyAlertService.filterBrandMatches(
                "Sunnyvale", "Sunnyvale Organic Strawberries", null).isEmpty());
        assertTrue(SafetyAlertService.filterBrandMatches(
                "Sunnyvale", "Sunnyvale Organic Strawberries",
                Collections.<SafetyAlert>emptyList()).isEmpty());
    }

    @Test
    public void count_possessiveBrand_noPhantomMatchFromStrayS() {
        // Regression (device, strawberries): WYMAN'S and WELCH'S each
        // showed 1 recall. Their token sets contain the possessive
        // fragment "s", which matched unrelated descriptions that
        // contain an apostrophe-s plus the product token. openFDA has
        // no Wyman's or Welch's strawberry recall at all.
        List<SafetyAlert> alerts = Arrays.asList(
                fdaAlert("Loard's Strawberry Ice Cream — 32 oz; 56 oz"),
                fdaAlert("Member's Mark FREEZE DRIED FRUIT VARIETY PACK "
                        + "STRAWBERRY POUCHES"));
        assertEquals(0, SafetyAlertService.countFdaMatches(
                "WYMAN'S", "Strawberries", alerts));
        assertEquals(0, SafetyAlertService.countFdaMatches(
                "WELCH'S", "Strawberries", alerts));
        assertTrue(SafetyAlertService.filterBrandMatches(
                "WYMAN'S", "Strawberries", alerts).isEmpty());
    }

    @Test
    public void count_possessiveBrand_realMatchStillCounts() {
        List<SafetyAlert> alerts = Collections.singletonList(
                fdaAlert("WYMAN'S Strawberries, frozen, 32 oz"));
        assertEquals(1, SafetyAlertService.countFdaMatches(
                "WYMAN'S", "Strawberries", alerts));
        assertEquals(1, SafetyAlertService.filterBrandMatches(
                "WYMAN'S", "Strawberries", alerts).size());
    }

    @Test
    public void badge_viaServiceInstance_delegatesToSameRule() {
        SafetyAlertService svc =
                service(new FakeFdaSource(), new FakeNewsSource());
        List<SafetyAlert> alerts = Collections.singletonList(
                fdaAlert("Sunnyvale Organic Strawberries, 16 oz"));
        assertTrue(svc.badgeForProduct("Sunnyvale",
                "Sunnyvale Organic Strawberries", alerts));
        assertFalse(svc.badgeForProduct("Sunnyvale",
                "Sunnyvale Organic Strawberries",
                Collections.<SafetyAlert>emptyList()));
    }

    // ------------------------------------------------------------------
    // Concurrency: both sources fetched, in parallel, bounded wait
    // ------------------------------------------------------------------

    @Test
    public void concurrency_bothSourcesFetchedOnce() {
        FakeFdaSource fda = strawberryFda();
        FakeNewsSource news = strawberryNews();
        service(fda, news).alertsForSearch("strawberry",
                REF_DATE, REF_EPOCH);
        assertEquals(1, fda.invocations.get());
        assertEquals(1, news.invocations.get());
    }

    @Test
    public void concurrency_sourcesRunInParallel() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        FakeFdaSource fda = strawberryFda();
        fda.sleepMs = 400;
        fda.inFlight = inFlight;
        fda.maxInFlight = maxInFlight;
        FakeNewsSource news = strawberryNews();
        news.sleepMs = 400;
        news.inFlight = inFlight;
        news.maxInFlight = maxInFlight;
        List<SafetyAlert> alerts = service(fda, news)
                .alertsForSearch("strawberry", REF_DATE, REF_EPOCH);
        assertEquals(4, alerts.size());
        assertEquals("both fetches overlapped", 2, maxInFlight.get());
    }

    @Test
    public void concurrency_slowSourceTimesOut_otherSourceStillReturns() {
        FakeFdaSource fda = new FakeFdaSource();
        fda.sleepMs = 30000;
        FakeNewsSource news = strawberryNews();
        SafetyAlertService svc = new SafetyAlertService(fda, news, 300);
        long start = System.currentTimeMillis();
        List<SafetyAlert> alerts = svc.alertsForSearch("strawberry",
                REF_DATE, REF_EPOCH);
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(2, alerts.size());
        assertEquals(SafetyAlert.Tier.NEWS, alerts.get(0).tier);
        assertTrue("bounded wait, elapsed=" + elapsed, elapsed < 10000);
    }

    // ------------------------------------------------------------------
    // §7 failure isolation + Throwable-proof boundary
    // ------------------------------------------------------------------

    @Test
    public void failure_fdaPathThrows_newsStillReturned() {
        FakeFdaSource fda = new FakeFdaSource();
        fda.throwOnFetch = true;
        List<SafetyAlert> alerts = service(fda, strawberryNews())
                .alertsForSearch("strawberry", REF_DATE, REF_EPOCH);
        assertEquals(2, alerts.size());
        assertEquals(SafetyAlert.Tier.NEWS, alerts.get(0).tier);
    }

    @Test
    public void failure_newsPathThrows_fdaStillReturned() {
        FakeNewsSource news = new FakeNewsSource();
        news.throwOnFetch = true;
        List<SafetyAlert> alerts = service(strawberryFda(), news)
                .alertsForSearch("strawberry", REF_DATE, REF_EPOCH);
        assertEquals(2, alerts.size());
        assertEquals(SafetyAlert.Tier.FDA, alerts.get(0).tier);
    }

    @Test
    public void failure_bothThrow_emptyAndNothingEscapes() {
        FakeFdaSource fda = new FakeFdaSource();
        fda.throwOnFetch = true;
        FakeNewsSource news = new FakeNewsSource();
        news.throwOnFetch = true;
        SafetyAlertService svc = service(fda, news);
        assertTrue(svc.alertsForSearch("strawberry",
                REF_DATE, REF_EPOCH).isEmpty());
        assertEquals(0, svc.alertCountForSearch("strawberry",
                REF_DATE, REF_EPOCH));
        assertTrue(svc.alertsForProduct("Grilled Strips",
                "Chicken products", REF_DATE, REF_EPOCH).isEmpty());
    }

    @Test
    public void failure_nullSources_emptyAndNothingEscapes() {
        SafetyAlertService svc = new SafetyAlertService(
                (SafetyAlertService.FdaSource) null,
                (SafetyAlertService.NewsSource) null);
        assertTrue(svc.alertsForSearch("strawberry",
                REF_DATE, REF_EPOCH).isEmpty());
        assertEquals(0, svc.alertCountForSearch("strawberry",
                REF_DATE, REF_EPOCH));
    }

    // ------------------------------------------------------------------
    // Empty-term behavior
    // ------------------------------------------------------------------

    @Test
    public void emptyTerm_noFetchAndEmptyResult() {
        FakeFdaSource fda = strawberryFda();
        FakeNewsSource news = strawberryNews();
        SafetyAlertService svc = service(fda, news);
        assertTrue(svc.alertsForSearch("", REF_DATE, REF_EPOCH).isEmpty());
        assertTrue(svc.alertsForSearch(null, REF_DATE, REF_EPOCH).isEmpty());
        assertTrue(svc.alertsForSearch("  !!!  ", REF_DATE, REF_EPOCH)
                .isEmpty());
        assertTrue(svc.alertsForProduct(null, null, REF_DATE, REF_EPOCH)
                .isEmpty());
        assertTrue(svc.alertsForProduct("", "", REF_DATE, REF_EPOCH)
                .isEmpty());
        assertEquals(0, fda.invocations.get());
        assertEquals(0, news.invocations.get());
    }

    @Test
    public void noMatch_eitherSource_emptyCombined() {
        FakeFdaSource fda = new FakeFdaSource();
        fda.records.add(fdaRecord("Other Co", "Canned peaches",
                "Undeclared sulfites", "Class II", "Ongoing",
                20261101, "ev-o", "F-0009-2026"));
        FakeNewsSource news = new FakeNewsSource();
        news.items.add(newsItem("Peach prices steady this fall", REF_EPOCH));
        assertTrue(service(fda, news)
                .alertsForSearch("strawberry", REF_DATE, REF_EPOCH)
                .isEmpty());
    }

    @Test
    public void defaultEntryPoints_doNotThrow() {
        // The no-reference-date overloads use the device clock; with
        // empty fakes they must simply return empty, without throwing.
        SafetyAlertService svc =
                service(new FakeFdaSource(), new FakeNewsSource());
        assertTrue(svc.alertsForSearch("strawberry").isEmpty());
        assertTrue(svc.alertsForProduct("Whole Milk", "Milk products")
                .isEmpty());
        assertEquals(0, svc.alertCountForSearch("strawberry"));
        assertEquals(0, svc.alertCountForProduct("Whole Milk", null));
    }
}
