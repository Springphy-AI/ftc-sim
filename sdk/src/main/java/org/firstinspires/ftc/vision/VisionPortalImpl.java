package org.firstinspires.ftc.vision;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

import org.firstinspires.ftc.robotcore.external.function.Consumer;
import org.firstinspires.ftc.robotcore.external.function.Continuation;
import org.firstinspires.ftc.robotcore.external.function.ContinuationResult;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraName;
import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.CameraControl;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibration;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibrationHelper;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.VendorProductCalibrationIdentity;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Size;

import com.qualcomm.robotcore.util.RobotLog;

import ftcsim.hal.CameraBackend;
import ftcsim.hal.CameraPreviews;
import ftcsim.hal.RobotContext;
import ftcsim.hal.SimCameraControls;
import ftcsim.hal.SimWebcamName;
import ftcsim.hal.VisionNatives;

/**
 * The simulator's VisionPortal. FIRST's own VisionPortal and VisionPortal.Builder (unmodified,
 * from the SDK's Vision package) create this class; on the robot it is FIRST's VisionPortalImpl,
 * which streams a USB webcam through EasyOpenCV. Here the frames come from the simulated webcam.
 *
 * <p>The processors see what they see on the robot: the camera's lens calibration from the SDK's
 * calibration table (by the webcam's USB vendor/product id and the resolution), then one RGBA
 * frame after another on the camera's own thread, each with the time it was captured. Their
 * onDrawFrame overlays go to the live view, which the Driver Station page shows.
 */
public class VisionPortalImpl extends VisionPortal {
    private static final String TAG = "VisionPortal";
    /** Display density the overlays are drawn for (FIRST's annotator scales text and lines by it). */
    private static final float CANVAS_DENSITY = 1.0f;

    private final CameraName cameraName;
    private final CameraBackend camera;
    private final VisionProcessor[] processors;
    private final boolean[] processorsEnabled;
    private final int width, height;
    private final boolean liveViewRequested;
    private final boolean showStats;
    private final SimCameraControls controls = new SimCameraControls();
    private final RobotContext robot = RobotContext.current();
    private Runnable cancelCloseOnStop;
    private final Object frameLock = new Object();

    private volatile CameraState cameraState = CameraState.OPENING_CAMERA_DEVICE;
    private volatile boolean streamRequested;
    private volatile boolean liveView;
    private volatile boolean closed;
    private volatile float fps;
    private volatile String captureNextFrame;
    private int[] latestFrame;   // ARGB pixels of the newest raw frame; guarded by frameLock
    private Bitmap view;         // the live view, drawn on the camera thread
    private int[] viewPixels;
    private CameraPreviews.Preview preview;
    private Thread thread;

    public VisionPortalImpl(CameraName camera, int cameraMonitorViewId, boolean autoPauseCameraMonitor, Size cameraResolution,
                            StreamFormat webcamStreamFormat, boolean autoStartStreamOnBuild, boolean showStatsOverlay,
                            VisionProcessor[] processors) {
        if (camera == null) throw new IllegalStateException("parameters.camera == null");
        if (cameraResolution == null) throw new IllegalStateException("parameters.cameraResolution == null");
        this.cameraName = camera;
        this.camera = camera instanceof SimWebcamName ? ((SimWebcamName) camera).camera() : null;
        this.processors = processors.clone();
        this.processorsEnabled = new boolean[processors.length];
        java.util.Arrays.fill(processorsEnabled, true);
        this.width = cameraResolution.getWidth();
        this.height = cameraResolution.getHeight();
        this.liveViewRequested = cameraMonitorViewId != 0;
        this.liveView = liveViewRequested;
        this.showStats = showStatsOverlay;
        this.streamRequested = autoStartStreamOnBuild;
        if (robot != null) cancelCloseOnStop = robot.onOpModeStopped(this::close);

        thread = new Thread(this::run, "VisionPortal " + describe(camera));
        thread.setDaemon(true);
        thread.start();
    }

    // ---- the camera thread -------------------------------------------------------------------

