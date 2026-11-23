package android.view;

/** Stand-in for Android's View, as FIRST's JavaUtil.showColor refers to it. Nothing is drawn. */
public class View {
    public boolean post(Runnable action) {
        return false;
    }

    public void setBackgroundColor(int color) {
    }
}
