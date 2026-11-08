package com.barelabel.app.nutrition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validated, immutable nutrition configuration (Contract §1). Loaded once
 * per process by {@link NutritionConfigLoader} from
 * {@code nutrition_config.json} and passed down — no class re-reads the
 * asset.
 */
public final class NutritionConfig {

    public final int version;
    /** Overall Score blend weights; validated to sum to 1.0 (±0.001). */
    public final double ingredientWeight;
    public final double nutritionWeight;
    /** Peer cohort rules. */
    public final int minPeers;
    public final int preferredPeers;
    public final int maxCategoryPages;
    public final int roundBelowPreferredToNearest;
    /** Scoring rules: coverage gate and quality-combination weights. */
    public final double minCoverage;
    public final double relativeWeight;
    public final double absoluteWeight;
    /** Alternative-search limits. */
    public final int alternativesMaxPagesPerQuery;
    public final int alternativesPoolCap;
    /** Nutrient definitions by stable key, in config order. Unmodifiable. */
    public final Map<String, NutrientDefinition> byKey;
    /** Nutrient definitions by USDA nutrient ID. Unmodifiable. */
    public final Map<Integer, NutrientDefinition> byUsdaId;
    /** Ranking profiles by key ("dirty_concerning", ...). Unmodifiable. */
    public final Map<String, RankingProfile> rankingProfiles;

    public NutritionConfig(int version, double ingredientWeight,
                           double nutritionWeight, int minPeers,
                           int preferredPeers, int maxCategoryPages,
                           int roundBelowPreferredToNearest,
                           double minCoverage, double relativeWeight,
                           double absoluteWeight,
                           int alternativesMaxPagesPerQuery,
                           int alternativesPoolCap,
                           Map<String, NutrientDefinition> byKey,
                           Map<String, RankingProfile> rankingProfiles) {
        this.version = version;
        this.ingredientWeight = ingredientWeight;
        this.nutritionWeight = nutritionWeight;
        this.minPeers = minPeers;
        this.preferredPeers = preferredPeers;
        this.maxCategoryPages = maxCategoryPages;
        this.roundBelowPreferredToNearest = roundBelowPreferredToNearest;
        this.minCoverage = minCoverage;
        this.relativeWeight = relativeWeight;
        this.absoluteWeight = absoluteWeight;
        this.alternativesMaxPagesPerQuery = alternativesMaxPagesPerQuery;
        this.alternativesPoolCap = alternativesPoolCap;
        this.byKey = Collections.unmodifiableMap(
                new LinkedHashMap<>(byKey));
        Map<Integer, NutrientDefinition> byId = new LinkedHashMap<>();
        for (NutrientDefinition def : byKey.values()) {
            for (Integer usdaId : def.usdaIds) {
                byId.put(usdaId, def);
            }
        }
        this.byUsdaId = Collections.unmodifiableMap(byId);
        this.rankingProfiles = Collections.unmodifiableMap(
                new LinkedHashMap<>(rankingProfiles));
    }
}
