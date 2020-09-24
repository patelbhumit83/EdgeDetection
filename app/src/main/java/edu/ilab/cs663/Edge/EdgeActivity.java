package edu.ilab.cs663.Edge;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.util.Size;
import android.util.TypedValue;
import android.widget.Toast;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;

import edu.ilab.cs663.Edge.customview.OverlayView;
import edu.ilab.cs663.Edge.env.BorderedText;
import edu.ilab.cs663.Edge.env.ImageUtils;
import edu.ilab.cs663.Edge.env.Logger;
import edu.ilab.cs663.Edge.tflite.Classifier;
import edu.ilab.cs663.Edge.tflite.TFLiteObjectDetectionAPIModel;
import edu.ilab.cs663.Edge.tracking.MultiBoxTracker;
import edu.ilab.cs663.R;

/*
import org.tensorflow.lite.examples.detection.CameraActivity;
import org.tensorflow.lite.examples.detection.R;
import org.tensorflow.lite.examples.detection.customview.OverlayView;
import org.tensorflow.lite.examples.detection.customview.OverlayView.DrawCallback;
import org.tensorflow.lite.examples.detection.env.BorderedText;
import org.tensorflow.lite.examples.detection.env.ImageUtils;
import org.tensorflow.lite.examples.detection.env.Logger;
import org.tensorflow.lite.examples.detection.tflite.Classifier;
import org.tensorflow.lite.examples.detection.tflite.TFLiteObjectDetectionAPIModel;
import org.tensorflow.lite.examples.detection.tracking.MultiBoxTracker;


 */

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class EdgeActivity extends CameraActivity implements OnImageAvailableListener {
    private static final Logger LOGGER = new Logger();

    // Configuration values for the prepackaged SSD model.
    private static final int TF_OD_API_INPUT_SIZE = 300;
    private static final boolean TF_OD_API_IS_QUANTIZED = true;
    private static final String TF_OD_API_MODEL_FILE = "detect.tflite";
    private static final String TF_OD_API_LABELS_FILE = "labelmap.txt";
    private static final DetectorMode MODE = DetectorMode.TF_OD_API;
    // Minimum detection confidence to track a detection.
    private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;
    private static final boolean MAINTAIN_ASPECT = false;
    private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);
    private static final boolean SAVE_PREVIEW_BITMAP = false;
    private static final float TEXT_SIZE_DIP = 10;
    OverlayView trackingOverlay;
    private Integer sensorOrientation;

    private Classifier detector;

    private long lastProcessingTimeMs;
    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;
    private Bitmap cropCopyBitmap = null;

    private boolean computingDetection = false;

    private long timestamp = 0;

    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;

    private MultiBoxTracker tracker;

    private BorderedText borderedText;

    @Override
    public void onPreviewSizeChosen(final Size size, final int rotation) {
        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "Unable to load OpenCV", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "OpenCV has been loaded!", Toast.LENGTH_LONG).show();
        }


        final float textSizePx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);
        borderedText.setTypeface(Typeface.MONOSPACE);

        tracker = new MultiBoxTracker(this);

        int cropSize = TF_OD_API_INPUT_SIZE;

        try {
            detector =
                    TFLiteObjectDetectionAPIModel.create(
                            getAssets(),
                            TF_OD_API_MODEL_FILE,
                            TF_OD_API_LABELS_FILE,
                            TF_OD_API_INPUT_SIZE,
                            TF_OD_API_IS_QUANTIZED);
            cropSize = TF_OD_API_INPUT_SIZE;
        } catch (final IOException e) {
            e.printStackTrace();
            LOGGER.e(e, "Exception initializing classifier!");
            Toast toast =
                    Toast.makeText(
                            getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
            toast.show();
            finish();
        }

        previewWidth = size.getWidth();
        previewHeight = size.getHeight();

        sensorOrientation = rotation - getScreenOrientation();
        LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

        frameToCropTransform =
                ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        cropSize, cropSize,
                        sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
        trackingOverlay.addCallback(
                new OverlayView.DrawCallback() {
                    @Override
                    public void drawCallback(final Canvas canvas) {
                        if(rgbFrameBitmap != null) {
                            // this will process the incoming image and display the results on the
                            // associated canvas, which is associated with the OverlayView
                            // that sits on top of the Preview Widget
                            processImage(canvas);

                            // the following is the ORIGINAL call which directly invokes drawing
                            // of results on the overlay from the ORIGINAL processImage()
                            // which is currently invoked by the camera activity

//                            tracker.draw(canvas);
//                            if (isDebug()) {
//                                tracker.drawDebug(canvas);
//                            }
                        }
                    }
                });

        tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);
    }

    /**
     * This processImage is being invoked everytime there needs to be a new display on the image
     * overlay and we are creating our OpenCV calls here so theyc an be displayed directly.
     * NOTE: There is also processImage(), which is called directly from the base CameraActivity
     * and is necessary to cycle through images as they are captured by the camera
     * @param canvas - to draw over
     */
    private void processImage(Canvas canvas) {
        int[] grabbedRGBBytes = getRgbBytes();
        if(getRgbBytes() == null)
            return;

        rgbFrameBitmap.setPixels(grabbedRGBBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight);
        Bitmap bmp32 = rgbFrameBitmap.copy(Bitmap.Config.ARGB_8888, true);

        // PUT YOUR OPENCV CODE HERE
        // INPUT: bmp32
        // OUTPUT: bmp32 (after processing)

        Mat rgba = new Mat();
        Utils.bitmapToMat(bmp32, rgba);

        Mat edges = new Mat(rgba.size(), CvType.CV_8UC1);
        Imgproc.cvtColor(rgba, edges, Imgproc.COLOR_RGB2GRAY, 4);
        Imgproc.Canny(edges, edges, 80, 100);

        // Don't do that at home or work it's for visualization purpose.
        //BitmapHelper.showBitmap(this, bitmap, imageView);
        //Bitmap resultBitmap = Bitmap.createBitmap(edges.cols(), edges.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(edges, bmp32);
        //Toast.makeText(getApplicationContext(), "Bhumit", Toast.LENGTH_LONG);
        //BitmapHelper.showBitmap(this, resultBitmap, detectEdgesImageView);





        // END OPENCV CODE

        // calculate the transformation matrix that will appropriately rotate the image to the correct
        // landscape or horizontal view
        final boolean rotated = sensorOrientation % 180 == 90;
        final float multiplier =
                Math.min(
                        previewHeight / (float) (rotated ? previewWidth : previewHeight),
                        previewWidth / (float) (rotated ? previewHeight : previewWidth));
        Matrix frameToCanvasMatrix =
                ImageUtils.getTransformationMatrix(
                        previewWidth,
                        previewHeight,
                        (int) (multiplier * (rotated ? previewHeight : previewWidth)),
                        (int) (multiplier * (rotated ? previewWidth: previewHeight)),
                        sensorOrientation,
                        false);

        // transform bmp32 using the above matrix to the correct orientation and preview size
        Bitmap resizedBitmap = Bitmap.createBitmap(bmp32, 0, 0,
                previewWidth, previewHeight, frameToCanvasMatrix, true);

        // draw the transformed bitmap into the canvas for display and additionally scale it to the size necessary
        // for the canvas
        canvas.drawBitmap(resizedBitmap, null, new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), new Paint());
    }

    /**
     * this method will be called automatically by the base camera activity and currently simply
     * tells the system it's ready for the next image and grabs the RGBFrame
     */
    @Override
    protected void processImage() {
        ++timestamp;
        final long currTimestamp = timestamp;
        trackingOverlay.postInvalidate();

        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage();
            return;
        }
        computingDetection = true;
        LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

        readyForNextImage();

        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap);
        }
    }

    @Override
    protected int getLayoutId() {
        return R.layout.tfe_od_camera_connection_fragment_tracking;
    }

    @Override
    protected Size getDesiredPreviewFrameSize() {
        return DESIRED_PREVIEW_SIZE;
    }

    // Which detection model to use: by default uses Tensorflow Object Detection API frozen
    // checkpoints.
    private enum DetectorMode {
        TF_OD_API;
    }

    @Override
    protected void setUseNNAPI(final boolean isChecked) {
        runInBackground(() -> detector.setUseNNAPI(isChecked));
    }

    @Override
    protected void setNumThreads(final int numThreads) {
        runInBackground(() -> detector.setNumThreads(numThreads));
    }
}
