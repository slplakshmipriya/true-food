package com.barelabel.app.news;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
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
        FakeFdaSource fda = new FakeFdaSource();
        fda.records.add(fdaRecord("Poultry Co", "Frozen poultry strips",
                "Potential salmonella contamination", "Class I",
                "Ongoing", 20261101, "ev-p", "F-0003-2026"));
        FakeNewsSource news = new FakeNewsSource();
        news.items.add(newsItem("Chicken recall hits grocery stores",
                REF_EPOCH));
        List<SafetyAlert> alerts = service(fda, news)
                .alertsForProduct("Grilled Strips", "Chicken products",
                        REF_DATE, REF_EPOCH);
        assertEquals(2, alerts.size());
        assertEquals(SafetyAlert.Tier.FDA, alerts.get(0).tier);
        assertEquals(SafetyAlert.Tier.NEWS, alerts.get(1).tier);
        // The fetch term for a product is its name.
        assertEquals("Grilled Strips", fda.lastTerm);
        assertEquals("Grilled Strips", news.lastTerm);
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
