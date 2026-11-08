package com.barelabel.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.barelabel.app.R;
import com.barelabel.app.model.NutritionProfile;
import com.barelabel.app.nutrition.NutrientComparison;
import com.barelabel.app.nutrition.NutrientDefinition;
import com.barelabel.app.nutrition.NutritionAnalysis;
import com.barelabel.app.nutrition.NutritionConfig;
import com.barelabel.app.nutrition.NutritionStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the nutrition detail panel inside the verdict card
 * (Contract §7.1): the single nutrition surface of the card.
 *
 * <p>{@link #bind} renders, in order: the outstanding nutrients first —
 * flagged nutrients under "Needs attention", then the top favorable
 * nutrients under "Strengths" (partitioning lives in the pure
 * {@link NutritionPanelPartition}) — then a "Show remaining nutrients"
 * toggle that expands the rest <i>inside this same panel</i>, then the
 * footer naming the comparison category and peer count and stating
 * that missing values are not treated as zero. There is no second
 * nutrition list anywhere else in the card.
 *
 * <p>UNAVAILABLE (or a missing analysis) renders the exact text
 * "Nutrition unavailable" and no scores, percentiles, or bars — only
 * the amounts USDA actually reported, when any exist (BL-N5).
 *
 * <p>Binding never touches the card's fixed top block (header, LLM
 * summary, score strip, bubbles): the host binds those once per result
 * and this controller only fills the detail slot below them. Every
 * bind resets the remaining-nutrients toggle to collapsed.
 */
public class NutritionPanelController {

    private static final int C_GREEN_700 = Color.parseColor("#15803D");
    private static final int C_RED_700 = Color.parseColor("#B91C1C");
    private static final int C_GRAY_500 = Color.parseColor("#6B7280");
    private static final int C_GRAY_700 = Color.parseColor("#374151");
    private static final int C_GRAY_900 = Color.parseColor("#111827");
    private static final int C_BAR_TRACK = Color.parseColor("#E5E7EB");
    private static final int C_BAR_GREEN = Color.parseColor("#16A34A");
    private static final int C_BAR_AMBER = Color.parseColor("#F59E0B");
    private static final int C_BAR_RED = Color.parseColor("#DC2626");

    private final Context context;

    public NutritionPanelController(Context context) {
        this.context = context;
    }

