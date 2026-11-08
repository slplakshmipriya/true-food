package com.barelabel.app.nutrition;

/**
 * Thrown when the nutrition configuration is missing, malformed, or fails
 * any Contract §1.3 validation rule. The loader fails closed on this
 * exception: nutrition is treated as UNAVAILABLE and no hard-coded defaults
 * are substituted.
 */
public class NutritionConfigException extends Exception {

    public NutritionConfigException(String message) {
        super(message);
    }

    public NutritionConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
