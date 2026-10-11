package com.barelabel.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.browser.customtabs.CustomTabsIntent;

import com.barelabel.app.pricing.UpcOffers;

import java.util.List;

/**
 * Shared retailer-offers UI used by the search-results alternate rows and
 * the History tab rows so both surfaces behave identically: a logo strip
 * (cheapest first, logos only, capped with a "+N" overflow chip) plus
 * dead-link-safe opening of the merchant page in a Custom Tab.
 */
public final class RetailerOffers {

    private static final int MAX_RETAILER_LOGOS = 6;
    private static final int C_WHITE = Color.parseColor("#FFFFFF");
    private static final int C_GRAY_500 = Color.parseColor("#6B7280");

    private RetailerOffers() {}

    /**
     * Fills the strip: one logo chip per store carrying the item (cheapest
     * first) — tapping a chip opens that store's product page. GONE when
     * there are no offers.
     */
    public static void bindStrip(Context ctx, LinearLayout strip,
                                 List<UpcOffers.Offer> offers, String productName) {
        final Activity activity = ctx instanceof Activity ? (Activity) ctx : null;
        strip.removeAllViews();
        if (offers == null || offers.isEmpty()) {
            strip.setVisibility(View.GONE);
            return;
        }
        strip.setVisibility(View.VISIBLE);
        int shown = Math.min(offers.size(), MAX_RETAILER_LOGOS);
        for (int i = 0; i < shown; i++) {
            final UpcOffers.Offer offer = offers.get(i);
            ImageView logo = new ImageView(ctx);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(dp(ctx, 26), dp(ctx, 26));
            lp.setMarginEnd(dp(ctx, 6));
            logo.setLayoutParams(lp);
            logo.setPadding(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3));
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setShape(GradientDrawable.RECTANGLE);
            chipBg.setCornerRadius(dp(ctx, 6));
            chipBg.setColor(C_WHITE);
            logo.setBackground(chipBg);
            logo.setContentDescription(offer.merchant);
            RetailerLogos.loadInto(ctx, logo, offer.merchant, offer.domain);
            if (!offer.link.isEmpty() && offer.link.startsWith("http")) {
                logo.setClickable(true);
                final String link = offer.link;
                final String merchant = offer.merchant;
                final String domain = offer.domain;
                logo.setOnClickListener(v -> {
                    if (activity != null) {
                        openOfferLink(activity, link, productName, merchant, domain);
                    }
                });
            }
            strip.addView(logo);
        }
        if (offers.size() > shown) {
            TextView more = new TextView(ctx);
            more.setText("+" + (offers.size() - shown));
            more.setTextSize(10f);
            more.setTextColor(C_GRAY_500);
            strip.addView(more);
        }
    }

    /**
     * Opens a retailer's product page in a Custom Tab (URL bar visible).
     * UPCitemdb offer URLs rot — products get delisted, merchants die — and
     * a Custom Tab can't report a 404, so the URL is probed first on a
     * background thread. A hard-dead page (404/410/DNS/timeout) falls back
     * to a merchant-scoped search for the product; anything ambiguous
     * (bot-blocked 403s, 5xx) opens the link as-is rather than stranding
     * the user.
     */
    public static void openOfferLink(Activity activity, String url,
                                     String productName, String merchant,
                                     String domain) {
        new Thread(() -> {
            String target = url;
            boolean dead = linkLooksDead(url);
            if (dead) {
                String q = (!domain.isEmpty() ? "site:" + domain + " " : "")
                        + productName
                        + (merchant.isEmpty() ? "" : " " + merchant);
                try {
                    target = "https://www.google.com/search?q="
                            + java.net.URLEncoder.encode(q.trim(), "UTF-8");
                } catch (Exception ignored) {
                    target = url;
                    dead = false;
                }
            }
            final String open = target;
            final boolean showFallbackNote = dead;
            activity.runOnUiThread(() -> {
                if (showFallbackNote) {
                    Toast.makeText(activity,
                            "That product page is gone — showing search results instead.",
                            Toast.LENGTH_SHORT).show();
                }
                launchUrl(activity, open);
            });
        }).start();
    }

    /** True only on a hard-dead page: 404/410, unresolvable host, or timeout. */
    private static boolean linkLooksDead(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
            int code = conn.getResponseCode();
            if (code == 405 || code == 501) {
                // HEAD not implemented: retry as GET, headers only.
                conn.disconnect();
                conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
                code = conn.getResponseCode();
            }
            return code == 404 || code == 410;
        } catch (java.net.UnknownHostException | java.net.SocketTimeoutException e) {
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Opens a URL in a Custom Tab, falling back to a plain VIEW intent. */
    private static void launchUrl(Activity activity, String url) {
        try {
            new CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                    .launchUrl(activity, Uri.parse(url));
        } catch (Exception e) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(url)));
            } catch (Exception ignored) {
                // No browser available: the logo stays a passive indicator.
            }
        }
    }

    private static int dp(Context ctx, int dps) {
        return Math.round(dps * ctx.getResources().getDisplayMetrics().density);
    }
}
