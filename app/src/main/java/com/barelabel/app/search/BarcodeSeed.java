package com.barelabel.app.search;

/**
 * Android-free decision logic for barcode scans: what text a scan seeds into
 * the search box. Kept out of MainActivity so it stays unit-testable on the
 * JVM (the same split as pricing/UpcOffers — Activity statics break JVM test
 * loading).
 */
public final class BarcodeSeed {
    private BarcodeSeed() {
    }

    /**
     * Picks the search-box seed for a scanned barcode.
     *
     * <p>Priority: USDA description (+ brand) when the GTIN lookup hit;
     * otherwise the Open Food Facts display name when the OFF fallback found
     * the product; otherwise the raw GTIN digits (the previous behavior when
     * USDA had no record for the scanned variant).
     *
     * @param usdaDescription USDA description, "" when the GTIN lookup missed
     * @param usdaBrand       USDA brand/owner, "" when unknown
     * @param offName         OFF display name, "" when the fallback missed
     * @param gtin            raw scanned GTIN, last resort
     * @return the text to seed into the search box, never null
     */
    public static String resolve(String usdaDescription, String usdaBrand,
                                 String offName, String gtin) {
        String desc = usdaDescription == null ? "" : usdaDescription.trim();
        if (!desc.isEmpty()) {
            String brand = usdaBrand == null ? "" : usdaBrand.trim();
            return brand.isEmpty() ? desc : desc + " (" + brand + ")";
        }
        String off = offName == null ? "" : offName.trim();
        if (!off.isEmpty()) return off;
        return gtin == null ? "" : gtin;
    }
}
