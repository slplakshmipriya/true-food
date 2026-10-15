package com.barelabel.app.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * JVM tests for the llm package: response parsing and payload building.
 * Network calls themselves are not tested (null-safe by contract).
 */
public class LlmClientTest {

    @Test
    public void parseContentExtractsAssistantText() {
        String json = "{\"id\":\"chatcmpl-1\",\"choices\":[{\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"Hello \\\"there\\\"\"}}]}";
        assertEquals("Hello \"there\"", GroqClient.parseContent(json));
    }

    @Test
    public void parseContentHandlesNullAndMalformed() {
        assertNull(GroqClient.parseContent(null));
        assertNull(GroqClient.parseContent("{}"));
        assertNull(GroqClient.parseContent(
                "{\"choices\":[{\"message\":{\"content\":null}}]}"));
    }

    @Test
    public void jsonEscapeHandlesQuotesAndNewlines() {
        assertEquals("a\\\"b\\\\c\\nd", GroqClient.jsonEscape("a\"b\\c\nd"));
    }

    @Test
    public void buildPayloadHasExpectedShape() {
        String payload = VerdictExplainer.buildPayload(false, 79,
                Arrays.asList(new VerdictExplainer.Flag("all purpose flour",
                        "Gluten")),
                "all purpose flour, yogurt",
                Arrays.asList("gluten", "dairy"),
                Collections.emptyList(),
                "processed near wheat");
        assertTrue(payload.contains("\"verdict\":\"DIRTY\""));
        assertTrue(payload.contains("\"clean_score\":79"));
        assertTrue(payload.contains(
                "\"ingredient\":\"all purpose flour\",\"category\":\"Gluten\""));
        assertTrue(payload.contains("\"filters\":[\"gluten\",\"dairy\"]"));
        assertTrue(payload.contains(
                "\"allergen_advice\":\"processed near wheat\""));
    }

    @Test
    public void buildPayloadToleratesNulls() {
        String payload = VerdictExplainer.buildPayload(true, 95, null, null,
                null, null, null);
        assertTrue(payload.contains("\"verdict\":\"CLEAN\""));
        assertTrue(payload.contains("\"flagged\":[]"));
    }

    @Test
    public void promptsAreNonEmpty() {
        assertTrue(LlmPrompts.EXPLAIN_SYSTEM.length() > 100);
        assertTrue(LlmPrompts.SPELLCHECK_SYSTEM.length() > 100);
    }
}
