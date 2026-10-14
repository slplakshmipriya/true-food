package com.barelabel.app.billing;

/**
 * Feature gates for premium features.
 *
 * Premium feature #1 is the ingredient-label OCR scanner (one-time lifetime
 * unlock). The billing plumbing is NOT built yet, so every gate below returns
 * false (locked for everyone). When Play Billing lands, flip these to consult
 * the entitlement store; call sites stay untouched.
 */
public final class PremiumGates {
    private PremiumGates() {
    }

    /** Ingredient-label OCR scanner. */
    public static boolean isOcrScanEnabled() {
        return false;
    }
}
