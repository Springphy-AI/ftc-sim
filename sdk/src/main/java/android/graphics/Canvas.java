package android.graphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Stand-in for Android's Canvas, drawing into a {@link Bitmap} with Java2D. VisionProcessors draw
 * their overlays (tag outlines, axes, ids, blobs) through this in {@code onDrawFrame}; the
 * simulator shows the result as the camera preview.
 */
public class Canvas {
    private final Bitmap bitmap;
    private final Graphics2D g;
    private final Deque<AffineTransform> saved = new ArrayDeque<>();

    public Canvas(Bitmap bitmap) {
        this.bitmap = bitmap;
        this.g = bitmap.image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    public int getWidth() {
        return bitmap.getWidth();
    }

    public int getHeight() {
        return bitmap.getHeight();
    }

    public int save() {
        saved.push(g.getTransform());
        return saved.size();
    }

    public void restore() {
        if (saved.isEmpty()) throw new IllegalStateException("Underflow in restore - more restores than saves");
        g.setTransform(saved.pop());
    }

    public int getSaveCount() {
        return saved.size() + 1;
    }

    public void translate(float dx, float dy) {
        g.translate(dx, dy);
    }

    public void scale(float sx, float sy) {
        g.scale(sx, sy);
    }

    public final void scale(float sx, float sy, float px, float py) {
        translate(px, py);
        scale(sx, sy);
        translate(-px, -py);
    }

    public void rotate(float degrees) {
        g.rotate(Math.toRadians(degrees));
    }

    public final void rotate(float degrees, float px, float py) {
        g.rotate(Math.toRadians(degrees), px, py);
    }

    public void drawColor(int color) {
        AffineTransform t = g.getTransform();
        g.setTransform(new AffineTransform());
        g.setColor(new java.awt.Color(color, true));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setTransform(t);
    }

    public void drawRGB(int r, int g, int b) {
        drawColor(Color.rgb(r, g, b));
    }

    public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {
        stroke(new Line2D.Float(startX, startY, stopX, stopY), paint);
    }

    /** Points come in groups of four: x0, y0, x1, y1 per line. */
    public void drawLines(float[] pts, Paint paint) {
        drawLines(pts, 0, pts.length, paint);
    }

    public void drawLines(float[] pts, int offset, int count, Paint paint) {
        for (int i = offset; i + 3 < offset + count; i += 4) drawLine(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], paint);
    }

    public void drawPoint(float x, float y, Paint paint) {
        float w = Math.max(1f, paint.getStrokeWidth());
        Shape s = paint.getStrokeCap() == Paint.Cap.ROUND
                ? new Ellipse2D.Float(x - w / 2, y - w / 2, w, w)
                : new Rectangle2D.Float(x - w / 2, y - w / 2, w, w);
        setup(paint);
        g.fill(s);
    }

    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
        draw(new Rectangle2D.Float(left, top, right - left, bottom - top), paint);
    }

    public void drawRect(Rect r, Paint paint) {
        drawRect(r.left, r.top, r.right, r.bottom, paint);
    }

    public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry, Paint paint) {
        draw(new RoundRectangle2D.Float(left, top, right - left, bottom - top, 2 * rx, 2 * ry), paint);
    }

    public void drawCircle(float cx, float cy, float radius, Paint paint) {
        draw(new Ellipse2D.Float(cx - radius, cy - radius, 2 * radius, 2 * radius), paint);
    }

    public void drawOval(float left, float top, float right, float bottom, Paint paint) {
        draw(new Ellipse2D.Float(left, top, right - left, bottom - top), paint);
    }

    public void drawPath(Path path, Paint paint) {
        draw(path.shape, paint);
    }

    /** {@code y} is the text baseline, as on Android. */
    public void drawText(String text, float x, float y, Paint paint) {
        if (text == null || text.isEmpty()) return;
        setup(paint);
        g.setFont(paint.font());
        float w = paint.measureText(text);
        float left = paint.getTextAlign() == Paint.Align.CENTER ? x - w / 2 : paint.getTextAlign() == Paint.Align.RIGHT ? x - w : x;
        g.drawString(text, left, y);
    }

    public void drawBitmap(Bitmap source, float left, float top, Paint paint) {
        g.drawImage(source.image, Math.round(left), Math.round(top), null);
    }

    public void drawBitmap(Bitmap source, Rect src, Rect dst, Paint paint) {
        Rect s = src != null ? src : new Rect(0, 0, source.getWidth(), source.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source.image, dst.left, dst.top, dst.right, dst.bottom, s.left, s.top, s.right, s.bottom, null);
    }

    private void draw(Shape shape, Paint paint) {
        setup(paint);
        Paint.Style style = paint.getStyle();
        if (style == Paint.Style.FILL || style == Paint.Style.FILL_AND_STROKE) g.fill(shape);
        if (style == Paint.Style.STROKE || style == Paint.Style.FILL_AND_STROKE) g.draw(shape);
    }

    private void stroke(Shape shape, Paint paint) {
        setup(paint);
        g.draw(shape);
    }

    private void setup(Paint paint) {
        g.setColor(paint.awtColor());
        g.setStroke(paint.stroke());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                paint.isAntiAlias() ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
    }
}
