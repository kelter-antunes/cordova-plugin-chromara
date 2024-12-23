package com.kelter.chromara;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.graphics.ImageFormat; // Added
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap; // Added
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Surface; // Added
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.View;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;

public class Chromara extends CordovaPlugin {
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private CordovaWebView webView;
    private Activity activity;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private TextureView textureView;
    private FrameLayout cameraPreviewLayout;
    private String cameraId;
    private Size imageDimension;
    private ImageReader imageReader;

    private CallbackContext savedCallbackContext; // Added declaration

     // Removed unused 'self' variable
    // private CordovaPlugin self = this;

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        this.webView = webView;
        this.activity = cordova.getActivity();
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
        this.savedCallbackContext = callbackContext; // Save the callback context
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                    // Do not call callbackContext.error here; wait for permission result
                    return;
                }
    
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Initialize TextureView
                        textureView = new TextureView(activity);
                        textureView.setSurfaceTextureListener(textureListener);
    
                        // Add TextureView to a FrameLayout
                        cameraPreviewLayout = new FrameLayout(activity);
                        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                        );
                        cameraPreviewLayout.setLayoutParams(layoutParams);
                        cameraPreviewLayout.addView(textureView);
    
                        // Add the cameraPreviewLayout below the WebView
                        ViewGroup root = (ViewGroup) activity.findViewById(android.R.id.content).getRootView();
                        ((ViewGroup) root).addView(cameraPreviewLayout);
                        callbackContext.success("Camera preview shown.");
                    }
                });
            }
        });
    }

    private void removePreview(final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (captureSession != null) {
                            captureSession.close();
                            captureSession = null;
                        }

                        if (cameraDevice != null) {
                            cameraDevice.close();
                            cameraDevice = null;
                        }

                        if (cameraPreviewLayout != null) {
                            ((ViewGroup) activity.findViewById(android.R.id.content).getRootView()).removeView(cameraPreviewLayout);
                            cameraPreviewLayout = null;
                        }

                        callbackContext.success("Camera preview removed.");
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

                    ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG,1);
                    List<Surface> outputSurfaces = new ArrayList<Surface>(2);
                    outputSurfaces.add(reader.getSurface());
                    outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));
                    final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    captureBuilder.addTarget(reader.getSurface());
                    captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

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

                    reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                        @Override
                        public void onImageAvailable(ImageReader reader) {
                            Image image = null;
                            FileOutputStream fos = null;
                            try {
                                image = reader.acquireLatestImage();
                                if (image == null) {
                                    callbackContext.error("Image is null.");
                                    return;
                                }
                                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                byte[] bytes = new byte[buffer.capacity()];
                                buffer.get(bytes);

                                OutputStream os = activity.getContentResolver().openOutputStream(imageUri);
                                os.write(bytes);
                                os.close();

                                callbackContext.success("Photo captured and saved.");
                            } catch (IOException e) {
                                callbackContext.error("Failed to save image: " + e.getMessage());
                            } finally {
                                if (image != null) {
                                    image.close();
                                }
                            }
                        }
                    }, null);

                    final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                       @NonNull CaptureRequest request,
                                                       @NonNull TotalCaptureResult result) {
                            // Do nothing
                        }
                    };

                    cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                session.capture(captureBuilder.build(), captureListener, null);
                            } catch (CameraAccessException e) {
                                callbackContext.error("CameraAccessException: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            callbackContext.error("Failed to configure camera for capture.");
                        }
                    }, null);

                } catch (CameraAccessException e) {
                    callbackContext.error("CameraAccessException: " + e.getMessage());
                }
            }
        });
    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            // Open your camera here
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    private void openCamera() {
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            // Check permissions again
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            // Handle exception
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraDevice.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if(texture == null){
                return;
            }
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if(cameraDevice == null){
                        return;
                    }
                    captureSession = session;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    // Handle failure
                }
            }, null );
        } catch (CameraAccessException e){
            // Handle exception
        }
    }

    private void updatePreview() {
        if(cameraDevice == null){
            return;
        }
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try{
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
        } catch(CameraAccessException e){
            // Handle exception
        }
    }

    // Handle permission result
    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        switch (requestCode){
            case REQUEST_CAMERA_PERMISSION:
                if(grantResults.length > 0){
                    boolean cameraAccepted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
                    boolean storageAccepted = grantResults[1] == PackageManager.PERMISSION_GRANTED;
                    if(cameraAccepted && storageAccepted){
                        // Permissions granted, proceed to show preview
                        showPreview(savedCallbackContext);
                    }
                    else{
                        savedCallbackContext.error("Permissions denied.");
                    }
                }
                break;
        }
    }
}