package com.lihbr.plugins.camera2;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
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
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.getcapacitor.Logger;

import java.util.List;
import java.util.Objects;

public class Camera2Activity extends Fragment {
    private static final String TAG = "Camera2Activity";
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    public interface Camera2EventListeners {
        void onStart();
    }

    private final Camera2EventListeners eventListeners;
    public Boolean toBack;

    public Camera2Activity(Camera2EventListeners eventListeners, Boolean toBack) {
        this.eventListeners = eventListeners;
        this.toBack = toBack;
    }

    private Handler backgroundHandler;
    private HandlerThread backgroundHandlerThread;

    public int width = 0;
    public int height = 0;
    public int x = 0;
    public int y = 0;

    public AutoFitTextureView textureView;
    private CameraManager cameraManager;
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCharacteristics cameraCharacteristics;
    private Size previewSize;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession cameraCaptureSession;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.activity_camera2, container, false);

        textureView = view.findViewById(R.id.texture);
        if (textureView != null) {
            textureView.setSurfaceTextureListener(textureListener);
        }

        cameraManager = (CameraManager) requireContext().getSystemService(Context.CAMERA_SERVICE);

        startBackgroundThread();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.e(TAG, "onResume");
        startBackgroundThread();
        if (textureView.isAvailable()) {
            startCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        Log.e(TAG, "onPause");
        stopBackgroundThread();
        super.onPause();
    }

    public void setRect(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        if (cameraCharacteristics != null && previewSize != null) {
            setTextureTransform();
        }
    }

    private void startCamera() {
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            for (String id : cameraIds) {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(id);

                Integer cameraLensFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                assert cameraLensFacing != null;
                if (cameraLensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                cameraId = id;
                break;
            }
            assert cameraId != null;

            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
            previewSize = getPreviewSize();
            if (width == 0) width = previewSize.getWidth();
            if (height == 0) height = previewSize.getHeight();

            // Add permission for camera and let user grant the permission
            if (
                    ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
                            ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                        requireActivity(),
                        new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_CAMERA_PERMISSION
                );
                return;
            }

            cameraManager.openCamera(cameraId, cameraStateCallback, null);
        } catch (CameraAccessException e) {
            Toast.makeText(requireContext(), "CameraAccessException", Toast.LENGTH_SHORT).show();
        }
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            startCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;

            Toast.makeText(requireContext(), "Error when trying to connect camera", Toast.LENGTH_SHORT).show();
        }
    };

    private void startCameraPreview() {
        try {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            assert surfaceTexture != null;

            setTextureTransform();

            Surface previewSurface = new Surface(surfaceTexture);

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(previewSurface);

            cameraDevice.createCaptureSession(List.of(previewSurface), captureStateCallback, null);
        } catch (CameraAccessException e) {
            Toast.makeText(requireContext(), "CameraAccessException", Toast.LENGTH_SHORT).show();
        }
    }

    private final CameraCaptureSession.StateCallback captureStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession _cameraCaptureSession) {
            //The camera is already closed
            if (null == cameraDevice) {
                return;
            }
            // When the session is ready, we start displaying the preview.
            cameraCaptureSession = _cameraCaptureSession;
            updatePreview();
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            Toast.makeText(requireContext(), "Configuration change", Toast.LENGTH_SHORT).show();
        }
    };

    protected void updatePreview() {
        if (cameraDevice == null) {
            Log.e(TAG, "updatePreview error");
        }

        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

        try {
            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);

            eventListeners.onStart();
        } catch (CameraAccessException e) {
            Toast.makeText(requireContext(), "CameraAccessException", Toast.LENGTH_SHORT).show();
        }
    }

    private final AutoFitTextureView.SurfaceTextureListener textureListener = new AutoFitTextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            startCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        }
    };

    void setTextureTransform() {
        // int width = previewSize.getWidth();
        // int height = previewSize.getHeight();

        // Set correct aspect ratio
        textureView.setAspectRatio(width, height);
        textureView.setLayoutParams(new ViewGroup.LayoutParams(width, height));

        // Indicate the size of the buffer the texture should expect
        Objects.requireNonNull(textureView.getSurfaceTexture()).setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

        // Save the texture dimensions in a rectangle
        RectF viewRect = new RectF(0,0, width, height);
        RectF imageRect = new RectF(0, 0, previewSize.getWidth(), previewSize.getHeight());

        int sensorOrientation = getCameraSensorOrientation(cameraCharacteristics);
        // Determine the rotation of the display
        float rotationDegrees = 0;
        try {
            rotationDegrees = (float) getDisplayRotation();
        } catch (Exception ignored) {
        }
        float w, h;
        if ((sensorOrientation - rotationDegrees) % 180 == 0) {
            w = imageRect.width();
            h = imageRect.height();
        } else {
            // Swap the width and height if the sensor orientation and display rotation don't match
            w = imageRect.height();
            h = imageRect.width();
        }
        float viewAspectRatio = viewRect.width()/viewRect.height();
        float imageAspectRatio = w/h;
        final PointF scale;
        // This will make the camera frame fill the texture view, if you'd like to fit it into the view swap the "<" sign for ">"
        if (viewAspectRatio < imageAspectRatio) {
            Logger.warn(TAG, "thinner");
            // If the view is "thinner" than the image constrain the height and calculate the scale for the texture width
            scale = new PointF((viewRect.height() / viewRect.width()) * ((float) imageRect.height() / (float) imageRect.width()), 1f);
        } else {
            Logger.warn(TAG, "thicker");
            scale = new PointF(1f, (viewRect.width() / viewRect.height()) * ((float) imageRect.width() / (float) imageRect.height()));
        }

        Logger.warn(TAG, "scale1: "
                + (viewRect.height() / viewRect.width()) * ((float) imageRect.height() / (float) imageRect.width())
                + ", scale2: "
                + (viewRect.width() / viewRect.height()) * ((float) imageRect.width() / (float) imageRect.height())
        );

        if (rotationDegrees % 180 != 0) {
            // If we need to rotate the texture 90º we need to adjust the scale
            float multiplier = viewAspectRatio < imageAspectRatio ? w/h : h/w;
            scale.x *= multiplier;
            scale.y *= multiplier;

            Logger.warn(TAG, "multiplier: " + multiplier + ", var: " + viewAspectRatio + ", iar: " + imageAspectRatio);
        }

        Logger.warn(TAG, "width: " + width + ", height: " + height + ", rotationDegrees: " + rotationDegrees);

        Logger.warn(TAG, "x: " + scale.x + ", y: " + scale.y + ", cX: " + viewRect.centerX() + ", cY: " + viewRect.centerY());

        Matrix matrix = new Matrix();
        // Set the scale
        matrix.setScale(scale.x, scale.y, viewRect.centerX(), viewRect.centerY());
        if (rotationDegrees != 0) {
            // Set rotation of the device isn't upright
            matrix.postRotate(0 - rotationDegrees, viewRect.centerX(), viewRect.centerY());
        }
        // Transform the texture
        textureView.setTransform(matrix);
    }

    int getDisplayRotation() {
        return switch (textureView.getDisplay().getRotation()) {
            case Surface.ROTATION_0 -> 0;
            case Surface.ROTATION_90 -> 90;
            case Surface.ROTATION_180 -> 180;
            case Surface.ROTATION_270 -> 270;
            default -> 0;
        };
    }

    Size getPreviewSize() {
        StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        assert map != null;
        Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);

        // TODO: decide on which size fits your view size the best

        return previewSizes[0];
    }

    int getCameraSensorOrientation(CameraCharacteristics characteristics) {
        Integer cameraOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        return (360 - (cameraOrientation != null ? cameraOrientation : 0)) % 360;
    }

    protected void startBackgroundThread() {
        backgroundHandlerThread = new HandlerThread("Camera Background");
        backgroundHandlerThread.start();
        backgroundHandler = new Handler(backgroundHandlerThread.getLooper());
    }

    @SuppressWarnings("CallToPrintStackTrace")
    protected void stopBackgroundThread() {
        backgroundHandlerThread.quitSafely();
        try {
            backgroundHandlerThread.join();
            backgroundHandlerThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}