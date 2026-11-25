/*
 * Copyright 2017 Zhihu Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zhihu.matisse.internal.ui.camera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.common.util.concurrent.ListenableFuture;
import com.zhihu.matisse.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * A lightweight fullscreen camera screen used to capture photos without launching the system
 * camera application. The photo is written directly to the file passed by
 * {@link #EXTRA_OUTPUT_PATH}, so the captured result can be delivered back to callers exactly
 * like the system camera flow, without any change to their code.
 * <p>
 * Memory footprint is kept low: preview is rendered by a {@link PreviewView} Surface and
 * {@link ImageCapture} writes the JPEG straight to the destination file instead of decoding
 * bitmaps. Camera resources are bound to the lifecycle and released in {@link #onDestroy()}.
 */
public class CaptureActivity extends AppCompatActivity {

    public static final String EXTRA_OUTPUT_PATH = "extra_output_path";
    public static final String EXTRA_URI_AUTHORITY = "extra_uri_authority";
    public static final String EXTRA_RESULT_PATH = "extra_result_capture_path";
    public static final String EXTRA_RESULT_URI = "extra_result_capture_uri";

    private static final int REQUEST_CODE_CAMERA_PERMISSION = 100;

    private PreviewView mPreviewView;
    private View mShutterButton;
    private ImageButton mSwitchCameraButton;
    private FrameLayout mRoot;
    private ImageView mPhotoPreview;

    private ProcessCameraProvider mCameraProvider;
    private Camera mCamera;
    private ImageCapture mImageCapture;
    private String mOutputPath;
    private String mUriAuthority;
    private int mLensFacing = CameraSelector.LENS_FACING_BACK;
    private boolean mIsCapturing;
    private View mFocusIndicatorView;
    private boolean mConfirming;
    private boolean mSaving;
    private File mTempPhotoFile;
    private Bitmap mPreviewBitmap;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mOutputPath = getIntent().getStringExtra(EXTRA_OUTPUT_PATH);
        mUriAuthority = getIntent().getStringExtra(EXTRA_URI_AUTHORITY);
        if (TextUtils.isEmpty(mOutputPath)) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        setContentView(R.layout.activity_capture_camera);
        enterFullscreen();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mPreviewView = findViewById(R.id.preview_view);
        mShutterButton = findViewById(R.id.button_shutter);
        mSwitchCameraButton = findViewById(R.id.button_switch_camera);
        mRoot = findViewById(R.id.capture_root);
        mPhotoPreview = findViewById(R.id.photo_preview);
        mShutterButton.setEnabled(false);
        mSwitchCameraButton.setVisibility(View.INVISIBLE);

