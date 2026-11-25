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
package com.zhihu.matisse.camera;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.zhihu.matisse.internal.ui.camera.CaptureActivity;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Entry for launching Matisse's built-in camera screen directly, without opening the media
 * selector.
 * <p>
 * Usage from an {@link Activity}:
 * <pre>
 * CameraCapture.from(activity)
 *         .uriAuthority("com.example.fileprovider")   // optional, to also receive a content Uri
 *         .forResult(REQUEST_CODE_CAPTURE);
 * </pre>
 * and in {@link Activity#onActivityResult(int, int, Intent)}:
 * <pre>
 * if (resultCode == RESULT_OK) {
 *     String path = CameraCapture.obtainPath(data);
 *     Uri uri = CameraCapture.obtainUri(data);
 * }
 * </pre>
 * By default the photo is saved into the public {@code Pictures/Matisse} directory, so it is
 * indexed into the system gallery after the user confirms it. Call {@link #directory(String)}
 * or {@link #outputPath(String)} to change the destination.
 */
public final class CameraCapture {

    private static final String DEFAULT_DIRECTORY = "Matisse";

    private final WeakReference<Activity> mActivity;
    private final WeakReference<Fragment> mFragment;

    private String mDirectory = DEFAULT_DIRECTORY;
    private String mOutputPath;
    private String mUriAuthority;

    private CameraCapture(Activity activity, Fragment fragment) {
        mActivity = new WeakReference<>(activity);
        mFragment = new WeakReference<>(fragment);
    }

    /**
     * Start the built-in camera from an {@link Activity}.
     *
     * @param activity Activity instance.
     * @return {@link CameraCapture} instance.
     */
    public static CameraCapture from(@NonNull Activity activity) {
        return new CameraCapture(activity, null);
    }

    /**
     * Start the built-in camera from a {@link Fragment}.
     *
     * @param fragment Fragment instance.
     * @return {@link CameraCapture} instance.
     */
    public static CameraCapture from(@NonNull Fragment fragment) {
        return new CameraCapture(fragment.getActivity(), fragment);
    }

    /**
     * Sets the sub directory under the public {@code Pictures} folder where photos are saved.
     * Default value is {@code Matisse}. Ignored when {@link #outputPath(String)} is set.
     *
     * @param directory directory name, e.g. {@code "MyApp"}.
     * @return {@link CameraCapture} for fluent API.
     */
    public CameraCapture directory(String directory) {
        mDirectory = directory;
        return this;
    }

    /**
     * Sets the exact file path (including the file name) to save the captured photo to.
     * The parent directory is created automatically when missing.
     *
     * @param outputPath absolute file path.
     * @return {@link CameraCapture} for fluent API.
     */
    public CameraCapture outputPath(String outputPath) {
        mOutputPath = outputPath;
        return this;
    }

    /**
     * Sets the authority of the caller's {@link androidx.core.content.FileProvider}, so that a
     * content {@link Uri} pointing to the captured photo can be returned as well.
     * <p>
     * The uri is only generated when the path of the photo is covered by the
     * {@code file_paths} configuration of that provider; otherwise {@link #obtainUri(Intent)}
     * returns {@code null} and the photo path should be used instead.
     *
     * @param authority FileProvider authority declared by the caller.
     * @return {@link CameraCapture} for fluent API.
     */
    public CameraCapture uriAuthority(String authority) {
        mUriAuthority = authority;
        return this;
    }

    /**
     * Start the built-in camera and wait for the result.
     *
     * @param requestCode Identity of the request Activity or Fragment.
     */
    public void forResult(int requestCode) {
        Activity activity = mActivity.get();
        if (activity == null) {
            return;
        }
        File photoFile = createImageFile(activity);
        if (photoFile == null) {
            return;
        }
        Intent intent = new Intent(activity, CaptureActivity.class);
        intent.putExtra(CaptureActivity.EXTRA_OUTPUT_PATH, photoFile.getAbsolutePath());
        if (!TextUtils.isEmpty(mUriAuthority)) {
            intent.putExtra(CaptureActivity.EXTRA_URI_AUTHORITY, mUriAuthority);
        }

        Fragment fragment = mFragment != null ? mFragment.get() : null;
        if (fragment != null) {
            fragment.startActivityForResult(intent, requestCode);
        } else {
            activity.startActivityForResult(intent, requestCode);
        }
    }

    /**
     * Obtain the path of the captured photo in the starting Activity or Fragment.
     *
     * @param data Intent passed by {@link Activity#onActivityResult(int, int, Intent)} or
     *             {@link Fragment#onActivityResult(int, int, Intent)}.
     * @return absolute path of the photo, or {@code null} when no photo was captured.
     */
    @Nullable
    public static String obtainPath(@Nullable Intent data) {
        return data == null ? null : data.getStringExtra(CaptureActivity.EXTRA_RESULT_PATH);
    }

    /**
     * Obtain the content {@link Uri} of the captured photo in the starting Activity or Fragment.
     *
     * @param data Intent passed by {@link Activity#onActivityResult(int, int, Intent)} or
     *             {@link Fragment#onActivityResult(int, int, Intent)}.
     * @return content uri of the photo, or {@code null} when {@link #uriAuthority(String)} was
     * not provided or the photo is not covered by the provider's {@code file_paths}.
     */
    @Nullable
    public static Uri obtainUri(@Nullable Intent data) {
        return data == null ? null : data.getParcelableExtra(CaptureActivity.EXTRA_RESULT_URI);
    }

    private File createImageFile(Activity activity) {
        if (!TextUtils.isEmpty(mOutputPath)) {
            File file = new File(mOutputPath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            return file;
        }
        File storageDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        if (!TextUtils.isEmpty(mDirectory)) {
            storageDir = new File(storageDir, mDirectory);
        }
        if (!storageDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            storageDir.mkdirs();
        }
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        return new File(storageDir, String.format("JPEG_%s.jpg", timeStamp));
    }
}