    /**
     * Binds the panel rooted at {@code panel} (the layout's
     * {@code nutritionDetailPanel}) to one product's analysis. The
     * panel's own visibility is the host's concern (bubble accordion);
     * this method only fills its contents and resets the
     * remaining-nutrients toggle to collapsed.
     */
    public void bind(View panel, NutritionAnalysis analysis,
                     NutritionProfile profile, NutritionConfig config) {
        if (panel == null) {
            return;
        }
        LinearLayout outstanding =
                panel.findViewById(R.id.nutritionOutstandingContainer);
        if (outstanding == null) {
            return;
        }
        LinearLayout remainingContainer =
                panel.findViewById(R.id.nutritionRemainingContainer);
        Button toggle = panel.findViewById(R.id.toggleRemainingNutrients);
        TextView footer = panel.findViewById(R.id.nutritionPanelFooter);

        reset(panel);
        String basis = profile != null ? profile.basis : "g";

        if (analysis == null
                || analysis.status == NutritionStatus.UNAVAILABLE) {
            TextView unavailable = new TextView(context);
            unavailable.setText("Nutrition unavailable");
            unavailable.setTextSize(14f);
            unavailable.setTypeface(null, Typeface.BOLD);
            unavailable.setTextColor(C_GRAY_700);
            outstanding.addView(unavailable);
            // Amounts USDA reported are still shown (BL-N5), but with
            // no percentile, bar, or score attached — nothing is
            // fabricated from inadequate peer data.
            for (NutrientComparison c : NutritionPanelPartition
                    .reportedDisplayed(analysis, config)) {
                outstanding.addView(
                        buildNutrientRow(c, config, basis, false));
            }
            if (footer != null) {
                footer.setText(footerText(analysis));
            }
            return;
        }

        NutritionPanelPartition partition =
                NutritionPanelPartition.partition(analysis, config);
        if (!partition.needsAttention.isEmpty()) {
            outstanding.addView(buildSectionHeader("Needs attention",
                    C_RED_700));
            for (NutrientComparison c : partition.needsAttention) {
                outstanding.addView(buildNutrientRow(c, config, basis, true));
            }
        }
        if (!partition.strengths.isEmpty()) {
            outstanding.addView(buildSectionHeader("Strengths",
                    C_GREEN_700));
            for (NutrientComparison c : partition.strengths) {
                outstanding.addView(buildNutrientRow(c, config, basis, true));
            }
        }
        if (partition.needsAttention.isEmpty()
                && partition.strengths.isEmpty()) {
            TextView none = new TextView(context);
            none.setText("No scored nutrient crossed a concern threshold.");
            none.setTextSize(13f);
            none.setTextColor(C_GRAY_500);
            outstanding.addView(none);
        }

        if (remainingContainer != null) {
            for (NutrientComparison c : partition.remaining) {
                remainingContainer.addView(
                        buildNutrientRow(c, config, basis, true));
            }
        }
        if (toggle != null) {
            if (partition.remaining.isEmpty()
                    || remainingContainer == null) {
                toggle.setVisibility(View.GONE);
            } else {
                toggle.setVisibility(View.VISIBLE);
                final LinearLayout remainingFinal = remainingContainer;
                final Button toggleFinal = toggle;
                toggle.setOnClickListener(v -> {
                    boolean expand =
                            remainingFinal.getVisibility() != View.VISIBLE;
                    remainingFinal.setVisibility(
                            expand ? View.VISIBLE : View.GONE);
                    toggleFinal.setText(expand
                            ? "Hide remaining nutrients"
                            : "Show remaining nutrients");
                    toggleFinal.setContentDescription(expand
                            ? "Remaining nutrients, expanded"
                            : "Remaining nutrients, collapsed");
                });
            }
        }
        if (footer != null) {
            footer.setText(footerText(analysis));
        }
    }

    /**
     * Clears the panel back to its just-inflated state: no rows, the
     * remaining-nutrients container collapsed, its toggle reset and
     * hidden, and an empty footer. Called on every bind and by the
     * host when a new search resets the verdict card.
     */
    public void reset(View panel) {
        if (panel == null) {
            return;
        }
        LinearLayout outstanding =
                panel.findViewById(R.id.nutritionOutstandingContainer);
        if (outstanding != null) {
            outstanding.removeAllViews();
        }
        LinearLayout remainingContainer =
                panel.findViewById(R.id.nutritionRemainingContainer);
        if (remainingContainer != null) {
            remainingContainer.removeAllViews();
            remainingContainer.setVisibility(View.GONE);
        }
        Button toggle = panel.findViewById(R.id.toggleRemainingNutrients);
        if (toggle != null) {
            toggle.setText("Show remaining nutrients");
            toggle.setContentDescription("Remaining nutrients, collapsed");
            toggle.setOnClickListener(null);
            toggle.setVisibility(View.GONE);
        }
        TextView footer = panel.findViewById(R.id.nutritionPanelFooter);
        if (footer != null) {
            footer.setText("");
        }
    }

    /** Footer: cohort actually used + the missing-values note (PRD §10.2). */
    private String footerText(NutritionAnalysis analysis) {
        List<String> parts = new ArrayList<>();
        if (analysis != null && !analysis.cohortCategory.isEmpty()) {
            parts.add("Compared with " + analysis.cohortCategory
                    + " \u2022 " + analysis.cohortPeerCount
                    + " similar products");
        }
        parts.add("Missing values are not treated as zero");
        return android.text.TextUtils.join(". ", parts) + ".";
    }

