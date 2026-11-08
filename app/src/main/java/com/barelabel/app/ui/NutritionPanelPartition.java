package com.barelabel.app.ui;

import com.barelabel.app.nutrition.NutrientComparison;
import com.barelabel.app.nutrition.NutrientDefinition;
import com.barelabel.app.nutrition.NutritionAnalysis;
import com.barelabel.app.nutrition.NutritionConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure outstanding-vs-remaining partitioning for the nutrition detail
 * panel (Contract §7.1, PRD §10.2) — no Android dependencies, so the
 * panel's one non-trivial decision is JVM-testable.
 *
 * <p>Outstanding nutrients are shown first in the panel:
 * <ul>
 *   <li><b>Needs attention</b> — the flagged nutrients, in config
 *       order;</li>
 *   <li><b>Strengths</b> — the top favorable non-flagged nutrients:
 *       favorable percentile in the green band (&#8805; 67, the green
 *       threshold of the §7.1 bar colors), best first with config order
 *       breaking ties, capped at {@link #MAX_STRENGTHS}.</li>
 * </ul>
 * Every other reported, displayed nutrient is "remaining", in config
 * order; the three lists are disjoint and their union is exactly the
 * reported displayed set. Unreported nutrients (per-100 amount null)
 * and non-displayed definitions appear nowhere — a missing value is
 * never rendered as zero.
 */
public final class NutritionPanelPartition {

    /** How many "Strengths" rows the outstanding section shows at most. */
    public static final int MAX_STRENGTHS = 3;

    /** Green-band floor (Contract §7.1 bar colors): a strength's floor. */
    public static final double STRENGTH_MIN_FAVORABLE = 67.0;

    /** Fill-bar color band for a favorable percentile (Contract §7.1). */
    public enum BarBand { RED, AMBER, GREEN }

    public final List<NutrientComparison> needsAttention;
    public final List<NutrientComparison> strengths;
    public final List<NutrientComparison> remaining;

    private NutritionPanelPartition(List<NutrientComparison> needsAttention,
                                    List<NutrientComparison> strengths,
                                    List<NutrientComparison> remaining) {
        this.needsAttention = Collections.unmodifiableList(needsAttention);
        this.strengths = Collections.unmodifiableList(strengths);
        this.remaining = Collections.unmodifiableList(remaining);
    }

    public static NutritionPanelPartition partition(
            NutritionAnalysis analysis, NutritionConfig config) {
        List<NutrientComparison> reported = reportedDisplayed(analysis, config);

        List<NutrientComparison> needsAttention = new ArrayList<>();
        List<NutrientComparison> strengthCandidates = new ArrayList<>();
        for (NutrientComparison c : reported) {
            if (c.flagged) {
                needsAttention.add(c);
            } else if (c.favorablePercentile != null
                    && c.favorablePercentile >= STRENGTH_MIN_FAVORABLE) {
                strengthCandidates.add(c);
            }
        }
        // Best favorable first; the sort is stable, so config order
        // (the order of `reported`) breaks ties deterministically.
        // (Anonymous Comparator, not Comparator.comparingDouble: the
        // app's minSdk predates that static factory.)
        Collections.sort(strengthCandidates, new Comparator<NutrientComparison>() {
            @Override
            public int compare(NutrientComparison a, NutrientComparison b) {
                return Double.compare(b.favorablePercentile,
                        a.favorablePercentile);
            }
        });
        List<NutrientComparison> strengths = new ArrayList<>(
                strengthCandidates.subList(
                        0, Math.min(MAX_STRENGTHS, strengthCandidates.size())));

        Set<String> outstanding = new HashSet<>();
        for (NutrientComparison c : needsAttention) {
            outstanding.add(c.key);
        }
        for (NutrientComparison c : strengths) {
            outstanding.add(c.key);
        }
        List<NutrientComparison> remaining = new ArrayList<>();
        for (NutrientComparison c : reported) {
            if (!outstanding.contains(c.key)) {
                remaining.add(c);
            }
        }
        return new NutritionPanelPartition(needsAttention, strengths, remaining);
    }

    /**
     * The reported, displayed comparisons in config order (comparison
     * order when there is no config): exactly the nutrients the panel
     * may render. Unreported and non-displayed nutrients are excluded.
     */
    public static List<NutrientComparison> reportedDisplayed(
            NutritionAnalysis analysis, NutritionConfig config) {
        List<NutrientComparison> out = new ArrayList<>();
        if (analysis == null) {
            return out;
        }
        if (config != null) {
            for (NutrientDefinition def : config.byKey.values()) {
                if (!def.display) {
                    continue;
                }
                NutrientComparison c = analysis.comparisons.get(def.key);
                if (c != null && c.per100 != null) {
                    out.add(c);
                }
            }
        } else {
            for (NutrientComparison c : analysis.comparisons.values()) {
                if (c != null && c.per100 != null) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** Bar band for a favorable percentile: green ≥ 67, amber 34–66, red ≤ 33. */
    public static BarBand barBand(double favorablePercentile) {
        if (favorablePercentile >= 67.0) {
            return BarBand.GREEN;
        }
        if (favorablePercentile <= 33.0) {
            return BarBand.RED;
        }
        return BarBand.AMBER;
    }

    /**
     * Deterministic amount text (cf. Contract §6 formatting): mg/kcal
     * round to whole numbers; grams show at most one decimal, integral
     * values without a fraction. Null amount ⇒ "" (never "0").
     */
    public static String formatAmount(Double amount, String unit) {
        if (amount == null) {
            return "";
        }
        String number;
        if ("mg".equals(unit) || "kcal".equals(unit)) {
            number = String.valueOf(Math.round(amount));
        } else if (amount == Math.floor(amount) && Double.isFinite(amount)) {
            number = String.valueOf(amount.longValue());
        } else {
            number = String.format(Locale.US, "%.1f", amount);
        }
        return unit == null || unit.isEmpty() ? number : number + " " + unit;
    }

    /**
     * Peer statement for a row, e.g. "Higher than 85% of 21 similar
     * products" (PRD §10.2) — based on the raw percentile, exactly as
     * the mockup phrases it for both directions. "" when the comparison
     * carries no valid percentile (peer count below the minimum), so
     * the panel shows the amount with no fabricated comparison.
     */
    public static String peerStatement(NutrientComparison comparison) {
        if (comparison == null || comparison.rawPercentile == null
                || comparison.peerCount <= 0) {
            return "";
        }
        return "Higher than " + Math.round(comparison.rawPercentile)
                + "% of " + comparison.peerCount + " similar products";
    }
}
