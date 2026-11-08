package com.barelabel.app.nutrition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The filtered peer values for one nutrient (Contract §4): deduplicated,
 * plausible-range- and MAD-fence-filtered by {@link PeerCohort}, held
 * sorted ascending. Immutable.
 *
 * <p>Percentiles use the PRD §6.5 half-credit tie formula,
 * {@code 100 * (below + 0.5 * equal) / count}, so when every peer value
 * equals the queried value the result is a neutral 50, never a fabricated
 * best or worst.
 *
 * <p>Empty distribution: {@link #count()} is 0, {@link #median} is
 * {@link Double#NaN}, and {@link #rawPercentile(double)} returns
 * {@link Double#NaN} for every input. That NaN is a defined "no data"
 * signal, not an error: the analyzer treats {@code peerCount < minPeers}
 * as unavailable before it ever reads a percentile.
 */
public final class PeerDistribution {

    /** Filtered peer values, sorted ascending. Unmodifiable. */
    public final List<Double> values;
    /** Middle value (mean of the two middle values for even counts);
     *  {@link Double#NaN} when the distribution is empty. */
    public final double median;

    public PeerDistribution(List<Double> values) {
        List<Double> copy = values == null
                ? new ArrayList<Double>()
                : new ArrayList<>(values);
        Collections.sort(copy);
        this.values = Collections.unmodifiableList(copy);
        this.median = medianOfSorted(copy);
    }

    public int count() {
        return values.size();
    }

    /**
     * Raw percentile of {@code v} among the peer values (PRD §6.5):
     * {@code 100 * (peersBelow + 0.5 * peersEqual) / count}. Returns
     * {@link Double#NaN} when the distribution is empty.
     */
    public double rawPercentile(double v) {
        int n = values.size();
        if (n == 0) {
            return Double.NaN;
        }
        long below = 0;
        long equal = 0;
        for (Double peer : values) {
            int cmp = Double.compare(peer, v);
            if (cmp < 0) {
                below++;
            } else if (cmp == 0) {
                equal++;
            }
        }
        return 100.0 * (below + 0.5 * equal) / n;
    }

    /** Median of an already-sorted list; NaN when empty. */
    static double medianOfSorted(List<Double> sorted) {
        int n = sorted.size();
        if (n == 0) {
            return Double.NaN;
        }
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
