package com.barelabel.app.news;

import android.content.Context;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Service orchestration for the news-alerts feature (Contracts
 * sections 3.1, 4, 6.2, 6.3 and 7; task NA-04). This is the single
 * entry point the UI calls: given a search term, or a product (name
 * plus USDA category), it fetches both sources CONCURRENTLY, runs
 * the FDA matcher (NA-01) and the news matcher (NA-02) over the
 * section 3.1 query token set, and returns the combined list in
 * section 4 order — every FDA alert first (in FDA order), then the
 * news alerts (in news order). The size of that list is the combined
 * count behind the section 6.2 count line. The static
 * {@link #hasFdaBadge} implements the section 6.3 row-badge rule.
 *
 * <p>Section 7 boundary: no method of this class ever throws. Every
 * entry point catches Throwable; the two sources are fetched and
 * matched in independent tasks, so a failure (or a timeout) in one
 * source still returns the other source's alerts, and a failure in
 * both yields an empty list, a count of 0, or a false badge.
 *
 * <p>Concurrency: each call runs the two source pipelines on a
 * per-call two-thread {@link ExecutorService} (the per-call pool
 * pattern already used elsewhere in the app) and waits with a
 * single overall deadline of {@link #DEFAULT_OVERALL_TIMEOUT_MS}.
 * The bound covers the slowest source: the FDA client may issue up
 * to 3 sequential variant requests at the section 7 limit of 10 s
 * each (30 s worst case) while the news client needs one 10 s
 * request, and the sources run in parallel. The pool is always shut
 * down before the call returns, so no threads leak (important for
 * JVM unit tests). Only minSdk-23 APIs are used — no lambdas, no
 * streams, no java.time, no CompletableFuture.
 *
 * <p>Testability: the two clients sit behind the {@link FdaSource}
 * and {@link NewsSource} seams, so unit tests inject fakes and run
 * with no network and no Android. The {@link Context} constructor
 * is a thin convenience that only builds the production clients;
 * all logic in this class is Context-free.
 */
public final class SafetyAlertService {

    /**
     * Overall wait bound for one combined call, in milliseconds.
     * See the class javadoc: it covers the FDA client's worst case
     * of 3 sequential 10 s variant requests, plus slack.
     */
    public static final long DEFAULT_OVERALL_TIMEOUT_MS = 35000L;

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    /** FDA fetch seam: NA-03's client in production, a fake in tests. */
    public interface FdaSource {
        /** Raw records for {@code term}; may throw — the service copes. */
        List<FdaRecord> fetchRecalls(String term);
    }

    /** News fetch seam: NA-03's client in production, a fake in tests. */
    public interface NewsSource {
        /** Raw RSS items for {@code term}; may throw — the service copes. */
        List<RawNewsItem> fetchNews(String term);
    }

    private final FdaSource fdaSource;
    private final NewsSource newsSource;
    private final long overallTimeoutMs;
    private final NewsMatcher newsMatcher = new NewsMatcher();

    /** Seam constructor (unit tests, custom wiring). */
    public SafetyAlertService(FdaSource fdaSource, NewsSource newsSource) {
        this(fdaSource, newsSource, DEFAULT_OVERALL_TIMEOUT_MS);
    }

    /** Seam constructor with an explicit overall wait bound. */
    public SafetyAlertService(FdaSource fdaSource, NewsSource newsSource,
                              long overallTimeoutMs) {
        this.fdaSource = fdaSource;
        this.newsSource = newsSource;
        this.overallTimeoutMs = overallTimeoutMs > 0
                ? overallTimeoutMs : DEFAULT_OVERALL_TIMEOUT_MS;
    }

    /** Production constructor over NA-03's two clients. */
    public SafetyAlertService(final OpenFdaClient fdaClient,
                              final GoogleNewsClient newsClient) {
        this(new FdaSource() {
                    @Override
                    public List<FdaRecord> fetchRecalls(String term) {
                        return fdaClient == null
                                ? Collections.<FdaRecord>emptyList()
                                : fdaClient.fetchRecalls(term);
                    }
                },
                new NewsSource() {
                    @Override
                    public List<RawNewsItem> fetchNews(String term) {
                        return newsClient == null
                                ? Collections.<RawNewsItem>emptyList()
                                : newsClient.fetchNews(term);
                    }
                });
    }

    /**
     * Thin convenience constructor: builds both production clients
     * from the app cache dir. All service logic stays Context-free.
     */
    public SafetyAlertService(Context context) {
        this(new OpenFdaClient(context), new GoogleNewsClient(context));
    }

    // ------------------------------------------------------------------
    // Query token sets (Contract section 3.1)
    // ------------------------------------------------------------------

    /** Token set for a search term: its tokens, raw + folded forms. */
    public static Set<String> queryTokensForSearch(String searchText) {
        try {
            return NewsText.queryTokens(searchText);
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }

    /**
     * Token set for a product card: tokens of the product name PLUS
     * tokens of its USDA category string (Contract section 3.1).
     */
    public static Set<String> queryTokensForProduct(String productName,
                                                    String usdaCategory) {
        try {
            Set<String> out = new LinkedHashSet<>();
            out.addAll(NewsText.queryTokens(productName));
            out.addAll(NewsText.queryTokens(usdaCategory));
            return Collections.unmodifiableSet(out);
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }

    // ------------------------------------------------------------------
    // Combined alerts (Contract section 4)
    // ------------------------------------------------------------------

    /** Combined alerts for a search term, dated against today. */
    public List<SafetyAlert> alertsForSearch(String searchText) {
        return alertsForSearch(searchText, todayYyyymmdd(),
                System.currentTimeMillis());
    }

    /**
     * Combined alerts for a search term: the section 3.1 token set
     * is built from the raw search text, both sources are fetched
     * concurrently for that text, matched, and combined in section
     * 4 order. Empty (tokenless) terms short-circuit: no fetch, an
     * empty list. Never throws (section 7).
     */
    public List<SafetyAlert> alertsForSearch(String searchText,
                                             int referenceDateYyyymmdd,
                                             long referenceEpochMillis) {
        try {
            Set<String> tokens = queryTokensForSearch(searchText);
            if (tokens.isEmpty()) {
                return Collections.emptyList();
            }
            return fetchAndMatch(searchText, tokens,
                    referenceDateYyyymmdd, referenceEpochMillis);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /** Combined alerts for a product (verdict card), dated today. */
    public List<SafetyAlert> alertsForProduct(String productName,
                                              String usdaCategory) {
        return alertsForProduct(productName, usdaCategory,
                todayYyyymmdd(), System.currentTimeMillis());
    }

    /**
     * Combined alerts for a product: the token set is the product
     * name tokens plus the USDA category tokens (section 3.1); the
     * sources are fetched for the product name. Ordering and the
     * never-throws boundary are as for {@link #alertsForSearch}.
     */
    public List<SafetyAlert> alertsForProduct(String productName,
                                              String usdaCategory,
                                              int referenceDateYyyymmdd,
                                              long referenceEpochMillis) {
        try {
            Set<String> tokens =
                    queryTokensForProduct(productName, usdaCategory);
            if (tokens.isEmpty()) {
                return Collections.emptyList();
            }
            return fetchAndMatch(productName, tokens,
                    referenceDateYyyymmdd, referenceEpochMillis);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /** Combined alert count for a search term (section 6.2 copy). */
    public int alertCountForSearch(String searchText) {
        try {
            return alertsForSearch(searchText).size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Combined alert count, with explicit reference dates. */
    public int alertCountForSearch(String searchText,
                                   int referenceDateYyyymmdd,
                                   long referenceEpochMillis) {
        try {
            return alertsForSearch(searchText, referenceDateYyyymmdd,
                    referenceEpochMillis).size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Combined alert count for a product (section 6.2 copy). */
    public int alertCountForProduct(String productName,
                                    String usdaCategory) {
        try {
            return alertsForProduct(productName, usdaCategory).size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Combined alert count for a product, explicit reference dates. */
    public int alertCountForProduct(String productName,
                                    String usdaCategory,
                                    int referenceDateYyyymmdd,
                                    long referenceEpochMillis) {
        try {
            return alertsForProduct(productName, usdaCategory,
                    referenceDateYyyymmdd, referenceEpochMillis).size();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Badge rule (Contract section 6.3)
    // ------------------------------------------------------------------

    /** Instance form of {@link #hasFdaBadge}; never throws. */
    public boolean badgeForProduct(String brand, String productName,
                                   List<SafetyAlert> fdaAlerts) {
        return hasFdaBadge(brand, productName, fdaAlerts);
    }

    /**
     * The section 6.3 badge rule, as a pure function: true iff at
     * least one FDA alert's normalized product description contains
     * BOTH a brand token of the product AND at least one other
     * (non-brand) token of the product's name — whole tokens,
     * normalized via {@link NewsText} (so case, punctuation and
     * singular/plural variants all match). NEWS alerts never badge.
     * Null or empty inputs yield false. Never throws.
     */
    public static boolean hasFdaBadge(String brand, String productName,
                                      List<SafetyAlert> fdaAlerts) {
        try {
            if (fdaAlerts == null || fdaAlerts.isEmpty()) {
                return false;
            }
            Set<String> brandTokens = NewsText.tokenSet(brand);
            if (brandTokens.isEmpty()) {
                return false;
            }
            Set<String> otherNameTokens =
                    new LinkedHashSet<>(NewsText.tokenSet(productName));
            otherNameTokens.removeAll(brandTokens);
            if (otherNameTokens.isEmpty()) {
                return false;
            }
            for (SafetyAlert alert : fdaAlerts) {
                if (alert == null
                        || alert.tier != SafetyAlert.Tier.FDA
                        || alert.productText == null) {
                    continue;
                }
                Set<String> descriptionTokens =
                        NewsText.tokenSet(alert.productText);
                if (!containsAny(descriptionTokens, brandTokens)) {
                    continue;
                }
                if (containsAny(descriptionTokens, otherNameTokens)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean containsAny(Set<String> haystack,
                                       Set<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Concurrent pipeline
    // ------------------------------------------------------------------

    /**
     * Runs the FDA and news pipelines concurrently and combines
     * their results: FDA alerts (matcher order) first, then news
     * alerts (matcher order). Each pipeline is independently
     * Throwable-proof; a pipeline that fails or misses the overall
     * deadline contributes an empty block.
     */
    private List<SafetyAlert> fetchAndMatch(
            final String fetchTerm, final Set<String> tokens,
            final int referenceDateYyyymmdd,
            final long referenceEpochMillis) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<SafetyAlert>> fdaFuture =
                    pool.submit(new Callable<List<SafetyAlert>>() {
                        @Override
                        public List<SafetyAlert> call() {
                            return fdaAlerts(fetchTerm, tokens,
                                    referenceDateYyyymmdd);
                        }
                    });
            Future<List<SafetyAlert>> newsFuture =
                    pool.submit(new Callable<List<SafetyAlert>>() {
                        @Override
                        public List<SafetyAlert> call() {
                            return newsAlerts(fetchTerm, tokens,
                                    referenceEpochMillis);
                        }
                    });
            long deadline = System.currentTimeMillis() + overallTimeoutMs;
            List<SafetyAlert> combined = new ArrayList<>();
            combined.addAll(await(fdaFuture, deadline));
            combined.addAll(await(newsFuture, deadline));
            return combined;
        } catch (Throwable t) {
            return Collections.emptyList();
        } finally {
            // Interrupt any straggler and release the threads: no
            // executor outlives the call.
            pool.shutdownNow();
        }
    }

    /**
     * One source's block; empty on timeout or any failure (§7). A
     * future that already finished is always collected, even when
     * the other source consumed the whole deadline first — the
     * deadline bounds waiting for unfinished work, never the
     * collection of finished work.
     */
    private static List<SafetyAlert> await(Future<List<SafetyAlert>> future,
                                           long deadlineMillis) {
        try {
            if (!future.isDone()) {
                long remaining =
                        deadlineMillis - System.currentTimeMillis();
                if (remaining <= 0) {
                    future.cancel(true);
                    return Collections.emptyList();
                }
                future.get(remaining, TimeUnit.MILLISECONDS);
            }
            List<SafetyAlert> result = future.get();
            return result != null
                    ? result : Collections.<SafetyAlert>emptyList();
        } catch (Throwable t) {
            future.cancel(true);
            return Collections.emptyList();
        }
    }

    /** FDA pipeline: fetch via the seam, match via NA-01. Never throws. */
    private List<SafetyAlert> fdaAlerts(String fetchTerm,
                                        Set<String> tokens,
                                        int referenceDateYyyymmdd) {
        try {
            if (fdaSource == null) {
                return Collections.emptyList();
            }
            List<FdaRecord> records = fdaSource.fetchRecalls(fetchTerm);
            List<SafetyAlert> alerts = FdaRecallMatcher.match(
                    records, tokens, referenceDateYyyymmdd);
            return alerts != null
                    ? alerts : Collections.<SafetyAlert>emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /** News pipeline: fetch via the seam, match via NA-02, adapt. */
    private List<SafetyAlert> newsAlerts(String fetchTerm,
                                         Set<String> tokens,
                                         long referenceEpochMillis) {
        try {
            if (newsSource == null) {
                return Collections.emptyList();
            }
            List<RawNewsItem> items = newsSource.fetchNews(fetchTerm);
            List<NewsStory> stories =
                    newsMatcher.match(items, tokens, referenceEpochMillis);
            if (stories == null) {
                return Collections.emptyList();
            }
            List<SafetyAlert> alerts = new ArrayList<>();
            for (NewsStory story : stories) {
                if (story != null) {
                    alerts.add(toAlert(story));
                }
            }
            return alerts;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /** Adapts an NA-02 {@link NewsStory} to the section 1 model. */
    private static SafetyAlert toAlert(NewsStory story) {
        return new SafetyAlert(
                SafetyAlert.Tier.NEWS,
                story.getHeadline(),
                null,
                null,
                story.getMetaLine(),
                story.getSourceLabel(),
                story.getLink(),
                null,
                null,
                null,
                null,
                yyyymmddFromEpochMillisUtc(story.getPubDateEpochMillis()));
    }

    // ------------------------------------------------------------------
    // Date helpers (minSdk 23: no java.time)
    // ------------------------------------------------------------------

    /** Today as a YYYYMMDD int, in the device local timezone (§4). */
    private static int todayYyyymmdd() {
        Calendar cal = Calendar.getInstance();
        return cal.get(Calendar.YEAR) * 10000
                + (cal.get(Calendar.MONTH) + 1) * 100
                + cal.get(Calendar.DAY_OF_MONTH);
    }

    /**
     * UTC calendar date of an epoch-millis instant as a YYYYMMDD
     * int (0 when the instant is not positive), via the
     * civil-from-days algorithm — the inverse of the days-from-civil
     * conversion in {@link NewsDates}.
     */
    static int yyyymmddFromEpochMillisUtc(long epochMillis) {
        if (epochMillis <= 0) {
            return 0;
        }
        long epochDay = epochMillis / DAY_MS;
        long z = epochDay + 719468L;
        long era = (z >= 0 ? z : z - 146096L) / 146097L;
        long doe = z - era * 146097L;                    // [0, 146096]
        long yoe = (doe - doe / 1460L + doe / 36524L
                - doe / 146096L) / 365L;                 // [0, 399]
        long year = yoe + era * 400L;
        long doy = doe - (365L * yoe + yoe / 4L - yoe / 100L);
        long mp = (5L * doy + 2L) / 153L;                // [0, 11]
        long day = doy - (153L * mp + 2L) / 5L + 1L;     // [1, 31]
        long month = mp < 10 ? mp + 3 : mp - 9;          // [1, 12]
        if (month <= 2) {
            year += 1;
        }
        return (int) (year * 10000L + month * 100L + day);
    }
}
