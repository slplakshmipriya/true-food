package com.barelabel.app.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * ServerSpellChecker: JSON escaping and response parsing.
 * The network call itself is not unit-tested (needs a live server).
 */
public class ServerSpellCheckerTest {

    @Test
    public void parseCorrected_extractsField() {
        String json = "{\"corrected\":\"all purpose flour, yogurt\","
                + "\"corrections\":[{\"original\":\"Fou\",\"corrected\":\"flour\"}]}";
        assertEquals("all purpose flour, yogurt",
                ServerSpellChecker.parseCorrected(json));
    }

    @Test
    public void parseCorrected_handlesEscapes() {
        String json = "{\"corrected\":\"a \\\"quoted\\\" word\\nnewline\"}";
        assertEquals("a \"quoted\" word\nnewline",
                ServerSpellChecker.parseCorrected(json));
    }

    @Test
    public void parseCorrected_missingField() {
        assertNull(ServerSpellChecker.parseCorrected("{\"corrections\":[]}"));
        assertNull(ServerSpellChecker.parseCorrected("not json"));
        assertNull(ServerSpellChecker.parseCorrected(null));
        assertNull(ServerSpellChecker.parseCorrected("{\"corrected\":}"));
    }

    @Test
    public void parseCorrected_ignoresOtherFields() {
        // "corrected" appearing inside corrections[] must not confuse it;
        // first occurrence wins and it is the top-level one here.
        String json = "{\"corrected\":\"sugar\",\"error\":null}";
        assertEquals("sugar", ServerSpellChecker.parseCorrected(json));
    }

    @Test
    public void jsonEscape_escapesSpecials() {
        assertEquals("a\\\"b\\\\c\\nd",
                ServerSpellChecker.jsonEscape("a\"b\\c\nd"));
        assertEquals("plain", ServerSpellChecker.jsonEscape("plain"));
    }

    @Test
    public void correct_nullOrEmpty_returnsNull() {
        assertNull(ServerSpellChecker.correct(null));
        assertNull(ServerSpellChecker.correct(""));
    }
}
