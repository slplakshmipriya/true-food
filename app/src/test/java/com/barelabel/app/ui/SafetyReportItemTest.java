package com.barelabel.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.barelabel.app.news.FdaRecallMatcher;
import com.barelabel.app.news.SafetyAlert;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * NA-06 verdict-card "Safety reports" section (Contract §6.4, BL-S4):
 * the pure view-model construction from {@link SafetyAlert} — tag
 * text and colors by tier, FDA body trimming at 220 chars, meta-line
 * passthrough, link target/label — plus section visibility (alerts
 * non-empty) and the per-instance collapse state transitions.
 *
 * Sample data mirrors the Option C mockup hand-trace: 2 FDA alerts
 * then 1 NEWS alert, rendered in the order given (§4 combined order
 * is the service's job; the section preserves it).
 */
public class SafetyReportItemTest {

    private static final String FDA_LINK =
            "https://www.fda.gov/safety/recalls-market-withdrawals-safety-alerts"
                    + "?search_api_fulltext=F-0451-2026";
    private static final String NEWS_LINK =
            "https://news.example.com/strawberry-outbreak";

    private static SafetyAlert fda(String firm, String product,
                                   String reason, String meta) {
        return new SafetyAlert(SafetyAlert.Tier.FDA, firm, product, reason,
                meta, "FDA recall", FDA_LINK, "Class I", "Ongoing",
                "98241", "F-0451-2026", 20261105);
    }

    private static SafetyAlert news(String title, String meta, String link) {
        return new SafetyAlert(SafetyAlert.Tier.NEWS, title, null, null,
                meta, "News report", link, null, null, null, null, 20261106);
    }

    private static List<SafetyAlert> sampleAlerts() {
        List<SafetyAlert> alerts = new ArrayList<>();
        alerts.add(fda("FreshHarvest Farms",
                "Fresh whole strawberries, 1 lb clamshell",
                "Potential hepatitis A contamination",
                "Class I \u00b7 Reported Nov 5, 2026 \u00b7 Ongoing"));
        alerts.add(fda("BerryBest",
                "Frozen strawberry slices, 2 lb bag",
                "Potential Listeria monocytogenes contamination",
                "Class II \u00b7 Reported Oct 28, 2026 \u00b7 Ongoing"));
        alerts.add(news(
                "FDA investigating hepatitis A outbreak linked to fresh strawberries",
                "Food Safety News \u00b7 2 days ago", NEWS_LINK));
        return alerts;
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    // ------------------------------------------------------------------
    // Heading + visibility (§6.4: present only when >= 1 alert)
    // ------------------------------------------------------------------

    @Test
    public void headingIsExactlySafetyReports() {
        assertEquals("Safety reports", SafetyReportItem.HEADING);
    }

    @Test
    public void sectionHiddenWhenNoAlerts() {
        assertFalse(SafetyReportItem.isSectionVisible(null));
        assertFalse(SafetyReportItem.isSectionVisible(
                Collections.<SafetyAlert>emptyList()));
        assertFalse(SafetyReportItem.isSectionVisible(
                Collections.singletonList(null)));
    }

    @Test
    public void sectionVisibleWhenAlertsExist() {
        assertTrue(SafetyReportItem.isSectionVisible(sampleAlerts()));
    }

    // ------------------------------------------------------------------
    // FDA item view model
    // ------------------------------------------------------------------

    @Test
    public void fdaItemTagHeadlineMetaAndLink() {
        SafetyReportItem item =
                SafetyReportItem.fromAlert(sampleAlerts().get(0));
        assertEquals("FDA RECALL", item.tagText);
        assertEquals(SafetyReportItem.TagStyle.FDA, item.tagStyle);
        assertEquals("FreshHarvest Farms", item.headline);
        // Meta line passes through from the model verbatim (§6.4:
        // the formatter builds it, the UI layer never rebuilds it).
        assertEquals("Class I \u00b7 Reported Nov 5, 2026 \u00b7 Ongoing",
                item.metaLine);
        assertEquals(FDA_LINK, item.linkUrl);
        assertEquals("View FDA report", item.linkLabel);
        assertTrue(item.hasLink());
    }

    @Test
    public void fdaItemTagColorsReuseDirtyPillTintAndRed900Text() {
        SafetyReportItem item =
                SafetyReportItem.fromAlert(sampleAlerts().get(0));
        // Existing chip_dirty_background tint + the app's red-900
        // verdict-bubble text shade; no new color tokens (§6.4).
        assertEquals(0xFFFEE2E2, item.tagBackgroundColor);
        assertEquals(0xFF7F1D1D, item.tagTextColor);
    }

    @Test
    public void fdaItemBodyCombinesProductAndReason() {
        SafetyReportItem item =
                SafetyReportItem.fromAlert(sampleAlerts().get(0));
        assertEquals("Fresh whole strawberries, 1 lb clamshell",
                item.productText);
        assertEquals("Potential hepatitis A contamination", item.reasonText);
        assertTrue(item.bodyText.contains(item.productText));
        assertTrue(item.bodyText.contains(item.reasonText));
        assertTrue(item.hasBody());
    }

    // ------------------------------------------------------------------
    // NEWS item view model
    // ------------------------------------------------------------------

    @Test
    public void newsItemTagHeadlineMetaAndLink() {
        SafetyReportItem item =
                SafetyReportItem.fromAlert(sampleAlerts().get(2));
        assertEquals("NEWS REPORT", item.tagText);
        assertEquals(SafetyReportItem.TagStyle.NEWS, item.tagStyle);
        assertEquals("FDA investigating hepatitis A outbreak linked to "
                + "fresh strawberries", item.headline);
        // Meta is "<outlet> · <relative date>", from metaLine verbatim.
        assertEquals("Food Safety News \u00b7 2 days ago", item.metaLine);
        assertEquals(NEWS_LINK, item.linkUrl);
        assertEquals("View news report", item.linkLabel);
        assertTrue(item.hasLink());
        // NEWS alerts carry no product/reason body (§1: null fields).
        assertNull(item.productText);
        assertNull(item.reasonText);
        assertFalse(item.hasBody());
    }

    @Test
    public void newsItemTagColorsAreTheOneAllowedNewPairing() {
        SafetyReportItem item =
                SafetyReportItem.fromAlert(sampleAlerts().get(2));
        // §6.4: slate-200 bg #E2E8F0, slate-800 text #1E293B.
        assertEquals(0xFFE2E8F0, item.tagBackgroundColor);
        assertEquals(0xFF1E293B, item.tagTextColor);
    }

    // ------------------------------------------------------------------
    // Trimming at 220 (§6.4)
    // ------------------------------------------------------------------

    @Test
    public void longProductDescriptionTrimsTo220WithEllipsis() {
        SafetyAlert alert = fda("Firm", repeat('a', 300), "reason", "meta");
        SafetyReportItem item = SafetyReportItem.fromAlert(alert);
        assertEquals(220, item.productText.length());
        assertTrue(item.productText.endsWith("\u2026"));
        assertEquals(repeat('a', 219), item.productText.substring(0, 219));
    }

    @Test
    public void longReasonTrimsTo220WithEllipsis() {
        SafetyAlert alert = fda("Firm", "product", repeat('b', 300), "meta");
        SafetyReportItem item = SafetyReportItem.fromAlert(alert);
        assertEquals(220, item.reasonText.length());
        assertTrue(item.reasonText.endsWith("\u2026"));
    }

    @Test
    public void shortTextsAreNotTrimmed() {
        SafetyReportItem item =
                SafetyReportItem.fromAlert(sampleAlerts().get(0));
        assertFalse(item.productText.contains("\u2026"));
        assertFalse(item.reasonText.contains("\u2026"));
    }

    // ------------------------------------------------------------------
    // NA-07 (NA-06 MINOR 2): trim boundaries — exactly 220, 221,
    // word-boundary cut, and idempotence on each
    // ------------------------------------------------------------------

    private static String repeatStr(String unit, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }

    @Test
    public void trim_exactly220Chars_isUnchangedNoEllipsis() {
        String exact = repeat('a', 220);
        assertEquals(exact, FdaRecallMatcher.trimTo220(exact));
        SafetyReportItem item = SafetyReportItem.fromAlert(
                fda("Firm", exact, "reason", "meta"));
        assertEquals(exact, item.productText);
        assertEquals(220, item.productText.length());
        assertFalse(item.productText.contains("\u2026"));
        // Idempotence.
        assertEquals(item.productText,
                FdaRecallMatcher.trimTo220(item.productText));
    }

    @Test
    public void trim_221Chars_trimsTo220WithEllipsis() {
        String over = repeat('a', 221);
        String trimmed = FdaRecallMatcher.trimTo220(over);
        assertEquals(220, trimmed.length());
        assertTrue(trimmed.endsWith("\u2026"));
        assertEquals(repeat('a', 219), trimmed.substring(0, 219));
        SafetyReportItem item = SafetyReportItem.fromAlert(
                fda("Firm", over, "reason", "meta"));
        assertEquals(trimmed, item.productText);
        // Idempotence.
        assertEquals(trimmed, FdaRecallMatcher.trimTo220(trimmed));
    }

    @Test
    public void trim_wordBoundaryCut_producesLessThan220() {
        // 6-char words ("abcde "): the last space at or before the
        // 219 limit sits at index 215, so the cut lands there and
        // the result (215 + ellipsis) is shorter than 220.
        String text = repeatStr("abcde ", 40);
        String trimmed = FdaRecallMatcher.trimTo220(text);
        assertTrue(trimmed.length() < 220);
        assertEquals(216, trimmed.length());
        assertTrue(trimmed.endsWith("\u2026"));
        assertEquals(text.trim().substring(0, 215),
                trimmed.substring(0, 215));
        // Idempotence.
        assertEquals(trimmed, FdaRecallMatcher.trimTo220(trimmed));
        SafetyReportItem item = SafetyReportItem.fromAlert(
                fda("Firm", text, "reason", "meta"));
        assertEquals(trimmed, item.productText);
        assertEquals(trimmed,
                FdaRecallMatcher.trimTo220(item.productText));
    }

    // ------------------------------------------------------------------
    // NA-07 (NA-06 MINOR 3): the body joiner is exactly " — "
    // ------------------------------------------------------------------

    @Test
    public void bodyJoiner_isExactlySpaceEmDashSpace() {
        SafetyReportItem item =
                SafetyReportItem.fromAlert(sampleAlerts().get(0));
        // Full equality pins the joiner byte-for-byte: product,
        // space, em dash (U+2014), space, reason — nothing else.
        assertEquals("Fresh whole strawberries, 1 lb clamshell"
                + " \u2014 Potential hepatitis A contamination",
                item.bodyText);
    }

    @Test
    public void bodySinglePart_hasNoJoiner() {
        SafetyReportItem productOnly = SafetyReportItem.fromAlert(
                fda("Firm", "Only product", "", "meta"));
        assertEquals("Only product", productOnly.bodyText);
        SafetyReportItem reasonOnly = SafetyReportItem.fromAlert(
                fda("Firm", "", "Only reason", "meta"));
        assertEquals("Only reason", reasonOnly.bodyText);
    }

    @Test
    public void alreadyTrimmedModelTextIsNotDoubleTrimmed() {
        // The matcher already trims FDA text to 220; re-applying the
        // rule in the view model must be a no-op on its output.
        String trimmed = FdaRecallMatcher.trimTo220(repeat('a', 300));
        SafetyReportItem item = SafetyReportItem.fromAlert(
                fda("Firm", trimmed, "reason", "meta"));
        assertEquals(trimmed, item.productText);
    }

    // ------------------------------------------------------------------
    // List construction: order preserved, nulls skipped
    // ------------------------------------------------------------------

    @Test
    public void fromAlertsPreservesCombinedOrder() {
        List<SafetyReportItem> items =
                SafetyReportItem.fromAlerts(sampleAlerts());
        assertEquals(3, items.size());
        assertEquals("FDA RECALL", items.get(0).tagText);
        assertEquals("FreshHarvest Farms", items.get(0).headline);
        assertEquals("FDA RECALL", items.get(1).tagText);
        assertEquals("BerryBest", items.get(1).headline);
        assertEquals("Class II \u00b7 Reported Oct 28, 2026 \u00b7 Ongoing",
                items.get(1).metaLine);
        assertEquals("NEWS REPORT", items.get(2).tagText);
    }

    @Test
    public void fromAlertsToleratesNulls() {
        assertTrue(SafetyReportItem.fromAlerts(null).isEmpty());
        assertTrue(SafetyReportItem.fromAlerts(
                Collections.<SafetyAlert>emptyList()).isEmpty());
        List<SafetyAlert> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(sampleAlerts().get(0));
        List<SafetyReportItem> items = SafetyReportItem.fromAlerts(withNull);
        assertEquals(1, items.size());
        assertNull(SafetyReportItem.fromAlert(null));
    }

    @Test
    public void emptyLinkMeansNoLink() {
        SafetyReportItem item = SafetyReportItem.fromAlert(
                news("Title", "Outlet \u00b7 today", ""));
        assertFalse(item.hasLink());
    }

    // ------------------------------------------------------------------
    // Collapse state (§6.4: starts expanded, per card instance)
    // ------------------------------------------------------------------

    @Test
    public void collapseStateStartsExpanded() {
        assertTrue(new SafetyReportsCollapseState().isExpanded());
    }

    @Test
    public void collapseStateToggles() {
        SafetyReportsCollapseState state = new SafetyReportsCollapseState();
        state.toggle();
        assertFalse(state.isExpanded());
        state.toggle();
        assertTrue(state.isExpanded());
    }

    @Test
    public void collapseStateResetReturnsToExpanded() {
        SafetyReportsCollapseState state = new SafetyReportsCollapseState();
        state.toggle();
        state.reset();
        assertTrue(state.isExpanded());
    }

    @Test
    public void collapseStateIsPerInstanceNotShared() {
        SafetyReportsCollapseState first = new SafetyReportsCollapseState();
        first.toggle();
        // A newly shown product gets a fresh instance: expanded,
        // unaffected by the previous card's collapsed state.
        SafetyReportsCollapseState second = new SafetyReportsCollapseState();
        assertFalse(first.isExpanded());
        assertTrue(second.isExpanded());
    }
}
