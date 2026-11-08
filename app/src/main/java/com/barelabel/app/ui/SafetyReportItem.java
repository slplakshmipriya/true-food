package com.barelabel.app.ui;

import com.barelabel.app.news.FdaRecallMatcher;
import com.barelabel.app.news.SafetyAlert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure view model for one item of the verdict card's "Safety
 * reports" section (Contract §6.4, task NA-06). Built from a
 * {@link SafetyAlert}; the Android binder
 * ({@link SafetyReportsPanelController}) only renders these fields.
 *
 * <p>Rules pinned here, per §6.4:
 * <ul>
 *   <li>Tag text by tier: FDA → "FDA RECALL", NEWS → "NEWS REPORT".</li>
 *   <li>Tag colors reuse the app palette: FDA takes the existing
 *       DIRTY pill tint (#FEE2E2, {@code chip_dirty_background}) with
 *       red-900 text (#7F1D1D, the verdict bubbles' dirty shade);
 *       NEWS takes the one new pairing the contract allows —
 *       slate-200 bg #E2E8F0, slate-800 text #1E293B.</li>
 *   <li>FDA product and reason text are each trimmed to 220 chars
 *       via {@link FdaRecallMatcher#trimTo220} — the same rule the
 *       matcher already applied when building the alert, so this is
 *       idempotent on model text and only bites for alerts built
 *       outside the matcher. NEWS items have no body.</li>
 *   <li>{@code metaLine} and {@code link} pass through verbatim:
 *       the formatters (§6.4) build the meta line, never the UI.</li>
 *   <li>Link labels: FDA → "View FDA report"; NEWS → "View news
 *       report".</li>
 * </ul>
 *
 * Pure JVM (no Android, no lambdas/streams/java.time — minSdk 23),
 * so the section logic is unit-testable without a device.
 */
public final class SafetyReportItem {

    /** Section heading, exactly per §6.4. */
    public static final String HEADING = "Safety reports";

    public static final String FDA_TAG_TEXT = "FDA RECALL";
    public static final String NEWS_TAG_TEXT = "NEWS REPORT";
    public static final String FDA_LINK_LABEL = "View FDA report";
    public static final String NEWS_LINK_LABEL = "View news report";

    /** Existing DIRTY pill tint (chip_dirty_background) / red-900. */
    public static final int FDA_TAG_BG = 0xFFFEE2E2;
    public static final int FDA_TAG_TEXT_COLOR = 0xFF7F1D1D;
    /** §6.4's one new pairing: slate-200 bg, slate-800 text. */
    public static final int NEWS_TAG_BG = 0xFFE2E8F0;
    public static final int NEWS_TAG_TEXT_COLOR = 0xFF1E293B;

    /** Which tag styling an item uses, chosen by alert tier. */
    public enum TagStyle {
        FDA, NEWS
    }

    public final SafetyAlert.Tier tier;
    public final TagStyle tagStyle;
    public final String tagText;
    public final int tagBackgroundColor;
    public final int tagTextColor;
    public final String headline;
    /** FDA only, trimmed to 220; null for NEWS. */
    public final String productText;
    /** FDA only, trimmed to 220; null for NEWS. */
    public final String reasonText;
    /** Product + reason joined for display; "" when there is no body. */
    public final String bodyText;
    /** Fully formatted by the source formatter; passed through. */
    public final String metaLine;
    public final String linkUrl;
    public final String linkLabel;

    private SafetyReportItem(SafetyAlert alert) {
        boolean fda = alert.tier != SafetyAlert.Tier.NEWS;
        this.tier = alert.tier;
        this.tagStyle = fda ? TagStyle.FDA : TagStyle.NEWS;
        this.tagText = fda ? FDA_TAG_TEXT : NEWS_TAG_TEXT;
        this.tagBackgroundColor = fda ? FDA_TAG_BG : NEWS_TAG_BG;
        this.tagTextColor = fda ? FDA_TAG_TEXT_COLOR : NEWS_TAG_TEXT_COLOR;
        this.headline = alert.headline != null ? alert.headline : "";
        this.productText = fda
                ? FdaRecallMatcher.trimTo220(alert.productText) : null;
        this.reasonText = fda
                ? FdaRecallMatcher.trimTo220(alert.reasonText) : null;
        this.bodyText = joinBody(productText, reasonText);
        this.metaLine = alert.metaLine != null ? alert.metaLine : "";
        this.linkUrl = alert.link != null ? alert.link : "";
        this.linkLabel = fda ? FDA_LINK_LABEL : NEWS_LINK_LABEL;
    }

    /** One item from one alert; null in, null out. */
    public static SafetyReportItem fromAlert(SafetyAlert alert) {
        return alert == null ? null : new SafetyReportItem(alert);
    }

    /**
     * Items for a combined alert list, in the list's own order
     * (§4 combined order is the service's; the section preserves
     * it). Null alerts are skipped; a null list yields empty.
     */
    public static List<SafetyReportItem> fromAlerts(List<SafetyAlert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return Collections.emptyList();
        }
        List<SafetyReportItem> out = new ArrayList<>();
        for (SafetyAlert alert : alerts) {
            SafetyReportItem item = fromAlert(alert);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    /** §6.4: the section is present only when at least 1 alert exists. */
    public static boolean isSectionVisible(List<SafetyAlert> alerts) {
        return !fromAlerts(alerts).isEmpty();
    }

    public boolean hasBody() {
        return bodyText != null && !bodyText.isEmpty();
    }

    public boolean hasLink() {
        return linkUrl != null && !linkUrl.isEmpty();
    }

    private static String joinBody(String product, String reason) {
        boolean hasProduct = product != null && !product.isEmpty();
        boolean hasReason = reason != null && !reason.isEmpty();
        if (hasProduct && hasReason) {
            return product + " \u2014 " + reason;
        }
        if (hasProduct) {
            return product;
        }
        if (hasReason) {
            return reason;
        }
        return "";
    }
}
