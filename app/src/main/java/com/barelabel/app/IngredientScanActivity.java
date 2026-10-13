package com.barelabel.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
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

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CameraX ingredient-label scanner. The user frames the ingredients panel and
 * taps CAPTURE; the next frame is OCR'd with the bundled ML Kit text
 * recognizer and the raw text is returned to MainActivity, which
 * spellchecks it (OcrCorrector) and runs the verdict — no confirmation
 * screen, by design.
 */
public class IngredientScanActivity extends AppCompatActivity {

    public static final String EXTRA_INGREDIENT_TEXT = "extra_ingredient_text";
    private static final int PERMISSION_CAMERA_REQUEST = 102;
    /** Minimum non-whitespace chars for a usable label read. */
    private static final int MIN_TEXT_LENGTH = 15;

    private PreviewView previewView;
    private ExecutorService cameraExecutor;
    private TextRecognizer recognizer;
    private volatile boolean captureRequested = false;
    private volatile boolean done = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        previewView = new PreviewView(this);
        root.addView(previewView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button captureButton = new Button(this);
        captureButton.setText("CAPTURE LABEL");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.gravity = Gravity.CENTER_HORIZONTAL;
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        btnLp.setMargins(margin, margin, margin, margin);
        root.addView(captureButton, btnLp);
        captureButton.setOnClickListener(v -> {
            if (!done) {
                captureRequested = true;
                Toast.makeText(this, "Reading label…", Toast.LENGTH_SHORT).show();
            }
        });

        setContentView(root);

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.CAMERA}, PERMISSION_CAMERA_REQUEST);
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

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    String text = visionText.getText() == null
                            ? "" : visionText.getText().trim();
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
