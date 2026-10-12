package com.barelabel.app.sharing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** ShareLinks: share URL building, deep-link parsing, share text. */
public class ShareLinksTest {

    @Test
    public void productUrl_canonicalForm() {
        assertEquals("https://barelabel.online/p/012345678905",
                ShareLinks.productUrl("012345678905"));
    }

    @Test
    public void productUrl_rejectsBadGtin() {
        try {
            ShareLinks.productUrl("abc");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
        try {
            ShareLinks.productUrl("1234567"); // 7 digits: too short
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void productUrl_withParams_carriesOgHints() {
        String url = ShareLinks.productUrl("012345678905", true, 85, 0,
                "Dave's Bread", "https://img.example/p.jpg");
        assertTrue(url.startsWith("https://barelabel.online/p/012345678905?"));
        assertTrue(url.contains("v=clean"));
        assertTrue(url.contains("score=85"));
        assertTrue(url.contains("flagged=0"));
        assertTrue(url.contains("n=Dave%27s+Bread"));
        assertTrue(url.contains("img=https%3A%2F%2Fimg.example%2Fp.jpg"));
    }

    @Test
    public void extractGtin_validLink() {
        assertEquals("012345678905",
                ShareLinks.extractGtin("barelabel.online", "/p/012345678905"));
    }

    @Test
    public void extractGtin_caseInsensitiveHost() {
        assertEquals("012345678905",
                ShareLinks.extractGtin("BareLabel.App", "/p/012345678905"));
    }

    @Test
    public void extractGtin_rejectsWrongHost() {
        assertNull(ShareLinks.extractGtin("evil.com", "/p/012345678905"));
    }

    @Test
    public void extractGtin_rejectsWrongPath() {
        assertNull(ShareLinks.extractGtin("barelabel.online", "/q/012345678905"));
        assertNull(ShareLinks.extractGtin("barelabel.online", "/"));
    }

    @Test
    public void extractGtin_rejectsNonNumericGtin() {
        assertNull(ShareLinks.extractGtin("barelabel.online", "/p/abc"));
    }

    @Test
    public void extractGtin_rejectsTrailingSegments() {
        assertNull(
                ShareLinks.extractGtin("barelabel.online", "/p/012345678905/extra"));
    }

    @Test
    public void extractGtin_rejectsNulls() {
        assertNull(ShareLinks.extractGtin(null, "/p/012345678905"));
        assertNull(ShareLinks.extractGtin("barelabel.online", null));
    }

    @Test
    public void shareText_clean() {
        String text = ShareLinks.shareText("Dave's Bread", "Dave's Inc.",
                true, 85, 0, "https://barelabel.online/p/012345678905");
        assertTrue(text.startsWith("*Dave's Bread*\n"));
        assertTrue(text.contains("Dave's Inc. · ✓ CLEAN · 85/100"));
        assertTrue(text.contains("https://barelabel.online/p/012345678905"));
        assertTrue(!text.contains("via BareLabel"));
    }

    @Test
    public void shareText_titleCasesAllCapsName() {
        String text = ShareLinks.shareText("MULTIGRAIN ORGANIC BREAD, MULTIGRAIN",
                "SIMPLY BALANCED", true, 100, 0, "https://barelabel.online/p/1");
        assertTrue(text.startsWith("*Multigrain Organic Bread, Multigrain*\n"));
        assertTrue(text.contains("Simply Balanced · ✓ CLEAN · 100/100"));
    }

    @Test
    public void shareText_compactTwoLines() {
        String text = ShareLinks.shareText("Chips", null,
                false, 40, 3, "https://barelabel.online/p/012345678905");
        String[] parts = text.split("\n\n");
        assertEquals(2, parts.length);
        assertTrue(parts[0].contains("⚠ DIRTY · 3 flagged ingredients · 40/100"));
    }

    @Test
    public void shareText_dirtyPluralAndSingular() {
        String plural = ShareLinks.shareText("Chips", null,
                false, 40, 3, "https://barelabel.online/p/012345678905");
        assertTrue(plural.contains("DIRTY"));
        assertTrue(plural.contains("3 flagged ingredients"));

        String singular = ShareLinks.shareText("Chips", null,
                false, 70, 1, "https://barelabel.online/p/012345678905");
        assertTrue(singular.contains("1 flagged ingredient"));
    }

    @Test
    public void shareText_noScoreWhenUnavailable() {
        String text = ShareLinks.shareText("Chips", null,
                true, -1, 0, "https://barelabel.online/p/012345678905");
        assertTrue(!text.contains("/100"));
    }

    @Test
    public void displayName_titleCasesAllCaps() {
        assertEquals("Multigrain Organic Bread, Multigrain",
                ShareLinks.displayName("MULTIGRAIN ORGANIC BREAD, MULTIGRAIN"));
    }

    @Test
    public void displayName_leavesMixedCaseAlone() {
        assertEquals("Dave's Bread", ShareLinks.displayName("Dave's Bread"));
        assertEquals("McDonald's Fries",
                ShareLinks.displayName("McDonald's Fries"));
    }

    @Test
    public void displayName_nullAndEmpty() {
        assertEquals("Unknown product", ShareLinks.displayName(null));
        assertEquals("Unknown product", ShareLinks.displayName(""));
    }

    @Test
    public void productUrl_withMerchants_carriesMParam() {
        java.util.List<String> merchants =
                java.util.Arrays.asList("Amazon", "Walmart", "Target");
        String url = ShareLinks.productUrl("012345678905", true, 85, 0,
                "Bread", null, merchants);
        assertTrue(url.contains("m=Amazon%2CWalmart%2CTarget"));
    }

    @Test
    public void productUrl_nullMerchants_omitsMParam() {
        String url = ShareLinks.productUrl("012345678905", true, 85, 0,
                "Bread", null, null);
        assertTrue(!url.contains("&m="));
    }

    @Test
    public void productUrl_titleCasesNameParam() {
        String url = ShareLinks.productUrl("012345678905", true, 85, 0,
                "MULTIGRAIN BREAD", null);
        assertTrue(url.contains("n=Multigrain+Bread"));
    }

    @Test
    public void productUrl_nullName_omitsNParam() {
        String url = ShareLinks.productUrl("012345678905", true, 85, 0,
                null, null, null);
        assertTrue(!url.contains("&n="));
        assertTrue(url.contains("v=clean"));
        assertTrue(url.contains("score=85"));
    }
}
