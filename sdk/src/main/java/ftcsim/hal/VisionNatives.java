package ftcsim.hal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Loads the native libraries robot vision code runs on, as the Robot Controller app does at
 * startup: OpenCV (4.10.0, the robot's version) and the AprilTag detector (natives/apriltag/, the
 * same source FIRST ships, compiled for this PC). TeamCode may create OpenCV objects as soon as an
 * OpMode is constructed, so the simulator starts this early and waits for it before INIT.
 */
public final class VisionNatives {

    private static CompletableFuture<String> loading;

    private VisionNatives() {
    }

    /** Starts loading in the background (once). */
    public static synchronized void startLoading() {
        if (loading != null) return;
        loading = CompletableFuture.supplyAsync(VisionNatives::load, r -> {
            Thread t = new Thread(r, "vision-natives");
            t.setDaemon(true);
            t.start();
        });
    }

    /**
     * Waits until loading finished. Returns null when everything loaded, otherwise what is
     * missing (vision code will then fail when it first touches the missing library).
     */
    public static String await() {
        startLoading();
        try {
            return loading.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "loading vision libraries failed: " + e;
        }
    }

    private static String load() {
        StringBuilder problems = new StringBuilder();
        try {
            org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.opencv_java.class);
        } catch (Throwable t) {
            problems.append("OpenCV: ").append(t).append(". ");
        }
        try {
            // FIRST's AprilTag wrapper calls System.loadLibrary("apriltag") itself; loading it here
            // first (same class loader) just reports a missing library clearly and early.
            System.loadLibrary("apriltag");
        } catch (Throwable t) {
            problems.append("AprilTag detector: ").append(t.getMessage())
                    .append(" (start the simulator with run-sim, which puts natives/apriltag on java.library.path). ");
        }
        String result = problems.length() == 0 ? null : problems.toString().trim();
        if (result != null) SimLog.log('E', "VisionNatives", result);
        return result;
    }
}
