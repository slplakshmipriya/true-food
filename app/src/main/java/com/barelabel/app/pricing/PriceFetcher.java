package com.barelabel.app.pricing;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Prototype price lookup for the alternates panel ("Exact matches" stack).
 *
 * <p>Current mode: queries the UPCitemdb keyless trial endpoint directly
 * (100 lookups/day per device IP, ~1 req/10s sustainable). Results are cached
 * in memory per GTIN so repeat views never burn quota.
 *
 * <p>Two reads over the same lookup: {@link #fetch} (cheapest in-stock offer,
 * the prototype price line) and {@link #fetchOffers} (every retailer carrying
 * the item, for the logo strip on alternate rows). One network call per GTIN
 * feeds both.
 *
 * <p>When the Cloudflare Worker in {@code workers/pricing-proxy} is deployed,
 * flip {@link #USE_WORKER} and set {@link #WORKER_URL}: the worker holds the
 * (paid) API key server-side and caches per GTIN for 12h at the edge.
 *
 * <p>USDA {@code gtinUpc} values are often malformed (dropped leading zeros,
 * bad check digits), so every lookup first runs {@link #normalizeGtin}: digits
 * only, left-padded to 12, GS1 check digit repaired. UPCitemdb rejects invalid
 * codes outright, so this is required, not cosmetic.
 */
public final class PriceFetcher {
    private static final String TAG = "PriceFetcher";

    private static final String UPCITEMDB_TRIAL_URL =
            "https://api.upcitemdb.com/prod/trial/lookup";

    private static final boolean USE_WORKER = false;
    private static final String WORKER_URL =
            "https://barelabel-pricing.<your-account>.workers.dev/price";

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Confirmed offers per GTIN; an empty list is a confirmed "no offers". */
    private static final Map<String, List<Offer>> OFFERS_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<OffersCallback>> OFFERS_IN_FLIGHT =
            new ConcurrentHashMap<>();
    /** Backoff after a 429: no new trial requests until this wall-clock time. */
    private static volatile long rateLimitedUntilMs = 0;
    private static final long RATE_LIMIT_COOLDOWN_MS = 60_000;

    /** Callback; always invoked on the main thread. {@code result} is null when unknown. */
    public interface PriceCallback {
        void onPrice(PriceResult result);
    }

    /** Transport/HTTP failure: never cached as a miss, always retried later. */
    private static final class PriceTransportException extends Exception {
        PriceTransportException(String msg) { super(msg); }
        PriceTransportException(String msg, Throwable cause) { super(msg, cause); }
    }

    public static final class PriceResult {
        public final double price;
        public final String merchant;
        public final String currency;

        PriceResult(double price, String merchant, String currency) {
            this.price = price;
            this.merchant = merchant == null ? "" : merchant;
            this.currency = (currency == null || currency.isEmpty()) ? "USD" : currency;
        }

        /** e.g. "$11.38 · Walmart" or "$11.38" when no merchant is known. */
        public String displayText() {
            String p = currencySymbol(currency) + String.format(Locale.US, "%.2f", price);
            return merchant.isEmpty() ? p : p + " · " + merchant;
        }
    }

    /** Callback for {@link #fetchOffers}; always on the main thread. The list
     *  is null when the lookup failed (unknown), empty when the item is
     *  confirmed to have no usable offers. */
    public interface OffersCallback {
        void onOffers(List<Offer> offers);
    }

    /** One retailer's offer for an item (UPCitemdb {@code offers[]} entry). */
    public static final class Offer {
        public final String merchant;
        public final String domain;
        public final double price;
        public final String currency;
        public final String link;

        Offer(String merchant, String domain, double price, String currency, String link) {
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

    private static String currencySymbol(String currency) {
        try {
            return java.util.Currency.getInstance(currency).getSymbol(Locale.US);
        } catch (Exception e) {
            return "USD".equals(currency) ? "$" : currency + " ";
        }
    }

    private PriceFetcher() {
    }

    /** Async cheapest-offer lookup; callback fires on the main thread. */
    public static void fetch(String gtinRaw, PriceCallback callback) {
        fetchOffers(gtinRaw, offers -> {
            if (offers == null || offers.isEmpty()) {
                callback.onPrice(null);
                return;
            }
            Offer best = offers.get(0);
            callback.onPrice(new PriceResult(best.price, best.merchant, best.currency));
        });
    }

    /**
     * Async all-offers lookup by raw GTIN/UPC; callback fires on the main
     * thread. The list is cheapest-first, one offer per retailer.
     */
    public static void fetchOffers(String gtinRaw, OffersCallback callback) {
        final String gtin = normalizeGtin(gtinRaw);
        if (gtin.isEmpty()) {
            MAIN.post(() -> callback.onOffers(null));
            return;
        }
        List<Offer> cached = OFFERS_CACHE.get(gtin);
        if (cached != null) {
            final List<Offer> hit = cached;
            MAIN.post(() -> callback.onOffers(hit));
            return;
        }
        if (rateLimitedUntilMs > System.currentTimeMillis()) {
            // Trial quota exhausted: don't burn requests, report unknown.
            MAIN.post(() -> callback.onOffers(null));
            return;
        }
        // In-flight coalescing, race-free: the map lock makes exactly one
        // thread the leader; joiners can only attach before the leader's
        // remove, so no callback is ever stranded.
        final boolean leader;
        synchronized (OFFERS_IN_FLIGHT) {
            List<OffersCallback> existing = OFFERS_IN_FLIGHT.get(gtin);
            if (existing == null) {
                existing = new ArrayList<>();
                OFFERS_IN_FLIGHT.put(gtin, existing);
                leader = true;
            } else {
                leader = false;
            }
            existing.add(callback);
        }
        if (!leader) return;
        EXEC.execute(() -> {
            List<Offer> result = null;
            try {
                result = USE_WORKER ? lookupOffersViaWorker(gtin)
                        : lookupOffersViaUpcitemdb(gtin);
                // Confirmed answer (possibly "no offers"): safe to remember.
                OFFERS_CACHE.put(gtin, result);
            } catch (PriceTransportException e) {
                // Timeout / 429 / 5xx / bad response: NOT a miss. The next
                // bind retries instead of showing nothing forever.
                Log.w(TAG, "offers lookup failed for " + gtin + "; not caching", e);
            }
            final List<OffersCallback> done;
            synchronized (OFFERS_IN_FLIGHT) {
                done = OFFERS_IN_FLIGHT.remove(gtin);
            }
            final List<Offer> finalResult = result;
            MAIN.post(() -> {
                if (done != null) {
                    for (OffersCallback cb : done) {
                        cb.onOffers(finalResult);
                    }
                }
            });
        });
    }

    /**
     * @return every usable offer for the GTIN (possibly empty when UPCitemdb
     *         confirms it has none), cheapest first.
     * @throws PriceTransportException on HTTP/transport failure (not a miss).
     */
    private static List<Offer> lookupOffersViaUpcitemdb(String gtin) throws PriceTransportException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(UPCITEMDB_TRIAL_URL + "?upc=" + gtin);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code == 429) {
                rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS;
                throw new PriceTransportException("UPCitemdb 429: trial quota exhausted");
            }
            if (code != 200) {
                throw new PriceTransportException("UPCitemdb HTTP " + code);
            }
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) sb.append(line);
            in.close();
            JSONObject root = new JSONObject(sb.toString());
            if (!"OK".equals(root.optString("code"))) return new ArrayList<>();
            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) return new ArrayList<>();
            return parseOffers(items.getJSONObject(0).optJSONArray("offers"));
        } catch (PriceTransportException e) {
            throw e;
        } catch (Exception e) {
            throw new PriceTransportException("UPCitemdb lookup failed for " + gtin, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Parses UPCitemdb {@code offers[]}: drops out-of-stock and unusable
     * prices, keeps the cheapest offer per retailer (dedupe key: domain,
     * else merchant name), sorted cheapest first. Pure — unit-testable.
     */
    static List<Offer> parseOffers(JSONArray offers) {
        List<Offer> out = new ArrayList<>();
        if (offers == null) return out;
        java.util.Map<String, Offer> cheapestByStore = new java.util.LinkedHashMap<>();
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
                    o.optString("currency", ""), o.optString("link", "").trim());
            Offer prev = cheapestByStore.get(key);
            if (prev == null || price < prev.price) {
                cheapestByStore.put(key, offer);
            }
        }
        out.addAll(cheapestByStore.values());
        java.util.Collections.sort(out, (a, b) -> Double.compare(a.price, b.price));
        return out;
    }

    /** Worker answers a single best offer; wrap it as a one-offer list. */
    private static List<Offer> lookupOffersViaWorker(String gtin) throws PriceTransportException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(WORKER_URL + "?gtin=" + gtin);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) {
                throw new PriceTransportException("worker HTTP " + conn.getResponseCode());
            }
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) sb.append(line);
            in.close();
            JSONObject root = new JSONObject(sb.toString());
            List<Offer> out = new ArrayList<>();
            if (!root.optBoolean("ok", false) || root.isNull("price")) return out;
            double price = root.optDouble("price", Double.NaN);
            if (Double.isNaN(price) || price <= 0) return out;
            out.add(new Offer(root.optString("merchant", ""), "", price,
                    root.optString("currency", "USD"), ""));
            return out;
        } catch (PriceTransportException e) {
            throw e;
        } catch (Exception e) {
            throw new PriceTransportException("Worker price lookup failed for " + gtin, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Normalizes a raw GTIN/UPC: digits only, left-padded to 12, GS1 check
     * digit validated and repaired. Returns "" when unusable. GTIN-8 is
     * accepted (zero-extended); UPCitemdb coverage for those is uneven.
     */
    static String normalizeGtin(String raw) {
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
