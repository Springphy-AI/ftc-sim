package ftcsim.hal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Loads the native libraries robot vision code runs on, as the Robot Controller app does at
 * startup: OpenCV (4.10.0, the robot's version; 4.11.0 on macOS, see sdk/build.gradle) and the
 * AprilTag detector (natives/apriltag/, the
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

    /**
     * The OpenCV modules libopencv_java links, each after the ones it needs. On Linux they are
     * libopencv_MODULE.so.410 (OpenCV 4.10).
     */
    private static final String[] JAVA_BINDING_MODULES = {"core", "imgproc", "flann", "features2d", "calib3d", "dnn",
            "video", "imgcodecs", "videoio", "plot", "objdetect", "photo", "ml", "phase_unwrapping", "tracking", "face",
            "bioinspired", "bgsegm", "aruco", "ximgproc", "xfeatures2d", "wechat_qrcode", "structured_light", "text",
            "img_hash", "dnn_superres", "xphoto"};

    private static String load() {
        StringBuilder problems = new StringBuilder();
        try {
            org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.opencv_java.class);
        } catch (Throwable t) {
            String fallback = loadJavaBindingsWithoutHighgui();
            if (fallback != null) problems.append("OpenCV: ").append(t).append("; ").append(fallback).append(". ");
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

    /**
     * Loads OpenCV's own Java bindings (libopencv_java, what FIRST's vision code and org.opencv call)
     * and the modules they link, without bytedeco's presets. On Linux, loading the opencv_java preset
     * also loads highgui, whose library needs GTK 2, which Ubuntu 24.04 does not install; the bindings
     * themselves do not use highgui, and the simulator never opens an OpenCV window. Returns null when
     * this worked, otherwise why not.
     */
    private static String loadJavaBindingsWithoutHighgui() {
        String platform = org.bytedeco.javacpp.Loader.getPlatform();
        if (!platform.startsWith("linux")) return "no fallback on " + platform;
        try {
            org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.global.opencv_core.class);   // OpenBLAS, which core links
            Class<?> owner = org.bytedeco.opencv.opencv_java.class;
            for (String module : JAVA_BINDING_MODULES) {
                java.io.File lib = org.bytedeco.javacpp.Loader.cacheResource(owner, platform + "/libopencv_" + module + ".so.410");
                if (lib == null) return "libopencv_" + module + " is not in the OpenCV jar";
                System.load(lib.getAbsolutePath());
            }
            java.io.File bindings = org.bytedeco.javacpp.Loader.cacheResource(owner, platform + "/libopencv_java.so");
            if (bindings == null) return "libopencv_java is not in the OpenCV jar";
            System.load(bindings.getAbsolutePath());
            SimLog.log('I', "VisionNatives", "OpenCV loaded without highgui (its library needs GTK 2, which is not installed)");
            return null;
        } catch (Throwable t) {
            return "loading it without highgui failed too: " + t;
        }
    }
}
