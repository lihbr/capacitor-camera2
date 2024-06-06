package com.lihbr.plugins.camera2;

import static android.Manifest.permission.CAMERA;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Point;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

@CapacitorPlugin(
        name = "Camera2",
        permissions = { @Permission(strings = { CAMERA }, alias = Camera2Plugin.CAMERA_PERMISSION_ALIAS) }
)
public class Camera2Plugin extends Plugin implements Camera2Activity.Camera2EventListeners {
    static final String CAMERA_PERMISSION_ALIAS = "camera";
    private Camera2Activity camera2;
    private final int containerViewId = 20;

    @PluginMethod
    public void echo(PluginCall call) {
        String value = call.getString("value");
        assert value != null;

        JSObject ret = new JSObject();
        Log.i("Echo", value);
        ret.put("value", value);
        call.resolve(ret);
    }

    private String startCallbackId;
    private int previousOrientationRequest = -1;

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
        final Boolean toBack = call.getBoolean("toBack", false);
        final Boolean lockOrientation = call.getBoolean("lockAndroidOrientation", false);

        // It feels silly having to assert all of those despite providing default values everywhere...
        assert x != null;
        assert y != null;
        assert width != null;
        assert height != null;
        assert paddingBottom != null;
        assert toBack != null;
        assert lockOrientation != null;

        previousOrientationRequest = getBridge().getActivity().getRequestedOrientation();

        camera2 = new Camera2Activity(this, toBack);

        bridge.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                DisplayMetrics metrics = getBridge().getActivity().getResources().getDisplayMetrics();

                // lock orientation
                if (lockOrientation) {
                    getBridge().getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
                }

                // offset
                int computedX = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, metrics);
                int computedY = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, y, metrics);

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

                camera2.setRect(computedX, computedY, computedWidth, computedHeight);

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
                                if (camera2 != null && camera2.toBack) {
                                    camera2.textureView.dispatchTouchEvent(event);
                                }
                                return false;
                            }
                        }
                );
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