    private void run() {
        if (camera == null) {
            RobotLog.ee(TAG, "cannot open " + describe(cameraName) + ": "
                    + (cameraName.isCameraDirection() ? "a Control Hub has no built-in camera; use a webcam"
                    : "only webcams from the robot configuration can be opened"));
            cameraState = CameraState.ERROR;
            return;
        }
        String nativesProblem = VisionNatives.await();
        if (nativesProblem != null) RobotLog.ee(TAG, nativesProblem);
        pause(150);                                    // the webcam enumerates and opens
        if (closed) return;
        cameraState = CameraState.CAMERA_DEVICE_READY;

        Mat frame = null;
        byte[] rgba = new byte[width * height * 4];
        int[] argb = new int[width * height];
        long period = Math.round(1e9 / Math.max(1, camera.fps()));
        long fpsWindowStart = System.nanoTime();
        long nextFrame = System.nanoTime();
        int fpsFrames = 0;
        boolean initialized = false;
        try {
            while (!closed) {
                if (!streamRequested) {
                    CameraState st = cameraState;
                    if (st == CameraState.STREAMING || st == CameraState.STOPPING_STREAM) cameraState = CameraState.CAMERA_DEVICE_READY;
                    initialized = false;
                    fps = 0;
                    pause(20);
                    continue;
                }
                if (!initialized) {
                    cameraState = CameraState.STARTING_STREAM;
                    pause(100);
                    if (frame == null) frame = new Mat(height, width, CvType.CV_8UC4);
                    CameraCalibration calibration = CameraCalibrationHelper.getInstance().getCalibration(
                            new VendorProductCalibrationIdentity(camera.vendorId(), camera.productId()), width, height);
                    for (VisionProcessor p : processors) p.init(width, height, calibration);
                    initialized = true;
                    cameraState = CameraState.STREAMING;
                    fpsWindowStart = System.nanoTime();
                    fpsFrames = 0;
                    nextFrame = System.nanoTime();
                }
                waitUntil(nextFrame);
                long frameStart = System.nanoTime();
                // Frames come at a fixed rate; after falling behind, start over rather than burst.
                nextFrame = frameStart - nextFrame > period ? frameStart + period : nextFrame + period;
                long captureNanos = camera.capture(width, height, rgba);
                frame.put(0, 0, rgba);

                String saveAs = captureNextFrame;
                if (saveAs != null) {
                    captureNextFrame = null;
                    saveFrame(saveAs, rgba);
                }
                Object[] contexts = new Object[processors.length];
                boolean[] enabled = enabledSnapshot();
                for (int i = 0; i < processors.length; i++) {
                    if (enabled[i]) contexts[i] = processors[i].processFrame(frame, captureNanos);
                }

                toArgb(rgba, argb);
                if (liveView) drawLiveView(argb, enabled, contexts, frameStart);
                synchronized (frameLock) {   // keep this frame for getFrameBitmap; reuse the older buffer
                    int[] older = latestFrame;
                    latestFrame = argb;
                    argb = older != null ? older : new int[width * height];
                }

                fpsFrames++;
                long now = System.nanoTime();
                if (now - fpsWindowStart >= 1_000_000_000L) {
                    fps = (float) (fpsFrames * 1e9 / (now - fpsWindowStart));
                    fpsWindowStart = now;
                    fpsFrames = 0;
                }
            }
        } catch (Throwable t) {
            if (!closed) {
                RobotLog.ee(TAG, t, "camera stream failed");
                cameraState = CameraState.ERROR;
            }
        } finally {
            if (frame != null) frame.release();
            closePreview();
        }
    }

    private void drawLiveView(int[] rawFrame, boolean[] enabled, Object[] contexts, long frameStart) {
        if (view == null) {
            view = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            viewPixels = new int[width * height];
        }
        view.setPixels(rawFrame, 0, width, 0, 0, width, height);
        Canvas canvas = new Canvas(view);
        for (int i = 0; i < processors.length; i++) {
            if (enabled[i]) processors[i].onDrawFrame(canvas, width, height, 1.0f, CANVAS_DENSITY, contexts[i]);
        }
        if (showStats) {
            Paint band = new Paint();
            band.setColor(Color.argb(150, 0, 0, 0));
            canvas.drawRect(0, height - 22, width, height, band);
            Paint text = new Paint();
            text.setColor(Color.WHITE);
            text.setAntiAlias(true);
            text.setTextSize(14);
            canvas.drawText(String.format(Locale.US, "FPS: %.1f   Pipeline: %d ms   %dx%d", fps,
                    (System.nanoTime() - frameStart) / 1_000_000L, width, height), 6, height - 6, text);
        }
        view.getPixels(viewPixels, 0, width, 0, 0, width, height);
        CameraPreviews.Preview target;
        synchronized (this) {   // stopLiveView() and close() decide under the same lock
            if (closed || !liveView) return;
            if (preview == null) {
                String name = cameraName instanceof WebcamName ? ((WebcamName) cameraName).getDeviceName() : cameraName.toString();
                preview = CameraPreviews.open(robot == null ? 0 : robot.index, name);
            }
            target = preview;
        }
        target.publish(viewPixels, width, height, fps);
    }

    private synchronized void closePreview() {
        if (preview != null) {
            CameraPreviews.close(preview);
            preview = null;
        }
    }

    private static void toArgb(byte[] rgba, int[] argb) {
        for (int i = 0, j = 0; i < argb.length; i++, j += 4) {
            argb[i] = 0xFF000000 | (rgba[j] & 0xFF) << 16 | (rgba[j + 1] & 0xFF) << 8 | (rgba[j + 2] & 0xFF);
        }
    }

