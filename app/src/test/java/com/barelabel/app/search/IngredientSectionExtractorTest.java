package com.barelabel.app.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.barelabel.app.search.IngredientSectionExtractor.OcrLine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class IngredientSectionExtractorTest {

    private static List<OcrLine> lines(Object[]... rows) {
        List<OcrLine> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new OcrLine((String) r[0], (Integer) r[1], (Integer) r[2]));
        }
        return out;
    }

    @Test
    public void badushaScan_stopsBeforeAllergenSection() {
        List<OcrLine> in = lines(
                new Object[]{"Bliss", 0, 20},
                new Object[]{"tree", 25, 45},
                new Object[]{"INGREDIENTS", 50, 70},
                new Object[]{"MITHAI MAGIC", 75, 95},
                new Object[]{"BADUSHA", 100, 120},
                new Object[]{"AM purpose Fou, yogurt, Ghee, Sugr &milk", 125, 145},
                new Object[]{"Allergen Advice: This product Was processed in a", 160, 180},
                new Object[]{"focilty that also", 185, 205},
                new Object[]{"Lock the pork perecty to secure the feshness", 300, 320});
        String out = IngredientSectionExtractor.extract(in);
        assertEquals("MITHAI MAGIC BADUSHA AM purpose Fou, yogurt, Ghee, Sugr &milk", out);
        assertFalse(out.toLowerCase().contains("pork"));
        assertFalse(out.toLowerCase().contains("allergen"));
    }

    @Test
    public void inlineIngredientsAfterColon() {
        List<OcrLine> in = lines(
                new Object[]{"Ingredients: flour, sugar, salt", 0, 20},
                new Object[]{"Storage: cool dry place", 30, 50});
        assertEquals("flour, sugar, salt",
                IngredientSectionExtractor.extract(in));
    }

    @Test
    public void noHeader_fallsBackToFullText() {
        List<OcrLine> in = lines(
                new Object[]{"Net wt 200g", 0, 20},
                new Object[]{"Best before 2025", 30, 50});
        assertEquals("Net wt 200g Best before 2025",
                IngredientSectionExtractor.extract(in));
    }

    @Test
    public void emptyInput_returnsEmpty() {
        assertEquals("", IngredientSectionExtractor.extract(new ArrayList<>()));
        assertEquals("", IngredientSectionExtractor.extract(null));
    }

    @Test
    public void gapBackstop_stopsAtUnlabeledHeader() {
        List<OcrLine> in = lines(
                new Object[]{"INGREDIENTS", 0, 20},
                new Object[]{"flour, water, salt, yeast, sugar, oil, milk and butter", 25, 45},
                new Object[]{"NUTRITION", 200, 220},
                new Object[]{"per 100g: 250 kcal", 225, 245});
        assertEquals("flour, water, salt, yeast, sugar, oil, milk and butter",
                IngredientSectionExtractor.extract(in));
    }

    @Test
    public void headerWordCaseInsensitive() {
        List<OcrLine> in = lines(
                new Object[]{"ingredients", 0, 20},
                new Object[]{"oats, honey", 25, 45});
        String out = IngredientSectionExtractor.extract(in);
        assertTrue(out.contains("oats"));
    }
}
