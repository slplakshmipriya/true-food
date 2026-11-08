package com.barelabel.app.news;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FdaRecallMatcher / FdaRecordParser / FdaMetaFormatter: Contract
 * §3.2 matching, §4 FDA filter/order/cap and §6.4 FDA formatting,
 * pinned against the NA-00 fixture at reference date 2026-11-08.
 */
public class FdaRecallMatcherTest {

    private static final int REFERENCE = 20261108;

    // ------------------------------------------------------------------
    // Fixture loading (mirrors UsdaNutritionParserTest)
    // ------------------------------------------------------------------

    private static List<FdaRecord> fixtureRecords() throws Exception {
        InputStream is = FdaRecallMatcherTest.class
                .getResourceAsStream("/fda_strawberry.json");
        assertNotNull("fda fixture on test classpath", is);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        is.close();
        String body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        JSONArray results = new JSONObject(body).getJSONArray("results");
        List<FdaRecord> records = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            records.add(FdaRecordParser.parse(results.getJSONObject(i)));
        }
        return records;
    }

    private static FdaRecord record(String firm, String product,
                                    String reason, String classification,
                                    String status, int reportDate,
                                    String eventId, String recallNumber) {
        return new FdaRecord(firm, product, reason, classification,
                status, reportDate, eventId, recallNumber);
    }

    private static boolean hasRecall(List<SafetyAlert> alerts,
                                     String recallNumber) {
        for (SafetyAlert alert : alerts) {
            if (recallNumber.equals(alert.recallNumber)) {
                return true;
            }
        }
        return false;
    }

    private static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Fixture expectations (§9: R1/R2 kept, R3/R4/R5 dropped)
    // ------------------------------------------------------------------

    @Test
    public void fixture_strawberry_keepsExactlyR1ThenR2() throws Exception {
        List<SafetyAlert> alerts = FdaRecallMatcher.match(
                fixtureRecords(), NewsText.queryTokens("strawberry"),
                REFERENCE);

        assertEquals(2, alerts.size());

        SafetyAlert r1 = alerts.get(0);
        assertEquals(SafetyAlert.Tier.FDA, r1.tier);
        assertEquals("Fresh Breeze Farms", r1.headline);
        assertEquals("Class I", r1.classification);
        assertEquals("Ongoing", r1.status);
        assertEquals("98241", r1.eventId);
        assertEquals("F-0451-2026", r1.recallNumber);
        assertEquals("FDA recall", r1.sourceLabel);
        assertEquals("Class I \u00b7 Reported Nov 2, 2026 \u00b7 Ongoing",
                r1.metaLine);
        assertEquals("https://www.fda.gov/safety/"
                + "recalls-market-withdrawals-safety-alerts"
                + "?search_api_fulltext=F-0451-2026", r1.link);
        assertNotNull(r1.productText);
        assertTrue(r1.productText.contains("Strawberries"));
        assertNotNull(r1.reasonText);
        assertTrue(r1.reasonText.toLowerCase().contains("hepatitis a"));

        SafetyAlert r2 = alerts.get(1);
        assertEquals("Sunny Valley Produce LLC", r2.headline);
        assertEquals("Class II", r2.classification);
        assertEquals("F-0438-2026", r2.recallNumber);
        assertEquals("Class II \u00b7 Reported Oct 15, 2026 \u00b7 Completed",
                r2.metaLine);

        // R3 (Terminated, old), R4 (Completed, 120 days old) and
        // R5 (different food) are all absent.
        assertFalse(hasRecall(alerts, "F-1987-2025"));
        assertFalse(hasRecall(alerts, "F-1675-2026"));
        assertFalse(hasRecall(alerts, "F-0449-2026"));
    }

    @Test
    public void parser_readsFixtureFields() throws Exception {
        List<FdaRecord> records = fixtureRecords();
        assertEquals(5, records.size());
        FdaRecord r1 = records.get(0);
        assertEquals("Fresh Breeze Farms", r1.recallingFirm);
        assertEquals("Class I", r1.classification);
        assertEquals("Ongoing", r1.status);
        assertEquals(20261102, r1.reportDateYyyymmdd);
        assertEquals("98241", r1.eventId);
        assertEquals("F-0451-2026", r1.recallNumber);
    }

    @Test
    public void parser_missingFieldsYieldSafeDefaults() throws Exception {
        FdaRecord empty = FdaRecordParser.parse(new JSONObject());
        assertEquals("", empty.recallingFirm);
        assertEquals("", empty.productDescription);
        assertEquals("", empty.reasonForRecall);
        assertEquals("", empty.classification);
        assertEquals("", empty.status);
        assertEquals(0, empty.reportDateYyyymmdd);
        assertEquals("", empty.eventId);
        assertEquals("", empty.recallNumber);

        FdaRecord fromNull = FdaRecordParser.parse(null);
        assertNotNull(fromNull);
        assertEquals("", fromNull.recallingFirm);
        assertEquals(0, fromNull.reportDateYyyymmdd);
    }

    @Test
    public void metaFormatter_formatsContractExample() {
        assertEquals("Class I \u00b7 Reported Nov 2, 2026 \u00b7 Ongoing",
                FdaMetaFormatter.format("Class I", 20261102, "Ongoing"));
        assertEquals("Nov 2, 2026", FdaMetaFormatter.formatDate(20261102));
        assertEquals("Jan 5, 2026", FdaMetaFormatter.formatDate(20260105));
        assertEquals("Dec 25, 2026", FdaMetaFormatter.formatDate(20261225));
    }

    // ------------------------------------------------------------------
    // Whole-token and folding rules (§3.2 / §3.1)
    // ------------------------------------------------------------------

    @Test
    public void wholeToken_berryDoesNotMatchStrawberries() {
        List<FdaRecord> records = Arrays.asList(record(
                "Some Farm", "Fresh Strawberries, 16 oz", "",
                "Class I", "Ongoing", 20261101, "1", "F-0001-2026"));
        List<SafetyAlert> alerts = FdaRecallMatcher.match(
                records, NewsText.queryTokens("berry"), REFERENCE);
        assertTrue(alerts.isEmpty());
    }

    @Test
    public void pluralFold_queryStrawberriesMatchesStrawberryRecord() {
        List<FdaRecord> records = Arrays.asList(record(
                "Some Farm", "Fresh Strawberry Jam", "",
                "Class I", "Ongoing", 20261101, "1", "F-0001-2026"));
        List<SafetyAlert> alerts = FdaRecallMatcher.match(
                records, NewsText.queryTokens("strawberries"), REFERENCE);
        assertEquals(1, alerts.size());
    }

    @Test
    public void match_canComeFromReasonForRecall() {
        List<FdaRecord> records = Arrays.asList(record(
                "Some Farm", "Fresh Fruit Cups", "May contain strawberry",
                "Class II", "Ongoing", 20261101, "1", "F-0001-2026"));
        List<SafetyAlert> alerts = FdaRecallMatcher.match(
                records, NewsText.queryTokens("strawberry"), REFERENCE);
        assertEquals(1, alerts.size());
    }

    @Test
    public void chickenQuery_matchesPoultryRecordViaMap() {
        List<FdaRecord> records = Arrays.asList(record(
                "Some Farm", "Frozen Poultry Breasts", "",
                "Class I", "Ongoing", 20261101, "1", "F-0001-2026"));
        List<SafetyAlert> alerts = FdaRecallMatcher.match(
                records, NewsText.queryTokens("chicken"), REFERENCE);
        assertEquals(1, alerts.size());
        assertEquals("Some Farm", alerts.get(0).headline);
    }

    // ------------------------------------------------------------------
    // Filter (§4, literal): Ongoing OR within 90 days
    // ------------------------------------------------------------------

    @Test
    public void recentTerminated_isKept() {
        // Terminated but only 10 days old (2026-10-29): kept.
        List<FdaRecord> records = Arrays.asList(record(
                "Some Farm", "Strawberry Jam", "",
                "Class II", "Terminated", 20261029, "1", "F-0001-2026"));
        List<SafetyAlert> alerts = FdaRecallMatcher.match(
                records, NewsText.queryTokens("strawberry"), REFERENCE);
        assertEquals(1, alerts.size());
    }

    @Test
    public void oldTerminated_isDropped() {
        // Terminated and 100 days old (2026-07-31): dropped.
        List<FdaRecord> records = Arrays.asList(record(
                "Some Farm", "Strawberry Jam", "",
                "Class II", "Terminated", 20260731, "1", "F-0001-2026"));
        List<SafetyAlert> alerts = FdaRecallMatcher.match(
                records, NewsText.queryTokens("strawberry"), REFERENCE);
        assertTrue(alerts.isEmpty());
    }

    @Test
    public void completedExactly90DaysOld_isKept() {
        // 2026-08-10 is exactly 90 days before 2026-11-08.
        List<FdaRecord> records = Arrays.asList(record(
                "Some Farm", "Strawberry Jam", "",
                "Class II", "Completed", 20260810, "1", "F-0001-2026"));
        List<SafetyAlert> alerts = FdaRecallMatcher.match(
                records, NewsText.queryTokens("strawberry"), REFERENCE);
        assertEquals(1, alerts.size());
    }

    @Test
    public void completed91DaysOld_isDropped() {
        // 2026-08-09 is 91 days before 2026-11-08.
        List<FdaRecord> records = Arrays.asList(record(
                "Some Farm", "Strawberry Jam", "",
                "Class II", "Completed", 20260809, "1", "F-0001-2026"));
        List<SafetyAlert> alerts = FdaRecallMatcher.match(
                records, NewsText.queryTokens("strawberry"), REFERENCE);
        assertTrue(alerts.isEmpty());
    }

    @Test
    public void ongoingOfAnyAge_isKeptAndStatusIsCaseInsensitive() {
        List<FdaRecord> records = Arrays.asList(record(
                "Some Farm", "Strawberry Jam", "",
                "Class II", "ongoing", 20250101, "1", "F-0001-2026"));
        List<SafetyAlert> alerts = FdaRecallMatcher.match(
                records, NewsText.queryTokens("strawberry"), REFERENCE);
        assertEquals(1, alerts.size());
    }

    @Test
    public void futureDatedNonOngoing_isDropped() {
        List<FdaRecord> records = Arrays.asList(record(
                "Some Farm", "Strawberry Jam", "",
                "Class II", "Completed", 20261201, "1", "F-0001-2026"));
        List<SafetyAlert> alerts = FdaRecallMatcher.match(
                records, NewsText.queryTokens("strawberry"), REFERENCE);
        assertTrue(alerts.isEmpty());
    }

    // ------------------------------------------------------------------
    // Ordering and cap (§4)
    // ------------------------------------------------------------------

    @Test
    public void order_classRankThenReportDateDesc() {
        List<FdaRecord> records = Arrays.asList(
                record("Firm III", "Strawberry Jam", "", "Class III",
                        "Ongoing", 20261107, "3", "F-0003-2026"),
                record("Firm II newer", "Strawberry Jam", "", "Class II",
                        "Ongoing", 20261106, "2", "F-0002-2026"),
                record("Firm I older", "Strawberry Jam", "", "Class I",
                        "Ongoing", 20261001, "1", "F-0001-2026"),
                record("Firm II older", "Strawberry Jam", "", "Class II",
                        "Ongoing", 20261101, "4", "F-0004-2026"),
                record("Firm Unknown", "Strawberry Jam", "", "",
                        "Ongoing", 20261107, "5", "F-0005-2026"));
        List<SafetyAlert> alerts = FdaRecallMatcher.match(
                records, NewsText.queryTokens("strawberry"), REFERENCE);
        assertEquals(5, alerts.size());
        assertEquals("Firm I older", alerts.get(0).headline);
        assertEquals("Firm II newer", alerts.get(1).headline);
        assertEquals("Firm II older", alerts.get(2).headline);
        assertEquals("Firm III", alerts.get(3).headline);
        assertEquals("Firm Unknown", alerts.get(4).headline);
        // Missing classification surfaces as null on the alert.
        assertNull(alerts.get(4).classification);
    }

    @Test
    public void cap_sixOngoingMatchesYieldFiveInOrder() {
        List<FdaRecord> records = new ArrayList<>();
        // Six Class I ongoing records, dates 2026-11-01 .. 2026-11-06.
        for (int i = 1; i <= 6; i++) {
            records.add(record("Firm " + i, "Strawberry Jam", "",
                    "Class I", "Ongoing", 20261100 + i,
                    String.valueOf(i), "F-000" + i + "-2026"));
        }
        List<SafetyAlert> alerts = FdaRecallMatcher.match(
                records, NewsText.queryTokens("strawberry"), REFERENCE);
        assertEquals(5, alerts.size());
        // Newest first; the oldest (Firm 1, Nov 1) is capped out.
        assertEquals("Firm 6", alerts.get(0).headline);
        assertEquals("Firm 5", alerts.get(1).headline);
        assertEquals("Firm 4", alerts.get(2).headline);
        assertEquals("Firm 3", alerts.get(3).headline);
        assertEquals("Firm 2", alerts.get(4).headline);
        assertFalse(hasRecall(alerts, "F-0001-2026"));
    }

    // ------------------------------------------------------------------
    // Trimming (§6.4)
    // ------------------------------------------------------------------

    @Test
    public void trimTo220_shortTextUnchanged() {
        assertEquals("Strawberry Jam", FdaRecallMatcher.trimTo220("Strawberry Jam"));
        assertEquals("", FdaRecallMatcher.trimTo220(""));
        assertNull(FdaRecallMatcher.trimTo220(null));
    }

    @Test
    public void trimTo220_longTextNeverExceeds220AndEndsWithEllipsis() {
        String longText = "strawberry " + repeat("abcdefghij", 40);
        String trimmed = FdaRecallMatcher.trimTo220(longText);
        assertTrue(trimmed.length() <= 220);
        assertTrue(trimmed.endsWith("\u2026"));
    }

    @Test
    public void trimTo220_noSpacesCutsAtExactly220() {
        String trimmed = FdaRecallMatcher.trimTo220(repeat("a", 300));
        assertEquals(220, trimmed.length());
        assertTrue(trimmed.endsWith("\u2026"));
    }

    @Test
    public void alertProductAndReasonAreTrimmedTo220() {
        String longProduct = "strawberry " + repeat("word ", 80);
        String longReason = repeat("reason ", 80) + "strawberry";
        List<FdaRecord> records = Arrays.asList(record(
                "Some Farm", longProduct, longReason,
                "Class I", "Ongoing", 20261101, "1", "F-0001-2026"));
        List<SafetyAlert> alerts = FdaRecallMatcher.match(
                records, NewsText.queryTokens("strawberry"), REFERENCE);
        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).productText.length() <= 220);
        assertTrue(alerts.get(0).productText.endsWith("\u2026"));
        assertTrue(alerts.get(0).reasonText.length() <= 220);
        assertTrue(alerts.get(0).reasonText.endsWith("\u2026"));
    }

    @Test
    public void emptyInputsYieldEmptyList() {
        assertTrue(FdaRecallMatcher.match(null,
                NewsText.queryTokens("strawberry"), REFERENCE).isEmpty());
        assertTrue(FdaRecallMatcher.match(new ArrayList<FdaRecord>(),
                NewsText.queryTokens("strawberry"), REFERENCE).isEmpty());
        List<FdaRecord> records = Arrays.asList(record(
                "Some Farm", "Strawberry Jam", "",
                "Class I", "Ongoing", 20261101, "1", "F-0001-2026"));
        assertTrue(FdaRecallMatcher.match(records, null, REFERENCE).isEmpty());
    }
}
