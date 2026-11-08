package com.barelabel.app.nutrition;

/**
 * One displayed nutrient's comparison against the peer cohort
 * (Contract §4). Immutable.
 *
 * <p>Nullability: {@link #per100} / {@link #perServing} are null when
 * the product did not report the nutrient (missing is never zero).
 * {@link #peerMedian} is present whenever the peer distribution is
 * non-empty. {@link #rawPercentile} / {@link #favorablePercentile} are
 * the DISPLAY percentiles — rounded per Contract §4.1 when the peer
 * count is below the preferred count — and are null when the peer
 * count is below {@code minPeers} (or the nutrient was not reported).
 * {@link #absoluteQuality} is present whenever the nutrient was
 * reported, even without a valid percentile; {@link #quality} requires
 * a valid percentile. {@link #flagReason} is "" when not flagged, else
 * exactly "severe", "relative_floor", or "low" (Contract §4.2).
 */
public final class NutrientComparison {

    public final String key;
    /** Amount per 100 basis units; null when unreported. */
    public final Double per100;
    /** Amount per serving; null when unavailable or unreported. */
    public final Double perServing;
    /** Valid peers for this nutrient (after cohort filtering). */
    public final int peerCount;
    /** Peer median; null when the peer distribution is empty. */
    public final Double peerMedian;
    /** Display raw percentile; null when peerCount &lt; minPeers. */
    public final Double rawPercentile;
    /** Display favorable percentile; null when peerCount &lt; minPeers. */
    public final Double favorablePercentile;
    /** 0–100 absolute quality; null when the nutrient was not reported. */
    public final Double absoluteQuality;
    /** Combined quality; null when not computable (no valid percentile). */
    public final Double quality;
    public final boolean flagged;
    /** "" when not flagged; otherwise "severe"/"relative_floor"/"low". */
    public final String flagReason;

    public NutrientComparison(String key, Double per100, Double perServing,
                              int peerCount, Double peerMedian,
                              Double rawPercentile, Double favorablePercentile,
                              Double absoluteQuality, Double quality,
                              boolean flagged, String flagReason) {
        this.key = key;
        this.per100 = per100;
        this.perServing = perServing;
        this.peerCount = peerCount;
        this.peerMedian = peerMedian;
        this.rawPercentile = rawPercentile;
        this.favorablePercentile = favorablePercentile;
        this.absoluteQuality = absoluteQuality;
        this.quality = quality;
        this.flagged = flagged;
        this.flagReason = flagReason != null ? flagReason : "";
    }
}
