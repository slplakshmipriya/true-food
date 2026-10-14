package com.barelabel.app.billing;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Feature gates for premium features.
 *
 * Premium feature #1 is the ingredient-label OCR scanner (one-time lifetime
 * unlock). The billing plumbing is NOT built yet: tapping "Get Premium"
 * simply flips a local flag (simulated purchase). When Play Billing lands,
 * call {@link #setOcrScanUnlocked} from the purchase callback instead;
 * call sites stay untouched.
 */
public final class PremiumGates {
    private static final String PREFS = "barelabel_premium";
    private static final String KEY_OCR_SCAN = "ocr_scan_unlocked";

    private PremiumGates() {
    }

    /** Ingredient-label OCR scanner. */
    public static boolean isOcrScanEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OCR_SCAN, false);
    }

    /**
     * Records the OCR scanner unlock. Today this is the simulated purchase;
     * tomorrow the Play Billing purchase callback.
     */
    public static void setOcrScanUnlocked(Context context, boolean unlocked) {
        prefs(context).edit().putBoolean(KEY_OCR_SCAN, unlocked).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
