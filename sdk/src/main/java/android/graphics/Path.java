package android.graphics;

import java.awt.geom.Path2D;

/** Stand-in for Android's Path: a polygon/outline built from moveTo/lineTo/close. */
public class Path {
    final Path2D.Float shape = new Path2D.Float();
    private boolean started;

    public void moveTo(float x, float y) {
        shape.moveTo(x, y);
        started = true;
    }

    public void lineTo(float x, float y) {
        if (!started) moveTo(0, 0);
        shape.lineTo(x, y);
    }

    public void quadTo(float x1, float y1, float x2, float y2) {
        if (!started) moveTo(0, 0);
        shape.quadTo(x1, y1, x2, y2);
    }

    public void close() {
        if (started) shape.closePath();
    }

    public void reset() {
        shape.reset();
        started = false;
    }

    public boolean isEmpty() {
        return !started;
    }
}
