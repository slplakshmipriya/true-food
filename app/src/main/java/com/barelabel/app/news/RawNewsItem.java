package com.barelabel.app.news;

import java.util.Objects;

/**
 * One parsed RSS item (Contracts section 2.2): the working headline
 * (outlet suffix already split off), the resolved outlet, the article
 * link and the publication instant. Immutable value type produced by
 * {@link RssFeedParser} and consumed by {@link NewsMatcher}.
 */
public final class RawNewsItem {

    private final String headline;
    private final String outlet;
    private final String link;
    private final long pubDateEpochMillis;

    public RawNewsItem(String headline, String outlet, String link,
                       long pubDateEpochMillis) {
        this.headline = headline == null ? "" : headline;
        this.outlet = outlet == null ? "" : outlet;
        this.link = link == null ? "" : link;
        this.pubDateEpochMillis = pubDateEpochMillis;
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RawNewsItem)) {
            return false;
        }
        RawNewsItem that = (RawNewsItem) other;
        return pubDateEpochMillis == that.pubDateEpochMillis
                && headline.equals(that.headline)
                && outlet.equals(that.outlet)
                && link.equals(that.link);
    }

    @Override
    public int hashCode() {
        return Objects.hash(headline, outlet, link, pubDateEpochMillis);
    }

    @Override
    public String toString() {
        return "RawNewsItem{headline='" + headline + '\''
                + ", outlet='" + outlet + '\''
                + ", link='" + link + '\''
                + ", pubDateEpochMillis=" + pubDateEpochMillis + '}';
    }
}
