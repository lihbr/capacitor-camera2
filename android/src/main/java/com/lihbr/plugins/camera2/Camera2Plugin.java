package com.lihbr.plugins.camera2;

import static android.Manifest.permission.CAMERA;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import android.hardware.camera2.CaptureResult;
import android.os.Environment;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Range;
import android.util.TypedValue;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.exifinterface.media.ExifInterface;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

@CapacitorPlugin(
        name = "Camera2",
        permissions = { @Permission(strings = { CAMERA }, alias = Camera2Plugin.CAMERA_PERMISSION_ALIAS) }
)
public class Camera2Plugin extends Plugin implements Camera2Fragment.Camera2EventListeners {
    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "Camera2Plugin";
    static final String CAMERA_PERMISSION_ALIAS = "camera";
    private final int containerViewId = 20;
    private int previousOrientationRequest = -1;

    private Camera2Fragment camera2;
    private DisplayMetrics metrics;
    private boolean toBack;
    private String startCallbackId;
    private String captureCallbackId;

    @PluginMethod
    public void start(PluginCall call) {
        if (PermissionState.GRANTED.equals(getPermissionState(CAMERA_PERMISSION_ALIAS))) {
            _start(call);
        } else {
            requestPermissionForAlias(CAMERA_PERMISSION_ALIAS, call, "handleCameraPermissionResult");
        }
    }

    private void _start(PluginCall call) {
        final Integer x = call.getInt("x", 0);
        final Integer y = call.getInt("y", 0);
        final Integer width = call.getInt("width", 0);
        final Integer height = call.getInt("height", 0);
        final Integer paddingBottom = call.getInt("paddingBottom", 0);
        toBack = Boolean.TRUE.equals(call.getBoolean("toBack", false));
        final Boolean lockOrientation = call.getBoolean("lockAndroidOrientation", false);

        // It feels silly having to assert all of those despite providing default values everywhere...
        assert x != null;
        assert y != null;
        assert width != null;
        assert height != null;
        assert paddingBottom != null;
        assert lockOrientation != null;

        previousOrientationRequest = getBridge().getActivity().getRequestedOrientation();

        camera2 = new Camera2Fragment(this);

        bridge.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                metrics = getBridge().getActivity().getResources().getDisplayMetrics();

                // lock orientation
                if (lockOrientation) {
                    getBridge().getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
                }

                // offset
                // int computedX = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, metrics);
                /// int computedY = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, y, metrics);

                // size
                int computedWidth;
                int computedHeight;
                int computedPaddingBottom;

                if (paddingBottom != 0) {
                    computedPaddingBottom = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, paddingBottom, metrics);
                } else {
                    computedPaddingBottom = 0;
                }

