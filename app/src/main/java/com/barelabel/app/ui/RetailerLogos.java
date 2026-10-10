package com.barelabel.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Retailer logo loading for the offers strip on alternate rows.
 *
 * Logos come from the site's own favicon (Google's favicon service, keyed by
 * the retailer's domain from the UPCitemdb offer), so no logo assets or keys
 * ship in the APK. When a domain is missing or the favicon fails to load,
 * fall back to a letter tile (first letter of the merchant name) — the same
 * "logo or initial" pattern shopping surfaces use, so a row never shows a
 * broken-image glyph.
 */
public final class RetailerLogos {

    /** Merchant-name -> domain, used only when an offer carries no domain. */
    private static final Map<String, String> MERCHANT_DOMAINS = new HashMap<>();
    static {
        MERCHANT_DOMAINS.put("walmart", "walmart.com");
        MERCHANT_DOMAINS.put("amazon", "amazon.com");
        MERCHANT_DOMAINS.put("target", "target.com");
        MERCHANT_DOMAINS.put("ebay", "ebay.com");
        MERCHANT_DOMAINS.put("kroger", "kroger.com");
        MERCHANT_DOMAINS.put("cvs", "cvs.com");
        MERCHANT_DOMAINS.put("cvs pharmacy", "cvs.com");
        MERCHANT_DOMAINS.put("walgreens", "walgreens.com");
        MERCHANT_DOMAINS.put("costco", "costco.com");
        MERCHANT_DOMAINS.put("best buy", "bestbuy.com");
        MERCHANT_DOMAINS.put("whole foods market", "wholefoodsmarket.com");
        MERCHANT_DOMAINS.put("instacart", "instacart.com");
        MERCHANT_DOMAINS.put("aldi", "aldi.us");
        MERCHANT_DOMAINS.put("publix", "publix.com");
        MERCHANT_DOMAINS.put("safeway", "safeway.com");
        MERCHANT_DOMAINS.put("heb", "heb.com");
        MERCHANT_DOMAINS.put("h-e-b", "heb.com");
        MERCHANT_DOMAINS.put("meijer", "meijer.com");
        MERCHANT_DOMAINS.put("dollar general", "dollargeneral.com");
        MERCHANT_DOMAINS.put("dollar tree", "dollartree.com");
        MERCHANT_DOMAINS.put("family dollar", "familydollar.com");
        MERCHANT_DOMAINS.put("rite aid", "riteaid.com");
        MERCHANT_DOMAINS.put("sam's club", "samsclub.com");
        MERCHANT_DOMAINS.put("bj's wholesale club", "bjs.com");
        MERCHANT_DOMAINS.put("shoprite", "shoprite.com");
        MERCHANT_DOMAINS.put("wegmans", "wegmans.com");
        MERCHANT_DOMAINS.put("sprouts farmers market", "sprouts.com");
        MERCHANT_DOMAINS.put("vitacost", "vitacost.com");
        MERCHANT_DOMAINS.put("iherb", "iherb.com");
        MERCHANT_DOMAINS.put("thrive market", "thrivemarket.com");
        MERCHANT_DOMAINS.put("chewy", "chewy.com");
        MERCHANT_DOMAINS.put("petco", "petco.com");
        MERCHANT_DOMAINS.put("petsmart", "petsmart.com");
        MERCHANT_DOMAINS.put("the home depot", "homedepot.com");
        MERCHANT_DOMAINS.put("lowe's", "lowes.com");
        MERCHANT_DOMAINS.put("sears", "sears.com");
        MERCHANT_DOMAINS.put("rakuten", "rakuten.com");
        MERCHANT_DOMAINS.put("newegg", "newegg.com");
        MERCHANT_DOMAINS.put("jet.com", "jet.com");
    }

    private RetailerLogos() {
    }

    /** Favicon URL for a retailer domain ("" domain -> ""). */
    static String logoUrl(String domain) {
        String d = normalizeDomain(domain);
        return d.isEmpty() ? ""
                : "https://www.google.com/s2/favicons?domain=" + d + "&sz=128";
    }

    /** Domain for a merchant name when the offer didn't carry one ("" if unknown). */
    static String domainForMerchant(String merchant) {
        if (TextUtils.isEmpty(merchant)) return "";
        String key = merchant.trim().toLowerCase(Locale.US);
        String exact = MERCHANT_DOMAINS.get(key);
        if (exact != null) return exact;
        for (Map.Entry<String, String> e : MERCHANT_DOMAINS.entrySet()) {
            if (key.contains(e.getKey())) return e.getValue();
        }
        return "";
    }

    /**
     * Loads the retailer's logo into the view: favicon by domain, letter tile
     * when the domain is unknown or the favicon fails to load.
     */
    public static void loadInto(Context ctx, ImageView view, String merchant, String domain) {
        String d = normalizeDomain(domain);
        if (d.isEmpty()) d = domainForMerchant(merchant);
        if (d.isEmpty()) {
            view.setImageDrawable(letterTile(ctx, merchant));
            return;
        }
        Glide.with(ctx)
                .load(logoUrl(d))
                .error(letterTile(ctx, merchant))
                .into(view);
    }

    private static String normalizeDomain(String domain) {
        if (domain == null) return "";
        String d = domain.trim().toLowerCase(Locale.US);
        if (d.startsWith("www.")) d = d.substring(4);
        return d;
    }

    /** Gray rounded tile with the merchant's initial, e.g. "W" for Walmart. */
    private static Drawable letterTile(Context ctx, String merchant) {
        String letter = "?";
        if (!TextUtils.isEmpty(merchant)) {
            String m = merchant.trim();
            for (int i = 0; i < m.length(); i++) {
                char c = m.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    letter = String.valueOf(Character.toUpperCase(c));
                    break;
                }
            }
        }
        int size = 96;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.parseColor("#6B7280"));
        float radius = size / 2f;
        canvas.drawCircle(radius, radius, radius, bg);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(size * 0.5f);
        text.setFakeBoldText(true);
        text.setTextAlign(Paint.Align.CENTER);
        float y = radius - (text.descent() + text.ascent()) / 2f;
        canvas.drawText(letter, radius, y, text);
        return new BitmapDrawable(ctx.getResources(), bmp);
    }
}