        findViewById(R.id.button_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mConfirming) {
                    // Discard the temporary shot and go back to capturing.
                    cancelConfirm();
                } else {
                    setResult(RESULT_CANCELED);
                    finish();
                }
            }
        });
        mShutterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takePicture();
            }
        });
        mSwitchCameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mConfirming) {
                    confirmPhoto();
                } else {
                    switchCamera();
                }
            }
        });
        mPreviewView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!mConfirming && event.getAction() == MotionEvent.ACTION_UP) {
                    focusAt(event.getX(), event.getY());
                }
                return true;
            }
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CODE_CAMERA_PERMISSION);
        } else {
            // CAMERA permission is granted at install time before Android 6.0.
            showErrorAndFinish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CODE_CAMERA_PERMISSION) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, R.string.capture_permission_denied, Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                try {
                    mCameraProvider = cameraProviderFuture.get();
                } catch (Exception e) {
                    showErrorAndFinish();
                    return;
                }
                bindUseCases();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases() {
        if (mCameraProvider == null) {
            return;
        }
        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(mLensFacing).build();
        if (!hasCamera(selector)) {
            int alternate = mLensFacing == CameraSelector.LENS_FACING_BACK
                    ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            selector = new CameraSelector.Builder().requireLensFacing(alternate).build();
            if (!hasCamera(selector)) {
                showErrorAndFinish();
                return;
            }
            mLensFacing = alternate;
        }

        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        // Photos are always captured in 16:9 (portrait 9:16 after EXIF rotation is applied),
        // capped at 1080p to keep shutter latency and memory footprint low.
        ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(new ResolutionStrategy(new Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build();
        Preview preview = new Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(rotation)
                .build();
        mImageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(95)
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(rotation)
                .build();

        mCameraProvider.unbindAll();
        try {
            mCamera = mCameraProvider.bindToLifecycle(this, selector, preview, mImageCapture);
        } catch (Exception e) {
            showErrorAndFinish();
            return;
        }
        preview.setSurfaceProvider(mPreviewView.getSurfaceProvider());

        mShutterButton.setEnabled(true);
        updateSwitchCameraVisibility();
    }

    private void updateSwitchCameraVisibility() {
        boolean hasFront = hasCamera(new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build());
        boolean hasBack = hasCamera(new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK).build());
        mSwitchCameraButton.setVisibility(hasFront && hasBack ? View.VISIBLE : View.INVISIBLE);
    }

    private boolean hasCamera(CameraSelector selector) {
        if (mCameraProvider == null) {
            return false;
        }
        try {
            return mCameraProvider.hasCamera(selector);
        } catch (CameraInfoUnavailableException e) {
            return false;
        }
    }

    private void switchCamera() {
        if (mCameraProvider == null || mIsCapturing) {
            return;
        }
        // Spin the switch icon a full turn counter-clockwise on every camera switch.
        mSwitchCameraButton.animate()
                .rotation(mSwitchCameraButton.getRotation() - 360f)
                .setDuration(400)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        mLensFacing = mLensFacing == CameraSelector.LENS_FACING_BACK
                ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        bindUseCases();
    }

    private void focusAt(float x, float y) {
        showFocusIndicator(x, y);
        if (mCamera == null) {
            return;
        }
        MeteringPointFactory factory = mPreviewView.getMeteringPointFactory();
        MeteringPoint point = factory.createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(
                point, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE).build();
        try {
            mCamera.getCameraControl().startFocusAndMetering(action);
        } catch (RuntimeException e) {
            // Some cameras (e.g. fixed-focus front cameras) don't support focus metering.
        }
    }

    private void showFocusIndicator(float x, float y) {
        if (mRoot == null) {
            return;
        }
        // Only one focus indicator is kept on screen: a new tap removes the previous one.
        if (mFocusIndicatorView != null) {
            mFocusIndicatorView.animate().cancel();
            mRoot.removeView(mFocusIndicatorView);
            mFocusIndicatorView = null;
        }
        final int size = (int) (getResources().getDisplayMetrics().density * 72f);
        final View indicator = new View(this);
        indicator.setBackgroundResource(R.drawable.ic_camera_focus);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.leftMargin = Math.round(x - size / 2f);
        params.topMargin = Math.round(y - size / 2f);
        mRoot.addView(indicator, params);
        mFocusIndicatorView = indicator;
        indicator.setScaleX(1.2f);
        indicator.setScaleY(1.2f);
        indicator.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
        indicator.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mFocusIndicatorView != indicator) {
                    return;
                }
                indicator.animate().alpha(0f).setDuration(300)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                if (mRoot != null && indicator.getParent() == mRoot) {
                                    mRoot.removeView(indicator);
                                }
                                if (mFocusIndicatorView == indicator) {
                                    mFocusIndicatorView = null;
                                }
                            }
                        }).start();
            }
        }, 700);
    }

    private void takePicture() {
        if (mIsCapturing || mImageCapture == null || TextUtils.isEmpty(mOutputPath)) {
            return;
        }
        mIsCapturing = true;
        mShutterButton.setEnabled(false);
        mSwitchCameraButton.setEnabled(false);

        // The shot is written to a temporary file first; it is copied to the final
        // destination (and handed back to callers) only after the user confirms it.
        mTempPhotoFile = new File(getCacheDir(), "matisse_capture_tmp.jpg");
        if (mTempPhotoFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mTempPhotoFile.delete();
        }
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(mTempPhotoFile).build();
        mImageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (mLensFacing == CameraSelector.LENS_FACING_FRONT) {
                            // The front preview is mirrored, so the temporary shot is mirrored
                            // as well before entering the confirm screen.
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    mirrorPhoto(mTempPhotoFile);
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            enterConfirmMode();
                                        }
                                    });
                                }
                            }, "MatissePhotoMirror").start();
                        } else {
                            enterConfirmMode();
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        mIsCapturing = false;
                        mShutterButton.setEnabled(true);
                        mSwitchCameraButton.setEnabled(true);
                        Toast.makeText(CaptureActivity.this, R.string.capture_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void enterConfirmMode() {
        if (isFinishing() || isDestroyed()
                || mTempPhotoFile == null || !mTempPhotoFile.exists()) {
            return;
        }
        mConfirming = true;
        mIsCapturing = false;
        mShutterButton.setVisibility(View.GONE);
        mSwitchCameraButton.setVisibility(View.VISIBLE);
        mSwitchCameraButton.setImageResource(R.drawable.ic_camera_confirm);
        mSwitchCameraButton.setEnabled(true);

        releasePreviewBitmap();
        mPreviewBitmap = loadPreviewBitmap(mTempPhotoFile);
        if (mPreviewBitmap != null) {
            mPhotoPreview.setImageBitmap(mPreviewBitmap);
            mPhotoPreview.setVisibility(View.VISIBLE);
        }
    }

    private void cancelConfirm() {
        if (!mConfirming) {
            return;
        }
        mConfirming = false;
        deleteTempPhoto();
        mPhotoPreview.setVisibility(View.GONE);
        mPhotoPreview.setImageDrawable(null);
        releasePreviewBitmap();
        mShutterButton.setVisibility(View.VISIBLE);
        mShutterButton.setEnabled(true);
        mSwitchCameraButton.setImageResource(R.drawable.ic_camera_switch);
        mSwitchCameraButton.setEnabled(true);
    }

    private void confirmPhoto() {
        if (!mConfirming || mSaving || mTempPhotoFile == null || !mTempPhotoFile.exists()) {
            return;
        }
        mSaving = true;
        mSwitchCameraButton.setEnabled(false);
        final File tempFile = mTempPhotoFile;
        final File outputFile = new File(mOutputPath);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean saved = copyFile(tempFile, outputFile);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (saved) {
                            //noinspection ResultOfMethodCallIgnored
                            tempFile.delete();
                            mTempPhotoFile = null;
                            if (!isFinishing() && !isDestroyed()) {
                                notifyGallery(outputFile);
                                setResult(RESULT_OK, buildResult(outputFile));
                                finish();
                            }
                        } else {
                            mSaving = false;
                            mSwitchCameraButton.setEnabled(true);
                            if (!isFinishing() && !isDestroyed()) {
                                Toast.makeText(CaptureActivity.this, R.string.capture_failed,
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                });
            }
        }, "MatissePhotoSave").start();
    }

    private Intent buildResult(File photoFile) {
        Intent result = new Intent();
        result.putExtra(EXTRA_RESULT_PATH, photoFile.getAbsolutePath());
        if (!TextUtils.isEmpty(mUriAuthority)) {
            try {
                Uri uri = FileProvider.getUriForFile(this, mUriAuthority, photoFile);
                result.putExtra(EXTRA_RESULT_URI, uri);
            } catch (Exception e) {
                // The provider may not cover this path; callers can still use the file path.
            }
        }
        return result;
    }

    private void deleteTempPhoto() {
        if (mTempPhotoFile != null) {
            //noinspection ResultOfMethodCallIgnored
            mTempPhotoFile.delete();
            mTempPhotoFile = null;
        }
    }

    private static Bitmap loadPreviewBitmap(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        int sampleSize = 1;
        int maxDimension = Math.max(options.outWidth, options.outHeight);
        while (maxDimension / (sampleSize * 2) >= 1920) {
            sampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = sampleSize;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private void releasePreviewBitmap() {
        if (mPreviewBitmap != null) {
            mPreviewBitmap.recycle();
            mPreviewBitmap = null;
        }
    }

    private static boolean copyFile(File source, File target) {
        FileInputStream input = null;
        FileOutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = new FileOutputStream(target);
            byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return true;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return false;
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Makes sure the photo shows up in the system gallery. Only photos located in public
     * directories are indexed by the media scanner; photos kept in app-private directories
     * stay private by design.
     */
    private void notifyGallery(File photoFile) {
        try {
            MediaScannerConnection.scanFile(getApplicationContext(),
                    new String[]{photoFile.getAbsolutePath()}, null, null);
        } catch (Exception e) {
            // Scanning is best-effort; the photo itself has already been saved.
        }
    }

    private static void mirrorPhoto(File file) {
        Bitmap source = null;
        Bitmap mirrored = null;
        FileOutputStream output = null;
        try {
            source = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (source == null) {
                return;
            }
            Matrix matrix = new Matrix();
            matrix.setScale(-1f, 1f);
            mirrored = Bitmap.createBitmap(source, 0, 0,
                    source.getWidth(), source.getHeight(), matrix, false);
            output = new FileOutputStream(file);
            mirrored.compress(Bitmap.CompressFormat.JPEG, 95, output);
        } catch (Exception e) {
            // Keep the original photo if mirroring fails.
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
            if (mirrored != null) {
                mirrored.recycle();
            }
            if (source != null) {
                source.recycle();
            }
        }
    }

    private void enterFullscreen() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void showErrorAndFinish() {
        Toast.makeText(this, R.string.capture_failed, Toast.LENGTH_SHORT).show();
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (mConfirming) {
            cancelConfirm();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (mCameraProvider != null) {
            mCameraProvider.unbindAll();
            mCameraProvider = null;
        }
        mCamera = null;
        mImageCapture = null;
        releasePreviewBitmap();
        mPhotoPreview = null;
        if (!mSaving) {
            deleteTempPhoto();
        }
        super.onDestroy();
    }
}