package com.barelabel.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.barelabel.app.R;
import com.barelabel.app.news.SafetyAlert;
import com.barelabel.app.news.SearchSafetyPresentation;
import com.barelabel.app.news.SearchSafetyState;

import java.util.ArrayList;
import java.util.List;

/**
 * Android binding for the search-results safety surface (NA-05;
 * Contracts sections 6.2, 6.3 and 6.5). Owns no decisions: the
 * count-line copy, its visibility, and every row's badge decision
 * come from {@link SearchSafetyPresentation} /
 * {@link SearchSafetyState} (pure, unit-tested); this class only
 * renders them.
 *
 * <p>Flow: {@link #beginSearch} runs when a search is issued (the
 * count line hides, previous row registrations drop). Result rows
 * register their pill row as they are built via
 * {@link #registerRow}; rows built before the alerts arrive are
 * badged in place when {@link #applyAlerts} lands, and rows built
 * after are badged at registration. No placeholders are ever added:
 * until alerts arrive — and forever when they are empty (no match
 * or failure, section 7) — every bound view stays exactly as it
 * was before this feature. The badge reuses the existing amber
 * concern chip drawable and the amber-900 text color already used
 * for the nutrition CONCERNING bubble; no new color tokens.
 */
public final class SearchSafetyBinder {

    /** Amber-900, same value as MainActivity's C_AMBER_900. */
    private static final int C_AMBER_900 = Color.parseColor("#78350F");

    /** One registered result row awaiting (or holding) its badge. */
    private static final class RowBinding {
        final LinearLayout pillRow;
        final String brand;
        final String name;
        TextView badgeView;

        RowBinding(LinearLayout pillRow, String brand, String name) {
            this.pillRow = pillRow;
            this.brand = brand;
            this.name = name;
        }
    }

    private final Context context;
    private final TextView countLine;
    private final SearchSafetyState state = new SearchSafetyState();
    private final List<RowBinding> rows = new ArrayList<>();

    public SearchSafetyBinder(Context context, TextView countLine) {
        this.context = context;
        this.countLine = countLine;
    }

    /**
     * Starts tracking a new search: hides the count line and drops
     * all row registrations. Returns the generation token the
     * caller passes back to {@link #applyAlerts}.
     */
    public int beginSearch(String searchTerm) {
        int generation = state.beginSearch(searchTerm);
        rows.clear();
        renderCountLine();
        return generation;
    }

    /** Clears the whole surface (a non-search flow takes over). */
    public void clear() {
        state.clear();
        rows.clear();
        renderCountLine();
    }

    /** Drops row registrations (the row list is being rebuilt). */
    public void clearRows() {
        rows.clear();
    }

    /**
     * Registers one result row's pill row (the horizontal container
     * holding its verdict pill). If the current search's alerts
     * have already been applied, the row's badge decision is
     * rendered immediately; otherwise it renders when the alerts
     * land.
     */
    public void registerRow(LinearLayout pillRow, String brand,
                            String productName) {
        if (pillRow == null) {
            return;
        }
        RowBinding binding = new RowBinding(pillRow, brand, productName);
        rows.add(binding);
        renderBadge(binding);
    }

    /**
     * Applies a finished alert fetch (UI thread). Alerts for a
     * superseded search are dropped by the state and change
     * nothing. On an applied fetch, the count line and any pending
     * row badges insert together.
     */
    public void applyAlerts(int generation, List<SafetyAlert> alerts) {
        if (!state.applyAlerts(generation, alerts)) {
            return;
        }
        renderCountLine();
        for (RowBinding binding : rows) {
            renderBadge(binding);
        }
    }

    /** Renders the section 6.2 count line from the current state. */
    private void renderCountLine() {
        if (countLine == null) {
            return;
        }
        String text = state.countLineText();
        if (text == null) {
            countLine.setVisibility(View.GONE);
        } else {
            countLine.setText(text);
            countLine.setVisibility(View.VISIBLE);
        }
    }

    /** Adds the row's badge beside its pill iff its decision is on. */
    private void renderBadge(RowBinding binding) {
        if (binding.badgeView != null) {
            return;
        }
        if (!state.badgeVisibleFor(binding.brand, binding.name)) {
            return;
        }
        TextView badge = new TextView(context);
        badge.setText(SearchSafetyPresentation.BADGE_TEXT);
        badge.setTextColor(C_AMBER_900);
        badge.setBackgroundResource(R.drawable.chip_concern_background);
        badge.setTextSize(11f);
        badge.setTypeface(null, Typeface.BOLD);
        int padH = dp(3), padW = dp(8);
        badge.setPadding(padW, padH, padW, padH);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        badge.setLayoutParams(lp);
        binding.pillRow.addView(badge);
        binding.badgeView = badge;
    }

    private int dp(int dps) {
        return Math.round(dps
                * context.getResources().getDisplayMetrics().density);
    }
}
