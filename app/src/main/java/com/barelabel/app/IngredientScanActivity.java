package com.barelabel.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.barelabel.app.ocr.OcrModelManager;
import com.barelabel.app.search.IngredientSectionExtractor;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognizer;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CameraX ingredient-label scanner. The user frames the ingredients panel and
 * taps CAPTURE; the next frame is OCR'd with the Play Services ML Kit text
 * recognizer and the raw text is returned to MainActivity, which
 * spellchecks it (OcrCorrector) and runs the verdict — no confirmation
 * screen, by design.
 *
 * <p>The unbundled recognition model is prepared via {@link OcrModelManager}
 * with a progress bar before the capture button enables.
 */
public class IngredientScanActivity extends AppCompatActivity {

    public static final String EXTRA_INGREDIENT_TEXT = "extra_ingredient_text";
    private static final int PERMISSION_CAMERA_REQUEST = 102;
    /** Minimum non-whitespace chars for a usable label read. */
    private static final int MIN_TEXT_LENGTH = 15;

    private PreviewView previewView;
    private ExecutorService cameraExecutor;
    private TextRecognizer recognizer;
    private ProgressBar modelProgress;
    private TextView modelStatus;
    private Button captureButton;
    private volatile boolean captureRequested = false;
    private volatile boolean done = false;
    private volatile boolean modelReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        previewView = new PreviewView(this);
        root.addView(previewView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Model-preparation row: progress bar + status, hidden once ready.
        LinearLayout prepRow = new LinearLayout(this);
        prepRow.setOrientation(LinearLayout.HORIZONTAL);
        prepRow.setGravity(Gravity.CENTER_VERTICAL);
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams prepLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        prepLp.setMargins(margin, margin, margin, 0);
        modelProgress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        modelStatus = new TextView(this);
        modelStatus.setText("Preparing scanner…");
        modelStatus.setPadding(margin, 0, 0, 0);
        prepRow.addView(modelProgress, barLp);
        prepRow.addView(modelStatus);
        root.addView(prepRow, prepLp);

        captureButton = new Button(this);
        captureButton.setText("CAPTURE LABEL");
        captureButton.setEnabled(false);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.gravity = Gravity.CENTER_HORIZONTAL;
        btnLp.setMargins(margin, margin, margin, margin);
        root.addView(captureButton, btnLp);
        captureButton.setOnClickListener(v -> {
            if (done) {
                return;
            }
            if (!modelReady) {
                // Error state doubles as retry.
                prepareModel();
                return;
            }
            captureRequested = true;
            Toast.makeText(this, "Reading label…", Toast.LENGTH_SHORT).show();
        });

        setContentView(root);

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.CAMERA}, PERMISSION_CAMERA_REQUEST);
        }
        prepareModel();
    }

    /** Pre-downloads the OCR model with progress; enables capture when ready. */
    private void prepareModel() {
        modelReady = false;
        captureButton.setEnabled(false);
        captureButton.setText("CAPTURE LABEL");
        prepRowVisible(true);
        OcrModelManager.ensureReady(this, new OcrModelManager.Listener() {
            @Override
            public void onPreparing(long bytesDownloaded, long totalBytes) {
                if (totalBytes > 0) {
                    modelProgress.setIndeterminate(false);
                    modelProgress.setMax(100);
                    modelProgress.setProgress(
                            (int) (100 * bytesDownloaded / totalBytes));
                } else {
                    modelProgress.setIndeterminate(true);
                }
                modelStatus.setText("Preparing scanner…");
            }

            @Override
            public void onReady(TextRecognizer readyRecognizer) {
                recognizer = readyRecognizer;
                modelReady = true;
                prepRowVisible(false);
                captureButton.setEnabled(true);
            }

            @Override
            public void onError() {
                modelProgress.setIndeterminate(false);
                modelProgress.setProgress(0);
                modelStatus.setText("Couldn't prepare the scanner. Check your connection.");
                captureButton.setText("RETRY");
                captureButton.setEnabled(true);
            }
        });
    }

    private void prepRowVisible(boolean visible) {
        ViewParent parent = modelProgress.getParent();
        if (parent instanceof View) {
            ((View) parent).setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(new Size(1280, 720))
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    private void processImageProxy(ImageProxy imageProxy) {
        if (!captureRequested || done || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }
        captureRequested = false;
        final TextRecognizer activeRecognizer = recognizer;
        if (activeRecognizer == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        activeRecognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    String section = IngredientSectionExtractor.extract(
                            OcrModelManager.toOcrLines(visionText));
                    String text = section.trim();
                    if (!done && text.replaceAll("\\s+", "").length() >= MIN_TEXT_LENGTH) {
                        done = true;
                        Intent intent = new Intent();
                        intent.putExtra(EXTRA_INGREDIENT_TEXT, text);
                        setResult(RESULT_OK, intent);
                        finish();
                    } else if (!done) {
                        Toast.makeText(IngredientScanActivity.this,
                                "No text found — hold steady and try again.",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .addOnFailureListener(e ->
                        Toast.makeText(IngredientScanActivity.this,
                                "Couldn't read the label — try again.",
                                Toast.LENGTH_SHORT).show())
                .addOnCompleteListener(task -> imageProxy.close());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CAMERA_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission required to scan labels.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (recognizer != null) {
            recognizer.close();
        }
    }
}
