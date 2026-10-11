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
        assertEquals("https://barelabel.app/p/012345678905",
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
        assertTrue(url.startsWith("https://barelabel.app/p/012345678905?"));
        assertTrue(url.contains("v=clean"));
        assertTrue(url.contains("score=85"));
        assertTrue(url.contains("flagged=0"));
        assertTrue(url.contains("n=Dave%27s+Bread"));
        assertTrue(url.contains("img=https%3A%2F%2Fimg.example%2Fp.jpg"));
    }

    @Test
    public void extractGtin_validLink() {
        assertEquals("012345678905",
                ShareLinks.extractGtin("barelabel.app", "/p/012345678905"));
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
        assertNull(ShareLinks.extractGtin("barelabel.app", "/q/012345678905"));
        assertNull(ShareLinks.extractGtin("barelabel.app", "/"));
    }

    @Test
    public void extractGtin_rejectsNonNumericGtin() {
        assertNull(ShareLinks.extractGtin("barelabel.app", "/p/abc"));
    }

    @Test
    public void extractGtin_rejectsTrailingSegments() {
        assertNull(
                ShareLinks.extractGtin("barelabel.app", "/p/012345678905/extra"));
    }

    @Test
    public void extractGtin_rejectsNulls() {
        assertNull(ShareLinks.extractGtin(null, "/p/012345678905"));
        assertNull(ShareLinks.extractGtin("barelabel.app", null));
    }

    @Test
    public void shareText_clean() {
        String text = ShareLinks.shareText("Dave's Bread", "Dave's Inc.",
                true, 85, 0, "https://barelabel.app/p/012345678905");
        assertTrue(text.contains("Dave's Bread (Dave's Inc.)"));
        assertTrue(text.contains("CLEAN"));
        assertTrue(text.contains("85/100"));
        assertTrue(text.contains("https://barelabel.app/p/012345678905"));
        assertTrue(text.contains("via BareLabel"));
    }

    @Test
    public void shareText_dirtyPluralAndSingular() {
        String plural = ShareLinks.shareText("Chips", null,
                false, 40, 3, "https://barelabel.app/p/012345678905");
        assertTrue(plural.contains("DIRTY"));
        assertTrue(plural.contains("3 flagged ingredients"));

        String singular = ShareLinks.shareText("Chips", null,
                false, 70, 1, "https://barelabel.app/p/012345678905");
        assertTrue(singular.contains("1 flagged ingredient"));
    }

    @Test
    public void shareText_noScoreWhenUnavailable() {
        String text = ShareLinks.shareText("Chips", null,
                true, -1, 0, "https://barelabel.app/p/012345678905");
        assertTrue(!text.contains("Clean Score"));
    }
}
