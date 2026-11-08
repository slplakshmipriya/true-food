package com.barelabel.app.news;

import java.util.Objects;

/**
 * A matched NEWS alert (Contracts sections 1, 3.3, 4, 6.4): everything
 * the NEWS half of a SafetyAlert carries, in NA-02's own value type.
 * NA-04 adapts this to SafetyAlert when the branches merge; NA-02
 * deliberately does not define SafetyAlert (NA-01 owns it).
 *
 * Immutable. {@code sourceLabel} is always "News report".
 */
public final class NewsStory {

    /** Contracts section 1: the NEWS source label. */
    public static final String SOURCE_LABEL = "News report";

    private final String headline;
    private final String outlet;
    private final String link;
    private final long pubDateEpochMillis;
    private final String metaLine;
    private final String sourceLabel;

    public NewsStory(String headline, String outlet, String link,
                     long pubDateEpochMillis, String metaLine) {
        this.headline = headline == null ? "" : headline;
        this.outlet = outlet == null ? "" : outlet;
        this.link = link == null ? "" : link;
        this.pubDateEpochMillis = pubDateEpochMillis;
        this.metaLine = metaLine == null ? "" : metaLine;
        this.sourceLabel = SOURCE_LABEL;
    }

    public String getHeadline() {
        return headline;
    }

    public String getOutlet() {
        return outlet;
    }

    public String getLink() {
        return link;
    }

    public long getPubDateEpochMillis() {
        return pubDateEpochMillis;
    }

    public String getMetaLine() {
        return metaLine;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NewsStory)) {
            return false;
        }
        NewsStory that = (NewsStory) other;
        return pubDateEpochMillis == that.pubDateEpochMillis
                && headline.equals(that.headline)
                && outlet.equals(that.outlet)
                && link.equals(that.link)
                && metaLine.equals(that.metaLine)
                && sourceLabel.equals(that.sourceLabel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(headline, outlet, link, pubDateEpochMillis,
                metaLine, sourceLabel);
    }

    @Override
    public String toString() {
        return "NewsStory{headline='" + headline + '\''
                + ", outlet='" + outlet + '\''
                + ", link='" + link + '\''
                + ", pubDateEpochMillis=" + pubDateEpochMillis
                + ", metaLine='" + metaLine + '\''
                + ", sourceLabel='" + sourceLabel + '\'' + '}';
    }
}
