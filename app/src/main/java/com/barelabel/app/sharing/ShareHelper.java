package com.barelabel.app.sharing;

import android.content.Context;
import android.content.Intent;

import com.barelabel.app.CleanScoreCalculator;
import com.barelabel.app.images.ProductImageResolver;
import com.barelabel.app.model.ProductResult;
import com.barelabel.app.pricing.PriceFetcher;
import com.barelabel.app.pricing.UpcOffers;

import java.util.ArrayList;
import java.util.List;

/**
 * Fires the system share sheet for a product. Share text and the deep link
 * come from the pure {@link ShareLinks}; this class only owns the Android
 * Intent plumbing.
 */
public final class ShareHelper {

    private ShareHelper() {}

    /**
     * Shares a product via the system share sheet. Safe to call with a null
     * or not-found product (no-op).
     */
    public static void shareProduct(Context context, ProductResult product) {
        if (context == null || product == null || !product.found) return;

        boolean isClean = product.flagged == null || product.flagged.isEmpty();
        int flaggedCount = product.flagged == null ? 0 : product.flagged.size();
        int categoryCount = (product.matchResult != null
                && product.matchResult.categoryMap != null)
                ? product.matchResult.categoryMap.size() : 0;
        int score = CleanScoreCalculator.score(true, flaggedCount, categoryCount);

        String imageUrl = null;
        if (product.gtinUpc != null && !product.gtinUpc.isEmpty()) {
            ProductImageResolver.OffProductInfo info =
                    ProductImageResolver.getCached(context, product.gtinUpc);
            if (info != null && info.hasImage()) {
                imageUrl = info.imageUrl;
            }
        }

        // Best-effort retailer hints: merchants already resolved for this
        // GTIN (e.g. by the alternate rows). Cache-only — sharing never
        // waits on the network.
        List<String> merchants = null;
        if (product.gtinUpc != null && !product.gtinUpc.isEmpty()) {
            List<UpcOffers.Offer> offers = PriceFetcher.getCached(product.gtinUpc);
            if (offers != null && !offers.isEmpty()) {
                merchants = new ArrayList<>();
                for (int i = 0; i < Math.min(4, offers.size()); i++) {
                    String m = offers.get(i).merchant;
                    if (m != null && !m.trim().isEmpty()) {
                        merchants.add(m.trim());
                    }
                }
                if (merchants.isEmpty()) merchants = null;
            }
        }

        String url = (product.gtinUpc != null && product.gtinUpc.matches("[0-9]{8,14}"))
                ? ShareLinks.productUrl(product.gtinUpc, isClean, score,
                        flaggedCount, product.name, imageUrl, merchants)
                : ShareLinks.PLAY_STORE_URL;

        String text = ShareLinks.shareText(
                product.name, product.brand, isClean, score, flaggedCount, url);

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        context.startActivity(Intent.createChooser(send, "Share product"));
    }
}
