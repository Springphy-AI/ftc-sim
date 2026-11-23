package org.firstinspires.ftc.robotcore.internal.system;

import java.io.File;

import android.app.Application;

/**
 * The parts of the Robot Controller app's utility singleton that robot code and FIRST's SDK
 * classes running in the simulator use: the application context, the "can't happen" helper, and
 * the robot's storage folders. The Control Hub's storage ({@code /sdcard}) is a folder of the
 * simulator: build/sdcard in the simulator's folder, or the {@code ftcsim.sdcard} system property.
 * The simulator's robots share it.
 */
public class AppUtil {
    public static final String TAG = "AppUtil";

    /** The robot's storage, /sdcard on a Control Hub. */
    public static final File ROOT_FOLDER = new File(System.getProperty("ftcsim.sdcard",
            new File(System.getProperty("user.dir"), "build/sdcard").getAbsolutePath()));
    public static final File FIRST_FOLDER = new File(ROOT_FOLDER + "/FIRST/");
    public static final File ROBOT_SETTINGS = new File(FIRST_FOLDER, "/settings/");
    public static final File ROBOT_DATA_DIR = new File(FIRST_FOLDER, "/data/");

    private static final AppUtil INSTANCE = new AppUtil();
    private final Application application = new Application();

    private AppUtil() {
    }

    public static AppUtil getInstance() {
        return INSTANCE;
    }

    public static Application getDefContext() {
        return INSTANCE.application;
    }

    public Application getApplication() {
        return application;
    }

    /** As FIRST's: a file name that isn't absolute is in the robot's settings folder, which is created. */
    public File getSettingsFile(String filename) {
        File file = new File(filename);
        if (!file.isAbsolute()) {
            ensureDirectoryExists(ROBOT_SETTINGS);
            file = new File(ROBOT_SETTINGS, filename);
        }
        return file;
    }

    public void ensureDirectoryExists(File directory) {
        ensureDirectoryExists(directory, true);
    }

    public void ensureDirectoryExists(File directory, boolean logFailure) {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory() && logFailure) {
            com.qualcomm.robotcore.util.RobotLog.ee(TAG, "failed to create directory %s", directory);
        }
    }

    public RuntimeException unreachable() {
        return unreachable(TAG);
    }

    public RuntimeException unreachable(Throwable throwable) {
        return unreachable(TAG, throwable);
    }

    public RuntimeException unreachable(String tag) {
        return new IllegalStateException("internal error: this code is unreachable (" + tag + ")");
    }

    public RuntimeException unreachable(String tag, Throwable throwable) {
        return new IllegalStateException("internal error: this code is unreachable (" + tag + ")", throwable);
    }
}
