package com.barelabel.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.barelabel.app.nutrition.NutrientComparison;
import com.barelabel.app.nutrition.NutrientDefinition;
import com.barelabel.app.nutrition.NutritionAnalysis;
import com.barelabel.app.nutrition.NutritionConfig;
import com.barelabel.app.nutrition.NutritionStatus;
import com.barelabel.app.nutrition.RankingProfile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NutritionPanelPartition: the pure outstanding-vs-remaining logic behind
 * the nutrition detail panel (Contract §7.1, PRD §10.2).
 *
 * Outstanding = flagged nutrients ("Needs attention", config order) plus
 * the top favorable non-flagged nutrients ("Strengths": favorable
 * percentile in the green band, §7.1 bar colors, best first, capped).
 * Everything else that was actually reported lands in remaining, in
 * config order, with no key appearing in two lists. Unreported and
 * non-displayed nutrients appear nowhere (missing is never zero).
 */
public class NutritionPanelPartitionTest {

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private static NutrientDefinition def(String key, String direction,
                                          boolean display) {
        return new NutrientDefinition(key, Collections.singletonList(9999),
                key, "g", direction, display, true, true, 0.10, key,
                0.0, 5000.0, 0.0, 100.0, null, null, null, null, null,
                null, null);
    }

    private static NutritionConfig config(NutrientDefinition... defs) {
        Map<String, NutrientDefinition> byKey = new LinkedHashMap<>();
        for (NutrientDefinition d : defs) {
            byKey.put(d.key, d);
        }
        return new NutritionConfig(1, 0.70, 0.30, 10, 20, 3, 5,
                0.50, 0.65, 0.35, 3, 60, byKey,
                Collections.<String, RankingProfile>emptyMap());
    }

    private static NutrientComparison comp(String key, Double per100,
                                           Double favorable,
                                           boolean flagged) {
        return new NutrientComparison(key, per100, null, 12, null,
                favorable == null ? null : 100.0 - favorable, favorable,
                null, null, flagged, flagged ? "severe" : "");
    }

    private static NutritionAnalysis analysis(NutrientComparison... comps) {
        Map<String, NutrientComparison> byKey = new LinkedHashMap<>();
        List<String> flagged = new ArrayList<>();
        for (NutrientComparison c : comps) {
            byKey.put(c.key, c);
            if (c.flagged) {
                flagged.add(c.key);
            }
        }
        return new NutritionAnalysis(NutritionStatus.CONCERNING, 40, 1.0,
                "Snacks", 12, byKey, flagged);
    }

