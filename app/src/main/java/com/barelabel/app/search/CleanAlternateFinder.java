package com.barelabel.app.search;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.barelabel.app.FlaggedIngredientManager;
import com.barelabel.app.model.AlternateSearchResult;
import com.barelabel.app.model.NutritionProfile;
import com.barelabel.app.model.ProductResult;
import com.barelabel.app.network.UsdaApiClient;
import com.barelabel.app.nutrition.CohortPeer;
import com.barelabel.app.nutrition.NutrientComparison;
import com.barelabel.app.nutrition.NutritionAnalysis;
import com.barelabel.app.nutrition.NutritionAnalyzer;
import com.barelabel.app.nutrition.NutritionConfig;
import com.barelabel.app.nutrition.NutritionStatus;
import com.barelabel.app.nutrition.PeerCohort;
import com.barelabel.app.nutrition.UsdaNutritionParser;
import com.barelabel.app.util.StringNormalizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * Finds clean (unflagged) USDA alternates for a product or a category.
 * <p>
 * Clean items can be rare in a category (e.g. almost every cookie contains
 * wheat), so every page is scanned into a full clean pool instead of stopping
 * at the first few hits — USDA sorts by relevance, not organic-ness, and
 * organic items are a small minority that would otherwise never be seen.
 * A second, explicit {@code "organic <category>"} query hunts organic
 * versions, since USDA full-text search matches "organic" against the
 * description, ingredients, and brand. For category (unbranded) searches the
 * pool is seeded with the user's raw query first, so the strict all-tokens
 * exact filter has query-relevant items to match (e.g. "cream cheese"
 * classifies to the broad "Cheese" category).
 * <p>
 * Nutrition eligibility (Contract §5 step 5, PRD §9.2): every scanned
 * product gets a {@link NutritionProfile} through the single parser
 * path ({@link UsdaNutritionParser}) when it is built. After scanning,
 * the final peer cohort is rebuilt once — the step-3 cohort peers
 * (seeds + category top-up, supplied by the caller) extended with the
 * scanned pool — and the primary and every scanned candidate are
 * analyzed against it. Only candidates that are ingredient CLEAN
 * <em>and</em> nutrition SAFE are returned in
 * {@link AlternateSearchResult#alternates}; a DIRTY, CONCERNING, or
 * UNAVAILABLE candidate is never returned and never substituted when
 * nothing qualifies (the alternates list is then explicitly empty).
 * Scanning is bounded by the per-query page budgets and the pool cap,
 * not by how many clean candidates were seen, so early clean but
 * nutrition-ineligible candidates cannot crowd out later SAFE ones.
 */
public class CleanAlternateFinder {

    private static final String TAG = "CleanAlternateFinder";
    private static final int PAGE_SIZE = 50;
    private static final int MAX_POOL_SIZE = 60;

    private final Context appContext;
    private final UsdaApiClient apiClient;

    public CleanAlternateFinder(Context context, UsdaApiClient apiClient) {
        this.appContext = context.getApplicationContext();
        this.apiClient = apiClient;
    }

    /**
     * Legacy entry point without nutrition context. Without a config
     * and a step-3 cohort no candidate can be verified nutrition SAFE,
     * so the scan, the scanned peer pool, and the flagged-category
     * block statistics are produced exactly as in the nutrition-aware
     * path, but {@code alternates} stays empty (fail closed, PRD §9.2)
     * and {@code primaryAnalysis} stays null. Production callers use
     * the extended overload below.
     */
    public AlternateSearchResult findCleanAlternates(String foodCategory, boolean filterByCategory,
                                                     boolean categoryIntent, ProductResult primaryResult,
                                                     String userQuery)
            throws Exception {
        return findCleanAlternates(foodCategory, filterByCategory, categoryIntent,
                primaryResult, userQuery, null, null, null);
    }

    /**
     * Nutrition-aware search (Contract §5 step 5).
     *
     * @param primaryAnalysis the primary's step-3 analysis; used as the
     *                        fallback for {@code result.primaryAnalysis}
     *                        when no refreshed analysis can be produced
     *                        (e.g. a null config)
     * @param cohortPeers     the step-3 cohort peers (seeds + top-up)
     *                        the final cohort is rebuilt from, extended
     *                        with the scanned pool; may be null
     * @param config          validated nutrition config; null makes
     *                        every analysis UNAVAILABLE and therefore
     *                        no candidate eligible
     */
    public AlternateSearchResult findCleanAlternates(String foodCategory, boolean filterByCategory,
                                                     boolean categoryIntent, ProductResult primaryResult,
                                                     String userQuery,
                                                     NutritionAnalysis primaryAnalysis,
                                                     List<CohortPeer> cohortPeers,
                                                     NutritionConfig config)
            throws Exception {
        AlternateSearchResult result = new AlternateSearchResult();
        result.primaryAnalysis = primaryAnalysis;

        if (!primaryResult.found
                || primaryResult.ingredients == null
                || primaryResult.ingredients.trim().isEmpty()
                || foodCategory == null
                || "Uncategorized".equalsIgnoreCase(foodCategory)) {
            return result;
        }

        // Whether alternates are sourced at all is the caller's decision
        // (NutritionCohortService.shouldSource, Contract §5 step 4): this
        // finder no longer early-returns for clean primaries, so a clean
        // but nutrition-CONCERNING primary can still be sourced.

        Set<String> seenProductKeys = new HashSet<>();
        String primaryKey = StringNormalizer.normalize(primaryResult.name)
                + "|" + StringNormalizer.normalize(primaryResult.brand);
        seenProductKeys.add(primaryKey);

        // Resolve the user's flagged-category toggles once per search and
        // reuse the pure (I/O-free) matcher per candidate — the old code
        // re-read assets, recompiled ~200 regexes, and re-read prefs per
        // candidate.
        Set<String> enabledCategories =
                FlaggedIngredientManager.getEnabledCategories(appContext);

        int poolCap = config != null && config.alternativesPoolCap > 0
                ? config.alternativesPoolCap : MAX_POOL_SIZE;
        List<ProductResult> scanned = new ArrayList<>();

        // For a category (unbranded) search, seed the pool with the user's actual
        // query first. The category pass below is broad ("cream cheese" classifies
        // to "Cheese"), so without this the pool would hold generic category items
        // and the strict all-tokens exact filter would rarely match anything.
        if (categoryIntent && userQuery != null && !userQuery.trim().isEmpty()
                && !userQuery.trim().equalsIgnoreCase(foodCategory)) {
            collectScanned(result, scanned, seenProductKeys, foodCategory,
                    false, userQuery.trim(), 2, enabledCategories, poolCap, config);
        }

        collectScanned(result, scanned, seenProductKeys, foodCategory,
                filterByCategory, foodCategory, 3, enabledCategories, poolCap, config);

        // Organic versions exist in the database but rarely crack relevance-sorted
        // results, so hunt for them explicitly.
        collectScanned(result, scanned, seenProductKeys, foodCategory,
                filterByCategory, "organic " + foodCategory, 2, enabledCategories,
                poolCap, config);

        // Contract §5 step 5: rebuild the final cohort once (step-3
        // peers + scanned pool), analyze the primary and every scanned
        // candidate against it, keep only CLEAN ∧ SAFE alternates.
        finalizeScannedPool(primaryResult, scanned, cohortPeers, foodCategory,
                config, result);
        if (result.primaryAnalysis == null) {
            result.primaryAnalysis = primaryAnalysis;
        }
        if (result.primaryAnalysis != null) {
            primaryResult.nutritionAnalysis = result.primaryAnalysis;
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Eligibility and final-cohort seams (pure, JVM-unit-testable)
    // ------------------------------------------------------------------

    /**
     * The alternate eligibility predicate (PRD §9.2): ingredient CLEAN
     * ∧ nutrition SAFE. A null analysis reads as UNAVAILABLE — never
     * as SAFE — so unanalyzed candidates are ineligible.
     */
    public static boolean isEligibleAlternate(boolean ingredientClean,
                                              NutritionAnalysis analysis) {
        return ingredientClean
                && analysis != null
                && analysis.status == NutritionStatus.SAFE;
    }

    /**
     * Eligibility of one scanned candidate from its own state: its
     * ingredient flagged set (and match result, when present) must be
     * clean and its nutrition analysis must be SAFE.
     */
    public static boolean isEligibleAlternate(ProductResult candidate) {
        if (candidate == null) {
            return false;
        }
        boolean ingredientClean = candidate.flagged == null
                || candidate.flagged.isEmpty();
        if (ingredientClean && candidate.matchResult != null
                && candidate.matchResult.hasMatches()) {
            ingredientClean = false;
        }
        return isEligibleAlternate(ingredientClean, candidate.nutritionAnalysis);
    }

    /**
     * Records one ingredient-blocked candidate in the result's
     * flagged-category block statistics: the categories join
     * {@code flaggedCategories} and each increments its
     * {@code filterBlockCounts} entry, so the UI can explain an empty
     * result and rank filters by restrictiveness. Clean or null match
     * results record nothing.
     */
    public static void recordBlockedCandidate(
            AlternateSearchResult result,
            FlaggedIngredientManager.MatchResult matchResult) {
        if (result == null || matchResult == null || !matchResult.hasMatches()) {
            return;
        }
        Set<String> blockedBy = matchResult.categoryMap.keySet();
        result.flaggedCategories.addAll(blockedBy);
        // Per-filter block histogram: a candidate blocked by several
        // filters counts toward each, ranking filters by restrictiveness.
        for (String category : blockedBy) {
            Integer n = result.filterBlockCounts.get(category);
            result.filterBlockCounts.put(category, n == null ? 1 : n + 1);
        }
    }

    /**
     * Builds one scanned product from its raw USDA food object — the
     * finder's manual build path. The nutrition profile and {@code
     * fdcId} are attached through {@link UsdaNutritionParser}, the
     * single parser path (Contract §3); a null config attaches an
     * empty profile (nutrition UNAVAILABLE) instead of parsing.
     */
    public static ProductResult buildScannedProduct(
            JSONObject food, FlaggedIngredientManager.MatchResult matchResult,
            NutritionConfig config) {
        String name = food.optString("description", "");
        // Show the brand on the food label (what shoppers recognize);
        // the brand owner is only a fallback — one owner often holds
        // many unrelated brands.
        String brandName = food.optString("brandName", "");
        String brandOwner = food.optString("brandOwner", "");
        String brand = !brandName.isEmpty() ? brandName : brandOwner;
        String ingredients = food.optString("ingredients", "");

        ProductResult scanned = new ProductResult(true, name, brand, ingredients, matchResult);
        scanned.foodCategory = food.optString("foodCategory", "");
        scanned.gtinUpc = food.optString("gtinUpc", "");
        scanned.brandName = brandName;
        scanned.brandOwner = brandOwner;
        scanned.fdcId = food.optLong("fdcId", 0);
        if (config != null) {
            NutritionProfile profile;
            try {
                profile = UsdaNutritionParser.parse(food, config);
            } catch (Exception ignored) {
                // A parser failure must never fail the search.
                profile = NutritionProfile.empty(scanned.fdcId);
            }
            scanned.nutritionProfile = profile;
            if (profile != null && profile.fdcId != 0) {
                scanned.fdcId = profile.fdcId;
            }
        } else {
            scanned.nutritionProfile = NutritionProfile.empty(scanned.fdcId);
        }
        return scanned;
    }

    /**
     * Final-cohort step (Contract §5 step 5), as a pure seam over the
     * already-scanned pool:
     * <ol>
     *   <li>{@code scannedPool} (deduped, in scan order) is recorded as
     *       {@code result.peerPool}.</li>
     *   <li>The final cohort is rebuilt once, anchored on the primary:
     *       its category is {@code cohortCategory}, its basis is the
     *       primary profile's basis, and its peers are the step-3
     *       {@code cohortPeers} (seeds + top-up) extended with the
     *       scanned pool. {@link PeerCohort} applies candidate
     *       exclusion, category/basis filtering, and dedupe.</li>
     *   <li>The primary is re-analyzed against the final cohort; the
     *       refreshed analysis lands on {@code result.primaryAnalysis}
     *       and on the primary itself.</li>
     *   <li>Every scanned candidate is analyzed against the same final
     *       cohort (the analysis is stored on the candidate) and only
     *       {@link #isEligibleAlternate(ProductResult) eligible} ones
     *       are appended to {@code result.alternates}, in scan order.
     *       When none qualify, {@code alternates} is left empty — no
     *       ineligible product is ever substituted.</li>
     * </ol>
     * A null config or primary skips analysis entirely (analyses stay
     * null, hence UNAVAILABLE and ineligible) while the peer pool is
     * still recorded. An analysis exception degrades that one product
     * to UNAVAILABLE; the search never fails (Contract §5).
     */
    public static AlternateSearchResult finalizeScannedPool(
            ProductResult primary, List<ProductResult> scannedPool,
            List<CohortPeer> cohortPeers, String cohortCategory,
            NutritionConfig config, AlternateSearchResult result) {
        if (result == null) {
            result = new AlternateSearchResult();
        }
        if (scannedPool != null) {
            for (ProductResult scanned : scannedPool) {
                if (scanned != null && !result.peerPool.contains(scanned)) {
                    result.peerPool.add(scanned);
                }
            }
        }
        if (config == null || primary == null) {
            return result;
        }

        NutritionProfile primaryProfile = primary.nutritionProfile != null
                ? primary.nutritionProfile
                : NutritionProfile.empty(primary.fdcId);

        List<CohortPeer> extendedPeers = new ArrayList<>();
        if (cohortPeers != null) {
            extendedPeers.addAll(cohortPeers);
        }
        if (scannedPool != null) {
            for (ProductResult scanned : scannedPool) {
                if (scanned == null) {
                    continue;
                }
                NutritionProfile profile = scanned.nutritionProfile != null
                        ? scanned.nutritionProfile
                        : NutritionProfile.empty(scanned.fdcId);
                extendedPeers.add(new CohortPeer(profile,
                        dedupeKeyFor(scanned), scanned.foodCategory));
            }
        }
        PeerCohort finalCohort = PeerCohort.build(primaryProfile,
                cohortCategory, extendedPeers, config);

        NutritionAnalysis refreshed = safeAnalyze(primaryProfile, finalCohort,
                config, cohortCategory);
        result.primaryAnalysis = refreshed;
        primary.nutritionAnalysis = refreshed;

        if (scannedPool != null) {
            for (ProductResult scanned : scannedPool) {
                if (scanned == null) {
                    continue;
                }
                NutritionProfile profile = scanned.nutritionProfile != null
                        ? scanned.nutritionProfile
                        : NutritionProfile.empty(scanned.fdcId);
                scanned.nutritionAnalysis = safeAnalyze(profile, finalCohort,
                        config, cohortCategory);
                if (isEligibleAlternate(scanned)) {
                    result.alternates.add(scanned);
                }
            }
        }
        return result;
    }

    /** Analyzer call that degrades any failure to UNAVAILABLE. */
    private static NutritionAnalysis safeAnalyze(NutritionProfile profile,
                                                 PeerCohort cohort,
                                                 NutritionConfig config,
                                                 String cohortCategory) {
        try {
            return NutritionAnalyzer.analyze(profile, cohort, config);
        } catch (Exception e) {
            return new NutritionAnalysis(NutritionStatus.UNAVAILABLE, null, 0.0,
                    cohortCategory != null ? cohortCategory : "",
                    cohort != null ? cohort.peerCount : 0,
                    new LinkedHashMap<String, NutrientComparison>(),
                    new ArrayList<String>());
        }
    }

    /**
     * Cohort dedupe key for one scanned product, mirroring
     * {@code NutritionCohortService.dedupeKeyFor(JSONObject)}: the
     * GTIN/UPC when present, else normalized brand+description+serving
     * (serving from the parsed profile: numeric serving size, else the
     * normalized household serving text).
     */
    static String dedupeKeyFor(ProductResult product) {
        String gtin = product.gtinUpc != null ? product.gtinUpc.trim() : "";
        if (!gtin.isEmpty()) {
            return gtin;
        }
        NutritionProfile profile = product.nutritionProfile;
        String serving;
        if (profile != null && profile.servingSize != null) {
            serving = String.valueOf(profile.servingSize);
        } else if (profile != null) {
            serving = StringNormalizer.normalize(profile.householdServing);
        } else {
            serving = "";
        }
        return StringNormalizer.normalize(product.brand != null ? product.brand : "")
                + "|" + StringNormalizer.normalize(product.name != null ? product.name : "")
                + "|" + serving;
    }

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    /**
     * Pages through USDA search results for one query, adding every
     * scanned (non-spam, ingredient-bearing, deduped) branded product
     * to {@code scanned} — clean and ingredient-blocked alike, since
     * the scanned pool feeds the final peer cohort and nutrition
     * eligibility is only decided afterwards, against that cohort.
     * Blocked candidates additionally update the result's
     * flagged-category block statistics. Scanning stops at the pool
     * cap or the page budget, whichever comes first; it does NOT stop
     * at any number of clean candidates.
     */
    private void collectScanned(AlternateSearchResult result, List<ProductResult> scanned,
                                Set<String> seenProductKeys,
                                String foodCategory, boolean filterByCategory,
                                String query, int maxPages,
                                Set<String> enabledCategories, int poolCap,
                                NutritionConfig config) throws Exception {
        int pageNumber = 1;
        while (scanned.size() < poolCap && pageNumber <= maxPages) {
            JSONArray foods;
            try {
                foods = apiClient.fetchFoodsPage(query, foodCategory, filterByCategory,
                        pageNumber, PAGE_SIZE);
            } catch (IOException e) {
                // Retries already exhausted: keep the partial pool instead of
                // failing the whole search on one bad page.
                Log.w(TAG, "alternates page " + pageNumber + " for '" + query
                        + "' failed; using partial results", e);
                break;
            }
            if (foods == null || foods.length() == 0) {
                break;
            }
            for (int i = 0; i < foods.length(); i++) {
                JSONObject f = foods.getJSONObject(i);
                String name = f.optString("description", "");
                // Show the brand on the food label (what shoppers recognize);
                // the brand owner is only a fallback — one owner often holds
                // many unrelated brands.
                String brandName = f.optString("brandName", "");
                String brandOwner = f.optString("brandOwner", "");
                String brand = !brandName.isEmpty() ? brandName : brandOwner;
                String ingredients = f.optString("ingredients", "");

                // Fake products filed under non-food companies (USDA spam)
                // must not be recommended as clean choices.
                if (UsdaSpamFilter.isSpamBrand(f.optString("brandOwner", ""))) {
                    continue;
                }

                if (TextUtils.isEmpty(ingredients.trim())) {
                    continue;
                }

                String productKey = StringNormalizer.normalize(name)
                        + "|" + StringNormalizer.normalize(brand);
                if (seenProductKeys.contains(productKey)) {
                    continue;
                }
                seenProductKeys.add(productKey);

                FlaggedIngredientManager.MatchResult matchResult =
                        FlaggedIngredientManager.analyzeIngredients(ingredients, enabledCategories);
                if (matchResult != null && matchResult.hasMatches()) {
                    // Remember which categories blocked this candidate so the UI can
                    // explain an empty result ("everything here contains gluten…").
                    recordBlockedCandidate(result, matchResult);
                }
                // Every scanned product joins the pool with its parsed
                // profile (Contract §3/§5 step 5), blocked or clean;
                // eligibility is decided in finalizeScannedPool.
                scanned.add(buildScannedProduct(f, matchResult, config));

                if (scanned.size() >= poolCap) {
                    break;
                }
            }
            if (foods.length() < PAGE_SIZE) {
                break; // last page
            }
            pageNumber++;
        }
    }
}
