package com.barelabel.app;

/**
 * One displayed nutrient's candidate-versus-alternative difference
 * (Contract §6): the per-100 amounts on both sides, each side's
 * favorable percentile (for the paired bar fills; a null hides that
 * side's bar only — the other side's bar, and both sides' labels and
 * values, still show), and a deterministic difference badge produced
 * by {@link AlternativeExplainer} (e.g. "61% less", "+2.7 g").
 * Immutable.
 */
public final class NutrientDelta {

    /** Stable nutrient key, e.g. "sodium". */
    public final String key;
    /** Display label from the nutrient definition, e.g. "Sodium". */
    public final String label;
    /** Definition unit: "g", "mg", or "kcal". */
    public final String unit;
    /** Candidate amount per 100 basis units. */
    public final double candidatePer100;
    /** Alternative amount per 100 basis units. */
    public final double alternativePer100;
    /** Candidate favorable percentile; null -&gt; hide this side's bar only. */
    public final Double candidateFavorable;
    /** Alternative favorable percentile; null -&gt; hide this side's bar only. */
    public final Double alternativeFavorable;
    /** Difference badge, e.g. "61% less", "+2.7 g", "120 mg less". */
    public final String badge;

    public NutrientDelta(String key, String label, String unit,
                         double candidatePer100, double alternativePer100,
                         Double candidateFavorable, Double alternativeFavorable,
                         String badge) {
        this.key = key;
        this.label = label;
        this.unit = unit;
        this.candidatePer100 = candidatePer100;
        this.alternativePer100 = alternativePer100;
        this.candidateFavorable = candidateFavorable;
        this.alternativeFavorable = alternativeFavorable;
        this.badge = badge;
    }
}
