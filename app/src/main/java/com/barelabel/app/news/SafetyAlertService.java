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
 * <p>Product fetching (fix round 1): a search is fetched as one
 * term (§2.1 variant behavior, frozen), but a PRODUCT is fetched
 * PER TOKEN — each distinct folded content token of the section
 * 3.1 set (product name tokens plus USDA category tokens) is sent
 * to each client as a single token, taking the client's bare-token
 * query path instead of a quoted phrase built from the raw product
 * name, which realistic names match almost nothing against. The
 * per-token results are merged and deduped (FDA by event_id +
 * recall_number, first-wins, mirroring {@link OpenFdaClient};
 * news by link, falling back to the normalized title), then
 * matched ONCE against the full section 3.1 token set. Because the
 * NA-03 clients key their section 5 response cache by the term
 * they are called with, per-token calls also make the cache key
 * cover the category tokens.
 *
 * <p>Concurrency: each call runs the two source pipelines on a
 * per-call two-thread {@link ExecutorService} (the per-call pool
 * pattern already used elsewhere in the app) and waits with a
 * single overall deadline of {@link #DEFAULT_OVERALL_TIMEOUT_MS}.
 * The bound covers the slowest source: the FDA client may issue up
 * to 3 sequential variant requests at the section 7 limit of 10 s
 * each (30 s worst case) while the news client needs one 10 s
 * request, and the sources run in parallel. The single deadline is
 * shared by every per-token call of a product fetch — no token
 * gets a fresh bound. The pool is shut down before the call
 * returns via {@code shutdownNow()} (without awaiting
 * termination): no executor is left accepting work, and its
 * threads are interrupted on a best-effort basis, so a task that
 * ignores interrupts could in principle outlive the call briefly.
 * Only minSdk-23 APIs are used — no lambdas, no streams, no
 * java.time, no CompletableFuture.
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
     * Retained as the section 3.1 definition; MATCHING a product's
     * alerts now uses the tightened set from
     * {@link #productMatchTokens} (fix round 3, see there).
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

    /**
     * The token set a product's alerts are MATCHED against (fix
     * round 3): the product NAME tokens, plus only those category
     * tokens that are curated food-identity terms — keys of the
     * section 3.2 category map (today: "chicken", whose expansion
     * "poultry" the FDA matcher adds). Every other category token
     * still widens the FETCH (see {@link #fetchTokensForProduct})
     * but can no longer make a record a "match" by itself.
     *
     * <p>Why: the section 3.1 any-token rule let a category's
     * generic vocabulary act as evidence. The Driscoll's
     * strawberries record sits in USDA category "Pre-Packaged Fruit
     * &amp; Vegetables"; its card surfaced three Raw Farm RAW
     * CHEDDAR recalls because the cheddar ingredient lists contain
     * the whole token "vegetable" ("VEGETABLE RENNET"). Generic
     * category words (fruit, vegetable, packaged, products) appear
     * in unrelated products' descriptions far too often to identify
     * a food. The curated map keys are the exception: they exist
     * precisely to carry a food's identity when the product name
     * cannot (a "Grilled Strips" product whose category "Chicken
     * products" must still match poultry recalls).
     */
    public static Set<String> productMatchTokens(String productName,
                                                 String usdaCategory) {
        try {
            Set<String> out = new LinkedHashSet<>(
                    queryTokensForSearch(productName));
            if (usdaCategory != null) {
                for (String raw : NewsText.tokens(usdaCategory)) {
                    String folded = NewsText.foldToken(raw);
                    if (FdaRecallMatcher.CATEGORY_MAP.containsKey(raw)
                            || FdaRecallMatcher.CATEGORY_MAP
                                    .containsKey(folded)) {
                        out.add(raw);
                        out.add(folded);
                    }
                }
            }
            return Collections.unmodifiableSet(out);
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }

    /**
     * Fetch tokens for a product card (fix round 1): the distinct
     * FOLDED content tokens of the product name plus its USDA
     * category, in first-seen order (name first, then category).
     * Each of these is sent to each source as a SINGLE-token fetch
     * term, so the folded form is the fetch spelling ("strawberry",
     * not both "strawberries" and "strawberry").
     *
     * <p>Exclusions (documented set): any token containing a digit
     * (pure sizes such as "16", and fused sizes such as "16oz" or
     * "500g") and the unit tokens in
     * {@link #EXCLUDED_FETCH_TOKENS} ("oz", "lb", "g", "kg", "ml",
     * ...) are package-size noise, never food terms, and are not
     * fetched. They stay in the section 3.1 MATCH token set built
     * by {@link #queryTokensForProduct}; only fetching skips them.
     */
    public static List<String> fetchTokensForProduct(String productName,
                                                     String usdaCategory) {
        try {
            List<String> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            addFetchTokens(out, seen, productName);
            addFetchTokens(out, seen, usdaCategory);
            return Collections.unmodifiableList(out);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    private static void addFetchTokens(List<String> out, Set<String> seen,
                                       String text) {
        for (String rawToken : NewsText.tokens(text)) {
            String folded = NewsText.foldToken(rawToken);
            if (folded.isEmpty() || isExcludedFetchToken(folded)) {
                continue;
            }
            if (seen.add(folded)) {
                out.add(folded);
            }
        }
    }

    /** Unit tokens never fetched (see {@link #fetchTokensForProduct}). */
    private static final Set<String> EXCLUDED_FETCH_TOKENS;

    static {
        Set<String> units = new LinkedHashSet<>();
        units.add("oz");
        units.add("lb");
        units.add("lbs");
        units.add("g");
        units.add("kg");
        units.add("mg");
        units.add("mcg");
        units.add("ml");
        units.add("l");
        units.add("liter");
        units.add("litre");
        units.add("gal");
        units.add("gallon");
        units.add("pt");
        units.add("pint");
        units.add("qt");
        units.add("quart");
        units.add("fl");
        units.add("ct");
        units.add("count");
        units.add("pack");
        units.add("pkg");
        EXCLUDED_FETCH_TOKENS = Collections.unmodifiableSet(units);
    }

    private static boolean isExcludedFetchToken(String foldedToken) {
        for (int i = 0; i < foldedToken.length(); i++) {
            char c = foldedToken.charAt(i);
            if (c >= '0' && c <= '9') {
                // Pure or fused package size ("16", "16oz", "500g").
                return true;
            }
        }
        return EXCLUDED_FETCH_TOKENS.contains(foldedToken);
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
     * Combined alerts for a product: the sources are fetched PER
     * FETCH TOKEN (see {@link #fetchTokensForProduct}) — never with
     * the raw product name — then matched once against the product
     * MATCH token set (see {@link #productMatchTokens}: name tokens
     * plus curated identity category tokens; generic category words
     * fetch but do not match, fix round 3). Ordering and the
     * never-throws boundary are as for {@link #alertsForSearch}.
     */
    public List<SafetyAlert> alertsForProduct(String productName,
                                              String usdaCategory,
                                              int referenceDateYyyymmdd,
                                              long referenceEpochMillis) {
        try {
            Set<String> tokens =
                    productMatchTokens(productName, usdaCategory);
            if (tokens.isEmpty()) {
                return Collections.emptyList();
            }
            List<String> fetchTokens =
                    fetchTokensForProduct(productName, usdaCategory);
            if (fetchTokens.isEmpty()) {
                return Collections.emptyList();
            }
            return fetchAndMatchPerToken(fetchTokens, tokens,
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
        return countFdaMatches(brand, productName, fdaAlerts) > 0;
    }

    /**
     * The counting twin of {@link #hasFdaBadge}: how many FDA alerts
     * match the product under the same per-alert rule (brand token
     * AND a non-brand name token in the alert's normalized product
     * description). Backs the brand panel's per-brand recall count.
     * NEWS alerts never count. Null or empty inputs yield 0. Never
     * throws.
     */
    public static int countFdaMatches(String brand, String productName,
                                      List<SafetyAlert> fdaAlerts) {
        try {
            if (fdaAlerts == null || fdaAlerts.isEmpty()) {
                return 0;
            }
            Set<String> brandTokens = badgeTokens(brand);
            if (brandTokens.isEmpty()) {
                return 0;
            }
            Set<String> otherNameTokens =
                    new LinkedHashSet<>(badgeTokens(productName));
            otherNameTokens.removeAll(brandTokens);
            if (otherNameTokens.isEmpty()) {
                return 0;
            }
            int count = 0;
            for (SafetyAlert alert : fdaAlerts) {
                if (matchesBadgeRule(brandTokens, otherNameTokens, alert)) {
                    count++;
                }
            }
            return count;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Token set for the badge rule: {@link NewsText#tokenSet} minus
     * single-character tokens. A one-letter token cannot identify a
     * brand or a product, and the possessive fragment is a real
     * false-positive source: "WYMAN'S" tokenizes to {wyman, s}, and
     * the stray "s" matched any alert description containing an
     * apostrophe-s ("Loard's Strawberry Ice Cream", "Member's Mark
     * ... Strawberry"), giving every brand ending in 'S a phantom
     * recall. Length-1 tokens are dropped on BOTH sides of the rule.
     */
    private static Set<String> badgeTokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String token : NewsText.tokenSet(text)) {
            if (token.length() >= 2) {
                out.add(token);
            }
        }
        return out;
    }

    /**
     * The filtering twin of {@link #countFdaMatches}: the FDA alerts
     * that match the brand under the same per-alert badge rule, in
     * input order. Backs the brand-focused Safety reports section on
     * a verdict opened from the brand panel — the section lists
     * exactly the recalls the brand's pill counted. NEWS alerts are
     * never returned (they carry no brand attribution). Null or
     * empty inputs yield an empty list. Never throws.
     */
    public static List<SafetyAlert> filterBrandMatches(
            String brand, String productName, List<SafetyAlert> alerts) {
        try {
            if (alerts == null || alerts.isEmpty()) {
                return Collections.emptyList();
            }
            Set<String> brandTokens = badgeTokens(brand);
            if (brandTokens.isEmpty()) {
                return Collections.emptyList();
            }
            Set<String> otherNameTokens =
                    new LinkedHashSet<>(badgeTokens(productName));
            otherNameTokens.removeAll(brandTokens);
            if (otherNameTokens.isEmpty()) {
                return Collections.emptyList();
            }
            List<SafetyAlert> out = new ArrayList<>();
            for (SafetyAlert alert : alerts) {
                if (matchesBadgeRule(brandTokens, otherNameTokens, alert)) {
                    out.add(alert);
                }
            }
            return out;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /** One alert against the section 6.3 badge rule (FDA tier only). */
    private static boolean matchesBadgeRule(Set<String> brandTokens,
                                            Set<String> otherNameTokens,
                                            SafetyAlert alert) {
        if (alert == null
                || alert.tier != SafetyAlert.Tier.FDA
                || alert.productText == null) {
            return false;
        }
        Set<String> descriptionTokens = NewsText.tokenSet(alert.productText);
        return containsAny(descriptionTokens, brandTokens)
                && containsAny(descriptionTokens, otherNameTokens);
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
        final long deadline =
                System.currentTimeMillis() + overallTimeoutMs;
        return executePipelines(deadline,
                new Callable<List<SafetyAlert>>() {
                    @Override
                    public List<SafetyAlert> call() {
                        return fdaAlerts(fetchTerm, tokens,
                                referenceDateYyyymmdd);
                    }
                },
                new Callable<List<SafetyAlert>>() {
                    @Override
                    public List<SafetyAlert> call() {
                        return newsAlerts(fetchTerm, tokens,
                                referenceEpochMillis);
                    }
                });
    }

    /**
     * Product variant of {@link #fetchAndMatch}: each source
     * pipeline fetches per token (see the class javadoc) under the
     * SAME single overall deadline computed here — the deadline is
     * passed into both pipelines, which stop starting new token
     * calls once it passes, and {@link #await} bounds the wait for
     * each pipeline by that same deadline.
     */
    private List<SafetyAlert> fetchAndMatchPerToken(
            final List<String> fetchTokens, final Set<String> tokens,
            final int referenceDateYyyymmdd,
            final long referenceEpochMillis) {
        final long deadline =
                System.currentTimeMillis() + overallTimeoutMs;
        return executePipelines(deadline,
                new Callable<List<SafetyAlert>>() {
                    @Override
                    public List<SafetyAlert> call() {
                        return fdaAlertsPerToken(fetchTokens, tokens,
                                referenceDateYyyymmdd, deadline);
                    }
                },
                new Callable<List<SafetyAlert>>() {
                    @Override
                    public List<SafetyAlert> call() {
                        return newsAlertsPerToken(fetchTokens, tokens,
                                referenceEpochMillis, deadline);
                    }
                });
    }

    /**
     * Runs the FDA and news pipelines concurrently and combines
     * their results against one shared deadline: FDA alerts
     * (matcher order) first, then news alerts (matcher order).
     * Each pipeline is independently Throwable-proof; a pipeline
     * that fails or misses the deadline contributes an empty block.
     */
    private List<SafetyAlert> executePipelines(
            long deadline,
            Callable<List<SafetyAlert>> fdaTask,
            Callable<List<SafetyAlert>> newsTask) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<SafetyAlert>> fdaFuture = pool.submit(fdaTask);
            Future<List<SafetyAlert>> newsFuture = pool.submit(newsTask);
            List<SafetyAlert> combined = new ArrayList<>();
            combined.addAll(await(fdaFuture, deadline));
            combined.addAll(await(newsFuture, deadline));
            return combined;
        } catch (Throwable t) {
            return Collections.emptyList();
        } finally {
            // Interrupt any straggler (shutdownNow, without awaiting
            // termination): no executor outlives the call, though a
            // task ignoring interrupts may finish asynchronously.
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
            return matchNews(items, tokens, referenceEpochMillis);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /**
     * Product FDA pipeline (fix round 1): one single-token fetch
     * per fetch token, in order, under the shared overall deadline
     * (checked before each token call — a token call already in
     * flight when the deadline passes is bounded by
     * {@link #await}'s cancellation, as before).
     *
     * <p>Per-token failure rule (§7, decided here): a token call
     * that throws contributes NOTHING for that token, but does not
     * void the source — the remaining tokens are still fetched. If
     * every token call fails the merged set is simply empty, which
     * is exactly the total-source-failure outcome. The merged
     * records are deduped by {@link OpenFdaClient#mergeDedupe}
     * (event_id + recall_number, first-wins) and matched ONCE
     * against the full section 3.1 token set.
     */
    private List<SafetyAlert> fdaAlertsPerToken(List<String> fetchTokens,
                                                Set<String> tokens,
                                                int referenceDateYyyymmdd,
                                                long deadlineMillis) {
        try {
            if (fdaSource == null) {
                return Collections.emptyList();
            }
            List<FdaRecord> merged = new ArrayList<>();
            for (String fetchToken : fetchTokens) {
                if (System.currentTimeMillis() >= deadlineMillis) {
                    break;
                }
                try {
                    List<FdaRecord> records =
                            fdaSource.fetchRecalls(fetchToken);
                    if (records != null) {
                        merged.addAll(records);
                    }
                } catch (Throwable tokenFailure) {
                    // Contributes nothing for this token; the source
                    // and its other tokens are unaffected (§7).
                }
            }
            List<SafetyAlert> alerts = FdaRecallMatcher.match(
                    OpenFdaClient.mergeDedupe(merged), tokens,
                    referenceDateYyyymmdd);
            return alerts != null
                    ? alerts : Collections.<SafetyAlert>emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /**
     * Product news pipeline (fix round 1): the same per-token
     * fetching, deadline and failure rules as
     * {@link #fdaAlertsPerToken}. The merged items are deduped by
     * link, falling back to the normalized title when the link is
     * null/empty (first occurrence wins), then matched ONCE
     * against the full section 3.1 token set.
     */
    private List<SafetyAlert> newsAlertsPerToken(List<String> fetchTokens,
                                                 Set<String> tokens,
                                                 long referenceEpochMillis,
                                                 long deadlineMillis) {
        try {
            if (newsSource == null) {
                return Collections.emptyList();
            }
            List<RawNewsItem> merged = new ArrayList<>();
            for (String fetchToken : fetchTokens) {
                if (System.currentTimeMillis() >= deadlineMillis) {
                    break;
                }
                try {
                    List<RawNewsItem> items =
                            newsSource.fetchNews(fetchToken);
                    if (items != null) {
                        merged.addAll(items);
                    }
                } catch (Throwable tokenFailure) {
                    // Contributes nothing for this token; the source
                    // and its other tokens are unaffected (§7).
                }
            }
            return matchNews(dedupeNewsItems(merged), tokens,
                    referenceEpochMillis);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /**
     * News dedupe across per-token calls (fix round 1): by link,
     * falling back to the normalized title when the link is
     * null/empty; the first occurrence wins and order is preserved.
     */
    static List<RawNewsItem> dedupeNewsItems(List<RawNewsItem> items) {
        List<RawNewsItem> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (RawNewsItem item : items) {
            if (item == null) {
                continue;
            }
            String link = item.getLink();
            String key;
            if (link != null && !link.trim().isEmpty()) {
                key = "link\u0001" + link.trim();
            } else {
                key = "title\u0001" + NewsText.normalize(item.getHeadline());
            }
            if (seen.add(key)) {
                out.add(item);
            }
        }
        return out;
    }

    /** Matches raw news items via NA-02 and adapts the stories. */
    private List<SafetyAlert> matchNews(List<RawNewsItem> items,
                                        Set<String> tokens,
                                        long referenceEpochMillis) {
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
