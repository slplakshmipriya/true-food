package com.barelabel.app.ocr;

import android.content.Context;

import com.barelabel.app.search.IngredientSectionExtractor;
import com.google.android.gms.common.moduleinstall.InstallStatusListener;
import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallClient;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Ensures the Play Services ML Kit text-recognition model is on-device before
 * a scan. The unbundled model downloads silently on first use; this helper
 * pre-warms it with progress callbacks so the first scan doesn't stall
 * mysteriously, and surfaces a clean error when Play Services can't deliver
 * it (missing/disabled Play Services, download failure).
 *
 * <p>All callbacks run on the main thread. The returned recognizer must be
 * closed by the caller when done.
 */
public final class OcrModelManager {

    /** Callbacks for the model-preparation flow. */
    public interface Listener {
        /**
         * Model is being prepared; show a "preparing scanner" progress UI.
         * totalBytes is &lt;= 0 when the size is unknown (indeterminate).
         */
        void onPreparing(long bytesDownloaded, long totalBytes);

        /** Model is ready; safe to call recognizer.process(). */
        void onReady(TextRecognizer recognizer);

        /** Model unavailable; show an error and offer retry. */
        void onError();
    }

    private OcrModelManager() {
    }

    /**
     * Converts ML Kit vision text into sortable OCR lines for
     * {@link IngredientSectionExtractor}.
     */
    public static List<IngredientSectionExtractor.OcrLine> toOcrLines(Text visionText) {
        List<IngredientSectionExtractor.OcrLine> out = new ArrayList<>();
        if (visionText == null) {
            return out;
        }
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                int top = box != null ? box.top : 0;
                int bottom = box != null ? box.bottom : 0;
                out.add(new IngredientSectionExtractor.OcrLine(
                        line.getText(), top, bottom));
            }
        }
        return out;
    }

    /** Creates the recognizer and ensures its model, then calls the listener. */
    public static void ensureReady(Context context, Listener listener) {
        TextRecognizer recognizer =
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        ModuleInstallClient client =
                ModuleInstall.getClient(context.getApplicationContext());
        client.areModulesAvailable(recognizer)
                .addOnSuccessListener(response -> {
                    if (response.areModulesAvailable()) {
                        listener.onReady(recognizer);
                    } else {
                        downloadModel(client, recognizer, listener);
                    }
                })
                .addOnFailureListener(e -> listener.onError());
    }

    private static void downloadModel(ModuleInstallClient client,
                                      TextRecognizer recognizer,
                                      Listener listener) {
        listener.onPreparing(0, -1);
        InstallStatusListener statusListener = update -> {
            int state = update.getInstallState();
            if (state == ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED) {
                listener.onReady(recognizer);
            } else if (state == ModuleInstallStatusUpdate.InstallState.STATE_FAILED
                    || state == ModuleInstallStatusUpdate.InstallState.STATE_CANCELED) {
                listener.onError();
            } else {
                ModuleInstallStatusUpdate.ProgressInfo info = update.getProgressInfo();
                long done = info != null ? info.getBytesDownloaded() : 0;
                long total = info != null ? info.getTotalBytesToDownload() : -1;
                listener.onPreparing(done, total);
            }
        };
        ModuleInstallRequest request = ModuleInstallRequest.newBuilder()
                .addApi(recognizer)
                .setListener(statusListener)
                .build();
        client.installModules(request)
                .addOnFailureListener(e -> listener.onError());
    }
}
