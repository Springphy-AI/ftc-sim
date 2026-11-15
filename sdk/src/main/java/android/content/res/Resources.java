package android.content.res;

/** Stand-in for Android app resources: only identifier lookup, which FIRST's Vision module uses. */
public class Resources {

    /**
     * Returns a stable, non-zero id for a named resource. On the robot these name views in the
     * Robot Controller app's layout (for example the camera preview); here they are only labels.
     */
    public int getIdentifier(String name, String defType, String defPackage) {
        String key = defPackage + ":" + defType + "/" + name;
        return 0x7f000000 | (key.hashCode() & 0x00ffffff);
    }
}