    private static List<String> keys(List<NutrientComparison> comps) {
        List<String> out = new ArrayList<>();
        for (NutrientComparison c : comps) {
            out.add(c.key);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Partitioning
    // ------------------------------------------------------------------

    @Test
    public void flagged_goToNeedsAttentionInConfigOrder_notRepeated() {
        NutritionConfig cfg = config(
                def("sodium", "higher_bad", true),
                def("fiber", "higher_good", true),
                def("protein", "higher_good", true));
        NutritionAnalysis a = analysis(
                comp("protein", 7.0, 90.0, false),
                comp("fiber", 1.0, 5.0, true),
                comp("sodium", 900.0, 10.0, true));
        NutritionPanelPartition p = NutritionPanelPartition.partition(a, cfg);
        assertEquals(Arrays.asList("sodium", "fiber"), keys(p.needsAttention));
        assertEquals(Collections.singletonList("protein"), keys(p.strengths));
        assertTrue(p.remaining.isEmpty());
    }

    @Test
    public void strengths_topFavorableFirst_capped_greenBandOnly() {
        NutritionConfig cfg = config(
                def("a", "higher_good", true),
                def("b", "higher_good", true),
                def("c", "higher_good", true),
                def("d", "higher_good", true),
                def("e", "higher_good", true),
                def("f", "higher_good", true));
        NutritionAnalysis a = analysis(
                comp("a", 1.0, 80.0, false),
                comp("b", 1.0, 99.0, false),
                comp("c", 1.0, 90.0, false),
                comp("d", 1.0, 95.0, false),
                comp("e", 1.0, 67.0, false),
                comp("f", 1.0, 66.0, false));
        NutritionPanelPartition p = NutritionPanelPartition.partition(a, cfg);
        // Best first, capped at MAX_STRENGTHS; 67 is the green-band floor.
        assertEquals(Arrays.asList("b", "d", "c"), keys(p.strengths));
        // The rest stay in remaining, in config order.
        assertEquals(Arrays.asList("a", "e", "f"), keys(p.remaining));
        assertTrue(p.needsAttention.isEmpty());
    }

    @Test
    public void strengths_equalPercentiles_configOrderBreaksTies() {
        // Tie-break (NUT-11): four nutrients tied at favorable 90 plus
        // one at 95. The sort is stable over the config-ordered input,
        // so after the 95 the cap slots go to the earliest config-order
        // ties — c and d lose purely on config order, deterministically.
        NutritionConfig cfg = config(
                def("a", "higher_good", true),
                def("b", "higher_good", true),
                def("c", "higher_good", true),
                def("d", "higher_good", true),
                def("e", "higher_good", true));
        NutritionAnalysis a = analysis(
                comp("a", 1.0, 90.0, false),
                comp("b", 1.0, 90.0, false),
                comp("c", 1.0, 90.0, false),
                comp("d", 1.0, 90.0, false),
                comp("e", 1.0, 95.0, false));
        NutritionPanelPartition p = NutritionPanelPartition.partition(a, cfg);
        assertEquals(Arrays.asList("e", "a", "b"), keys(p.strengths));
        assertEquals(Arrays.asList("c", "d"), keys(p.remaining));
        assertTrue(p.needsAttention.isEmpty());
    }

    @Test
    public void strengths_excludeFlaggedEvenWhenFavorableHigh() {
        NutritionConfig cfg = config(
                def("sodium", "higher_bad", true),
                def("fiber", "higher_good", true));
        NutritionAnalysis a = analysis(
                comp("sodium", 900.0, 95.0, true),
                comp("fiber", 9.0, 90.0, false));
        NutritionPanelPartition p = NutritionPanelPartition.partition(a, cfg);
        assertEquals(Collections.singletonList("sodium"),
                keys(p.needsAttention));
        assertEquals(Collections.singletonList("fiber"), keys(p.strengths));
    }

    @Test
    public void remaining_configOrder_unreportedAndNonDisplayedExcluded() {
        NutritionConfig cfg = config(
                def("sodium", "higher_bad", true),
                def("fiber", "higher_good", true),
                def("protein", "higher_good", true),
                def("hidden", "higher_good", false));
        NutritionAnalysis a = analysis(
                comp("protein", 7.0, 50.0, false),
                comp("fiber", null, null, false),
                comp("sodium", 400.0, 50.0, false),
                comp("hidden", 3.0, 99.0, false));
        NutritionPanelPartition p = NutritionPanelPartition.partition(a, cfg);
        assertEquals(Arrays.asList("sodium", "protein"), keys(p.remaining));
        assertTrue(p.needsAttention.isEmpty());
        assertTrue(p.strengths.isEmpty());
        assertEquals(Arrays.asList("sodium", "protein"),
                keys(NutritionPanelPartition.reportedDisplayed(a, cfg)));
    }

    @Test
    public void noFavorable_neverAStrength_goesToRemaining() {
        NutritionConfig cfg = config(def("sodium", "higher_bad", true));
        NutritionAnalysis a = analysis(comp("sodium", 400.0, null, false));
        NutritionPanelPartition p = NutritionPanelPartition.partition(a, cfg);
        assertTrue(p.strengths.isEmpty());
        assertEquals(Collections.singletonList("sodium"), keys(p.remaining));
    }

    @Test
    public void nullAnalysis_emptyPartition() {
        NutritionPanelPartition p =
                NutritionPanelPartition.partition(null, config());
        assertTrue(p.needsAttention.isEmpty());
        assertTrue(p.strengths.isEmpty());
        assertTrue(p.remaining.isEmpty());
        assertTrue(NutritionPanelPartition.reportedDisplayed(null, config())
                .isEmpty());
    }

    @Test
    public void nullConfig_usesComparisonOrder() {
        NutritionAnalysis a = analysis(
                comp("fiber", 1.0, 5.0, true),
                comp("sodium", 400.0, 50.0, false));
        NutritionPanelPartition p = NutritionPanelPartition.partition(a, null);
        assertEquals(Collections.singletonList("fiber"),
                keys(p.needsAttention));
        assertEquals(Collections.singletonList("sodium"), keys(p.remaining));
    }

    // ------------------------------------------------------------------
    // Bar bands (Contract §7.1: green >= 67, amber 34-66, red <= 33)
    // ------------------------------------------------------------------

    @Test
    public void barBand_thresholds() {
        assertEquals(NutritionPanelPartition.BarBand.GREEN,
                NutritionPanelPartition.barBand(100.0));
        assertEquals(NutritionPanelPartition.BarBand.GREEN,
                NutritionPanelPartition.barBand(67.0));
        assertEquals(NutritionPanelPartition.BarBand.AMBER,
                NutritionPanelPartition.barBand(66.0));
        assertEquals(NutritionPanelPartition.BarBand.AMBER,
                NutritionPanelPartition.barBand(34.0));
        assertEquals(NutritionPanelPartition.BarBand.RED,
                NutritionPanelPartition.barBand(33.0));
        assertEquals(NutritionPanelPartition.BarBand.RED,
                NutritionPanelPartition.barBand(0.0));
    }

    // ------------------------------------------------------------------
    // Amount and peer-statement formatting
    // ------------------------------------------------------------------

    @Test
    public void formatAmount_unitsAndTrimming() {
        assertEquals("680 mg",
                NutritionPanelPartition.formatAmount(680.4, "mg"));
        assertEquals("536 kcal",
                NutritionPanelPartition.formatAmount(536.4, "kcal"));
        assertEquals("34 g",
                NutritionPanelPartition.formatAmount(34.0, "g"));
        assertEquals("2.1 g",
                NutritionPanelPartition.formatAmount(2.14, "g"));
        assertEquals("0 g",
                NutritionPanelPartition.formatAmount(0.0, "g"));
        assertEquals("", NutritionPanelPartition.formatAmount(null, "g"));
    }

    @Test
    public void peerStatement_rawPercentileAndPeerCount() {
        NutrientComparison withPeers = new NutrientComparison(
                "sodium", 680.0, null, 21, 410.0, 85.0, 15.0,
                null, null, true, "relative_floor");
        assertEquals("Higher than 85% of 21 similar products",
                NutritionPanelPartition.peerStatement(withPeers));
        NutrientComparison noPeers = new NutrientComparison(
                "sodium", 680.0, null, 3, 410.0, null, null,
                null, null, false, "");
        assertEquals("", NutritionPanelPartition.peerStatement(noPeers));
        assertEquals("", NutritionPanelPartition.peerStatement(null));
    }

    @Test
    public void peerStatement_edgePercentilesUseWordsNotImpossiblePercents() {
        // Percentile exactly 100: the value exceeds every peer.
        NutrientComparison top = new NutrientComparison(
                "fiber", 7.1, null, 75, 3.6, 100.0, 100.0,
                null, null, false, "");
        assertEquals("Higher than all 75 similar products",
                NutritionPanelPartition.peerStatement(top));
        // Percentile exactly 0: the value is below every peer.
        NutrientComparison bottom = new NutrientComparison(
                "sodium", 40.0, null, 75, 410.0, 0.0, 100.0,
                null, null, false, "");
        assertEquals("Lower than all 75 similar products",
                NutritionPanelPartition.peerStatement(bottom));
        // 99.6 rounds to 100 arithmetically but must print as 99%.
        NutrientComparison nearTop = new NutrientComparison(
                "sodium", 900.0, null, 112, 410.0, 99.6, 0.4,
                null, null, true, "relative_floor");
        assertEquals("Higher than 99% of 112 similar products",
                NutritionPanelPartition.peerStatement(nearTop));
        // 0.4 rounds to 0 arithmetically but must print as 1%.
        NutrientComparison nearBottom = new NutrientComparison(
                "total_sugars", 0.1, null, 112, 7.1, 0.4, 99.6,
                null, null, false, "");
        assertEquals("Higher than 1% of 112 similar products",
                NutritionPanelPartition.peerStatement(nearBottom));
    }
}
