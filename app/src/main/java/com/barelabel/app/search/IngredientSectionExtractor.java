package com.barelabel.app.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Android-free extraction of the ingredients section from OCR'd label text.
 * Kept out of the activities so it stays unit-testable on the JVM (same
 * split as OcrCorrector / TitleMatchScorer).
 *
 * <p>Why this exists: a label scan captures the whole package — storage
 * instructions, disclaimers, manufacturer addresses — and the verdict must
 * not run on that text (real example: "Lock the pack perfectly" OCR'd as
 * "Lock the pork perfectly" flagged a vegetarian mithai for pork). This
 * isolates the span between the ingredients header and the next section.
 *
 * <p>How: ML Kit gives per-line bounding boxes (no font/style info), so
 * section boundaries are detected by (1) known section keywords
 * ("allergen", "storage", "manufactured", …), and (2) as a backstop, a
 * large vertical gap into a short all-caps header line. Falls back to the
 * full text when no ingredients header is found.
 */
public final class IngredientSectionExtractor {

    /** One OCR'd line with its vertical position (px, image coordinates). */
    public static final class OcrLine {
        public final String text;
        public final int top;
        public final int bottom;

        public OcrLine(String text, int top, int bottom) {
            this.text = text == null ? "" : text;
            this.top = top;
            this.bottom = bottom;
        }
    }

    /** Section headers that terminate the ingredients section. */
    private static final String[] STOP_KEYWORDS = {
            "allergen", "allergy", "storage", "disclaimer",
            "manufactured", "marketed", "distributed", "imported",
            "nutrition", "directions", "warning", "net wt", "net weight",
            "best before", "expiry", "customer care", "keep in", "shelf life",
    };

    private IngredientSectionExtractor() {
    }

    /**
     * Returns the ingredients-section text. Never null; falls back to the
     * full joined text when no ingredients header is found (or nothing was
     * collected after it).
     */
    public static String extract(List<OcrLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        List<OcrLine> sorted = new ArrayList<>(lines);
        Collections.sort(sorted, (a, b) -> Integer.compare(a.top, b.top));

        int headerIdx = findHeader(sorted);
        if (headerIdx < 0) {
            return joinAll(sorted);
        }
        StringBuilder sb = new StringBuilder();
        String headerRest = afterHeader(sorted.get(headerIdx).text);
        if (!headerRest.isEmpty()) {
            sb.append(headerRest);
        }
        int medianGap = medianGap(sorted);
        OcrLine prev = sorted.get(headerIdx);
        for (int i = headerIdx + 1; i < sorted.size(); i++) {
            OcrLine line = sorted.get(i);
            String t = line.text.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (isSectionBreak(t, line, prev, medianGap, sb.length())) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
            prev = line;
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? joinAll(sorted) : out;
    }

    /** First line starting with "ingred", else first line containing it. */
    private static int findHeader(List<OcrLine> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).text.trim().toLowerCase(Locale.US);
            if (t.startsWith("ingred")) {
                return i;
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).text.toLowerCase(Locale.US).contains("ingred")) {
                return i;
            }
        }
        return -1;
    }

    /** Text after the header word on the header line itself, if any. */
    private static String afterHeader(String headerText) {
        String lower = headerText.toLowerCase(Locale.US);
        int h = lower.indexOf("ingred");
        if (h < 0) {
            return "";
        }
        int colon = headerText.indexOf(':', h);
        String rest;
        if (colon >= 0) {
            rest = headerText.substring(colon + 1);
        } else {
            int j = h + "ingred".length();
            while (j < headerText.length()
                    && Character.isLetter(headerText.charAt(j))) {
                j++;
            }
            rest = headerText.substring(j);
        }
        rest = rest.trim();
        while (!rest.isEmpty() && "-–—•".indexOf(rest.charAt(0)) >= 0) {
            rest = rest.substring(1).trim();
        }
        return rest;
    }

    private static boolean isSectionBreak(String text, OcrLine line, OcrLine prev,
                                          int medianGap, int collectedLen) {
        String lower = text.toLowerCase(Locale.US);
        for (String kw : STOP_KEYWORDS) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        // Backstop for unlabeled section changes: a big vertical gap into a
        // short all-caps header line, once real content was collected.
        if (collectedLen >= 30 && isAllCapsShort(text) && medianGap > 0) {
            int gap = line.top - prev.bottom;
            if (gap > 3 * medianGap) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllCapsShort(String text) {
        String[] words = text.split("\\s+");
        if (words.length == 0 || words.length > 6) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLowerCase(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int medianGap(List<OcrLine> lines) {
        List<Integer> gaps = new ArrayList<>();
        OcrLine prev = null;
        for (OcrLine l : lines) {
            if (l.text.trim().isEmpty()) {
                continue;
            }
            if (prev != null) {
                int g = l.top - prev.bottom;
                if (g > 0) {
                    gaps.add(g);
                }
            }
            prev = l;
        }
        if (gaps.isEmpty()) {
            return 0;
        }
        Collections.sort(gaps);
        return gaps.get(gaps.size() / 2);
    }

    private static String joinAll(List<OcrLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (OcrLine l : lines) {
            String t = l.text.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }
}
