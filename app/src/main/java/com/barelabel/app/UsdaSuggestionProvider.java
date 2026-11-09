package com.barelabel.app;

import android.content.Context;
import android.util.Log;

import com.barelabel.app.model.Suggestion;
import com.barelabel.app.search.BrandQueryDecider;
import com.barelabel.app.search.UsdaSpamFilter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class UsdaSuggestionProvider implements SuggestionProvider {

    private static final String TAG = "UsdaSuggestionProvider";
    private static final String API_KEY = BuildConfig.USDA_API_KEY;
    private final Context context;

    public UsdaSuggestionProvider(Context context) {
        this.context = context;
    }

    @Override
    public List<Suggestion> fetchSuggestions(String query) throws Exception {
        List<Suggestion> suggestions = new ArrayList<>();

        if (query == null || query.trim().length() < 2) {
            return suggestions;
        }

        String trimmedQuery = query.trim();
        // v2: larger page + spam filtering (see UsdaSpamFilter); old cached
        // v1 responses are unfiltered, so they must not be reused.
        String cacheKey = "usda_suggestions_v2_" + trimmedQuery.toLowerCase();

        // 1. Check local disk cache (7-day TTL)
        String body = null;
        if (context != null) {
            body = UsdaResponseCache.get(context, cacheKey);
        }

        // 2. Fetch from network if cache missed or expired
        if (body == null) {
            URL url = new URL("https://api.nal.usda.gov/fdc/v1/foods/search?api_key=" + API_KEY);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);

            JSONObject jsonPayload = new JSONObject();
            jsonPayload.put("query", trimmedQuery);
            
            JSONArray dataTypes = new JSONArray();
            dataTypes.put("Branded");
            jsonPayload.put("dataType", dataTypes);
            // 25 for re-ranking headroom: spam is filtered client-side and
            // only the top 8 survivors are shown.
            jsonPayload.put("pageSize", 25);

            JSONArray fields = new JSONArray();
            fields.put("fdcId");
            fields.put("description");
            fields.put("brandOwner");
            // brandName is the food-label brand (SIMPLY BALANCED); without
            // it in fields the display falls back to the corporate owner.
            fields.put("brandName");
            jsonPayload.put("fields", fields);

            try (OutputStream os = c.getOutputStream()) {
                byte[] inputBytes = jsonPayload.toString().getBytes(StandardCharsets.UTF_8);
                os.write(inputBytes, 0, inputBytes.length);
            }

            if (c.getResponseCode() == 200) {
                try (InputStream is = c.getInputStream()) {
                    body = readAll(is);
                    // Cache the successful network response
                    if (context != null && body != null && !body.isEmpty()) {
                        UsdaResponseCache.put(context, cacheKey, body);
                    }
                }
            } else {
                Log.e(TAG, "USDA Suggestion request failed with status: " + c.getResponseCode());
            }
            c.disconnect();
        }

        // 3. Parse JSON response (from either cache or network)
        if (body != null && !body.isEmpty()) {
            JSONObject root = new JSONObject(body);
            JSONArray foods = root.optJSONArray("foods");

            if (foods != null) {
                // Rank (whole-word matches first, brand-spam last), drop
                // spam, and dedupe by description — eight identical
                // "BREAD (obscure brand)" rows help nobody.
                List<JSONObject> ranked =
                        UsdaSpamFilter.rankedCandidates(foods, trimmedQuery);
                Set<String> seenDescriptions = new HashSet<String>();
                // Brand-aware pass first: for a brand+product query
                // whose brand the plain ranking failed to surface,
                // the brand's own records lead the list — they are
                // what the user is typing toward.
                appendBrandSuggestions(trimmedQuery, ranked,
                        suggestions, seenDescriptions);
                appendSuggestions(ranked, suggestions, seenDescriptions);
            }
        }

        return suggestions;
    }

    /** Converts ranked foods into suggestions (spam dropped, deduped). */
    private void appendSuggestions(List<JSONObject> ranked,
                                   List<Suggestion> out,
                                   Set<String> seenDescriptions) {
        for (JSONObject item : ranked) {
            if (out.size() >= 8) {
                break;
            }
            String description = item.optString("description", "");
            String owner = item.optString("brandOwner", "");
            if (UsdaSpamFilter.isSpamBrand(owner)) {
                continue;
            }
            // Suggest the food-label brand; the owner stays only as
            // the spam signal above, not the display text.
            String labelBrand = item.optString("brandName", "");
            String brand = !labelBrand.isEmpty() ? labelBrand : owner;
            String descKey = UsdaSpamFilter.normalize(description);
            if (!descKey.isEmpty() && !seenDescriptions.add(descKey)) {
                continue;
            }
            long fdcId = item.optLong("fdcId", 0);

            String label = brand.isEmpty() ? description : description + " (" + brand + ")";
            boolean known = false;
            for (Suggestion s : out) {
                if (s.label.equals(label)) { known = true; break; }
            }
            if (!known) {
                out.add(new Suggestion(label, fdcId));
            }
        }
    }

    /**
     * Brand-filtered suggestion pass (the search fallback's twin):
     * when the typed query names a brand that none of the plain
     * results carry, fetch that brand's records via the API's
     * brandOwner field, post-filter by exact brand-token containment,
     * and append them ahead of the plain suggestions. Best-effort:
     * any failure leaves the plain suggestions untouched.
     */
    private void appendBrandSuggestions(String query,
                                        List<JSONObject> rankedPlain,
                                        List<Suggestion> out,
                                        Set<String> seenDescriptions) {
        try {
            List<BrandQueryDecider.BrandAttempt> attempts =
                    BrandQueryDecider.attempts(query);
            if (attempts.isEmpty()) {
                return;
            }
            List<String> candidateBrands = new ArrayList<>();
            for (JSONObject f : rankedPlain) {
                candidateBrands.add(f.optString("brandName", ""));
                candidateBrands.add(f.optString("brandOwner", ""));
            }
            if (!BrandQueryDecider.noBrandSurfaced(candidateBrands, attempts)) {
                return;
            }
            for (BrandQueryDecider.BrandAttempt attempt : attempts) {
                String brandBody = fetchBrandSuggestionBody(attempt);
                if (brandBody == null || brandBody.isEmpty()) {
                    continue;
                }
                JSONArray foods = new JSONObject(brandBody).optJSONArray("foods");
                if (foods == null || foods.length() == 0) {
                    continue;
                }
                List<JSONObject> survivors = new ArrayList<>();
                for (int i = 0; i < foods.length(); i++) {
                    JSONObject f = foods.optJSONObject(i);
                    if (f == null
                            || UsdaSpamFilter.isSpamBrand(
                                    f.optString("brandOwner", ""))) {
                        continue;
                    }
                    if (BrandQueryDecider.brandMatches(
                                    f.optString("brandName", ""),
                                    attempt.brandTokens)
                            || BrandQueryDecider.brandMatches(
                                    f.optString("brandOwner", ""),
                                    attempt.brandTokens)) {
                        survivors.add(f);
                    }
                }
                if (survivors.isEmpty()) {
                    continue;
                }
                List<JSONObject> ranked = UsdaSpamFilter.rankedCandidates(
                        new JSONArray(survivors), attempt.productQuery);
                appendSuggestions(ranked, out, seenDescriptions);
                return;
            }
        } catch (Exception ignored) {
            // Best-effort only: plain suggestions stand.
        }
    }

    /**
     * One brand-filtered suggestion fetch (same fields and timeouts
     * as the plain fetch, plus the brandOwner field), through the
     * 7-day disk cache under its own key.
     */
    private String fetchBrandSuggestionBody(
            BrandQueryDecider.BrandAttempt attempt) {
        try {
            String cacheKey = "usda_brand_suggestions_v1_"
                    + attempt.brandOwner.replace(' ', '_')
                    + "_" + attempt.productQuery.replace(' ', '_');
            String body = null;
            if (context != null) {
                body = UsdaResponseCache.get(context, cacheKey);
            }
            if (body != null) {
                return body;
            }
            URL url = new URL("https://api.nal.usda.gov/fdc/v1/foods/search?api_key=" + API_KEY);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);

            JSONObject jsonPayload = new JSONObject();
            jsonPayload.put("query", attempt.productQuery);
            jsonPayload.put("brandOwner", attempt.brandOwner);
            JSONArray dataTypes = new JSONArray();
            dataTypes.put("Branded");
            jsonPayload.put("dataType", dataTypes);
            jsonPayload.put("pageSize", 25);
            JSONArray fields = new JSONArray();
            fields.put("fdcId");
            fields.put("description");
            fields.put("brandOwner");
            fields.put("brandName");
            jsonPayload.put("fields", fields);

            try (OutputStream os = c.getOutputStream()) {
                byte[] inputBytes = jsonPayload.toString().getBytes(StandardCharsets.UTF_8);
                os.write(inputBytes, 0, inputBytes.length);
            }
            if (c.getResponseCode() == 200) {
                try (InputStream is = c.getInputStream()) {
                    body = readAll(is);
                    if (context != null && body != null && !body.isEmpty()) {
                        UsdaResponseCache.put(context, cacheKey, body);
                    }
                }
            }
            c.disconnect();
            return body;
        } catch (Exception e) {
            return null;
        }
    }

    private String readAll(InputStream is) {
        Scanner s = new Scanner(is, StandardCharsets.UTF_8).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }
}
