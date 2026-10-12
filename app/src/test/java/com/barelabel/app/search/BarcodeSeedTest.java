package com.barelabel.app.search;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * BarcodeSeed.resolve: barcode-scan search-box seeding priority —
 * USDA description (+ brand) first, OFF display name on a USDA miss,
 * raw GTIN digits as the last resort.
 */
public class BarcodeSeedTest {

    @Test
    public void usdaDescriptionWithBrand() {
        assertEquals("RICE KRISPIES TREATS (Kellogg's)",
                BarcodeSeed.resolve("RICE KRISPIES TREATS", "Kellogg's",
                        "", "038000265013"));
    }

    @Test
    public void usdaDescriptionWithoutBrand() {
        assertEquals("BREAD",
                BarcodeSeed.resolve("BREAD", "", "", "012345678905"));
    }

    @Test
    public void offNameWhenUsdaMisses() {
        assertEquals("Rice Krispies Treats Original",
                BarcodeSeed.resolve("", "", "Rice Krispies Treats Original",
                        "038000265013"));
    }

    @Test
    public void offNameWhenUsdaDescriptionIsBlank() {
        assertEquals("Cheez-It Original",
                BarcodeSeed.resolve("   ", "Some Brand", "Cheez-It Original",
                        "012345678905"));
    }

    @Test
    public void usdaBrandOwnerUsedWhenBrandNameEmpty() {
        // MainActivity resolves brandName-then-brandOwner before calling;
        // resolve just formats whatever brand string it receives.
        assertEquals("BREAD (GRATEFUL)",
                BarcodeSeed.resolve("BREAD", "GRATEFUL", "", "012345678905"));
    }

    @Test
    public void rawGtinWhenEverythingMisses() {
        assertEquals("038000265013",
                BarcodeSeed.resolve("", "", "", "038000265013"));
    }

    @Test
    public void nullsAreSafe() {
        assertEquals("", BarcodeSeed.resolve(null, null, null, null));
        assertEquals("038000265013",
                BarcodeSeed.resolve(null, null, null, "038000265013"));
    }
}
