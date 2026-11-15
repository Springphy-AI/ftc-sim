package org.firstinspires.ftc.robotcore.internal.system;

import android.app.Application;

/**
 * The parts of the Robot Controller app's utility singleton that FIRST's SDK classes running in
 * the simulator call: the application context and the "can't happen" helper.
 */
public class AppUtil {
    public static final String TAG = "AppUtil";

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
