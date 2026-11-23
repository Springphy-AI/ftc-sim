package android.app;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;

/**
 * Stand-in for Android's Activity, which FIRST's JavaUtil.showColor checks a Context for. The
 * simulator has no Robot Controller screen, so there never is one.
 */
public class Activity extends Context {
    private final Resources resources = new Resources();

    @Override
    public String getPackageName() {
        return "com.qualcomm.ftcrobotcontroller";
    }

    @Override
    public Resources getResources() {
        return resources;
    }

    /** No views: the simulator draws no Robot Controller screen. */
    public View findViewById(int id) {
        return null;
    }
}
