package com.barelabel.app.nutrition;

/**
 * Nutrition verdict for one product (Contract §4, PRD §7.3). SAFE and
 * CONCERNING are user-facing verdicts; UNAVAILABLE is a data state —
 * coverage or peer data was inadequate and no severe absolute flag
 * fired — and must never be presented as SAFE.
 */
public enum NutritionStatus {
    SAFE,
    CONCERNING,
    UNAVAILABLE
}
