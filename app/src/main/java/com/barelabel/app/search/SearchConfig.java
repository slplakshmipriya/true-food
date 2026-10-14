package com.barelabel.app.search;

/**
 * Tuning knobs for text-search disambiguation (see
 * {@link DisambiguationDecider}). Plain constants so the behavior stays
 * deterministic and unit-testable; promote to Remote Config if merchandising
 * ever needs runtime tuning.
 */
public final class SearchConfig {

    private SearchConfig() {
    }

    /**
     * A title match must score strictly above this (0-100, see
     * {@link TitleMatchScorer}) for the search to offer a disambiguation
     * list instead of auto-picking one verdict.
     */
    public static final int DISAMBIGUATION_SCORE_THRESHOLD = 90;

    /** Maximum options shown on the disambiguation list. */
    public static final int DISAMBIGUATION_MAX_OPTIONS = 3;
}
