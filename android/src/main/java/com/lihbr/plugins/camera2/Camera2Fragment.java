package com.lihbr.plugins.camera2;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.exifinterface.media.ExifInterface;

import android.icu.text.SimpleDateFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Camera2Fragment extends Fragment {
    public interface Camera2EventListeners {
        void onStart();
        void onCapture();
        void onPIPSetPosition();
    }

    private final Camera2EventListeners eventListeners;

    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final int REQUEST_CAMERA_PERMISSION = 1;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "Camera2Fragment";

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;

    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;

    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;

    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
            if (vfWidth != 0 && vfHeight != 0) {
                setViewFinderSize(vfWidth, vfHeight);
            }
            openCamera(vfWidth > 0 ? vfWidth : width, vfHeight > 0 ? vfHeight : height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) {
            configureViewFinderTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {
        }

    };

    private ConstraintLayout mPIPContainer;
    private AutoFitTextureView mPIPTextureView;
    private int pipWidth;
    private int pipHeight;
    private int pipX = 0;
    private int pipY = 0;
    private final TextureView.SurfaceTextureListener mPIPSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
            if (pipWidth != 0 && pipHeight != 0) {
                setPIPSize(pipWidth, pipHeight);
            }
            setPIPPosition(pipX, pipY);

            if (mCameraDevice != null) {
                createCameraPreviewSession();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) {
            configurePIPTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {
        }
    };

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String mCameraId;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    /**
     * The {@link Size} of camera preview.
     */
    private Size mPreviewSize;

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }

    };

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;

    /**
     * This is the output file for our picture.
     */
    private File mPicture;
    private File mThumbnail;
    private Integer mThumbnailWidth;
    private Integer mThumbnailHeight;
    private Integer mThumbnailQuality;

    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            mBackgroundHandler.post(
                    new ImageSaver(
                            reader.acquireNextImage(),
                            mPicture,
                            (float) vfWidth / vfHeight,
                            mCaptureResult,
                            mThumbnail,
                            mThumbnailWidth,
                            mThumbnailHeight,
                            mThumbnailQuality
                    )
            );
        }

    };

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;

    /**
     * {@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
     */
    private CaptureRequest mPreviewRequest;

    /**
     * The current state of camera state for taking pictures.
     *
     * @see #mCaptureCallback
     */
    private int mState = STATE_PREVIEW;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * Whether the current camera device supports Flash or not.
     */
    private boolean mFlashSupported;

    /**
     * Orientation of the camera sensor
     */
    private int mSensorOrientation;

    private CaptureResult mCaptureResult;

    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private final CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            mCaptureResult = result;

            switch (mState) {
                case STATE_PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case STATE_WAITING_LOCK: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        captureStillPicture();
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState || seekFocus == -1) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (
                                aeState == null
                                || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
                                || (seekIso != -1 || seekSs != -1)
                        ) {
                            mState = STATE_PICTURE_TAKEN;
                            captureStillPicture();
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);

                    if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            process(result);
        }

    };

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output
     *                          class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                                          int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (!bigEnough.isEmpty()) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (!notBigEnough.isEmpty()) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    CameraCharacteristics characteristics;
    private int vfWidth;
    private int vfHeight;
    private float seekFocus = -1;
    private long seekSs = 5_000_000L;
    private float seekAperture = -1;
    private int seekIso = 1_600;
    private int seekExposureCompensation = 0;

    public Camera2Fragment(Camera2EventListeners eventListeners) {
        this.eventListeners = eventListeners;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera2, container, false);
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        mTextureView = view.findViewById(R.id.texture);
        mPIPContainer = view.findViewById(R.id.container);
        disableClipOnParents(view);
    }
    private void disableClipOnParents(View view) {
        if (view == null || view.getParent() == null) {
            return;
        }

        if (view instanceof ViewGroup) {
            ((ViewGroup) view).setClipChildren(false);
        }

        if (view.getParent() instanceof View) {
            disableClipOnParents((View) view.getParent());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private boolean requestCameraPermission() {
        if (
                ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
                        ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                    requireActivity(),
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_CAMERA_PERMISSION
            );

            return true;
        }

        return false;
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    @SuppressWarnings({"SuspiciousNameCombination", "CallToPrintStackTrace"})
    private void setUpCameraOutputs(int width, int height) {
        Activity activity = getActivity();
        assert activity != null;

        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                characteristics
                        = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // For still image captures, we use the largest available size.
                Size largest = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new CompareSizesByArea());

                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, /*maxImages*/2);
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                //noinspection ConstantConditions
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e(TAG, "Display rotation is invalid: " + displayRotation);
                }

                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                // Danger! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, largest);

                // Check if the flash is supported.
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                mFlashSupported = available != null && available;

                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Opens the camera specified by {@link Camera2Fragment#mCameraId}.
     */
    @SuppressWarnings({"CallToPrintStackTrace", "MissingPermission"})
    private void openCamera(int width, int height) {
        if (requestCameraPermission()) return;

        setUpCameraOutputs(width, height);
        configureViewFinderTransform(width, height);

        /*
         * Delay the opening of the camera until (hopefully) layout has been fully settled.
         * Otherwise there is a chance the preview will be distorted (tested on Sony Xperia Z3 Tablet Compact).
         */
        mTextureView.post(new Runnable() {
            @Override
            public void run() {
                /*
                 * Carry out the camera opening in a background thread so it does not cause lag
                 * to any potential running animation.
                 */
                mBackgroundHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        CameraManager manager = (CameraManager) requireContext().getSystemService(Context.CAMERA_SERVICE);

                        try {
                            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                                throw new RuntimeException("Time out waiting to lock camera opening.");
                            }
                            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        } catch (InterruptedException e) {
                            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
                        }
                    }
                });
            }
        });
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    public void setViewFinderSize(int width, int height) {
        vfWidth = width;
        vfHeight = height;
        setTextureViewSize(mTextureView, width, height);
    }

    public void setPIPSize(int width, int height) {
        pipWidth = width;
        pipHeight = height;
        setTextureViewSize(mPIPTextureView, width, height);
    }

    private void setTextureViewSize(AutoFitTextureView textureView, int width, int height) {
        if (textureView != null && textureView.isAvailable()) {
            Activity activity = getActivity();

            if (activity != null) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        textureView.setLayoutParams(new ViewGroup.LayoutParams(width, height));
                        textureView.setAspectRatio(width, height);
                    }
                });
            }
        }
    }

    public void setPIPPosition(int x, int y) {
        pipX = x;
        pipY = y;

        // setTextureViewPosition(mPIPTextureView, x, y);

        // We take a dirty shortcut here to avoid messing with the transform matrix of the texture view for now
        if (mPIPContainer != null) {
            Activity activity = getActivity();

            if (activity != null) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mPIPContainer.setTranslationX(x);
                        mPIPContainer.setTranslationY(y);

                        eventListeners.onPIPSetPosition();
                    }
                });
            }
        }
    }

    private void setTextureViewPosition(AutoFitTextureView textureView, int x, int y) {
        if (textureView != null && textureView.isAvailable()) {
            Activity activity = getActivity();

            if (activity != null) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        textureView.setTranslationX(x);
                        textureView.setTranslationY(y);
                    }
                });
            }
        }
    }

    public void setFocus(float focus) {
        seekFocus = focus;
        setCaptureBuilderFocus(mPreviewRequestBuilder);
    }

    public Range<Long> getShutterSpeedRange() {
        return characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
    }
    public void setShutterSpeed(long shutterSpeed) {
        seekSs = shutterSpeed;
        setCaptureBuilderSs(mPreviewRequestBuilder);
        setRepeatingRequest();
    }

    public float[] getApertureRange() {
        return characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
    }

    public void setAperture(float aperture) {
        seekAperture = aperture;
        setCaptureBuilderAperture(mPreviewRequestBuilder);
        setRepeatingRequest();
    }

    public Range<Integer> getIsoRange() {
        return characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
    }

    public void setIso(int iso) {
        seekIso = iso;
        setCaptureBuilderIso(mPreviewRequestBuilder);
        setRepeatingRequest();
    }

    public Range<Integer> getExposureCompensationRange() {
        return characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
    }

    public Rational getExposureCompensationStep() {
        return characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
    }

    public void setExposureCompensation(int exposureCompensation) {
        seekExposureCompensation = exposureCompensation;
        setCaptureBuilderExposureCompensation(mPreviewRequestBuilder);
        setRepeatingRequest();
    }

    /**
     * Initiate a still image capture.
     */
    public void takePicture(String picturePath) {
        mPicture = new File(Environment.getExternalStorageDirectory(), picturePath);
        mThumbnail = null;
        mThumbnailWidth = null;
        mThumbnailHeight = null;
        mThumbnailQuality = null;
        lockFocus();
    }

    public void takePicture(String picturePath, String thumbnailPath, Integer thumbnailWidth, Integer thumbnailHeight, Integer thumbnailQuality) {
        mPicture = new File(Environment.getExternalStorageDirectory(), picturePath);
        mThumbnail = new File(Environment.getExternalStorageDirectory(), thumbnailPath);
        mThumbnailWidth = thumbnailWidth;
        mThumbnailHeight = thumbnailHeight;
        mThumbnailQuality = thumbnailQuality;
        lockFocus();
    }

    public void openPIP(int width, int height, int x, int y) {
        Activity activity = getActivity();

        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // Using this dirty trick for now to value smoothness over performances
                    if (mPIPTextureView != null) {
                        mPIPContainer.setAlpha(1);
                        return;
                    }

                    mPIPTextureView = new AutoFitTextureView(requireContext());
                    mPIPContainer.setAlpha(0);
                    mPIPContainer.addView(mPIPTextureView, new ConstraintLayout.LayoutParams(
                            ConstraintLayout.LayoutParams.MATCH_PARENT,
                            ConstraintLayout.LayoutParams.MATCH_PARENT
                    ));
                    mPIPContainer.bringToFront();
                    setPIPPosition(x, y);
                    setPIPSize(width, height);
                    mPIPTextureView.setSurfaceTextureListener(mPIPSurfaceTextureListener);
                    mPIPContainer.setAlpha(1);
                }
            });
        }
    }

    public void closePIP() {
        Activity activity = getActivity();

        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // Using this dirty trick for now to value smoothness over performances
                    mPIPContainer.setAlpha(0);

                    // mPIPContainer.removeView(mPIPTextureView);
                    // mPIPTextureView.destroyDrawingCache();
                    // mPIPTextureView = null;

                    // createCameraPreviewSession();
                }
            });
        }
    }

    public void dispatchTouchEvent(MotionEvent event) {
        if (mTextureView != null && mTextureView.isAvailable()) {
            mTextureView.dispatchTouchEvent(event);
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    @SuppressWarnings({"CallToPrintStackTrace"})
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    @SuppressWarnings({"CallToPrintStackTrace"})
    private void createCameraPreviewSession() {
        try {
            if (mCaptureSession != null) {
                mCaptureSession.stopRepeating();
                mCaptureSession.abortCaptures();
                mCaptureSession.close();
                mCaptureSession = null;
            }

            SurfaceTexture vfTexture = mTextureView.getSurfaceTexture();
            assert vfTexture != null;
            vfTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            Surface vfSurface = new Surface(vfTexture);

            Surface pipSurface = null;
            if (mPIPTextureView != null && mPIPTextureView.isAvailable()) {
                SurfaceTexture pipTexture = mPIPTextureView.getSurfaceTexture();
                assert pipTexture != null;
                // TODO: It'll be nice to compute that properly
                pipTexture.setDefaultBufferSize(mPreviewSize.getWidth() / 4, mPreviewSize.getHeight() / 4);
                pipSurface = new Surface(pipTexture);
            }

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL);
            mPreviewRequestBuilder.addTarget(vfSurface);

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(vfSurface);

            if (pipSurface != null) {
                mPreviewRequestBuilder.addTarget(pipSurface);
                surfaces.add(pipSurface);
            }

            surfaces.add(mImageReader.getSurface());

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (mCameraDevice == null) {
                                return;
                            }

                            mCaptureSession = cameraCaptureSession;
                            setCaptureBuilder(mPreviewRequestBuilder);
                            setRepeatingRequest();

                            eventListeners.onStart();
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            Log.d(TAG, "Failed");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setRepeatingRequest() {
        try {
            mPreviewRequest = mPreviewRequestBuilder.build();
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException ignored) {}
    }

    private void setCaptureBuilder(CaptureRequest.Builder captureBuilder) {
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

        setCaptureBuilderSs(captureBuilder);
        setCaptureBuilderAperture(captureBuilder);
        setCaptureBuilderIso(captureBuilder);
        setCaptureBuilderSs(captureBuilder);
        setCaptureBuilderExposureCompensation(captureBuilder);
        setCaptureBuilderFocus(captureBuilder);

        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
    }

    private void setCaptureBuilderSs(CaptureRequest.Builder captureBuilder) {
        setCaptureBuilderIso(captureBuilder);
    }

    private void setCaptureBuilderIso(CaptureRequest.Builder captureBuilder) {
        if (seekSs < 0 || seekIso < 0) {
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            captureBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW);
        } else {
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            captureBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_MANUAL);

            captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, seekSs);
            captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, seekIso);
        }
    }

    private void setCaptureBuilderAperture(CaptureRequest.Builder captureBuilder) {
        if (seekAperture > 0) {
            captureBuilder.set(CaptureRequest.LENS_APERTURE, seekAperture);
        }
    }

    private void setCaptureBuilderExposureCompensation(CaptureRequest.Builder captureBuilder) {
        captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, seekExposureCompensation);
    }

    private void setCaptureBuilderFocus(CaptureRequest.Builder captureBuilder) {
        if (seekFocus < 0) {
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        } else {
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, seekFocus);
        }
    }

    private void configureViewFinderTransform(int viewWidth, int viewHeight) {
        configureTransform(mTextureView, viewWidth, viewHeight);
    }

    private void configurePIPTransform(int viewWidth, int viewHeight) {
        configureTransform(mPIPTextureView, viewWidth, viewHeight);
    }

    /**
     * Configures the necessary {@link Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param textureView  The width of `mTextureView`
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(TextureView textureView, int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == textureView || null == mPreviewSize || null == activity) {
            return;
        }

        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getWidth(), mPreviewSize.getHeight());

        int sensorOrientation = (360 - mSensorOrientation) % 360;
        int rotationDegrees = getDisplayRotation();

        float w, h;
        if ((sensorOrientation - rotationDegrees) % 180 == 0) {
            w = bufferRect.width();
            h = bufferRect.height();
        } else {
            // Swap the width and height if the sensor orientation and display rotation don't match
            w = bufferRect.height();
            h = bufferRect.width();
        }

        float viewAspectRatio = viewRect.width()/viewRect.height();
        float imageAspectRatio = w/h;
        final PointF scale;
        // This will make the camera frame fill the texture view, if you'd like to fit it into the view swap the "<" sign for ">"
        if (viewAspectRatio < imageAspectRatio) {
            // If the view is "thinner" than the image constrain the height and calculate the scale for the texture width
            scale = new PointF((viewRect.height() / viewRect.width()) * ((float) bufferRect.height() / (float) bufferRect.width()), 1f);
        } else {
            scale = new PointF(1f, (viewRect.width() / viewRect.height()) * ((float) bufferRect.width() / (float) bufferRect.height()));
        }

        if (textureView == mPIPTextureView) {
            Log.e(TAG, "ar: " + viewAspectRatio + ", iar: " + imageAspectRatio + ", scaleX: " + scale.x + ", scaleY: " + scale.y);
        } else {
            Log.e(TAG, "main: ar: " + viewAspectRatio + ", iar: " + imageAspectRatio + ", scaleX: " + scale.x + ", scaleY: " + scale.y);
        }

        if (rotationDegrees % 180 != 0) {
            // If we need to rotate the texture 90º we need to adjust the scale
            float multiplier = viewAspectRatio < imageAspectRatio ? w/h : h/w;
            scale.x *= multiplier;
            scale.y *= multiplier;

            if (textureView == mPIPTextureView) {
                Log.e(TAG, "multiplier: " + multiplier + ", scaleX: " + scale.x + ", scaleY: " + scale.y);
            } else {
                Log.e(TAG, "main: multiplier: " + multiplier + ", scaleX: " + scale.x + ", scaleY: " + scale.y);
            }
        }

        Matrix matrix = new Matrix();
        // Set the scale
        matrix.setScale(scale.x, scale.y, viewRect.centerX(), viewRect.centerY());
        if (rotationDegrees != 0) {
            // Set rotation of the device isn't upright
            matrix.postRotate(-rotationDegrees, viewRect.centerX(), viewRect.centerY());
        }
        // Transform the texture
        textureView.setTransform(matrix);
    }

    private int getDisplayRotation() {
        return switch (mTextureView.getDisplay().getRotation()) {
            case Surface.ROTATION_0 -> 0;
            case Surface.ROTATION_90 -> 90;
            case Surface.ROTATION_180 -> 180;
            case Surface.ROTATION_270 -> 270;
            default -> 0;
        };
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    @SuppressWarnings({"CallToPrintStackTrace"})
    private void lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    @SuppressWarnings({"CallToPrintStackTrace"})
    private void runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * {@link #mCaptureCallback} from both {@link #lockFocus()}.
     */
    CaptureRequest.Builder captureBuilder;

    @SuppressWarnings({"CallToPrintStackTrace"})
    private void captureStillPicture() {
        try {
            final Activity activity = getActivity();
            if (null == activity || null == mCameraDevice) {
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            setCaptureBuilder(captureBuilder);

            // Orientation
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));

            CameraCaptureSession.CaptureCallback CaptureCallback = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                Log.d(TAG, mPicture.toString());
                unlockFocus();
                }
            };

            mCaptureSession.stopRepeating();
            mCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the JPEG orientation from the specified screen rotation.
     *
     * @param rotation The screen rotation.
     * @return The JPEG orientation (one of 0, 90, 270, and 360)
     */
    private int getOrientation(int rotation) {
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    @SuppressWarnings({"CallToPrintStackTrace"})
    private void unlockFocus() {
        try {
            // Reset the auto-focus trigger
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            // setAutoFlash(mPreviewRequestBuilder);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW;
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        eventListeners.onCapture();
    }

    /**
     * Saves a JPEG {@link Image} into the specified {@link File}.
     */
    private static class ImageSaver implements Runnable {

        /**
         * The JPEG image
         */
        private final Image mImage;
        /**
         * The file we save the image into.
         */
        private final File mPicture;
        private final Float mRatio;
        private final CaptureResult mCaptureResult;
        private final File mThumbnail;
        private final Integer mThumbnailWidth;
        private final Integer mThumbnailHeight;
        private final Integer mThumbnailQuality;

        public ImageSaver(
                Image image,
                File picture,
                Float ratio,
                CaptureResult captureResult,
                File thumbnail,
                Integer thumbnailWidth,
                Integer thumbnailHeight,
                Integer thumbnailQuality
        ) {
            mImage = image;
            mPicture = picture;
            mRatio = ratio;
            mCaptureResult = captureResult;
            mThumbnail = thumbnail;
            mThumbnailWidth = thumbnailWidth;
            mThumbnailHeight = thumbnailHeight;
            mThumbnailQuality = thumbnailQuality;
        }

        @Override
        @SuppressWarnings({"CallToPrintStackTrace"})
        public void run() {
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.capacity()];
            buffer.get(bytes);

            // Crop image to aspect ratio
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

            // Original image dimensions
            int originalWidth = bitmap.getWidth();
            int originalHeight = bitmap.getHeight();

            // Desired aspect ratio

            // Calculate the desired crop dimensions
            int desiredWidth = originalWidth;
            int desiredHeight = (int) (originalWidth / mRatio);

            if (desiredHeight > originalHeight) {
                desiredHeight = originalHeight;
                desiredWidth = (int) (originalHeight * mRatio);
            }

            // Calculate the starting points for cropping (centered)
            int startX = (originalWidth - desiredWidth) / 2;
            int startY = (originalHeight - desiredHeight) / 2;

            Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, startX, startY, desiredWidth, desiredHeight);

            FileOutputStream output = null;
            FileOutputStream thumbnailOutput = null;
            try {
                output = new FileOutputStream(mPicture);
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, output);

                // EXIF
                ExifInterface exif = new ExifInterface(mPicture.getAbsoluteFile());

                Date currentDate = new Date();
                String formattedDate = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    formattedDate = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault()).format(currentDate);
                }
                if (formattedDate != null) exif.setAttribute(ExifInterface.TAG_DATETIME, formattedDate);

                Integer iso = mCaptureResult.get(CaptureResult.SENSOR_SENSITIVITY);
                if (iso != null) exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString());

                Long shutterSpeed = mCaptureResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                if (shutterSpeed != null) exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, String.valueOf(shutterSpeed / 1_000_000_000F));

                Float aperture = mCaptureResult.get(CaptureResult.LENS_APERTURE);
                if (aperture != null) exif.setAttribute(ExifInterface.TAG_F_NUMBER, aperture.toString());

                Float focalLength = mCaptureResult.get(CaptureResult.LENS_FOCAL_LENGTH);
                // We use this tag instead because TAG_FOCAL_LENGTH doesn't work correctly, always returning null
                if (focalLength != null) exif.setAttribute(ExifInterface.TAG_MAKER_NOTE, focalLength.toString());

                exif.saveAttributes();

                if (mThumbnail != null && mThumbnailWidth != null && mThumbnailWidth > 0 && mThumbnailHeight != null && mThumbnailHeight > 0) {
                    thumbnailOutput = new FileOutputStream(mThumbnail);

                    Bitmap resizedBitmap = Bitmap.createScaledBitmap(croppedBitmap, mThumbnailWidth, mThumbnailHeight, true);
                    resizedBitmap.compress(Bitmap.CompressFormat.JPEG, mThumbnailQuality == null ? 80 : mThumbnailQuality, thumbnailOutput);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (null != thumbnailOutput) {
                    try {
                        thumbnailOutput.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }
}