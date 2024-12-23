package com.kelter.chromara;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.util.Size;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;

public class Chromara extends CordovaPlugin {

    private static final String TAG = "ChromaraPlugin";
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private CallbackContext callbackContext;
    private String cameraId;
    private Size imageDimension;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if ("capturePhoto".equals(action)) {
            this.callbackContext = callbackContext;
            this.captuePhoto();
            return true;
        }
        return false;
    }

    private void captuePhoto() {
        Activity activity = this.cordova.getActivity();

        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            
            ActivityCompat.requestPermissions(activity, new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            }, 200);
            callbackContext.error("Camera permissions are not granted.");
            return;
        }

        CameraManager manager = (CameraManager) activity.getSystemService(Activity.CAMERA_SERVICE);
        try {
            cameraId = manager.getCameraIdList()[0]; // Use rear camera
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            imageDimension = map.getOutputSizes(ImageFormat.JPEG)[0];
            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, e.toString());
            callbackContext.error("Camera access error: " + e.getMessage());
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createCameraCaptureSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
            callbackContext.error("Camera disconnected.");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            cameraDevice.close();
            callbackContext.error("Camera error: " + error);
        }
    };

    private void createCameraCaptureSession() {
        try {
            imageReader = ImageReader.newInstance(imageDimension.getWidth(), imageDimension.getHeight(), ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, null);

            cameraDevice.createCaptureSession(Arrays.asList(imageReader.getSurface()), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    if (cameraDevice == null) {
                        callbackContext.error("Camera device is null.");
                        return;
                    }
                    captureSession = session;
                    capturePhoto();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    callbackContext.error("Capture session configuration failed.");
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, e.toString());
            callbackContext.error("Camera access error: " + e.getMessage());
        }
    }

    private void capturePhoto() {
        try {
            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            
            // Basic settings, adjust ISO, exposure, etc., as needed
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            // Orientation
            Activity activity = this.cordova.getActivity();
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
            
            captureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback(){
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    // Photo captured
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, e.toString());
            callbackContext.error("Capture error: " + e.getMessage());
        }
    }

    private final ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener(){
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.capacity()];
                    buffer.get(bytes);
                    
                    // Save JPEG
                    saveImage(bytes, "jpg");

                    // Save RAW (if needed, adjust based on camera capabilities)
                    // Note: Capturing RAW requires different handling, possibly RAW16 format
                    // Here, we assume only JPEG for simplicity
                }
            } finally {
                if (image != null) {
                    image.close();
                }
                callbackContext.success("Photo captured successfully.");
                closeCamera();
            }
        }
    };

    private void saveImage(byte[] bytes, String format) {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "Chromara_" + timeStamp + "." + format;
        File storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File file = new File(storageDir, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
            fos.flush();
            // Optionally, notify gallery about new image
            Activity activity = this.cordova.getActivity();
            this.cordova.getActivity().sendBroadcast(new android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(file)));
        } catch (IOException e) {
            Log.e(TAG, "File save failed: " + e.getMessage());
            callbackContext.error("File save failed: " + e.getMessage());
        }
    }

    private void closeCamera() {
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
    }

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static{
        ORIENTATIONS.append(android.view.Surface.ROTATION_0, 90);
        ORIENTATIONS.append(android.view.Surface.ROTATION_90, 0);
        ORIENTATIONS.append(android.view.Surface.ROTATION_180, 270);
        ORIENTATIONS.append(android.view.Surface.ROTATION_270, 180);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        closeCamera();
    }
}