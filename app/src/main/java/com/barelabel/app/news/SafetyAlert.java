package com.barelabel.app.news;

import java.util.Comparator;
import java.util.Objects;

/**
 * One safety alert (Contract §1). Immutable value class.
 *
 * <p>FDA alerts carry the recall fields ({@code classification},
 * {@code status}, {@code eventId}, {@code recallNumber},
 * {@code productText}, {@code reasonText}); NEWS alerts leave those
 * null. {@code metaLine} is fully formatted by the source formatter
 * (§6.4), never by the UI layer. {@code reportDateYyyymmdd} is the
 * sort date (FDA report_date / news pubDate as YYYYMMDD, 0 when
 * unknown) backing the §1/§4 ordering.
 */
public final class SafetyAlert implements Comparable<SafetyAlert> {

    public enum Tier {
        FDA, NEWS
    }

    public final Tier tier;
    public final String headline;
    /** FDA only; null for NEWS. */
    public final String productText;
    /** FDA only; null for NEWS. */
    public final String reasonText;
    public final String metaLine;
    /** "FDA recall" or "News report". */
    public final String sourceLabel;
    public final String link;
    /** FDA only; null otherwise or when not reported. */
    public final String classification;
    /** FDA only; null otherwise or when not reported. */
    public final String status;
    /** FDA only; null otherwise or when not reported. */
    public final String eventId;
    /** FDA only; null otherwise or when not reported. */
    public final String recallNumber;
    /** Sort date as YYYYMMDD; 0 when unknown. */
    public final int reportDateYyyymmdd;

    public SafetyAlert(Tier tier, String headline, String productText,
                       String reasonText, String metaLine, String sourceLabel,
                       String link, String classification, String status,
                       String eventId, String recallNumber,
                       int reportDateYyyymmdd) {
        this.tier = tier == null ? Tier.FDA : tier;
        this.headline = headline != null ? headline : "";
        this.productText = productText;
        this.reasonText = reasonText;
        this.metaLine = metaLine != null ? metaLine : "";
        this.sourceLabel = sourceLabel != null ? sourceLabel : "";
        this.link = link != null ? link : "";
        this.classification = classification;
        this.status = status;
        this.eventId = eventId;
        this.recallNumber = recallNumber;
        this.reportDateYyyymmdd = reportDateYyyymmdd;
    }

    /** Contract §1 constructor without the sort date (defaults to 0). */
    public SafetyAlert(Tier tier, String headline, String productText,
                       String reasonText, String metaLine, String sourceLabel,
                       String link, String classification, String status,
                       String eventId, String recallNumber) {
        this(tier, headline, productText, reasonText, metaLine,
                sourceLabel, link, classification, status, eventId,
                recallNumber, 0);
    }

    /**
     * Rank of an FDA classification for §4 ordering: Class I first,
     * then II, then III; unknown/missing ranks after III.
     */
    public static int classRank(String classification) {
        if (classification == null) {
            return 3;
        }
        String c = classification.trim();
        if ("Class I".equalsIgnoreCase(c)) {
            return 0;
        }
        if ("Class II".equalsIgnoreCase(c)) {
            return 1;
        }
        if ("Class III".equalsIgnoreCase(c)) {
            return 2;
        }
        return 3;
    }

    /** §1/§4 ordering: FDA before NEWS; FDA by class rank then date desc; NEWS by date desc. */
    public static final Comparator<SafetyAlert> ORDER =
            new Comparator<SafetyAlert>() {
                @Override
                public int compare(SafetyAlert a, SafetyAlert b) {
                    return a.compareTo(b);
                }
            };

    @Override
    public int compareTo(SafetyAlert other) {
        if (other == null) {
            return -1;
        }
        int byTier = Integer.compare(tierOrdinal(tier),
                tierOrdinal(other.tier));
        if (byTier != 0) {
            return byTier;
        }
        if (tier == Tier.FDA) {
            int byClass = Integer.compare(classRank(classification),
                    classRank(other.classification));
            if (byClass != 0) {
                return byClass;
            }
        }
        // Newest first within the same tier (and class, for FDA).
        return Integer.compare(other.reportDateYyyymmdd,
                reportDateYyyymmdd);
    }

    private static int tierOrdinal(Tier t) {
        return t == Tier.NEWS ? 1 : 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SafetyAlert)) {
            return false;
        }
        SafetyAlert other = (SafetyAlert) o;
        return tier == other.tier
                && reportDateYyyymmdd == other.reportDateYyyymmdd
                && Objects.equals(headline, other.headline)
                && Objects.equals(productText, other.productText)
                && Objects.equals(reasonText, other.reasonText)
                && Objects.equals(metaLine, other.metaLine)
                && Objects.equals(sourceLabel, other.sourceLabel)
                && Objects.equals(link, other.link)
                && Objects.equals(classification, other.classification)
                && Objects.equals(status, other.status)
                && Objects.equals(eventId, other.eventId)
                && Objects.equals(recallNumber, other.recallNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tier, headline, productText, reasonText,
                metaLine, sourceLabel, link, classification, status,
                eventId, recallNumber, reportDateYyyymmdd);
    }

    @Override
    public String toString() {
        return "SafetyAlert{tier=" + tier
                + ", headline=" + headline
                + ", classification=" + classification
                + ", status=" + status
                + ", recallNumber=" + recallNumber
                + ", eventId=" + eventId
                + ", reportDateYyyymmdd=" + reportDateYyyymmdd
                + ", metaLine=" + metaLine
                + ", sourceLabel=" + sourceLabel
                + ", link=" + link
                + ", productText=" + productText
                + ", reasonText=" + reasonText
                + "}";
    }
}
