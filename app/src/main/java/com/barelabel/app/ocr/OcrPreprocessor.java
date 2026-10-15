package com.barelabel.app.ocr;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.net.Uri;

import androidx.camera.core.ImageProxy;
import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Image preprocessing for the ingredient-label OCR path.
 *
 * <p>ML Kit's on-device recognizer exposes no tunable knobs, so input
 * quality is the lever: small/dense label text is upscaled 2x (thin strokes
 * like "ll" merge into "M" at low resolution, and dropped strokes turn
 * "flour" into "Fou") and contrast-boosted before recognition.
 *
 * <p>Pure Android graphics code; the Bitmap entry points are exercised on
 * device, not in JVM unit tests.
 */
public final class OcrPreprocessor {

    /**
     * Upscale factor applied before OCR. Tuning knob: higher values give
     * ML Kit more pixels on small label text (thin strokes like "ll"
     * merging into "M") at the cost of slower recognition and more memory.
     * 3x was chosen 2026-10-14 after 2x left single-char misreads.
     */
    private static final float UPSCALE = 3.0f;
    /** Cap on the longest side after upscaling (avoids OOM on huge photos). */
    private static final int MAX_SIDE = 3072;
    /** Contrast multiplier for the enhancement pass. */
    private static final float CONTRAST = 1.6f;

    private OcrPreprocessor() {
    }

    /**
     * Returns an upscaled, contrast-boosted copy of src. Returns src itself
     * on unexpected failure; never returns null for non-null input.
     */
    public static Bitmap enhance(Bitmap src) {
        if (src == null) {
            return null;
        }
        try {
            float scale = UPSCALE;
            int longest = Math.max(src.getWidth(), src.getHeight());
            if (longest * scale > MAX_SIDE) {
                scale = (float) MAX_SIDE / longest;
            }
            int w = Math.max(1, Math.round(src.getWidth() * scale));
            int h = Math.max(1, Math.round(src.getHeight() * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(src, w, h, true);

            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            float bias = 128f * (1f - CONTRAST);
            ColorMatrix cm = new ColorMatrix(new float[]{
                    CONTRAST, 0, 0, 0, bias,
                    0, CONTRAST, 0, 0, bias,
                    0, 0, CONTRAST, 0, bias,
                    0, 0, 0, 1, 0});
            paint.setColorFilter(new ColorMatrixColorFilter(cm));
            canvas.drawBitmap(scaled, 0, 0, paint);
            if (scaled != src) {
                scaled.recycle();
            }
            return out;
        } catch (Exception | OutOfMemoryError e) {
            return src;
        }
    }

    /**
     * Converts a CameraX ImageProxy (YUV_420_888) to an enhanced Bitmap,
     * applying the given rotation degrees. The proxy is NOT closed; the
     * caller owns it. Returns null when conversion fails.
     */
    public static Bitmap fromImageProxy(ImageProxy proxy, int rotationDegrees) {
        Image image = proxy.getImage();
        if (image == null) {
            return null;
        }
        try {
            int w = image.getWidth();
            int h = image.getHeight();
            byte[] nv21 = yuv420ToNv21(image);
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(w * h);
            yuv.compressToJpeg(new Rect(0, 0, w, h), 100, jpeg);
            byte[] bytes = jpeg.toByteArray();
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null) {
                return null;
            }
            Bitmap enhanced = enhance(bmp);
            bmp.recycle();
            if (rotationDegrees != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotationDegrees);
                Bitmap rotated = Bitmap.createBitmap(enhanced, 0, 0,
                        enhanced.getWidth(), enhanced.getHeight(), m, true);
                enhanced.recycle();
                return rotated;
            }
            return enhanced;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * Loads a gallery image, downsamples huge photos, applies EXIF rotation,
     * and returns the enhanced Bitmap. Returns null on failure.
     */
    public static Bitmap fromGalleryUri(ContentResolver cr, Uri uri) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, opts);
            }
            int sample = 1;
            while (Math.max(opts.outWidth, opts.outHeight) / sample > MAX_SIDE) {
                sample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            Bitmap bitmap;
            try (InputStream in = cr.openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bitmap == null) {
                return null;
            }
            int rotation = readExifRotation(cr, uri);
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(), bitmap.getHeight(), m, true);
                bitmap.recycle();
                bitmap = rotated;
            }
            Bitmap enhanced = enhance(bitmap);
            bitmap.recycle();
            return enhanced;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static int readExifRotation(ContentResolver cr, Uri uri) {
        try (InputStream in = cr.openInputStream(uri)) {
            ExifInterface exif = new ExifInterface(in);
            int orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            switch (orient) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /** Packs YUV_420_888 planes into NV21, honoring row/pixel strides. */
    private static byte[] yuv420ToNv21(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        byte[] nv21 = new byte[w * h * 3 / 2];
        int pos = 0;
        for (int row = 0; row < h; row++) {
            yBuf.position(row * yRowStride);
            yBuf.get(nv21, pos, w);
            pos += w;
        }
        for (int row = 0; row < h / 2; row++) {
            for (int col = 0; col < w / 2; col++) {
                int idx = row * uvRowStride + col * uvPixelStride;
                nv21[pos++] = vBuf.get(idx);
                nv21[pos++] = uBuf.get(idx);
            }
        }
        return nv21;
    }
}
