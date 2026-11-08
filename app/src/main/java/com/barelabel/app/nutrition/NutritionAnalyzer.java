package com.barelabel.app.nutrition;

import com.barelabel.app.model.NutritionProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic nutrition analysis of one product against its peer
 * cohort (Contract §4, §4.1, §4.2; PRD §7, §8). Pure and static: all
 * thresholds, weights, and anchors come from the supplied
 * {@link NutritionConfig} — tuning happens in the JSON asset, not here.
 *
 * <p>Per displayed nutrient, in config order, the analyzer builds a
 * {@link NutrientComparison}:
 * <ul>
 *   <li>Amounts come from the candidate's profile; an unreported
 *       nutrient keeps null amounts (missing is never zero).</li>
 *   <li>Peer median is exposed whenever the distribution is non-empty.
 *       Raw/favorable percentiles and the combined quality require a
 *       peer count of at least {@code minPeers}.</li>
 *   <li>The stored percentiles are the DISPLAY values: below the
 *       preferred peer count they are rounded to the nearest
 *       {@code roundBelowPreferredToNearest} (Contract §4.1). Scoring
 *       (quality, group scores, flags) always uses the unrounded
 *       values — the comparison is what the UI/LLM consume, so the
 *       rounding lives there and only there.</li>
 *   <li>{@code PeerDistribution.rawPercentile} is used as-is: its
 *       half-credit tie formula already yields the neutral 50 for
 *       all-equal peers, so no special case is applied.</li>
 *   <li>Absolute quality is interpolated between the definition's
 *       {@code goodAt} (100) and {@code badAt} (0) anchors and clamped
 *       to 0–100; it is present whenever the nutrient was reported,
 *       even when no percentile is valid.</li>
 *   <li>Flags evaluate in contract order — severe, then
 *       relative+floor (higher_bad) or low (higher_good) — and only
 *       for flag-eligible nutrients. A severe flag needs no peers.</li>
 * </ul>
 *
 * <p>Scoring groups contribute once each (Contract §1.2): the sugars
 * group's quality is {@code added_sugars'} when it has one, else
 * {@code total_sugars'}; flagging stays per-nutrient. Coverage is the
 * available group weight over the total group weight, weights
 * renormalize over groups that have a quality, and the Nutrition
 * Score is null below {@code minCoverage}.
 *
 * <p>Status (PRD §7.3): any flag ⇒ CONCERNING (a severe flag therefore
 * makes a product CONCERNING even with inadequate peers). Otherwise
 * SAFE requires coverage &gt;= {@code minCoverage} and an adequate
 * cohort — at least {@code minPeers} products; anything else is
 * UNAVAILABLE.
 *
 * <p>Never throws on null inputs: a null candidate is an empty
 * profile, a null cohort an empty cohort, and a null config yields an
 * UNAVAILABLE analysis with no comparisons.
 */
public final class NutritionAnalyzer {

    private NutritionAnalyzer() {
    }

    public static NutritionAnalysis analyze(NutritionProfile candidate,
                                            PeerCohort cohort,
                                            NutritionConfig config) {
        String cohortCategory = cohort != null && cohort.category != null
                ? cohort.category : "";
        int cohortPeerCount = cohort != null ? cohort.peerCount : 0;
        if (config == null) {
            return new NutritionAnalysis(NutritionStatus.UNAVAILABLE, null,
                    0.0, cohortCategory, cohortPeerCount,
                    new LinkedHashMap<String, NutrientComparison>(),
                    new ArrayList<String>());
        }
        NutritionProfile profile = candidate != null
                ? candidate : NutritionProfile.empty(0L);

        Map<String, NutrientComparison> comparisons = new LinkedHashMap<>();
        List<String> flaggedNutrients = new ArrayList<>();
        for (NutrientDefinition def : config.byKey.values()) {
            if (!def.display) {
                continue;
            }
            NutrientComparison comparison = compare(profile, def, cohort, config);
            comparisons.put(def.key, comparison);
            if (comparison.flagged) {
                flaggedNutrients.add(def.key);
            }
        }

        // Scoring groups (score-eligible definitions, config order).
        Map<String, List<NutrientDefinition>> groups = new LinkedHashMap<>();
        for (NutrientDefinition def : config.byKey.values()) {
            if (!def.scoreEligible) {
                continue;
            }
            List<NutrientDefinition> members = groups.get(def.scoringGroup);
            if (members == null) {
                members = new ArrayList<>();
                groups.put(def.scoringGroup, members);
            }
            members.add(def);
        }
        double totalGroupWeight = 0.0;
        double availableGroupWeight = 0.0;
        double weightedQualitySum = 0.0;
        for (List<NutrientDefinition> members : groups.values()) {
            double groupWeight = groupWeight(members);
            totalGroupWeight += groupWeight;
            Double groupQuality = groupQuality(members, comparisons);
            if (groupQuality != null) {
                availableGroupWeight += groupWeight;
                weightedQualitySum += groupWeight * groupQuality;
            }
        }
        double coverage = totalGroupWeight > 0.0
                ? availableGroupWeight / totalGroupWeight : 0.0;
        Integer nutritionScore = null;
        if (coverage >= config.minCoverage && availableGroupWeight > 0.0) {
            nutritionScore = (int) Math.round(
                    weightedQualitySum / availableGroupWeight);
        }

        NutritionStatus status;
        if (!flaggedNutrients.isEmpty()) {
            status = NutritionStatus.CONCERNING;
        } else if (coverage >= config.minCoverage
                && cohortPeerCount >= config.minPeers) {
            status = NutritionStatus.SAFE;
        } else {
            status = NutritionStatus.UNAVAILABLE;
        }
        return new NutritionAnalysis(status, nutritionScore, coverage,
                cohortCategory, cohortPeerCount, comparisons, flaggedNutrients);
    }

    // ------------------------------------------------------------------
    // Per-nutrient comparison
    // ------------------------------------------------------------------

    private static NutrientComparison compare(NutritionProfile profile,
                                              NutrientDefinition def,
                                              PeerCohort cohort,
                                              NutritionConfig config) {
        Double reported = profile.per100.get(def.key);
        Double per100 = reported != null && Double.isFinite(reported)
                ? reported : null;
        Double perServing = per100 != null ? profile.perServing(def.key) : null;

        PeerDistribution distribution = cohort != null
                ? cohort.byNutrient.get(def.key) : null;
        int peerCount = distribution != null ? distribution.count() : 0;
        Double peerMedian = peerCount > 0 ? distribution.median : null;

        Double absoluteQuality = per100 != null
                ? absoluteQuality(def, per100) : null;

        // Percentiles (and therefore quality and percentile-based flags)
        // require a reported value and at least minPeers valid peers.
        Double rawUnrounded = null;
        Double favorableUnrounded = null;
        Double quality = null;
        if (per100 != null && peerCount >= config.minPeers) {
            double raw = distribution.rawPercentile(per100);
            double favorable = "higher_bad".equals(def.direction)
                    ? 100.0 - raw : raw;
            rawUnrounded = raw;
            favorableUnrounded = favorable;
            quality = config.relativeWeight * favorable
                    + config.absoluteWeight * absoluteQuality;
        }
        Double rawDisplay = rawUnrounded != null
                ? displayPercentile(rawUnrounded, peerCount, config) : null;
        Double favorableDisplay = favorableUnrounded != null
                ? displayPercentile(favorableUnrounded, peerCount, config) : null;

        String flagReason = flagReason(def, per100, rawUnrounded);
        return new NutrientComparison(def.key, per100, perServing, peerCount,
                peerMedian, rawDisplay, favorableDisplay, absoluteQuality,
                quality, !flagReason.isEmpty(), flagReason);
    }

    /**
     * Absolute quality (Contract §4.1): linear interpolation with
     * {@code goodAt} scoring 100 and {@code badAt} scoring 0, clamped
     * to 0–100. The single formula is direction-aware because the
     * anchors themselves encode the direction (for higher_good,
     * goodAt &gt; badAt).
     */
    static double absoluteQuality(NutrientDefinition def, double value) {
        double quality = 100.0 * (value - def.badAt) / (def.goodAt - def.badAt);
        if (quality < 0.0) {
            return 0.0;
        }
        if (quality > 100.0) {
            return 100.0;
        }
        return quality;
    }

    /** Display rounding (Contract §4.1): nearest configured step below
     *  the preferred peer count; unrounded at or above it. */
    private static double displayPercentile(double percentile, int peerCount,
                                            NutritionConfig config) {
        if (peerCount >= config.preferredPeers
                || config.roundBelowPreferredToNearest <= 0) {
            return percentile;
        }
        double step = config.roundBelowPreferredToNearest;
        double rounded = Math.round(percentile / step) * step;
        if (rounded < 0.0) {
            return 0.0;
        }
        if (rounded > 100.0) {
            return 100.0;
        }
        return rounded;
    }

    /**
     * Flag evaluation (Contract §4.2), in order: severe first (no peers
     * needed), then relative+floor for higher_bad, then the low rule
     * for higher_good. Percentile-based rules use the unrounded raw
     * percentile and require it to be valid. Returns "" when unflagged.
     */
    private static String flagReason(NutrientDefinition def, Double per100,
                                     Double rawUnrounded) {
        if (!def.flagEligible || per100 == null) {
            return "";
        }
        if ("higher_bad".equals(def.direction)) {
            if (def.severeAt != null && per100 >= def.severeAt) {
                return "severe";
            }
            if (def.flagPercentile != null && def.flagFloor != null
                    && rawUnrounded != null
                    && rawUnrounded >= def.flagPercentile
                    && per100 >= def.flagFloor) {
                return "relative_floor";
            }
            return "";
        }
        if ("higher_good".equals(def.direction)) {
            if (def.lowFlagPercentile != null && def.lowFlagAtOrBelow != null
                    && rawUnrounded != null
                    && rawUnrounded <= def.lowFlagPercentile
                    && per100 <= def.lowFlagAtOrBelow) {
                return "low";
            }
            return "";
        }
        return "";
    }

    // ------------------------------------------------------------------
    // Scoring groups
    // ------------------------------------------------------------------

    /**
     * The quality a scoring group contributes: the quality of the
     * member carrying a {@code fallbackKey} (added_sugars for the
     * sugars group) when it has one, else the first member in config
     * order that has a quality (total_sugars as the fallback), else
     * null — the group then contributes nothing.
     */
    private static Double groupQuality(List<NutrientDefinition> members,
                                       Map<String, NutrientComparison> comparisons) {
        for (NutrientDefinition def : members) {
            if (def.fallbackKey != null) {
                Double quality = qualityOf(def, comparisons);
                if (quality != null) {
                    return quality;
                }
            }
        }
        for (NutrientDefinition def : members) {
            Double quality = qualityOf(def, comparisons);
            if (quality != null) {
                return quality;
            }
        }
        return null;
    }

    private static Double qualityOf(NutrientDefinition def,
                                    Map<String, NutrientComparison> comparisons) {
        NutrientComparison comparison = comparisons.get(def.key);
        return comparison != null ? comparison.quality : null;
    }

    /**
     * A group's weight: the fallback-carrying member's weight when the
     * group has one (added_sugars, 0.16 — equal to total_sugars' in v1,
     * so the group totals 0.16 either way), else the first member's.
     */
    private static double groupWeight(List<NutrientDefinition> members) {
        for (NutrientDefinition def : members) {
            if (def.fallbackKey != null) {
                return def.weight;
            }
        }
        return members.get(0).weight;
    }
}
