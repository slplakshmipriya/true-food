package com.barelabel.app;

import com.barelabel.app.model.ProductResult;
import com.barelabel.app.nutrition.NutrientComparison;
import com.barelabel.app.nutrition.NutrientDefinition;
import com.barelabel.app.nutrition.NutritionAnalysis;
import com.barelabel.app.nutrition.NutritionConfig;
import com.barelabel.app.nutrition.NutritionStatus;
import com.barelabel.app.nutrition.OverallScoreCalculator;
import com.barelabel.app.nutrition.RankingProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Pattern;

public class AlternateRanker {

    private static final Pattern ORGANIC_PATTERN =
            Pattern.compile("\\borganic\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_GMO_PATTERN =
            Pattern.compile("\\bnon[\\s-]*gmo\\b", Pattern.CASE_INSENSITIVE);

    /** True when at least one ingredient is labeled organic (e.g. "ORGANIC CANE SUGAR"). */
    public static boolean hasOrganicIngredient(ProductResult item) {
        return item != null && item.ingredients != null
                && ORGANIC_PATTERN.matcher(item.ingredients).find();
    }

    /** True when the product is labeled non-GMO (name or ingredients). */
    public static boolean isNonGmo(ProductResult item) {
        if (item == null) return false;
        String text = (item.name == null ? "" : item.name) + " "
                + (item.ingredients == null ? "" : item.ingredients);
        return NON_GMO_PATTERN.matcher(text).find();
    }

    public static class RankedProduct {
        public final ProductResult product;
        public final int rank;
        public final int ingredientCount;
        public final int superiorCount;
        public final boolean hasOrganic;
        public final boolean nonGmo;
        private final boolean preferOrganic;
        /** Weighted rank score (Contract §6); 0 for legacy rankings. */
        public final double rankScore;
        /** Blended Overall Score of the alternative; null for legacy. */
        public final Integer overallScore;
        /** The alternative's Nutrition Score; null for legacy. */
        public final Integer nutritionScore;
        /** Deterministic reasons from AlternativeExplainer; empty for legacy. */
        public final List<String> reasons;
        /** Per-nutrient deltas from AlternativeExplainer; empty for legacy. */
        public final List<NutrientDelta> deltas;

        public RankedProduct(ProductResult product, int rank, int ingredientCount,
                             int superiorCount, boolean hasOrganic, boolean nonGmo,
                             boolean preferOrganic) {
            this(product, rank, ingredientCount, superiorCount, hasOrganic,
                    nonGmo, preferOrganic, 0.0, null, null,
                    Collections.<String>emptyList(),
                    Collections.<NutrientDelta>emptyList());
        }

        public RankedProduct(ProductResult product, int rank, int ingredientCount,
                             int superiorCount, boolean hasOrganic, boolean nonGmo,
                             boolean preferOrganic, double rankScore,
                             Integer overallScore, Integer nutritionScore,
                             List<String> reasons, List<NutrientDelta> deltas) {
            this.product = product;
            this.rank = rank;
            this.ingredientCount = ingredientCount;
            this.superiorCount = superiorCount;
            this.hasOrganic = hasOrganic;
            this.nonGmo = nonGmo;
            this.preferOrganic = preferOrganic;
            this.rankScore = rankScore;
            this.overallScore = overallScore;
            this.nutritionScore = nutritionScore;
            this.reasons = reasons != null
                    ? Collections.unmodifiableList(new ArrayList<>(reasons))
                    : Collections.<String>emptyList();
            this.deltas = deltas != null
                    ? Collections.unmodifiableList(new ArrayList<>(deltas))
                    : Collections.<NutrientDelta>emptyList();
        }

        // 3 Stars for superior ingredients normally; in prefer-organic mode the
        // stars track the organic tiers instead (organic > non-GMO > rest).
        public String getStarRating() {
            if (preferOrganic) {
                if (hasOrganic) {
                    return "★★★";
                } else if (nonGmo) {
                    return "★★☆";
                } else {
                    return "★☆☆";
                }
            }
            if (superiorCount > 0) {
                return "★★★";
            } else if (rank <= 2) {
                return "★★☆";
            } else {
                return "★☆☆";
            }
        }

        /** Short tag explaining the organic tier, shown when prefer-organic is on. */
        public String getOrganicTag() {
            if (hasOrganic) return " • Organic";
            if (nonGmo) return " • Non-GMO";
            return "";
        }
    }

    /**
     * Filters out flagged products (Rank = Infinity) and ranks clean products
     * using ingredient count and superior ingredient bonuses.
     */
    public static List<RankedProduct> rankAndFilter(
            List<ProductResult> rawAlternates,
            Set<String> superiorTerms) {
        return rankAndFilter(rawAlternates, superiorTerms, false);
    }

    /** All ranking inputs for one product, computed exactly once (not per comparison). */
    private static final class Scored {
        final ProductResult item;
        final int ingredientCount;
        final int superiorCount;
        final boolean hasOrganic;
        final boolean nonGmo;

        Scored(ProductResult item, int ingredientCount, int superiorCount,
               boolean hasOrganic, boolean nonGmo) {
            this.item = item;
            this.ingredientCount = ingredientCount;
            this.superiorCount = superiorCount;
            this.hasOrganic = hasOrganic;
            this.nonGmo = nonGmo;
        }
    }

    /**
     * Same as above, but with prefer-organic on the ranking becomes:
     * items with an organic ingredient first (3 stars), then non-GMO items,
     * then the rest — with superior count and ingredient count as tiebreakers.
     */
    public static List<RankedProduct> rankAndFilter(
            List<ProductResult> rawAlternates,
            Set<String> superiorTerms,
            boolean preferOrganic) {

        // Pre-lowercase the superior terms once — the old code lowercased
        // every term inside every countSuperiorTerms call (per comparison).
        List<String> lowerSuperior = new ArrayList<>();
        if (superiorTerms != null) {
            for (String term : superiorTerms) {
                if (term != null) lowerSuperior.add(term.toLowerCase(Locale.US));
            }
        }

        // 1. Filter out flagged items and precompute every score once.
        // The old code recomputed these inside the sort comparator
        // (O(n log n) regex + substring scans) and then again below.
        List<Scored> scored = new ArrayList<>();
        for (ProductResult item : rawAlternates) {
            if (item.flagged != null && !item.flagged.isEmpty()) {
                continue;
            }
            String lowerText = ((item.name == null ? "" : item.name) + " "
                    + (item.ingredients == null ? "" : item.ingredients)).toLowerCase(Locale.US);
            int supCount = 0;
            for (String term : lowerSuperior) {
                if (lowerText.contains(term)) supCount++;
            }
            // hasOrganicIngredient historically matches ingredients only —
            // keep that exact semantic.
            String ingredientsText = item.ingredients == null ? "" : item.ingredients;
            scored.add(new Scored(item, countIngredients(item), supCount,
                    ORGANIC_PATTERN.matcher(ingredientsText).find(),
                    NON_GMO_PATTERN.matcher(lowerText).find()));
        }

        if (scored.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. Sort on the precomputed scores — the comparator does no
        // string work at all now.
        Comparator<Scored> bySuperiorThenCount = (a, b) -> {
            if (a.superiorCount != b.superiorCount) {
                return Integer.compare(b.superiorCount, a.superiorCount);
            }
            return Integer.compare(a.ingredientCount, b.ingredientCount);
        };
        if (preferOrganic) {
            Collections.sort(scored, (a, b) -> {
                int orgA = a.hasOrganic ? 0 : 1;
                int orgB = b.hasOrganic ? 0 : 1;
                if (orgA != orgB) {
                    return Integer.compare(orgA, orgB); // organic first
                }
                int gmoA = a.nonGmo ? 0 : 1;
                int gmoB = b.nonGmo ? 0 : 1;
                if (gmoA != gmoB) {
                    return Integer.compare(gmoA, gmoB); // non-GMO next
                }
                return bySuperiorThenCount.compare(a, b);
            });
        } else {
            Collections.sort(scored, bySuperiorThenCount);
        }

        // 3. Assign Ranks
        List<RankedProduct> rankedList = new ArrayList<>();
        int currentRank = 1;

        for (int i = 0; i < scored.size(); i++) {
            Scored s = scored.get(i);

            if (i > 0) {
                Scored prev = scored.get(i - 1);
                if (s.superiorCount < prev.superiorCount
                        || s.ingredientCount > prev.ingredientCount) {
                    currentRank++;
                }
            }

            rankedList.add(new RankedProduct(s.item, currentRank, s.ingredientCount,
                    s.superiorCount, s.hasOrganic, s.nonGmo, preferOrganic));
        }

        return rankedList;
    }

    // ------------------------------------------------------------------
    // Nutrition-aware ranking (Contract §6, PRD §9.3/§9.4)
    // ------------------------------------------------------------------

    /**
     * The ranking-profile key for a candidate state (Contract §6):
     * DIRTY ∧ CONCERNING → "dirty_concerning"; DIRTY ∧ (SAFE |
     * UNAVAILABLE | no analysis) → "dirty_only"; CLEAN candidates →
     * "nutrition_only" (the only profile defined for them; CLEAN ∧
     * CONCERNING is its intended case). Package-private for tests.
     */
    static String profileFor(ProductResult candidate,
                             NutritionAnalysis candidateAnalysis) {
        boolean dirty = candidate != null && candidate.flagged != null
                && !candidate.flagged.isEmpty();
        NutritionStatus status = candidateAnalysis != null
                ? candidateAnalysis.status : NutritionStatus.UNAVAILABLE;
        if (dirty) {
            return status == NutritionStatus.CONCERNING
                    ? "dirty_concerning" : "dirty_only";
        }
        return "nutrition_only";
    }

    /**
     * Ingredient advantage (0–100): the share of the candidate's
     * flagged ingredient items absent from the alternative, with
     * flagged categories weighted equally (each category contributes
     * its absent share, averaged over categories). An item is absent
     * when it is in neither the alternative's flagged set nor its
     * ingredient text (see AlternativeExplainer.ingredientPresent).
     * 100 when the candidate has no flagged items. When the candidate
     * carries no category breakdown, all flagged items form one
     * category. Package-private for tests.
     */
    static double ingredientAdvantage(ProductResult candidate, ProductResult alt) {
        if (candidate == null || candidate.flagged == null
                || candidate.flagged.isEmpty()) {
            return 100.0;
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        if (candidate.matchResult != null
                && candidate.matchResult.categoryMap != null) {
            for (Map.Entry<String, List<String>> entry
                    : candidate.matchResult.categoryMap.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                List<String> items = new ArrayList<>();
                for (String item : entry.getValue()) {
                    if (item != null && candidate.flagged.contains(item)) {
                        items.add(item);
                    }
                }
                if (!items.isEmpty()) {
                    groups.put(entry.getKey(), items);
                }
            }
        }
        if (groups.isEmpty()) {
            groups.put("", new ArrayList<>(candidate.flagged));
        }
        double sum = 0.0;
        for (List<String> items : groups.values()) {
            int absent = 0;
            for (String item : items) {
                if (!AlternativeExplainer.ingredientPresent(item, alt)) {
                    absent++;
                }
            }
            sum += 100.0 * absent / items.size();
        }
        return sum / groups.size();
    }

    /**
     * Focused nutrition (0–100): weighted mean over the candidate's
     * flagged nutrients of clamp(50 + (altQuality − candidateQuality),
     * 0, 100), weighted by configured nutrient weight. Combined
     * quality is used when both sides have it; otherwise the per-100
     * direction-aware gain falls back to absoluteQuality differences.
     * A nutrient with no usable value pair (in particular one the
     * alternative does not report) is skipped and the weights
     * renormalize — no 50-neutral credit. 0 when no flagged nutrient
     * is usable. Package-private for tests.
     */
    static double focusedNutrition(NutritionAnalysis candidateAnalysis,
                                   NutritionAnalysis altAnalysis,
                                   NutritionConfig config) {
        if (candidateAnalysis == null || altAnalysis == null || config == null
                || candidateAnalysis.flaggedNutrients.isEmpty()) {
            return 0.0;
        }
        double weightedSum = 0.0;
        double weightTotal = 0.0;
        for (String key : candidateAnalysis.flaggedNutrients) {
            NutrientDefinition def = config.byKey.get(key);
            if (def == null || def.weight <= 0) {
                continue;
            }
            NutrientComparison candC = candidateAnalysis.comparisons.get(key);
            NutrientComparison altC = altAnalysis.comparisons.get(key);
            if (candC == null || altC == null) {
                continue;
            }
            Double gain = null;
            if (candC.quality != null && altC.quality != null) {
                gain = clampFocused(50.0 + (altC.quality - candC.quality));
            } else if (candC.absoluteQuality != null
                    && altC.absoluteQuality != null) {
                gain = clampFocused(
                        50.0 + (altC.absoluteQuality - candC.absoluteQuality));
            }
            if (gain == null) {
                continue;
            }
            weightedSum += def.weight * gain;
            weightTotal += def.weight;
        }
        return weightTotal > 0.0 ? weightedSum / weightTotal : 0.0;
    }

    private static double clampFocused(double gain) {
        if (gain < 0.0) {
            return 0.0;
        }
        if (gain > 100.0) {
            return 100.0;
        }
        return gain;
    }

    /**
     * Preferences component (0–100): superior-ingredient count scaled
     * (≥ 3 → 100, else count × 33), plus a +10 bonus when the
     * alternative's ingredient list is strictly shorter than the
     * candidate's, capped at 100. Organic/non-GMO are sort tiers, not
     * part of this component. Package-private for tests.
     */
    static double preferencesScore(ProductResult candidate, ProductResult alt,
                                   Set<String> superiorTerms) {
        int superior = countSuperiorTermsNullSafe(alt, superiorTerms);
        double score = superior >= 3 ? 100.0 : superior * 33.0;
        if (candidate != null
                && countIngredients(alt) < countIngredients(candidate)) {
            score += 10.0;
        }
        return Math.min(100.0, score);
    }

    /** Null-safe superior-term count over name + ingredients. */
    private static int countSuperiorTermsNullSafe(ProductResult item,
                                                  Set<String> superiorTerms) {
        if (item == null || superiorTerms == null || superiorTerms.isEmpty()) {
            return 0;
        }
        String text = ((item.name == null ? "" : item.name) + " "
                + (item.ingredients == null ? "" : item.ingredients))
                .toLowerCase(Locale.US);
        int count = 0;
        for (String term : superiorTerms) {
            if (term != null && text.contains(term.toLowerCase(Locale.US))) {
                count++;
            }
        }
        return count;
    }

    /** Flagged-category count for an alternative, mirroring MainActivity. */
    private static int flaggedCategoryCount(ProductResult item) {
        return item != null && item.matchResult != null
                && item.matchResult.categoryMap != null
                ? item.matchResult.categoryMap.size() : 0;
    }

    /** All ranking inputs for one eligible alternative, computed once. */
    private static final class NutritionScored {
        final ProductResult item;
        final int index;
        final int ingredientCount;
        final int superiorCount;
        final boolean hasOrganic;
        final boolean nonGmo;
        final double rankScore;
        final Integer overallScore;
        final Integer nutritionScore;
        final List<String> reasons;
        final List<NutrientDelta> deltas;

        NutritionScored(ProductResult item, int index, int ingredientCount,
                        int superiorCount, boolean hasOrganic, boolean nonGmo,
                        double rankScore, Integer overallScore,
                        Integer nutritionScore, List<String> reasons,
                        List<NutrientDelta> deltas) {
            this.item = item;
            this.index = index;
            this.ingredientCount = ingredientCount;
            this.superiorCount = superiorCount;
            this.hasOrganic = hasOrganic;
            this.nonGmo = nonGmo;
            this.rankScore = rankScore;
            this.overallScore = overallScore;
            this.nutritionScore = nutritionScore;
            this.reasons = reasons;
            this.deltas = deltas;
        }
    }

    private static int organicTier(NutritionScored s) {
        if (s.hasOrganic) {
            return 0;
        }
        return s.nonGmo ? 1 : 2;
    }

    /**
     * Uniform nutrition-aware ranking (Contract §6). Every item in
     * {@code eligible} is expected to be ingredient CLEAN with a SAFE
     * nutrition analysis; items violating that are defensively
     * dropped. Components and profile weights come from the config's
     * ranking profiles; each alternative's Overall Score is
     * {@link OverallScoreCalculator#blend} of its Clean Score
     * (mirroring MainActivity's computation) and its Nutrition Score.
     *
     * <p>Sort: prefer-organic tier (organic → non-GMO → rest) when
     * enabled, then rank score desc, Overall Score desc, ingredient
     * count asc, original order asc (stable). Ranks are sequential
     * positions. Deterministic: no randomness, no LLM.
     *
     * <p>Defensive fallbacks (outside the contract's normal flow): a
     * null config delegates to the legacy {@link #rankAndFilter}; a
     * profile key missing from the config uses equal component
     * weights.
     */
    public static List<RankedProduct> rankNutritionAware(
            ProductResult candidate, NutritionAnalysis candidateAnalysis,
            List<ProductResult> eligible, Set<String> superiorTerms,
            boolean preferOrganic, NutritionConfig config) {
        List<RankedProduct> out = new ArrayList<>();
        if (eligible == null || eligible.isEmpty()) {
            return out;
        }
        if (config == null) {
            return rankAndFilter(eligible, superiorTerms, preferOrganic);
        }
        RankingProfile profile = config.rankingProfiles.get(
                profileFor(candidate, candidateAnalysis));
        if (profile == null) {
            profile = new RankingProfile(0.25, 0.25, 0.25, 0.25);
        }

        List<NutritionScored> scored = new ArrayList<>();
        for (int i = 0; i < eligible.size(); i++) {
            ProductResult alt = eligible.get(i);
            if (alt == null
                    || (alt.flagged != null && !alt.flagged.isEmpty())) {
                continue;
            }
            NutritionAnalysis altAnalysis = alt.nutritionAnalysis;
            if (altAnalysis == null
                    || altAnalysis.status != NutritionStatus.SAFE) {
                continue;
            }
            double rankScore = profile.ingredientAdvantage
                        * ingredientAdvantage(candidate, alt)
                    + profile.focusedNutrition
                        * focusedNutrition(candidateAnalysis, altAnalysis, config)
                    + profile.overallNutrition
                        * (altAnalysis.nutritionScore != null
                                ? altAnalysis.nutritionScore : 0)
                    + profile.preferences
                        * preferencesScore(candidate, alt, superiorTerms);
            int ingredientScore = CleanScoreCalculator.score(true,
                    alt.flagged == null ? 0 : alt.flagged.size(),
                    flaggedCategoryCount(alt));
            Integer overallScore = OverallScoreCalculator.blend(
                    ingredientScore, altAnalysis.nutritionScore, config);
            scored.add(new NutritionScored(alt, i, countIngredients(alt),
                    countSuperiorTermsNullSafe(alt, superiorTerms),
                    hasOrganicIngredient(alt), isNonGmo(alt),
                    rankScore, overallScore, altAnalysis.nutritionScore,
                    AlternativeExplainer.reasons(candidate, candidateAnalysis,
                            alt, altAnalysis, config),
                    AlternativeExplainer.deltas(candidate, candidateAnalysis,
                            alt, altAnalysis, config)));
        }

        Collections.sort(scored, (a, b) -> {
            if (preferOrganic) {
                int byTier = Integer.compare(organicTier(a), organicTier(b));
                if (byTier != 0) {
                    return byTier;
                }
            }
            int byScore = Double.compare(b.rankScore, a.rankScore);
            if (byScore != 0) {
                return byScore;
            }
            int overallA = a.overallScore != null
                    ? a.overallScore : Integer.MIN_VALUE;
            int overallB = b.overallScore != null
                    ? b.overallScore : Integer.MIN_VALUE;
            int byOverall = Integer.compare(overallB, overallA);
            if (byOverall != 0) {
                return byOverall;
            }
            int byCount = Integer.compare(a.ingredientCount, b.ingredientCount);
            if (byCount != 0) {
                return byCount;
            }
            return Integer.compare(a.index, b.index);
        });

        for (int i = 0; i < scored.size(); i++) {
            NutritionScored s = scored.get(i);
            out.add(new RankedProduct(s.item, i + 1, s.ingredientCount,
                    s.superiorCount, s.hasOrganic, s.nonGmo, preferOrganic,
                    s.rankScore, s.overallScore, s.nutritionScore,
                    s.reasons, s.deltas));
        }
        return out;
    }

    public static int countIngredients(ProductResult item) {
        if (item.ingredients == null || item.ingredients.trim().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        return item.ingredients.split(",").length;
    }

    public static int countSuperiorTerms(ProductResult item, Set<String> superiorTerms) {
        if (superiorTerms == null || superiorTerms.isEmpty()) return 0;
        String text = (item.name + " " + item.ingredients).toLowerCase(Locale.US);
        int count = 0;
        for (String term : superiorTerms) {
            if (text.contains(term.toLowerCase(Locale.US))) {
                count++;
            }
        }
        return count;
    }
}
