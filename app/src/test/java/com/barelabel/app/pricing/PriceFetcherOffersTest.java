package com.barelabel.app.pricing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * PriceFetcher.parseOffers: UPCitemdb offers -> cheapest-per-retailer list
 * for the alternate rows' price slot + retailer logo strip.
 */
public class PriceFetcherOffersTest {

    private static JSONObject offer(String merchant, String domain, double price,
                                    String availability, String link) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("merchant", merchant);
        o.put("domain", domain);
        o.put("price", price);
        o.put("currency", "USD");
        o.put("availability", availability);
        o.put("link", link);
        return o;
    }

    @Test
    public void nullOffers_returnsEmpty() {
        assertTrue(PriceFetcher.parseOffers(null).isEmpty());
    }

    @Test
    public void emptyOffers_returnsEmpty() throws Exception {
        assertTrue(PriceFetcher.parseOffers(new JSONArray()).isEmpty());
    }

    @Test
    public void filtersOutOfStockAndBadPrices() throws Exception {
        JSONArray offers = new JSONArray()
                .put(offer("Walmart", "walmart.com", 4.50, "", "https://x"))
                .put(offer("Target", "target.com", 3.00, "Out of Stock", "https://y"))
                .put(offer("Kroger", "kroger.com", 0, "", "https://z"));
        List<PriceFetcher.Offer> parsed = PriceFetcher.parseOffers(offers);
        assertEquals(1, parsed.size());
        assertEquals("Walmart", parsed.get(0).merchant);
    }

    @Test
    public void dedupesByDomain_keepingCheapest() throws Exception {
        JSONArray offers = new JSONArray()
                .put(offer("Walmart", "www.walmart.com", 5.00, "", "https://a"))
                .put(offer("Walmart Marketplace", "walmart.com", 3.25, "", "https://b"));
        List<PriceFetcher.Offer> parsed = PriceFetcher.parseOffers(offers);
        assertEquals(1, parsed.size());
        assertEquals(3.25, parsed.get(0).price, 0.001);
        assertEquals("https://b", parsed.get(0).link);
    }

    @Test
    public void sortsCheapestFirst() throws Exception {
        JSONArray offers = new JSONArray()
                .put(offer("A", "a.com", 9.99, "", "https://a"))
                .put(offer("B", "b.com", 2.50, "", "https://b"))
                .put(offer("C", "c.com", 5.00, "", "https://c"));
        List<PriceFetcher.Offer> parsed = PriceFetcher.parseOffers(offers);
        assertEquals(3, parsed.size());
        assertEquals("b.com", parsed.get(0).domain);
        assertEquals("c.com", parsed.get(1).domain);
        assertEquals("a.com", parsed.get(2).domain);
    }

    @Test
    public void missingDomain_keyedByMerchantName() throws Exception {
        JSONArray offers = new JSONArray()
                .put(offer("Corner Store", "", 4.00, "", "https://a"))
                .put(offer("Corner Store", "", 3.00, "", "https://b"));
        List<PriceFetcher.Offer> parsed = PriceFetcher.parseOffers(offers);
        assertEquals(1, parsed.size());
        assertEquals(3.00, parsed.get(0).price, 0.001);
    }

    @Test
    public void normalizeGtin_validUpcUnchanged() {
        assertEquals("036000291452", PriceFetcher.normalizeGtin("036000291452"));
    }

    @Test
    public void normalizeGtin_repairsBadCheckDigit() {
        assertEquals("036000291452", PriceFetcher.normalizeGtin("036000291450"));
    }

    @Test
    public void normalizeGtin_tooShortReturnsEmpty() {
        assertEquals("", PriceFetcher.normalizeGtin("12345"));
    }
}
