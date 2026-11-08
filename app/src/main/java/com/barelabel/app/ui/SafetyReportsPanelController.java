package com.barelabel.app.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.barelabel.app.R;
import com.barelabel.app.news.SafetyAlert;

import java.util.List;

/**
 * Owns the "Safety reports" section inside the verdict card
 * (Contract §6.4, task NA-06), following the
 * {@link NutritionPanelController} precedent: the host binds the
 * card's fixed top block (summary, scores, verdict pills) once per
 * result and this controller only fills the appended section below
 * the BUY! button, so the section's async arrival never moves the
 * top block (§6.5).
 *
 * <p>The section is a white inner panel ({@code bg_card} in the
 * layout) under a tappable "Safety reports" header. It is present
 * only when at least one alert exists; with none (or on failure —
 * the service returns empty, §7) the section stays GONE and the
 * card is exactly as before the feature. No spinners, no
 * placeholders.
 *
 * <p>Collapse state is per bind (per card instance): every
 * {@link #bind} installs a fresh {@link SafetyReportsCollapseState}
 * starting expanded, and {@link #reset} clears the section for the
 * next product, so a new card never inherits the previous card's
 * alerts or collapse state.
 *
 * <p>Read-only display only (BL-S6): nothing here writes to
 * verdict, score, or history state, and there is no premium gate
 * anywhere in the path (BL-S7). Tags are TextView chips, never
 * Material Buttons — the theme tint overrides pill drawables on
 * Buttons (nutrition build QA fix, commit 33080be).
 *
 * <p>minSdk 23 discipline: anonymous classes only, no lambdas,
 * streams, or java.time.
 */
public class SafetyReportsPanelController {

    private static final int C_HEADLINE = Color.parseColor("#111827");
    private static final int C_BODY = Color.parseColor("#374151");
    private static final int C_META = Color.parseColor("#6B7280");
    private static final int C_LINK = Color.parseColor("#2563EB");
    private static final int C_DIVIDER = Color.parseColor("#E5E7EB");

    private final Context context;
    private SafetyReportsCollapseState collapseState =
            new SafetyReportsCollapseState();

    public SafetyReportsPanelController(Context context) {
        this.context = context;
    }

    /**
     * Binds the section rooted at {@code section} (the layout's
     * {@code safetyReportsSection}) to one product's alerts. An
     * empty or null list hides the section entirely (§6.4/§6.5);
     * otherwise the section shows, expanded, with one item per
     * alert in list order.
     */
    public void bind(View section, List<SafetyAlert> alerts) {
        if (section == null) {
            return;
        }
        List<SafetyReportItem> items = SafetyReportItem.fromAlerts(alerts);
        if (items.isEmpty()) {
            reset(section);
            return;
        }
        LinearLayout itemsContainer =
                section.findViewById(R.id.safetyReportsItems);
        View panel = section.findViewById(R.id.safetyReportsPanel);
        View header = section.findViewById(R.id.safetyReportsHeader);
        if (itemsContainer == null || panel == null) {
            section.setVisibility(View.GONE);
            return;
        }
        // Fresh per-card-instance state: starts expanded (§6.4).
        collapseState = new SafetyReportsCollapseState();
        itemsContainer.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                itemsContainer.addView(buildDivider());
            }
            itemsContainer.addView(buildItemView(items.get(i)));
        }
        section.setVisibility(View.VISIBLE);
        applyCollapse(section, panel);
        if (header != null) {
            final View sectionFinal = section;
            final View panelFinal = panel;
            header.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    collapseState.toggle();
                    applyCollapse(sectionFinal, panelFinal);
                }
            });
        }
    }

    /**
     * Clears the section back to absent: no items, header listener
     * removed, collapse state reset, section GONE. Called by the
     * host whenever a new search/result resets the verdict card.
     */
    public void reset(View section) {
        collapseState.reset();
        if (section == null) {
            return;
        }
        LinearLayout itemsContainer =
                section.findViewById(R.id.safetyReportsItems);
        if (itemsContainer != null) {
            itemsContainer.removeAllViews();
        }
        View header = section.findViewById(R.id.safetyReportsHeader);
        if (header != null) {
            header.setOnClickListener(null);
        }
        section.setVisibility(View.GONE);
    }

    /** Panel visibility + chevron + description follow the state. */
    private void applyCollapse(View section, View panel) {
        boolean expanded = collapseState.isExpanded();
        panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        TextView chevron = section.findViewById(R.id.safetyReportsChevron);
        if (chevron != null) {
            chevron.setText(expanded ? "\u25bc" : "\u203a");
        }
        View header = section.findViewById(R.id.safetyReportsHeader);
        if (header != null) {
            header.setContentDescription(expanded
                    ? "Safety reports, expanded"
                    : "Safety reports, collapsed");
        }
    }

    /** One alert item: tag chip, headline, body (FDA), meta, link. */
    private View buildItemView(final SafetyReportItem item) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        TextView tag = new TextView(context);
        tag.setText(item.tagText);
        tag.setTextSize(10f);
        tag.setTypeface(null, Typeface.BOLD);
        tag.setTextColor(item.tagTextColor);
        GradientDrawable tagBg = new GradientDrawable();
        tagBg.setShape(GradientDrawable.RECTANGLE);
        tagBg.setCornerRadius(dp(6));
        tagBg.setColor(item.tagBackgroundColor);
        tag.setBackground(tagBg);
        tag.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.bottomMargin = dp(6);
        tag.setLayoutParams(tagLp);
        row.addView(tag);

        TextView headline = new TextView(context);
        headline.setText(item.headline);
        headline.setTextSize(15f);
        headline.setTypeface(null, Typeface.BOLD);
        headline.setTextColor(C_HEADLINE);
        row.addView(headline);

        if (item.hasBody()) {
            TextView body = new TextView(context);
            body.setText(item.bodyText);
            body.setTextSize(13f);
            body.setTextColor(C_BODY);
            body.setPadding(0, dp(2), 0, 0);
            row.addView(body);
        }

        if (item.metaLine != null && !item.metaLine.isEmpty()) {
            TextView meta = new TextView(context);
            meta.setText(item.metaLine);
            meta.setTextSize(12f);
            meta.setTextColor(C_META);
            meta.setPadding(0, dp(4), 0, 0);
            row.addView(meta);
        }

        if (item.hasLink()) {
            TextView link = new TextView(context);
            link.setText(item.linkLabel);
            link.setTextSize(13f);
            link.setTypeface(null, Typeface.BOLD);
            link.setTextColor(C_LINK);
            link.setPadding(0, dp(6), 0, 0);
            link.setClickable(true);
            link.setFocusable(true);
            link.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openLink(item.linkUrl);
                }
            });
            row.addView(link);
        }
        return row;
    }

    private View buildDivider() {
        View divider = new View(context);
        divider.setBackgroundColor(C_DIVIDER);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return divider;
    }

    /** Opens the source in the browser; failures stay silent (§7 spirit). */
    private void openLink(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            context.startActivity(intent);
        } catch (RuntimeException e) {
            // NA-07 (NA-06 review MINOR 1): broadened from
            // ActivityNotFoundException | SecurityException to
            // RuntimeException — no browser available, a malformed
            // link, or any other launch failure: the section is
            // informational, so there is nothing to surface
            // (§7 silence).
        }
    }

    private int dp(int dps) {
        return Math.round(
                dps * context.getResources().getDisplayMetrics().density);
    }
}
