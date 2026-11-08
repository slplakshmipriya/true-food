package com.barelabel.app.news;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Search-surface presentation logic (NA-05): Contract section 6.2
 * count-line copy (singular/plural, raw term quoting, absent at
 * zero), section 6.3 row-badge decisions (delegated to the NA-04
 * {@link SafetyAlertService#hasFdaBadge} rule, so NEWS alerts never
 * badge), and the insertion-state handling behind section 6.5
 * (alerts insert when they arrive; a superseded search's alerts can
 * never land on the current search's surface).
 *
 * Pure JVM: no Android, no network, no device.
 */
public class SearchSafetyPresentationTest {

    // ------------------------------------------------------------------
    // Alert fixtures
    // ------------------------------------------------------------------

    private static SafetyAlert fdaAlert(String productText) {
        return new SafetyAlert(SafetyAlert.Tier.FDA, "Some Firm",
                productText, "Some reason", "meta", "FDA recall",
                "report-link", "Class I", "Ongoing", "ev", "F-1",
                20261101);
    }

    private static SafetyAlert newsAlert(String headline) {
        return new SafetyAlert(SafetyAlert.Tier.NEWS, headline,
                null, null, "meta", "News report", "story-link",
                null, null, null, null, 20261107);
    }

    /** The hand-trace mix: 2 FDA strawberry alerts + 1 NEWS alert. */
    private static List<SafetyAlert> strawberryAlerts() {
        List<SafetyAlert> alerts = new ArrayList<>();
        alerts.add(fdaAlert("Fresh Breeze Farms Organic Strawberries"));
        alerts.add(fdaAlert("Sunnyvale Organic Strawberries, 16 oz"));
        alerts.add(newsAlert(
                "FDA investigating hepatitis A outbreak linked"
                        + " to fresh strawberries"));
        return alerts;
    }

    // ------------------------------------------------------------------
    // Section 6.2 count-line copy
    // ------------------------------------------------------------------

    @Test
    public void countLine_plural_exactCopy() {
        assertEquals("3 safety reports for \"strawberry\""
                        + " \u2014 tap a flagged item to see them",
                SearchSafetyPresentation.countLineText(3, "strawberry"));
    }

    @Test
    public void countLine_singular_exactCopy() {
        assertEquals("1 safety report for \"strawberry\""
                        + " \u2014 tap a flagged item to see them",
                SearchSafetyPresentation.countLineText(1, "strawberry"));
    }

    @Test
    public void countLine_twoAlerts_plural() {
        assertEquals("2 safety reports for \"strawberry\""
                        + " \u2014 tap a flagged item to see them",
                SearchSafetyPresentation.countLineText(2, "strawberry"));
    }

    @Test
    public void countLine_zeroAlerts_noLine() {
        assertNull(SearchSafetyPresentation.countLineText(0, "strawberry"));
        assertFalse(SearchSafetyPresentation.isCountLineVisible(0));
    }

    @Test
    public void countLine_negativeCount_noLine() {
        assertNull(SearchSafetyPresentation.countLineText(-1, "strawberry"));
        assertFalse(SearchSafetyPresentation.isCountLineVisible(-1));
    }

    @Test
    public void countLine_visibility_positiveCounts() {
        assertTrue(SearchSafetyPresentation.isCountLineVisible(1));
        assertTrue(SearchSafetyPresentation.isCountLineVisible(5));
    }

    @Test
    public void countLine_termShownVerbatim() {
        // The user's raw search text: case and inner spacing are
        // preserved exactly inside the quotes (no normalization).
        assertEquals("2 safety reports for \"Strawberry Jam\""
                        + " \u2014 tap a flagged item to see them",
                SearchSafetyPresentation.countLineText(
                        2, "Strawberry Jam"));
    }

    @Test
    public void countLine_missingTerm_noLine() {
        assertNull(SearchSafetyPresentation.countLineText(3, null));
        assertNull(SearchSafetyPresentation.countLineText(3, ""));
        assertNull(SearchSafetyPresentation.countLineText(3, "   "));
    }

    @Test
    public void countLine_combinedCountIncludesNewsAlerts() {
        // Section 6.2: the combined count (FDA + NEWS) drives the
        // copy — 2 FDA + 1 NEWS reads "3 safety reports".
        assertEquals(3, SearchSafetyPresentation.combinedCount(
                strawberryAlerts()));
        assertEquals("3 safety reports for \"strawberry\""
                        + " \u2014 tap a flagged item to see them",
                SearchSafetyPresentation.countLineText(
                        SearchSafetyPresentation.combinedCount(
                                strawberryAlerts()),
                        "strawberry"));
    }

    @Test
    public void combinedCount_nullAndEmpty_areZero() {
        assertEquals(0, SearchSafetyPresentation.combinedCount(null));
        assertEquals(0, SearchSafetyPresentation.combinedCount(
                Collections.<SafetyAlert>emptyList()));
    }

    // ------------------------------------------------------------------
    // Section 6.3 row badges
    // ------------------------------------------------------------------

    @Test
    public void badgeText_exactCopy() {
        assertEquals("\u26a0 recall", SearchSafetyPresentation.BADGE_TEXT);
    }

    @Test
    public void badge_matchingFdaAlert_visible() {
        assertTrue(SearchSafetyPresentation.badgeVisible(
                "Sunnyvale", "Sunnyvale Organic Strawberries",
                strawberryAlerts()));
    }

    @Test
    public void badge_newsAlertsNeverBadge() {
        List<SafetyAlert> newsOnly = Collections.singletonList(
                newsAlert("Sunnyvale strawberries recalled statewide"));
        assertFalse(SearchSafetyPresentation.badgeVisible(
                "Sunnyvale", "Sunnyvale Organic Strawberries", newsOnly));
    }

    @Test
    public void badge_noAlerts_notVisible() {
        assertFalse(SearchSafetyPresentation.badgeVisible(
                "Sunnyvale", "Sunnyvale Organic Strawberries",
                Collections.<SafetyAlert>emptyList()));
        assertFalse(SearchSafetyPresentation.badgeVisible(
                "Sunnyvale", "Sunnyvale Organic Strawberries", null));
    }

    @Test
    public void badge_unrelatedProduct_notVisible() {
        assertFalse(SearchSafetyPresentation.badgeVisible(
                "BerryFields", "BerryFields Strawberry Jam",
                strawberryAlerts()));
    }

    @Test
    public void badgeDecisions_perRowAlignedWithInput() {
        List<SearchSafetyPresentation.RowProduct> rows = Arrays.asList(
                new SearchSafetyPresentation.RowProduct(
                        "Sunnyvale", "Sunnyvale Organic Strawberries"),
                new SearchSafetyPresentation.RowProduct(
                        "BerryFields", "BerryFields Strawberry Jam"),
                new SearchSafetyPresentation.RowProduct(
                        "Fresh Breeze Farms",
                        "Fresh Breeze Farms Organic Strawberries"));
        List<Boolean> decisions =
                SearchSafetyPresentation.badgeVisibilityForRows(
                        rows, strawberryAlerts());
        assertEquals(3, decisions.size());
        assertEquals(Boolean.TRUE, decisions.get(0));
        assertEquals(Boolean.FALSE, decisions.get(1));
        assertEquals(Boolean.TRUE, decisions.get(2));
    }

    @Test
    public void badgeDecisions_nullInputs_safe() {
        assertTrue(SearchSafetyPresentation.badgeVisibilityForRows(
                null, strawberryAlerts()).isEmpty());
        List<SearchSafetyPresentation.RowProduct> rows =
                new ArrayList<>();
        rows.add(null);
        rows.add(new SearchSafetyPresentation.RowProduct(
                "Sunnyvale", "Sunnyvale Organic Strawberries"));
        List<Boolean> decisions =
                SearchSafetyPresentation.badgeVisibilityForRows(
                        rows, null);
        assertEquals(2, decisions.size());
        assertEquals(Boolean.FALSE, decisions.get(0));
        assertEquals(Boolean.FALSE, decisions.get(1));
    }

    // ------------------------------------------------------------------
    // Section 6.5 insertion state
    // ------------------------------------------------------------------

    @Test
    public void state_beforeAlertsArrive_noLineNoBadges() {
        SearchSafetyState state = new SearchSafetyState();
        state.beginSearch("strawberry");
        assertFalse(state.isApplied());
        assertFalse(state.isCountLineVisible());
        assertNull(state.countLineText());
        assertEquals(0, state.alertCount());
        assertFalse(state.badgeVisibleFor(
                "Sunnyvale", "Sunnyvale Organic Strawberries"));
    }

    @Test
    public void state_afterAlertsArrive_lineAndBadgesFromSameAlerts() {
        SearchSafetyState state = new SearchSafetyState();
        int generation = state.beginSearch("strawberry");
        assertTrue(state.applyAlerts(generation, strawberryAlerts()));
        assertTrue(state.isApplied());
        assertEquals(3, state.alertCount());
        assertEquals("3 safety reports for \"strawberry\""
                        + " \u2014 tap a flagged item to see them",
                state.countLineText());
        assertTrue(state.isCountLineVisible());
        assertTrue(state.badgeVisibleFor(
                "Sunnyvale", "Sunnyvale Organic Strawberries"));
        assertFalse(state.badgeVisibleFor(
                "BerryFields", "BerryFields Strawberry Jam"));
    }

    @Test
    public void state_singleAlert_singularLine() {
        SearchSafetyState state = new SearchSafetyState();
        int generation = state.beginSearch("strawberry");
        assertTrue(state.applyAlerts(generation,
                Collections.singletonList(
                        fdaAlert("Sunnyvale Organic Strawberries"))));
        assertEquals("1 safety report for \"strawberry\""
                        + " \u2014 tap a flagged item to see them",
                state.countLineText());
    }

    @Test
    public void state_emptyAlertsApplied_staysInvisible() {
        // Failure / no-match (section 7): the service returns an
        // empty list and the surface stays exactly as today.
        SearchSafetyState state = new SearchSafetyState();
        int generation = state.beginSearch("strawberry");
        assertTrue(state.applyAlerts(generation,
                Collections.<SafetyAlert>emptyList()));
        assertTrue(state.isApplied());
        assertFalse(state.isCountLineVisible());
        assertNull(state.countLineText());
        assertFalse(state.badgeVisibleFor(
                "Sunnyvale", "Sunnyvale Organic Strawberries"));
    }

    @Test
    public void state_staleGenerationAlerts_rejected() {
        SearchSafetyState state = new SearchSafetyState();
        int first = state.beginSearch("strawberry");
        int second = state.beginSearch("blueberry");
        assertFalse(state.applyAlerts(first, strawberryAlerts()));
        assertFalse(state.isApplied());
        assertNull(state.countLineText());
        // The current generation still accepts its own alerts.
        assertTrue(state.applyAlerts(second,
                Collections.<SafetyAlert>emptyList()));
        assertEquals("blueberry", state.term());
    }

    @Test
    public void state_newSearchClearsPreviousAlerts() {
        SearchSafetyState state = new SearchSafetyState();
        int generation = state.beginSearch("strawberry");
        assertTrue(state.applyAlerts(generation, strawberryAlerts()));
        assertTrue(state.isCountLineVisible());
        state.beginSearch("blueberry");
        assertFalse(state.isApplied());
        assertEquals(0, state.alertCount());
        assertNull(state.countLineText());
        assertFalse(state.badgeVisibleFor(
                "Sunnyvale", "Sunnyvale Organic Strawberries"));
    }

    @Test
    public void state_clearInvalidatesPendingAlerts() {
        SearchSafetyState state = new SearchSafetyState();
        int generation = state.beginSearch("strawberry");
        state.clear();
        assertFalse(state.applyAlerts(generation, strawberryAlerts()));
        assertFalse(state.isCountLineVisible());
        assertNull(state.term());
    }

    @Test
    public void state_applyAlertsNull_treatedAsEmpty() {
        SearchSafetyState state = new SearchSafetyState();
        int generation = state.beginSearch("strawberry");
        assertTrue(state.applyAlerts(generation, null));
        assertTrue(state.isApplied());
        assertEquals(0, state.alertCount());
        assertNull(state.countLineText());
    }

    @Test
    public void state_alertsSnapshot_isDefensiveCopy() {
        SearchSafetyState state = new SearchSafetyState();
        int generation = state.beginSearch("strawberry");
        List<SafetyAlert> alerts = strawberryAlerts();
        assertTrue(state.applyAlerts(generation, alerts));
        alerts.clear();
        // Mutating the caller's list afterwards changes nothing.
        assertEquals(3, state.alertCount());
    }
}