                if (width != 0) {
                    computedWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, width, metrics);
                } else {
                    Display defaultDisplay = getBridge().getActivity().getWindowManager().getDefaultDisplay();
                    final Point size = new Point();
                    defaultDisplay.getSize(size);

                    computedWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, size.x, metrics);
                }

                if (height != 0) {
                    computedHeight =
                            (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, height, metrics) - computedPaddingBottom;
                } else {
                    Display defaultDisplay = getBridge().getActivity().getWindowManager().getDefaultDisplay();
                    final Point size = new Point();
                    defaultDisplay.getSize(size);

                    computedHeight =
                            (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, size.y, metrics) - computedPaddingBottom;
                }

                FrameLayout containerView = getBridge().getActivity().findViewById(containerViewId);

                if (containerView == null) {
                    containerView = new FrameLayout(getActivity().getApplicationContext());
                    containerView.setId(containerViewId);

                    getBridge().getWebView().setBackgroundColor(Color.TRANSPARENT);
                    ((ViewGroup) getBridge().getWebView().getParent()).addView(containerView);
                    if (toBack) {
                        getBridge().getWebView().getParent().bringChildToFront(getBridge().getWebView());
                        setupBroadcast();
                    }

                    FragmentManager fragmentManager = getBridge().getActivity().getSupportFragmentManager();
                    FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
                    fragmentTransaction.add(containerView.getId(), camera2);
                    fragmentTransaction.commit();

                    // TODO: Reimplement computedX/computedY
                    camera2.setViewFinderSize(computedWidth, computedHeight);

                    bridge.saveCall(call);
                    startCallbackId = call.getCallbackId();
                } else {
                    call.reject("camera already started");
                }
            }
        });
    }

    public void onStart() {
        resolveCallbackId(startCallbackId);
        startCallbackId = null;
    }

    @PluginMethod
    public void stop(PluginCall call) {
        bridge
                .getActivity()
                .runOnUiThread(
                        new Runnable() {
                            @SuppressLint("WrongConstant")
                            @Override
                            public void run() {
                                FrameLayout containerView = getBridge().getActivity().findViewById(containerViewId);

                                getBridge().getActivity().setRequestedOrientation(previousOrientationRequest);

                                if (containerView != null) {
                                    ((ViewGroup) getBridge().getWebView().getParent()).removeView(containerView);
                                    getBridge().getWebView().setBackgroundColor(Color.WHITE);
                                    FragmentManager fragmentManager = getActivity().getSupportFragmentManager();
                                    FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
                                    fragmentTransaction.remove(camera2);
                                    fragmentTransaction.commit();
                                    camera2 = null;

                                    call.resolve();
                                } else {
                                    call.reject("camera already stopped");
                                }
                            }
                        }
                );
    }

    @PluginMethod
    public void capture(PluginCall call) {
        if (!isRunningOrReject(call)) return;

        bridge.saveCall(call);
        captureCallbackId = call.getCallbackId();

        String picturePath = call.getString("picturePath", "_tmp.jpg");
        String thumbnailPath = call.getString("thumbnailPath");
        Integer thumbnailWidth = call.getInt("thumbnailWidth");
        Integer thumbnailHeight = call.getInt("thumbnailHeight");
        Integer thumbnailQuality = call.getInt("thumbnailQuality", 80);

        if (thumbnailPath != null && thumbnailWidth != null && thumbnailWidth > 0 && thumbnailHeight != null && thumbnailHeight > 0) {
            camera2.takePicture(picturePath, thumbnailPath, thumbnailWidth, thumbnailHeight, thumbnailQuality);
        } else {
            camera2.takePicture(picturePath);
        }

    }

    public void onCapture() {
        resolveCallbackId(captureCallbackId);
        captureCallbackId = null;
    }

    @PluginMethod
    public void setViewFinderSize(PluginCall call) {
        if (!isRunningOrReject(call)) return;

        Float width = call.getFloat("width", 0F);
        Float height = call.getFloat("height", 0F);

        if (width != null && width > 0 && height != null && height > 0) {
            camera2.setViewFinderSize(
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, width, metrics),
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, height, metrics)
            );
        }

        call.resolve();
    }

    @PluginMethod
    public void getShutterSpeedRange(PluginCall call) {
        if (!isRunningOrReject(call)) return;

        JSObject jsObject = new JSObject();

        Range<Long> rangeNs = camera2.getShutterSpeedRange();
        jsObject.put("value", serializeRangeToJSArray(
                new Range<>(
                        rangeNs.getLower() / 1_000_000_000F,
                        rangeNs.getUpper() / 1_000_000_000F
                )
        ));

        call.resolve(jsObject);
    }

    @PluginMethod
    public void setShutterSpeed(PluginCall call) {
        if (!isRunningOrReject(call)) return;

        Float shutterSpeedS = call.getFloat("value", -1F);

        if (shutterSpeedS != null) {
            camera2.setShutterSpeed((long) (shutterSpeedS * 1_000_000_000L));
        }

        call.resolve();
    }

    @PluginMethod
    public void getApertureRange(PluginCall call) throws JSONException {
        if (!isRunningOrReject(call)) return;

        JSObject jsObject = new JSObject();
        jsObject.put("value", serializeToJSArray(camera2.getApertureRange()));

        call.resolve(jsObject);
    }

    @PluginMethod
    public void setAperture(PluginCall call) {
        if (!isRunningOrReject(call)) return;

        // Float aperture = call.getFloat("value", -1F);
        // TODO: Implement

        call.resolve();
    }

    @PluginMethod
    public void getIsoRange(PluginCall call) {
        if (!isRunningOrReject(call)) return;

        JSObject jsObject = new JSObject();
        jsObject.put("value", serializeRangeToJSArray(camera2.getIsoRange()));

        call.resolve(jsObject);
    }

    @PluginMethod
    public void setIso(PluginCall call) {
        if (!isRunningOrReject(call)) return;

        Integer iso = call.getInt("value", -1);

        if (iso != null) {
            camera2.setIso(iso);
        }

        call.resolve();
    }

    @PluginMethod
    public void getExposureCompensationInfo(PluginCall call) {
        if (!isRunningOrReject(call)) return;

        JSObject jsObject = new JSObject();
        jsObject.put("range", serializeRangeToJSArray(camera2.getExposureCompensationRange()));
        jsObject.put("step", camera2.getExposureCompensationStep().floatValue());

        call.resolve(jsObject);
    }

    @PluginMethod
    public void setExposureCompensation(PluginCall call) {
        if (!isRunningOrReject(call)) return;

        Integer exposureCompensation = call.getInt("value", 0);

        if (exposureCompensation != null) {
            camera2.setExposureCompensation(exposureCompensation);
        }

        call.resolve();
    }

    @PluginMethod
    public void pictureToThumbnail(PluginCall call) {
        String picture = call.getString("picture");
        Integer width = call.getInt("width", 0);
        Integer height = call.getInt("height", 0);
        Integer quality = call.getInt("quality", 80);

        if (picture == null) {
            call.reject("Picture is required.");
            return;
        }
        if (width == null || width <= 0 || height == null || height <= 0) {
            call.reject("Invalid width or height.");
            return;
        }

        try {
            // Decode base64 to Bitmap
            byte[] decodedString = Base64.decode(picture, Base64.DEFAULT);
            Bitmap originalBitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);

            // Resize the Bitmap
            Bitmap resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, width, height, true);

            // Convert Bitmap to base64
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality == null ? 80 : quality, byteArrayOutputStream);
            byte[] byteArray = byteArrayOutputStream.toByteArray();
            String resizedBase64String = Base64.encodeToString(byteArray, Base64.DEFAULT);

            // Return the resized base64 string
            JSObject result = new JSObject();
            result.put("thumbnail", resizedBase64String);
            call.resolve(result);

        } catch (Exception e) {
            call.reject("Error resizing image: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getExifData(PluginCall call) {
        String path = call.getString("path");

        if (path == null) {
            call.reject("Path is required.");
            return;
        }

        File file = new File(Environment.getExternalStorageDirectory(), path);

        try {
            JSObject result = new JSObject();

            ExifInterface exif = new ExifInterface(file.getAbsoluteFile());

            String iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY);
            result.put("iso", iso != null ? Integer.valueOf(iso) : null);

            String shutterSpeed = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
            result.put("shutterSpeed", shutterSpeed != null ? Float.parseFloat(shutterSpeed) : null);

            String aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER);
            result.put("aperture", aperture != null ? Float.parseFloat(aperture) : null);

            String focalLength = exif.getAttribute(ExifInterface.TAG_MAKER_NOTE);
            result.put("focalLength", focalLength != null ? Float.parseFloat(focalLength) : null);

            call.resolve(result);
        } catch (IOException e) {
            call.reject("Error reading EXIF data: " + e.getMessage());
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean isRunningOrReject(PluginCall call) {
        if (!isRunning()) {
            call.reject("Camera is not running");
            return false;
        } else {
            return true;
        }
    }


    @SuppressLint("ClickableViewAccessibility")
    private void setupBroadcast() {
        /* When touch event is triggered, relay it to camera view if needed so it can support pinch zoom */

        getBridge().getWebView().setClickable(true);
        getBridge()
                .getWebView()
                .setOnTouchListener(
                        new View.OnTouchListener() {
                            @Override
                            public boolean onTouch(View v, MotionEvent event) {
                                if (camera2 != null && toBack) {
                                    camera2.dispatchTouchEvent(event);
                                }
                                return false;
                            }
                        }
                );
    }

    private JSArray serializeToJSArray(float[] array) throws JSONException {
        JSArray jsArray = new JSArray();

        for (float value : array) {
            jsArray.put(value);
        }

        return jsArray;
    }

    private <T extends Comparable<T>> JSArray serializeRangeToJSArray(Range<T> range) {
        JSArray jsArray = new JSArray();

        jsArray.put(range.getLower());
        jsArray.put(range.getUpper());

        return jsArray;
    }

    private void resolveCallbackId(String callbackId) {
        resolveCallbackId(callbackId, null);
    }
    private void resolveCallbackId(String callbackId, JSObject data) {
        if (callbackId == null) return;

        PluginCall pluginCall = bridge.getSavedCall(callbackId);

        if (data != null) pluginCall.resolve(data);
        else pluginCall.resolve();

        bridge.releaseCall(pluginCall);
    }

    private boolean isRunning() {
        return camera2 != null;
    }
}
