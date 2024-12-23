package com.kelter.chromara;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;

public class Chromara extends CordovaPlugin {
    private static final String TAG = "Chromara";
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private CordovaWebView webView;
    private Activity activity;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private TextureView textureView;
    private String cameraId;
    private Size imageDimension;
    private ImageReader imageReader;

    private CallbackContext savedCallbackContext;

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        this.webView = webView;
        this.activity = cordova.getActivity();

        // Initialize TextureView
        textureView = new TextureView(activity);
        textureView.setSurfaceTextureListener(textureListener);
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        switch (action) {
            case "showPreview":
                showPreview(callbackContext);
                return true;
            case "removePreview":
                removePreview(callbackContext);
                return true;
            case "capturePhoto":
                capturePhoto(callbackContext);
                return true;
            default:
                callbackContext.error("Action not recognized.");
                return false;
        }
    }

    private void showPreview(final CallbackContext callbackContext) {
        this.savedCallbackContext = callbackContext;
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                // Check permissions
                if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(activity,
                            new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            REQUEST_CAMERA_PERMISSION);
                    // Permissions are handled in onRequestPermissionResult
                    return;
                }

                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        addTextureViewToViewHierarchy(callbackContext);
                    }
                });
            }
        });
    }

    private void addTextureViewToViewHierarchy(final CallbackContext callbackContext) {
        try {
            // Get the parent of the WebView to overlay the preview
            ViewGroup parent = (ViewGroup) webView.getView().getParent();

            if (parent == null) {
                callbackContext.error("Failed to get WebView parent.");
                return;
            }

            // Set layout parameters for TextureView
            FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );

            // Add TextureView directly to the WebView's parent
            parent.addView(textureView, layoutParams);
            callbackContext.success("Camera preview shown.");
        } catch (Exception e) {
            Log.e(TAG, "Error adding TextureView to view hierarchy: " + e.getMessage());
            callbackContext.error("Failed to show camera preview.");
        }
    }

    private void removePreview(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Close camera resources
                            closeCamera();

                            // Remove TextureView from the view hierarchy
                            ViewGroup parent = (ViewGroup) webView.getView().getParent();
                            if (parent != null && textureView != null) {
                                parent.removeView(textureView);
                            }

                            // Only send a success callback if callbackContext is not null
                            if (callbackContext != null) {
                                callbackContext.success("Camera preview removed.");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error removing TextureView: " + e.getMessage());
                            if (callbackContext != null) {
                                callbackContext.error("Failed to remove camera preview.");
                            }
                        }
                    }
                });
            }
        });
    }

    private void capturePhoto(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                if (cameraDevice == null) {
                    callbackContext.error("Camera is not initialized.");
                    return;
                }

                try {
                    CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                    Size[] jpegSizes = null;
                    if (characteristics != null) {
                        jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
                    }

                    int width = 1920;
                    int height = 1080;
                    if (jpegSizes != null && jpegSizes.length > 0) {
                        width = jpegSizes[0].getWidth();
                        height = jpegSizes[0].getHeight();
                    }

                    imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
                    List<Surface> outputSurfaces = new ArrayList<>(2);
                    outputSurfaces.add(imageReader.getSurface());
                    outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));
                    final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    captureBuilder.addTarget(imageReader.getSurface());
                    captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

                    // Orientation
                    captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation());

                    // Save the image
                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                    String fileName = "CHR_" + timeStamp + ".jpg";

                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Chromara");

                    final Uri imageUri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (imageUri == null) {
                        callbackContext.error("Failed to create new MediaStore record.");
                        return;
                    }

                    ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                        @Override
                        public void onImageAvailable(ImageReader reader) {
                            Image image = null;
                            try {
                                image = reader.acquireLatestImage();
                                if (image == null) {
                                    callbackContext.error("Image acquisition failed.");
                                    return;
                                }
                                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                byte[] bytes = new byte[buffer.capacity()];
                                buffer.get(bytes);
                                activity.getContentResolver().openOutputStream(imageUri).write(bytes);
                                callbackContext.success("Photo captured and saved.");
                            } catch (IOException e) {
                                Log.e(TAG, "Image saving failed: " + e.getMessage());
                                callbackContext.error("Failed to save image.");
                            } finally {
                                if (image != null) {
                                    image.close();
                                }
                            }
                        }
                    };

                    imageReader.setOnImageAvailableListener(readerListener, null);

                    final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                       @NonNull CaptureRequest request,
                                                       @NonNull TotalCaptureResult result) {
                            // Optionally handle capture completion
                        }
                    };

                    cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                session.capture(captureBuilder.build(), captureListener, null);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Capture failed: " + e.getMessage());
                                callbackContext.error("Capture failed.");
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Capture session configuration failed.");
                            callbackContext.error("Failed to configure camera for capture.");
                        }
                    }, null);

                } catch (CameraAccessException e) {
                    Log.e(TAG, "CameraAccessException: " + e.getMessage());
                    callbackContext.error("Camera access error.");
                }
            }
        });
    }

    private int getOrientation() {
        // Simplified: returns 90 degrees. Adjust as needed for device orientation.
        return 90;
    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            // Open the camera once the TextureView is available
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Handle size changes if necessary
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            // Update if necessary
        }
    };

    private void openCamera() {
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = manager.getCameraIdList()[0]; // Default to first camera
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                Log.e(TAG, "Cannot get available preview/video sizes");
                return;
            }
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];

            // Check permissions again
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException: " + e.getMessage());
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera opened.");
            cameraDevice = camera;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera disconnected.");
            cameraDevice.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera error: " + error);
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) {
                Log.e(TAG, "SurfaceTexture is null.");
                return;
            }
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback(){
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            if (cameraDevice == null) {
                                Log.e(TAG, "CameraDevice is null.");
                                return;
                            }
                            captureSession = cameraCaptureSession;
                            updatePreview();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            Log.e(TAG, "Configure failed.");
                        }
                    }, null );
        } catch (CameraAccessException e){
            Log.e(TAG, "CameraAccessException in createCameraPreview: " + e.getMessage());
        }
    }

    private void updatePreview() {
        if (cameraDevice == null) {
            Log.e(TAG, "updatePreview error, cameraDevice is null.");
            return;
        }
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try{
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
        } catch(CameraAccessException e){
            Log.e(TAG, "CameraAccessException in updatePreview: " + e.getMessage());
        }
    }

    // Handle permission result
    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        if(requestCode == REQUEST_CAMERA_PERMISSION){
            boolean cameraAccepted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            boolean storageAccepted = grantResults.length > 1 && grantResults[1] == PackageManager.PERMISSION_GRANTED;
            if(cameraAccepted && storageAccepted){
                // Permissions granted, proceed to show preview
                showPreview(savedCallbackContext);
            }
            else{
                savedCallbackContext.error("Permissions denied.");
            }
        }
        else{
            super.onRequestPermissionResult(requestCode, permissions, grantResults);
        }
    }

    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera: " + e.getMessage());
        }
    }

    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Call removePreview without a CallbackContext
                        removePreview(null);
                    }
                });
            }
        });
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        // Optionally, you can auto-show the preview on resume
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        closeCamera();
    }
}