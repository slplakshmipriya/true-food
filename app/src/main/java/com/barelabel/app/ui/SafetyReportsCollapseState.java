package com.barelabel.app.ui;

/**
 * Collapse state for the verdict card's "Safety reports" section
 * (Contract §6.4, task NA-06): starts EXPANDED, the header tap
 * toggles it, and the state is per card instance — held in memory
 * only, never persisted, and a fresh instance (a newly shown
 * product) always starts expanded regardless of any previous
 * card's state.
 *
 * Pure JVM so the transitions are unit-testable without a device.
 */
public final class SafetyReportsCollapseState {

    private boolean expanded = true;

    public boolean isExpanded() {
        return expanded;
    }

    /** Header tap: expanded ↔ collapsed. */
    public void toggle() {
        expanded = !expanded;
    }

    /** Back to the §6.4 default for a new card instance. */
    public void reset() {
        expanded = true;
    }
}
