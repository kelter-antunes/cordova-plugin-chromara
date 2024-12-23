package com.kelter.chromara;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Size;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceTexture;
import android.view.TextureView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@RequiresApi(api = Build.VERSION_CODES.S) // API Level 31+
public class Chromara extends CordovaPlugin {

    private static final String TAG = "ChromaraPlugin";
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader jpegImageReader;
    private ImageReader rawImageReader;
    private CallbackContext callbackContext;
    private String cameraId;
    private Size jpegSize;
    private Size rawSize;
    private Activity activity;
    private TextureView textureView;
    private SurfaceTextureListenerImpl surfaceTextureListener;
    private boolean isCameraOpened = false;

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        activity = this.cordova.getActivity();
        setupTextureView();
    }

    private void setupTextureView() {
        textureView = new TextureView(activity);
        textureView.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ));

        surfaceTextureListener = new SurfaceTextureListenerImpl();
        textureView.setSurfaceTextureListener(surfaceTextureListener);

        // Add TextureView to the Activity's content view
        activity.runOnUiThread(() -> {
            android.view.ViewGroup rootView = (android.view.ViewGroup) activity.findViewById(android.R.id.content);
            rootView.addView(textureView);
        });
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if ("capturePhoto".equals(action)) {
            this.callbackContext = callbackContext;
            this.capturePhoto();
            return true;
        } else if ("removePreview".equals(action)) {
            removePreview();
            return true;
        }
        return false;
    }

    private void capturePhoto() {
        if (isCameraOpened) {
            // Proceed to capture
            takePicture();
        } else {
            // Waiting for TextureView to be available
            callbackContext.error("Camera preview not ready.");
        }
    }

    private void removePreview() {
        activity.runOnUiThread(() -> {
            android.view.ViewGroup rootView = (android.view.ViewGroup) activity.findViewById(android.R.id.content);
            rootView.removeView(textureView);
        });
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) activity.getSystemService(Activity.CAMERA_SERVICE);
        try {
            cameraId = manager.getCameraIdList()[0]; // Rear camera
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

            // Get supported JPEG sizes
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            jpegSize = Collections.max(
                    Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                    new CompareSizesByArea()
            );
            // Get RAW sizes if supported
            if (map.isOutputSupportedFor(ImageFormat.RAW_SENSOR)) {
                rawSize = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.RAW_SENSOR)),
                        new CompareSizesByArea()
                );
            } else {
                rawSize = null;
                Log.w(TAG, "RAW_SENSOR format not supported on this device.");
            }

            // Initialize ImageReaders
            jpegImageReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
            jpegImageReader.setOnImageAvailableListener(onJpegImageAvailableListener, null);

            if (rawSize != null) {
                rawImageReader = ImageReader.newInstance(rawSize.getWidth(), rawSize.getHeight(), ImageFormat.RAW_SENSOR, 2);
                rawImageReader.setOnImageAvailableListener(onRawImageAvailableListener, null);
            }

            // Check permissions
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 200);
                callbackContext.error("Camera or storage permissions are not granted.");
                return;
            }

            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException: " + e.getMessage());
            callbackContext.error("Camera access error: " + e.getMessage());
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            isCameraOpened = true;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
            isCameraOpened = false;
            callbackContext.error("Camera disconnected.");
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            isCameraOpened = false;
            callbackContext.error("Camera error: " + error);
        }
    };

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            // Set the buffer size to the TextureView size
            texture.setDefaultBufferSize(jpegSize.getWidth(), jpegSize.getHeight());
            Surface previewSurface = new Surface(texture);

            final CaptureRequest.Builder previewRequestBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(previewSurface);

            List<Surface> outputSurfaces = new ArrayList<>();
            outputSurfaces.add(previewSurface);
            outputSurfaces.add(jpegImageReader.getSurface());
            if (rawImageReader != null) {
                outputSurfaces.add(rawImageReader.getSurface());
            }

            cameraDevice.createCaptureSession(outputSurfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) {
                                callbackContext.error("Camera device is null.");
                                return;
                            }
                            captureSession = session;
                            try {
                                previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, null);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "CameraAccessException during preview: " + e.getMessage());
                                callbackContext.error("Camera access error during preview: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            callbackContext.error("Failed to configure camera preview.");
                        }
                    }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException during preview setup: " + e.getMessage());
            callbackContext.error("Camera access error during preview setup: " + e.getMessage());
        }
    }

    private void takePicture() {
        if (cameraDevice == null) {
            callbackContext.error("Camera device is null.");
            return;
        }
        try {
            CameraCharacteristics characteristics = activity.getSystemService(CameraManager.class)
                    .getCameraCharacteristics(cameraId);
            Size[] jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(ImageFormat.JPEG);
            Size[] rawSizes = rawSize != null ? new Size[]{rawSize} : new Size[0];

            // Set up capture request
            final CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(jpegImageReader.getSurface());
            if (rawImageReader != null) {
                captureBuilder.addTarget(rawImageReader.getSurface());
            }

            // Setting auto-focus and auto-exposure
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            // Orientation
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(rotation));

            // Stop repeating request and capture the photo
            captureSession.stopRepeating();
            captureSession.capture(captureBuilder.build(), captureCallback, null);

        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException during capture: " + e.getMessage());
            callbackContext.error("Camera access error during capture: " + e.getMessage());
        }
    }

    private int getJpegOrientation(int rotation) {
        // Simplistic orientation calculation; can be enhanced based on sensor orientation
        switch (rotation) {
            case Surface.ROTATION_0:
                return 90;
            case Surface.ROTATION_90:
                return 0;
            case Surface.ROTATION_180:
                return 270;
            case Surface.ROTATION_270:
                return 180;
            default:
                return 90;
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        // Implement callbacks if needed
    };

    private final ImageReader.OnImageAvailableListener onJpegImageAvailableListener = reader -> {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image != null) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);

                // Apply image processing
                byte[] processedBytes = applyImageProcessing(bytes);

                // Save JPEG
                saveImage(processedBytes, "jpg");

                image.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "JPEG Image processing error: " + e.getMessage());
            callbackContext.error("JPEG Image processing error: " + e.getMessage());
        } finally {
            if (image != null) {
                image.close();
            }
        }
    };

    private final ImageReader.OnImageAvailableListener onRawImageAvailableListener = reader -> {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image != null) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);

                // Save RAW (DNG)
                saveRawImage(bytes);

                image.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "RAW Image processing error: " + e.getMessage());
            callbackContext.error("RAW Image processing error: " + e.getMessage());
        } finally {
            if (image != null) {
                image.close();
            }
        }
    };

    private byte[] applyImageProcessing(byte[] jpegData) {
        // Convert JPEG bytes to Bitmap
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        if (bitmap == null) {
            Log.e(TAG, "Failed to decode JPEG data.");
            return jpegData; // Return original if decoding fails
        }

        // Apply Fuji Kodachrome-like color adjustment
        Bitmap adjustedBitmap = applyFujiKodachromeEffect(bitmap);

        // Apply halation effect
        Bitmap finalBitmap = applyHalationEffect(adjustedBitmap);

        // Convert Bitmap back to JPEG bytes
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
        return baos.toByteArray();
    }

    private Bitmap applyFujiKodachromeEffect(Bitmap src) {
        // Simple color adjustment to emulate Kodachrome
        ColorMatrix colorMatrix = new ColorMatrix();

        // Adjust saturation moderately
        colorMatrix.setSaturation(1.1f);

        // Slightly increase contrast
        ColorMatrix contrastMatrix = new ColorMatrix(new float[]{
                1.2f, 0, 0, 0, -30,
                0, 1.2f, 0, 0, -30,
                0, 0, 1.2f, 0, -30,
                0, 0, 0, 1, 0
        });

        colorMatrix.postConcat(contrastMatrix);

        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));

        Bitmap adjusted = Bitmap.createBitmap(src.getWidth(), src.getHeight(), src.getConfig());
        Canvas canvas = new Canvas(adjusted);
        canvas.drawBitmap(src, 0, 0, paint);
        return adjusted;
    }

    private Bitmap applyHalationEffect(Bitmap src) {
        // Create a blurred version of the original bitmap
        Bitmap blurredBitmap = Bitmap.createBitmap(src.getWidth(), src.getHeight(), src.getConfig());
        Canvas canvas = new Canvas(blurredBitmap);
        Paint paint = new Paint();

        // Create a RenderEffect for blurring
        RenderEffect blurEffect = RenderEffect.createBlurEffect(10f, 10f, Shader.TileMode.CLAMP);
        paint.setRenderEffect(blurEffect);

        // Draw the original bitmap onto the blurred canvas with the blur effect
        canvas.drawBitmap(src, 0, 0, paint);

        // Create a new bitmap to hold the final image
        Bitmap finalImage = Bitmap.createBitmap(src.getWidth(), src.getHeight(), src.getConfig());
        Canvas finalCanvas = new Canvas(finalImage);
        Paint blendPaint = new Paint();
        blendPaint.setAlpha(80); // Adjust opacity as needed

        // Draw the original image
        finalCanvas.drawBitmap(src, 0, 0, null);

        // Overlay the blurred image to create the halation effect
        finalCanvas.drawBitmap(blurredBitmap, 0, 0, blendPaint);

        // Recycle the intermediate blurred bitmap to free memory
        blurredBitmap.recycle();

        return finalImage;
    }

    private void saveImage(byte[] bytes, String format) {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "Chromara_" + timeStamp + "." + format;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/" + format);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Chromara");

        Uri uri = null;
        try {
            uri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                callbackContext.error("Failed to create new MediaStore record.");
                return;
            }
            OutputStream out = activity.getContentResolver().openOutputStream(uri);
            if (out == null) {
                callbackContext.error("Failed to get output stream.");
                return;
            }
            out.write(bytes);
            out.close();
            Toast.makeText(activity, "Photo saved: " + fileName, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Failed to save image: " + e.getMessage());
            callbackContext.error("Failed to save image: " + e.getMessage());
        }
    }

    private void saveRawImage(byte[] bytes) {
        if (rawSize == null) {
            Log.w(TAG, "RAW image saving not supported on this device.");
            return;
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "Chromara_" + timeStamp + ".dng";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/dng");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Chromara/RAW");

        Uri uri = null;
        try {
            uri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Log.e(TAG, "Failed to create new MediaStore record for RAW image.");
                return;
            }
            OutputStream out = activity.getContentResolver().openOutputStream(uri);
            if (out == null) {
                Log.e(TAG, "Failed to get output stream for RAW image.");
                return;
            }
            out.write(bytes);
            out.close();
            Toast.makeText(activity, "RAW Photo saved: " + fileName, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Failed to save RAW image: " + e.getMessage());
        }
    }

    private class SurfaceTextureListenerImpl implements TextureView.SurfaceTextureListener {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            // Handle size changes if necessary
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            closeCamera();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            // Invoked every time there's a new Camera preview frame
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
            if (jpegImageReader != null) {
                jpegImageReader.close();
                jpegImageReader = null;
            }
            if (rawImageReader != null) {
                rawImageReader.close();
                rawImageReader = null;
            }
            isCameraOpened = false;
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera: " + e.getMessage());
        }
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // Cast to long to prevent overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        closeCamera();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        closeCamera();
    }
}