package com.barelabel.app.pricing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure UPCitemdb offer logic: parsing, per-retailer dedupe, GTIN repair.
 *
 * Android-free on purpose so it runs under JVM unit tests — PriceFetcher's
 * statics initialize an Android Handler/Looper, which are throwing stubs
 * off-device, so tests must never need to load that class. PriceFetcher
 * owns transport, caching, and callbacks; this class owns the data rules.
 */
public final class UpcOffers {

    private UpcOffers() {
    }

    /** One retailer's offer for an item (UPCitemdb {@code offers[]} entry). */
    public static final class Offer {
        public final String merchant;
        public final String domain;
        public final double price;
        public final String currency;
        public final String link;

        public Offer(String merchant, String domain, double price,
                     String currency, String link) {
            this.merchant = merchant == null ? "" : merchant;
            this.domain = domain == null ? "" : domain;
            this.price = price;
            this.currency = (currency == null || currency.isEmpty()) ? "USD" : currency;
            this.link = link == null ? "" : link;
        }

        /** "$11.38" — price only; the row shows the retailer's logo, not its name. */
        public String priceText() {
            return currencySymbol(currency) + String.format(Locale.US, "%.2f", price);
        }
    }

    static String currencySymbol(String currency) {
        try {
            return java.util.Currency.getInstance(currency).getSymbol(Locale.US);
        } catch (Exception e) {
            return "USD".equals(currency) ? "$" : currency + " ";
        }
    }

    /**
     * Cleans a raw UPCitemdb offer link into an absolute http(s) URL, or ""
     * when unusable. Handles protocol-relative ("//host/...") and bare-host
     * ("host/path") links; drops non-http(s) schemes (javascript:, …) and
     * anything that doesn't look like a host.
     */
    public static String sanitizeLink(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("//")) {
            s = "https:" + s;
        } else if (!s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            int slash = s.indexOf('/');
            String host = slash < 0 ? s : s.substring(0, slash);
            if (!host.contains(".") || s.contains(" ")) return "";
            s = "https://" + s;
        }
        if (!(s.startsWith("http://") || s.startsWith("https://"))) return "";
        return s.replace(" ", "%20");
    }

    /**
     * Parses UPCitemdb {@code offers[]}: drops out-of-stock and unusable
     * prices, keeps the cheapest offer per retailer (dedupe key: domain,
     * else merchant name), sorted cheapest first.
     */
    public static List<Offer> parseOffers(JSONArray offers) {
        List<Offer> out = new ArrayList<>();
        if (offers == null) return out;
        Map<String, Offer> cheapestByStore = new LinkedHashMap<>();
        for (int i = 0; i < offers.length(); i++) {
            JSONObject o = offers.optJSONObject(i);
            if (o == null) continue;
            String availability = o.optString("availability", "").toLowerCase(Locale.US);
            if (availability.contains("out of stock")) continue;
            double price = o.optDouble("price", Double.NaN);
            if (Double.isNaN(price) || price <= 0) continue;
            String merchant = o.optString("merchant", "").trim();
            String domain = o.optString("domain", "").trim().toLowerCase(Locale.US);
            if (domain.startsWith("www.")) domain = domain.substring(4);
            String key = !domain.isEmpty() ? domain : merchant.toLowerCase(Locale.US);
            if (key.isEmpty()) continue;
            Offer offer = new Offer(merchant, domain, price,
                    o.optString("currency", ""),
                    sanitizeLink(o.optString("link", "")));
            Offer prev = cheapestByStore.get(key);
            if (prev == null || price < prev.price) {
                cheapestByStore.put(key, offer);
            }
        }
        out.addAll(cheapestByStore.values());
        Collections.sort(out, (a, b) -> Double.compare(a.price, b.price));
        return out;
    }

    /**
     * Normalizes a raw GTIN/UPC: digits only, left-padded to 12, GS1 check
     * digit validated and repaired. Returns "" when unusable. GTIN-8 is
     * accepted (zero-extended); UPCitemdb coverage for those is uneven.
     */
    public static String normalizeGtin(String raw) {
        if (raw == null) return "";
        String d = raw.replaceAll("\\D", "");
        if (d.length() < 8) return "";
        while (d.length() < 12) d = "0" + d;
        if (d.length() > 14) d = d.substring(d.length() - 14);
        String payload = d.substring(0, d.length() - 1);
        int want = gs1CheckDigit(payload);
        if (d.charAt(d.length() - 1) - '0' != want) {
            d = payload + want;
        }
        return d;
    }

    /** GS1 mod-10 check digit for the payload (all digits except the check digit). */
    static int gs1CheckDigit(String payload) {
        int sum = 0;
        boolean times3 = true;
        for (int i = payload.length() - 1; i >= 0; i--) {
            int v = payload.charAt(i) - '0';
            sum += times3 ? v * 3 : v;
            times3 = !times3;
        }
        return (10 - (sum % 10)) % 10;
    }
}
