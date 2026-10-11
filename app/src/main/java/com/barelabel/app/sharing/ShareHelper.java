package com.barelabel.app.sharing;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import com.barelabel.app.CleanScoreCalculator;
import com.barelabel.app.R;
import com.barelabel.app.images.ProductImageResolver;
import com.barelabel.app.model.ProductResult;
import com.barelabel.app.pricing.PriceFetcher;
import com.barelabel.app.pricing.UpcOffers;
import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fires the system share sheet for a product. Share text and the deep link
 * come from the pure {@link ShareLinks}; this class only owns the Android
 * Intent plumbing.
 *
 * <p>The share carries a photo: the product image when one is cached (via
 * Glide's disk cache, copied into a FileProvider-visible file), otherwise
 * the BareLabel app icon. Chat apps (e.g. WhatsApp) show the photo with the
 * share text as its caption. Image resolution runs off the UI thread; any
 * failure falls back to the text-only share.
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

        final Context appContext = context.getApplicationContext();
        final String finalImageUrl = imageUrl;
        new Thread(() -> {
            final Uri imageUri = resolveShareImage(appContext, finalImageUrl);
            new Handler(Looper.getMainLooper()).post(() ->
                    fireShare(context, text, imageUri));
        }).start();
    }

    private static void fireShare(Context context, String text, Uri imageUri) {
        Intent send = new Intent(Intent.ACTION_SEND);
        if (imageUri != null) {
            send.setType("image/*");
            send.putExtra(Intent.EXTRA_STREAM, imageUri);
            send.setClipData(ClipData.newRawUri("", imageUri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            send.setType("text/plain");
        }
        send.putExtra(Intent.EXTRA_TEXT, text);
        context.startActivity(Intent.createChooser(send, "Share product"));
    }

    /**
     * Returns a FileProvider URI for the share image: the product photo when
     * a URL is available (via Glide's disk cache, no re-download when
     * cached), otherwise the BareLabel app icon. Null on any failure —
     * callers fall back to the text-only share.
     */
    private static Uri resolveShareImage(Context context, String imageUrl) {
        try {
            File dir = new File(context.getCacheDir(), "shared_images");
            deleteRecursive(dir);
            if (!dir.mkdirs() && !dir.isDirectory()) return null;

            File out;
            if (imageUrl != null && !imageUrl.isEmpty()) {
                File cached = Glide.with(context)
                        .downloadOnly()
                        .load(imageUrl)
                        .submit()
                        .get(15, TimeUnit.SECONDS);
                out = new File(dir, "product.jpg");
                copyFile(cached, out);
            } else {
                Bitmap logo = BitmapFactory.decodeResource(
                        context.getResources(), R.mipmap.ic_launcher);
                if (logo == null) return null;
                out = new File(dir, "logo.png");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    logo.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
            }
            return FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", out);
        } catch (Exception e) {
            return null;
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