    private TextView buildSectionHeader(String text, int color) {
        TextView header = new TextView(context);
        header.setText(text);
        header.setTextSize(14f);
        header.setTypeface(null, Typeface.BOLD);
        header.setTextColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        header.setLayoutParams(lp);
        return header;
    }

    /**
     * One nutrient row (PRD §10.2): name (with a concern marker when
     * flagged), per-100 amount, per-serving amount when reliable, the
     * peer statement and peer median when a valid percentile exists,
     * and — only then — a small rectangular fill bar whose width is
     * the favorable percentile and whose color follows the §7.1 bands.
     * The bar always accompanies numeric text, never color alone.
     * {@code showComparison} is false in the UNAVAILABLE state, where
     * no percentile or bar may be shown.
     */
    private View buildNutrientRow(NutrientComparison comparison,
                                  NutritionConfig config, String basis,
                                  boolean showComparison) {
        NutrientDefinition def = config != null
                ? config.byKey.get(comparison.key) : null;
        String label = def != null ? def.label : comparison.key;
        String unit = def != null ? def.unit : "";

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);

        TextView name = new TextView(context);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        name.setText(comparison.flagged ? "\u26A0 " + label : label);
        name.setTextSize(14f);
        name.setTypeface(null, Typeface.BOLD);
        name.setTextColor(C_GRAY_900);
        header.addView(name);

        TextView amount = new TextView(context);
        amount.setText(NutritionPanelPartition.formatAmount(
                comparison.per100, unit) + " / 100 " + basis);
        amount.setTextSize(13f);
        amount.setTypeface(null, Typeface.BOLD);
        amount.setTextColor(C_GRAY_700);
        header.addView(amount);
        row.addView(header);

        // Detail line order: the per-100 comparison cluster first
        // (peer statement + median, both on the per-100 basis and
        // labelled as such), the per-serving amount last. Leading
        // with the serving amount invited reading it against the
        // per-100 median, which made correct percentiles look wrong.
        List<String> details = new ArrayList<>();
        String peer = showComparison
                ? NutritionPanelPartition.peerStatement(comparison) : "";
        if (!peer.isEmpty()) {
            details.add(peer);
            if (comparison.peerMedian != null) {
                details.add("Median " + NutritionPanelPartition
                        .formatAmount(comparison.peerMedian, unit)
                        + " / 100 " + basis);
            }
        }
        if (comparison.perServing != null) {
            details.add(NutritionPanelPartition.formatAmount(
                    comparison.perServing, unit) + " per serving");
        }
        if (!details.isEmpty()) {
            TextView detail = new TextView(context);
            detail.setText(android.text.TextUtils.join(" \u2022 ", details));
            detail.setTextSize(12f);
            detail.setTextColor(C_GRAY_500);
            detail.setPadding(0, dp(2), 0, 0);
            row.addView(detail);
        }

        if (showComparison && comparison.favorablePercentile != null) {
            double favorable = Math.max(0.0,
                    Math.min(100.0, comparison.favorablePercentile));
            LinearLayout track = new LinearLayout(context);
            track.setOrientation(LinearLayout.HORIZONTAL);
            track.setBackgroundColor(C_BAR_TRACK);
            LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
            trackLp.topMargin = dp(6);
            track.setLayoutParams(trackLp);

            View fill = new View(context);
            fill.setBackgroundColor(barColor(comparison.favorablePercentile));
            fill.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT,
                    (float) favorable));
            track.addView(fill);
            View rest = new View(context);
            rest.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT,
                    (float) (100.0 - favorable)));
            track.addView(rest);
            row.addView(track);
        }
        return row;
    }

    private int barColor(double favorablePercentile) {
        switch (NutritionPanelPartition.barBand(favorablePercentile)) {
            case GREEN:
                return C_BAR_GREEN;
            case AMBER:
                return C_BAR_AMBER;
            default:
                return C_BAR_RED;
        }
    }

    private int dp(int dps) {
        return Math.round(
                dps * context.getResources().getDisplayMetrics().density);
    }
}
