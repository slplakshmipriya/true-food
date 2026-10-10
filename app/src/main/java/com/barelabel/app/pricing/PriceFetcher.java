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
 * feeds both. Parsing, retailer dedupe, and GTIN repair live in the pure
 * {@link UpcOffers} (no Android dependencies) so JVM unit tests can load
 * them — this class's statics initialize an Android Handler/Looper.
 *
 * <p>When the Cloudflare Worker in {@code workers/pricing-proxy} is deployed,
 * flip {@link #USE_WORKER} and set {@link #WORKER_URL}: the worker holds the
 * (paid) API key server-side and caches per GTIN for 12h at the edge.
 *
 * <p>USDA {@code gtinUpc} values are often malformed (dropped leading zeros,
 * bad check digits), so every lookup first runs
 * {@link UpcOffers#normalizeGtin(String)}: digits only, left-padded to 12,
 * GS1 check digit repaired. UPCitemdb rejects invalid codes outright, so
 * this is required, not cosmetic.
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
    private static final Map<String, List<UpcOffers.Offer>> OFFERS_CACHE = new ConcurrentHashMap<>();
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
            String p = UpcOffers.currencySymbol(currency)
                    + String.format(Locale.US, "%.2f", price);
            return merchant.isEmpty() ? p : p + " · " + merchant;
        }
    }

    /** Callback for {@link #fetchOffers}; always on the main thread. The list
     *  is null when the lookup failed (unknown), empty when the item is
     *  confirmed to have no usable offers. */
    public interface OffersCallback {
        void onOffers(List<UpcOffers.Offer> offers);
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
            UpcOffers.Offer best = offers.get(0);
            callback.onPrice(new PriceResult(best.price, best.merchant, best.currency));
        });
    }

    /**
     * Async all-offers lookup by raw GTIN/UPC; callback fires on the main
     * thread. The list is cheapest-first, one offer per retailer.
     */
    public static void fetchOffers(String gtinRaw, OffersCallback callback) {
        final String gtin = UpcOffers.normalizeGtin(gtinRaw);
        if (gtin.isEmpty()) {
            MAIN.post(() -> callback.onOffers(null));
            return;
        }
        List<UpcOffers.Offer> cached = OFFERS_CACHE.get(gtin);
        if (cached != null) {
            final List<UpcOffers.Offer> hit = cached;
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
            List<UpcOffers.Offer> result = null;
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
            final List<UpcOffers.Offer> finalResult = result;
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
    private static List<UpcOffers.Offer> lookupOffersViaUpcitemdb(String gtin)
            throws PriceTransportException {
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
            return UpcOffers.parseOffers(items.getJSONObject(0).optJSONArray("offers"));
        } catch (PriceTransportException e) {
            throw e;
        } catch (Exception e) {
            throw new PriceTransportException("UPCitemdb lookup failed for " + gtin, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Worker answers a single best offer; wrap it as a one-offer list. */
    private static List<UpcOffers.Offer> lookupOffersViaWorker(String gtin)
            throws PriceTransportException {
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
            List<UpcOffers.Offer> out = new ArrayList<>();
            if (!root.optBoolean("ok", false) || root.isNull("price")) return out;
            double price = root.optDouble("price", Double.NaN);
            if (Double.isNaN(price) || price <= 0) return out;
            out.add(new UpcOffers.Offer(root.optString("merchant", ""), "", price,
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
}
