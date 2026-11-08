package com.barelabel.app.nutrition;

import com.barelabel.app.model.NutritionProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A candidate product's peer cohort (Contract §4, PRD §6): one
 * {@link PeerDistribution} per configured nutrient, built locally from
 * USDA products the caller already fetched.
 *
 * <p>Construction rules, applied in this order:
 * <ol>
 *   <li>The candidate itself is excluded (FDC ID match when the
 *       candidate's fdcId &gt; 0, plus reference identity — the same
 *       profile instance in the pool is always the candidate).</li>
 *   <li>Category must equal the candidate category (case-insensitive,
 *       trimmed) and the per-100 basis must equal the candidate's —
 *       gram and millilitre cohorts are never mixed (PRD §6.3).</li>
 *   <li>The surviving pool is deduplicated, first occurrence wins: by
 *       FDC ID (only when &gt; 0; 0 means unknown and never dedupes),
 *       then by the {@link CohortPeer#dedupeKey} the caller precomputed
 *       (GTIN/UPC, else normalized brand+description+serving). Only
 *       entries that pass the checks above register a dedupe identity,
 *       so an ineligible entry never blocks a later eligible twin with
 *       the same FDC ID or key.</li>
 *   <li>Per nutrient, reported values outside the definition's
 *       plausible range are dropped, then a median/MAD fence removes
 *       values beyond median ± 3.5 × 1.4826 × MAD. When MAD is 0 the
 *       fence is disabled and every plausible value is kept.</li>
 * </ol>
 *
 * <p>Spam-brand filtering and the category-fallback choice (the
 * candidate's own category first, then the query majority category) are
 * the caller's responsibility (NUT-05, Contract §4/§5): the cohort is
 * built from profiles already screened for spam, against the single
 * category the caller resolved, and {@link #category} records the
 * category actually used.
 *
 * <p>Deterministic: distributions hold sorted values and the map keeps
 * config order, so identical inputs produce an identical cohort.
 * Immutable.
 */
public final class PeerCohort {

    /** Cohort category actually used (the resolved candidate category). */
    public final String category;
    /** Per-100 basis shared by candidate and peers: "g" or "ml". */
    public final String basis;
    /** Nutrient key -> peer distribution, in config order. Unmodifiable;
     *  every configured nutrient is present (empty when no peer reported
     *  a usable value — missing is never a 0.0 peer value). */
    public final Map<String, PeerDistribution> byNutrient;
    /** Distinct products that survived exclusion, dedupe, category and
     *  basis filtering (before per-nutrient value filtering). */
    public final int peerCount;

    public PeerCohort(String category, String basis,
                      Map<String, PeerDistribution> byNutrient,
                      int peerCount) {
        this.category = category;
        this.basis = basis;
        this.byNutrient = Collections.unmodifiableMap(
                new LinkedHashMap<>(byNutrient));
        this.peerCount = peerCount;
    }

    /**
     * Builds the cohort for {@code candidate} against the category the
     * caller resolved for it. Never throws on null/empty inputs: a null
     * candidate behaves as an unknown gram-basis product, a null pool as
     * an empty pool, and a null config yields a cohort with no nutrient
     * distributions (no plausible ranges exist to filter against).
     */
    public static PeerCohort build(NutritionProfile candidate,
                                   String candidateCategory,
                                   List<CohortPeer> pool,
                                   NutritionConfig config) {
        long candidateFdcId = candidate != null ? candidate.fdcId : 0L;
        String candidateBasis = candidate != null ? candidate.basis : "g";
        String wantedCategory = normalizeCategory(candidateCategory);

        List<NutritionProfile> survivors = new ArrayList<>();
        if (pool != null) {
            Set<Long> seenFdcIds = new HashSet<>();
            Set<String> seenKeys = new HashSet<>();
            for (CohortPeer peer : pool) {
                if (peer == null || peer.profile == null) {
                    continue;
                }
                NutritionProfile profile = peer.profile;
                // (1) Candidate exclusion.
                if (profile == candidate) {
                    continue;
                }
                if (candidateFdcId > 0 && profile.fdcId == candidateFdcId) {
                    continue;
                }
                // (2) Category and basis must match the candidate's.
                if (!normalizeCategory(peer.category).equals(wantedCategory)) {
                    continue;
                }
                if (!candidateBasis.equals(profile.basis)) {
                    continue;
                }
                // (3) Dedupe, first surviving occurrence wins: FDC ID,
                // then key. Registration happens only here, after the
                // eligibility checks, so an entry dropped above never
                // consumes an identity.
                if (profile.fdcId > 0 && seenFdcIds.contains(profile.fdcId)) {
                    continue;
                }
                String key = peer.dedupeKey;
                boolean hasKey = key != null && !key.isEmpty();
                if (hasKey && seenKeys.contains(key)) {
                    continue;
                }
                if (profile.fdcId > 0) {
                    seenFdcIds.add(profile.fdcId);
                }
                if (hasKey) {
                    seenKeys.add(key);
                }
                survivors.add(profile);
            }
        }

        // (4) Per-nutrient plausible range, then median/MAD fence.
        Map<String, PeerDistribution> byNutrient = new LinkedHashMap<>();
        if (config != null) {
            for (NutrientDefinition def : config.byKey.values()) {
                List<Double> plausible = new ArrayList<>();
                for (NutritionProfile profile : survivors) {
                    Double v = profile.per100.get(def.key);
                    if (v == null || !Double.isFinite(v)) {
                        continue;
                    }
                    if (v < def.plausibleMin || v > def.plausibleMax) {
                        continue;
                    }
                    plausible.add(v);
                }
                byNutrient.put(def.key,
                        new PeerDistribution(applyMadFence(plausible)));
            }
        }
        return new PeerCohort(candidateCategory, candidateBasis,
                byNutrient, survivors.size());
    }

    /**
     * Median/MAD fence (PRD §6.4): drops values beyond
     * median ± 3.5 × 1.4826 × MAD. MAD == 0 disables the fence (all
     * values are kept) — with most peers tied at the median there is no
     * spread estimate to fence against.
     */
    private static List<Double> applyMadFence(List<Double> values) {
        if (values.size() < 2) {
            return values;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double median = PeerDistribution.medianOfSorted(sorted);
        List<Double> deviations = new ArrayList<>(sorted.size());
        for (Double v : sorted) {
            deviations.add(Math.abs(v - median));
        }
        Collections.sort(deviations);
        double mad = PeerDistribution.medianOfSorted(deviations);
        if (mad == 0.0) {
            return values;
        }
        double halfWidth = 3.5 * 1.4826 * mad;
        double low = median - halfWidth;
        double high = median + halfWidth;
        List<Double> kept = new ArrayList<>();
        for (Double v : values) {
            if (v >= low && v <= high) {
                kept.add(v);
            }
        }
        return kept;
    }

    /** Category comparison key: trimmed, lower-cased, null as "". */
    private static String normalizeCategory(String category) {
        return category == null
                ? ""
                : category.trim().toLowerCase(Locale.US);
    }
}
