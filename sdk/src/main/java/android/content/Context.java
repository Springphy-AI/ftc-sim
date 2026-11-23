package android.content;

import android.content.res.Resources;

/**
 * Stand-in for Android's Context, as far as robot code uses one: {@code hardwareMap.appContext}
 * for the app's package name and resources (to look up a view id for a camera monitor, say).
 */
public abstract class Context {
    public abstract String getPackageName();

    public abstract Resources getResources();
}
