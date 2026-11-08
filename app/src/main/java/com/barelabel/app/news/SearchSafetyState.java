package com.barelabel.app.news;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Insertion state for the search-results safety surface (NA-05;
 * Contract section 6.5). One instance tracks the current search:
 * {@link #beginSearch} starts a new search (any previous alerts are
 * dropped, so the surface returns to exactly today's layout) and
 * returns a generation token; the asynchronous alert fetch for that
 * search later calls {@link #applyAlerts} with the token, and the
 * alerts land only when the token is still current — a superseded
 * search's alerts can never appear on the current search's surface.
 *
 * <p>Before any alerts are applied, and after an empty list is
 * applied (no match, or section 7 failure — the service returns an
 * empty list), the count line is invisible and no row badges: the
 * feature is simply absent. All decisions are delegated to
 * {@link SearchSafetyPresentation}; this class only holds state.
 * UI-thread confined in production; methods are synchronized so a
 * stray caller thread cannot corrupt the snapshot.
 */
public final class SearchSafetyState {

    private int generation;
    private String term;
    private List<SafetyAlert> alerts = Collections.emptyList();
    private boolean applied;

    /** Starts a new search; returns its generation token. */
    public synchronized int beginSearch(String searchTerm) {
        generation++;
        term = searchTerm;
        alerts = Collections.emptyList();
        applied = false;
        return generation;
    }

    /**
     * Clears the surface entirely (e.g. a non-search flow takes over
     * the screen) and invalidates any in-flight fetch's token.
     */
    public synchronized void clear() {
        generation++;
        term = null;
        alerts = Collections.emptyList();
        applied = false;
    }

    /** The current search's generation token. */
    public synchronized int currentGeneration() {
        return generation;
    }

    /** The current search's raw term (null after {@link #clear}). */
    public synchronized String term() {
        return term;
    }

    /**
     * Applies a finished fetch's alerts when {@code fetchGeneration}
     * is the current search's token. Returns true when the alerts
     * landed; false when they belonged to a superseded search and
     * were dropped. A null list is treated as empty (section 7).
     */
    public synchronized boolean applyAlerts(int fetchGeneration,
                                            List<SafetyAlert> newAlerts) {
        if (fetchGeneration != generation) {
            return false;
        }
        alerts = newAlerts == null
                ? Collections.<SafetyAlert>emptyList()
                : Collections.unmodifiableList(
                        new ArrayList<>(newAlerts));
        applied = true;
        return true;
    }

    /** True once the current search's fetch has been applied. */
    public synchronized boolean isApplied() {
        return applied;
    }

    /** The applied alerts (empty until applied, and on failure). */
    public synchronized List<SafetyAlert> alerts() {
        return alerts;
    }

    /** Combined alert count for the current search (section 6.2). */
    public synchronized int alertCount() {
        return SearchSafetyPresentation.combinedCount(alerts);
    }

    /**
     * The section 6.2 count-line text for the current state, or
     * null when the line must be absent (alerts not yet arrived,
     * or an applied count of zero).
     */
    public synchronized String countLineText() {
        if (!applied) {
            return null;
        }
        return SearchSafetyPresentation.countLineText(alertCount(), term);
    }

    /** True when the count line should currently be on screen. */
    public synchronized boolean isCountLineVisible() {
        return countLineText() != null;
    }

    /**
     * Badge decision for one row against the applied alerts; false
     * until alerts are applied (rows render exactly as today while
     * the fetch is in flight, per section 6.5).
     */
    public synchronized boolean badgeVisibleFor(String brand,
                                                String productName) {
        if (!applied) {
            return false;
        }
        return SearchSafetyPresentation.badgeVisible(
                brand, productName, alerts);
    }
}