    private void saveFrame(String name, byte[] rgba) {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            int[] argb = new int[width * height];
            toArgb(rgba, argb);
            img.setRGB(0, 0, width, height, argb, 0, width);
            File dir = new File("captures");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("cannot create " + dir.getAbsolutePath());
            File out = new File(dir, "VisionPortal-" + name + ".png");
            javax.imageio.ImageIO.write(img, "png", out);
            RobotLog.ii(TAG, "saved frame to " + out.getAbsolutePath());
        } catch (Exception e) {
            RobotLog.ee(TAG, e, "saveNextFrameRaw failed");
        }
    }

    private static void pause(long ms) {
        LockSupport.parkNanos(ms * 1_000_000L);
    }

    /**
     * Waits until {@code deadline} (System.nanoTime()). Sleeps for most of it, then yields: on
     * Windows a sleep can overshoot by a whole 15.6 ms timer tick, which would cost frames.
     */
    private void waitUntil(long deadline) {
        long left;
        while (!closed && (left = deadline - System.nanoTime()) > 0) {
            if (left > 3_000_000L) LockSupport.parkNanos(left - 2_500_000L);
            else Thread.yield();
        }
    }

    private synchronized boolean[] enabledSnapshot() {
        return processorsEnabled.clone();
    }

    private static String describe(CameraName c) {
        return c instanceof WebcamName ? "\"" + ((WebcamName) c).getDeviceName() + "\"" : String.valueOf(c);
    }

    // ---- VisionPortal ------------------------------------------------------------------------

    @Override
    public synchronized void setProcessorEnabled(VisionProcessor processor, boolean enabled) {
        for (int i = 0; i < processors.length; i++) {
            if (processors[i] == processor) {
                processorsEnabled[i] = enabled;
                return;
            }
        }
        throw new IllegalArgumentException("Processor not attached to this portal");
    }

    @Override
    public synchronized boolean getProcessorEnabled(VisionProcessor processor) {
        for (int i = 0; i < processors.length; i++) {
            if (processors[i] == processor) return processorsEnabled[i];
        }
        throw new IllegalArgumentException("Processor not attached to this portal");
    }

    @Override
    public CameraState getCameraState() {
        return cameraState;
    }

    @Override
    public void setActiveCamera(WebcamName webcamName) {
        throw new UnsupportedOperationException("setActiveCamera(...) is only supported for a switchable webcam");
    }

    @Override
    public WebcamName getActiveCamera() {
        throw new UnsupportedOperationException("getActiveCamera() is only supported for a switchable webcam");
    }

    /** Same rules as FIRST's VisionPortalImpl: webcams only, and only while streaming. */
    @Override
    public <T extends CameraControl> T getCameraControl(Class<T> controlType) {
        if (cameraState != CameraState.STREAMING) {
            throw new IllegalStateException("You cannot use camera controls until the camera is streaming");
        }
        if (camera == null) throw new UnsupportedOperationException("Getting controls is only supported for webcams");
        return controls.get(controlType);
    }

    @Override
    public void getFrameBitmap(Continuation<? extends Consumer<Bitmap>> continuation) {
        final Bitmap frame;
        synchronized (frameLock) {
            if (latestFrame == null) return;
            frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            frame.setPixels(latestFrame, 0, width, 0, 0, width, height);
        }
        continuation.dispatch(new ContinuationResult<Consumer<Bitmap>>() {
            @Override
            public void handle(Consumer<Bitmap> consumer) {
                consumer.accept(frame);
            }
        });
    }

    @Override
    public void saveNextFrameRaw(String filename) {
        captureNextFrame = filename;
    }

    /**
     * As FIRST's: stops a stream that is starting or running, does nothing when none is, and throws
     * while the camera is opening, closed or failed. Code that stops the stream right after build(),
     * before the camera has opened, fails on the robot, so it fails here too.
     */
    @Override
    public void stopStreaming() {
        CameraState st = cameraState;
        if (st == CameraState.STREAMING || st == CameraState.STARTING_STREAM) {
            cameraState = CameraState.STOPPING_STREAM;
            streamRequested = false;
        } else if (st == CameraState.CAMERA_DEVICE_READY || st == CameraState.STOPPING_STREAM || st == CameraState.CLOSING_CAMERA_DEVICE) {
            streamRequested = false;   // nothing streams
        } else {
            throw new RuntimeException(String.format("Illegal CameraState %s when calling stopStreaming()", st));
        }
    }

    /** As FIRST's: starts the stream of an open camera, does nothing while one streams, and throws otherwise. */
    @Override
    public void resumeStreaming() {
        CameraState st = cameraState;
        if (st == CameraState.CAMERA_DEVICE_READY || st == CameraState.STOPPING_STREAM
                || st == CameraState.STREAMING || st == CameraState.STARTING_STREAM) {
            streamRequested = true;
        } else {
            throw new RuntimeException(String.format("Illegal CameraState %s when calling resumeStreaming()", st));
        }
    }

    @Override
    public synchronized void stopLiveView() {
        liveView = false;
        closePreview();
    }

    @Override
    public synchronized void resumeLiveView() {
        liveView = liveViewRequested;
    }

    @Override
    public float getFps() {
        return fps;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        if (cancelCloseOnStop != null) cancelCloseOnStop.run();
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            LockSupport.unpark(t);
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closePreview();
        cameraState = CameraState.CAMERA_DEVICE_CLOSED;
    }
}
