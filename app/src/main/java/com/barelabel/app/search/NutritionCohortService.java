package com.barelabel.app.search;

import com.barelabel.app.model.NutritionProfile;
import com.barelabel.app.model.ProductResult;
import com.barelabel.app.network.UsdaApiClient;
import com.barelabel.app.nutrition.CohortPeer;
import com.barelabel.app.nutrition.NutrientComparison;
import com.barelabel.app.nutrition.NutrientDefinition;
import com.barelabel.app.nutrition.NutritionAnalysis;
import com.barelabel.app.nutrition.NutritionAnalyzer;
import com.barelabel.app.nutrition.NutritionConfig;
import com.barelabel.app.nutrition.NutritionStatus;
import com.barelabel.app.nutrition.PeerCohort;
import com.barelabel.app.nutrition.PeerDistribution;
import com.barelabel.app.nutrition.UsdaNutritionParser;
import com.barelabel.app.util.StringNormalizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Cohort acquisition and primary-product nutrition analysis for the
 * search flow (Contract §5 step 3), plus the alternatives sourcing
 * decision (Contract §5 step 4, PRD §9.1).
 *
 * <p>{@link #analyzePrimary} builds the primary's {@link PeerCohort}
 * from caller-supplied seed peers (profiles parsed from the search
 * candidates — no extra ingredient analysis), then tops the cohort up
 * with same-category USDA pages while any displayed nutrient still has
 * fewer than {@code minPeers} valid peers and the
 * {@code maxCategoryPages} budget lasts. Page peers are parsed with
 * {@link UsdaNutritionParser} only — pure peers get no ingredient
 * matching — and screened for spam brands. The finished cohort feeds
 * {@link NutritionAnalyzer}; the result is stored on
 * {@code primary.nutritionAnalysis} and also returned.
 *
 * <p>Failure posture (Contract §5 error cases, PRD §12/§13): a null
 * config makes every analysis UNAVAILABLE; a failing top-up page
 * degrades to the partial cohort; any other analysis failure degrades
 * that product's analysis to UNAVAILABLE. The search itself never
 * fails because of nutrition. Nothing is persisted: profiles, cohorts,
 * and analyses live in memory for the current search only, and the
 * only network traffic is the existing USDA page fetch (whose raw
 * responses stay in the existing USDA response cache).
 *
 * <p>Pure Java + org.json (no Android APIs) so the orchestration seams
 * are JVM-unit-testable with a fake {@link FoodsPageSource}.
 */
public class NutritionCohortService {

    /** Page size for cohort top-up fetches (matches the finder's). */
    public static final int TOP_UP_PAGE_SIZE = 50;

    /**
     * One page of raw USDA foods for a category. Implementations may
     * throw on transport failure; the service treats a throw as
     * end-of-top-up and keeps the partial cohort.
     */
    public interface FoodsPageSource {
        List<JSONObject> fetchPage(String category, int page) throws Exception;
    }

    private final NutritionConfig config;
    private final FoodsPageSource pageSource;

    /**
     * @param config     validated nutrition config; null means the
     *                   config failed to load and every analysis is
     *                   UNAVAILABLE (no top-up is attempted)
     * @param pageSource top-up page source; may be null, in which case
     *                   cohorts are built from seeds only
     */
    public NutritionCohortService(NutritionConfig config, FoodsPageSource pageSource) {
        this.config = config;
        this.pageSource = pageSource;
    }

    /**
     * Production page source backed by
     * {@link UsdaApiClient#fetchFoodsPage}: a category query for the
     * category itself, category-filtered, {@link #TOP_UP_PAGE_SIZE}
     * foods per page.
     */
    public static FoodsPageSource usdaPageSource(final UsdaApiClient apiClient) {
        return new FoodsPageSource() {
            @Override
            public List<JSONObject> fetchPage(String category, int page)
                    throws Exception {
                if (apiClient == null) {
                    return Collections.emptyList();
                }
                JSONArray foods = apiClient.fetchFoodsPage(
                        category, category, true, page, TOP_UP_PAGE_SIZE);
                List<JSONObject> out = new ArrayList<>();
                if (foods != null) {
                    for (int i = 0; i < foods.length(); i++) {
                        JSONObject food = foods.optJSONObject(i);
                        if (food != null) {
                            out.add(food);
                        }
                    }
                }
                return out;
            }
        };
    }

    // ------------------------------------------------------------------
    // Sourcing decision (Contract §5 step 4, PRD §9.1)
    // ------------------------------------------------------------------

    /**
     * Whether alternatives must be sourced for a primary with the given
     * ingredient verdict and nutrition status. The PRD §9.1 matrix:
     * CLEAN+SAFE → no; CLEAN+CONCERNING → yes; DIRTY+SAFE → yes;
     * DIRTY+CONCERNING → yes; DIRTY+UNAVAILABLE → yes (ingredient
     * flags drive sourcing); CLEAN+UNAVAILABLE → no. A category-intent
     * search always sources, whatever the primary's verdicts are. A
     * null status reads as UNAVAILABLE (no analysis was produced).
     */
    public static boolean shouldSource(boolean ingredientClean,
                                       NutritionStatus status,
                                       boolean categoryIntent) {
        if (categoryIntent) {
            return true;
        }
        if (!ingredientClean) {
            return true;
        }
        return status == NutritionStatus.CONCERNING;
    }

    // ------------------------------------------------------------------
    // Seed peers
    // ------------------------------------------------------------------

    /**
     * Seed peers from raw USDA search-candidate foods: each food is
     * parsed through the single parser path, spam brands are skipped,
     * and the peer's category is the food's own USDA category. The
     * dedupe key is the GTIN/UPC when present, else the normalized
     * brand+description+serving key. Returns an empty list when the
     * config is null (nutrition UNAVAILABLE) or there are no foods.
     */
    public static List<CohortPeer> seedPeersFromCandidates(List<JSONObject> foods,
                                                           NutritionConfig config) {
        if (config == null || foods == null) {
            return new ArrayList<>();
        }
        return peersFromFoods(foods, null, config);
    }

    // ------------------------------------------------------------------
    // Primary analysis
    // ------------------------------------------------------------------

    /** Analyzes the primary using the constructor's page source. */
    public NutritionAnalysis analyzePrimary(ProductResult primary,
                                            List<CohortPeer> seedPeers,
                                            String category) {
        return analyzePrimary(primary, seedPeers, category, pageSource);
    }

    /**
     * Builds the primary's cohort (seeds, then top-up pages from
     * {@code source} while any displayed nutrient has &lt;
     * {@code minPeers} valid peers and pages remain), runs the
     * analyzer, stores the result on {@code primary.nutritionAnalysis},
     * and returns it. Never throws: any failure yields an UNAVAILABLE
     * analysis, which is still stored on the primary.
     */
    public NutritionAnalysis analyzePrimary(ProductResult primary,
                                            List<CohortPeer> seedPeers,
                                            String category,
                                            FoodsPageSource source) {
        NutritionAnalysis analysis;
        try {
            analysis = analyze(primary, seedPeers, category, source);
        } catch (Exception e) {
            // Analysis must never fail the search (Contract §5).
            analysis = unavailable(category, 0);
        }
        if (primary != null) {
            primary.nutritionAnalysis = analysis;
        }
        return analysis;
    }

    private NutritionAnalysis analyze(ProductResult primary,
                                      List<CohortPeer> seedPeers,
                                      String category,
                                      FoodsPageSource source) {
        if (config == null) {
            return unavailable(category, 0);
        }
        // Short-circuit (NUT-05 review, Minor 3): a missing or not-found
        // primary, or a profile reporting none of the configured
        // nutrients, is UNAVAILABLE regardless of cohort — skip the
        // top-up page fetches entirely instead of issuing up to
        // maxCategoryPages fetches whose result cannot change this.
        if (primary == null || !primary.found) {
            return unavailable(category, 0);
        }
        NutritionProfile profile = primary.nutritionProfile != null
                ? primary.nutritionProfile
                : NutritionProfile.empty(primary.fdcId);
        if (!reportsAnyConfiguredNutrient(profile)) {
            return unavailable(category, 0);
        }

        List<CohortPeer> pool = new ArrayList<>();
        if (seedPeers != null) {
            pool.addAll(seedPeers);
        }
        PeerCohort cohort = PeerCohort.build(profile, category, pool, config);

        // Top-up (Contract §5 step 3): fetch same-category pages until
        // every displayed nutrient has at least minPeers valid peers
        // or the page budget is exhausted. A failing or empty page
        // ends the top-up with whatever cohort exists so far.
        int page = 1;
        while (source != null && page <= config.maxCategoryPages
                && needsTopUp(cohort)) {
            List<JSONObject> foods;
            try {
                foods = source.fetchPage(category, page);
            } catch (Exception e) {
                break; // partial cohort, never a failed search
            }
            if (foods == null || foods.isEmpty()) {
                break;
            }
            pool.addAll(peersFromFoods(foods, category, config));
            cohort = PeerCohort.build(profile, category, pool, config);
            page++;
        }
        return NutritionAnalyzer.analyze(profile, cohort, config);
    }

    /** True when the profile reports at least one configured nutrient. */
    private boolean reportsAnyConfiguredNutrient(NutritionProfile profile) {
        for (NutrientDefinition def : config.byKey.values()) {
            if (profile.reported(def.key)) {
                return true;
            }
        }
        return false;
    }

    /** True while any displayed nutrient has &lt; minPeers valid peers. */
    private boolean needsTopUp(PeerCohort cohort) {
        for (NutrientDefinition def : config.byKey.values()) {
            if (!def.display) {
                continue;
            }
            PeerDistribution distribution = cohort.byNutrient.get(def.key);
            int count = distribution != null ? distribution.count() : 0;
            if (count < config.minPeers) {
                return true;
            }
        }
        return false;
    }

    private static NutritionAnalysis unavailable(String category, int peerCount) {
        return new NutritionAnalysis(NutritionStatus.UNAVAILABLE, null, 0.0,
                category != null ? category : "", peerCount,
                new LinkedHashMap<String, NutrientComparison>(),
                new ArrayList<String>());
    }

    // ------------------------------------------------------------------
    // Raw foods -> peers
    // ------------------------------------------------------------------

    /**
     * Parses raw USDA foods into cohort peers (parser only — no
     * ingredient matching for pure peers). Spam brands are skipped.
     * When {@code categoryOverride} is non-null it is recorded as every
     * peer's category (top-up pages are fetched for that category);
     * otherwise each food's own USDA category is used (search seeds).
     */
    private static List<CohortPeer> peersFromFoods(List<JSONObject> foods,
                                                   String categoryOverride,
                                                   NutritionConfig config) {
        List<CohortPeer> peers = new ArrayList<>();
        for (JSONObject food : foods) {
            if (food == null) {
                continue;
            }
            if (UsdaSpamFilter.isSpamBrand(food.optString("brandOwner", ""))) {
                continue;
            }
            NutritionProfile profile = UsdaNutritionParser.parse(food, config);
            String category = categoryOverride != null
                    ? categoryOverride
                    : food.optString("foodCategory", "");
            peers.add(new CohortPeer(profile, dedupeKeyFor(food), category));
        }
        return peers;
    }

    /**
     * Dedupe key for one USDA food: the GTIN/UPC when present, else a
     * normalized brand+description+serving key (brand name preferred,
     * brand owner as fallback — the same display rule the product
     * builders use).
     */
    static String dedupeKeyFor(JSONObject food) {
        String gtin = food.optString("gtinUpc", "").trim();
        if (!gtin.isEmpty()) {
            return gtin;
        }
        String brandName = food.optString("brandName", "");
        String brand = !brandName.isEmpty()
                ? brandName : food.optString("brandOwner", "");
        StringBuilder key = new StringBuilder();
        key.append(StringNormalizer.normalize(brand)).append('|')
                .append(StringNormalizer.normalize(
                        food.optString("description", ""))).append('|');
        if (food.has("servingSize") && !food.isNull("servingSize")) {
            key.append(food.optDouble("servingSize", 0));
        } else {
            key.append(StringNormalizer.normalize(
                    food.optString("householdServingFullText", "")));
        }
        return key.toString();
    }
}
