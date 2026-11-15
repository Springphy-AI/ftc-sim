package android.graphics;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.font.FontRenderContext;

/** Stand-in for Android's Paint: color, stroke and text settings for {@link Canvas} drawing. */
public class Paint {

    public static final int ANTI_ALIAS_FLAG = 1;

    public enum Style {
        FILL, STROKE, FILL_AND_STROKE
    }

    public enum Cap {
        BUTT, ROUND, SQUARE
    }

    public enum Join {
        MITER, ROUND, BEVEL
    }

    public enum Align {
        LEFT, CENTER, RIGHT
    }

    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    private int color = Color.BLACK;
    private float strokeWidth = 0;
    private Style style = Style.FILL;
    private Cap strokeCap = Cap.BUTT;
    private Join strokeJoin = Join.MITER;
    private Align textAlign = Align.LEFT;
    private boolean antiAlias;
    private float textSize = 12;
    private Typeface typeface = Typeface.DEFAULT;

    public Paint() {
    }

    public Paint(int flags) {
        antiAlias = (flags & ANTI_ALIAS_FLAG) != 0;
    }

    public Paint(Paint paint) {
        set(paint);
    }

    public void set(Paint src) {
        color = src.color;
        strokeWidth = src.strokeWidth;
        style = src.style;
        strokeCap = src.strokeCap;
        strokeJoin = src.strokeJoin;
        textAlign = src.textAlign;
        antiAlias = src.antiAlias;
        textSize = src.textSize;
        typeface = src.typeface;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public void setARGB(int a, int r, int g, int b) {
        color = Color.argb(a, r, g, b);
    }

    public int getAlpha() {
        return Color.alpha(color);
    }

    public void setAlpha(int a) {
        color = (color & 0x00ffffff) | ((a & 0xff) << 24);
    }

    public float getStrokeWidth() {
        return strokeWidth;
    }

    public void setStrokeWidth(float width) {
        strokeWidth = width;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style;
    }

    public Cap getStrokeCap() {
        return strokeCap;
    }

    public void setStrokeCap(Cap cap) {
        strokeCap = cap;
    }

    public Join getStrokeJoin() {
        return strokeJoin;
    }

    public void setStrokeJoin(Join join) {
        strokeJoin = join;
    }

    public Align getTextAlign() {
        return textAlign;
    }

    public void setTextAlign(Align align) {
        textAlign = align;
    }

    public final boolean isAntiAlias() {
        return antiAlias;
    }

    public void setAntiAlias(boolean aa) {
        antiAlias = aa;
    }

    public float getTextSize() {
        return textSize;
    }

    public void setTextSize(float textSize) {
        this.textSize = textSize;
    }

    public Typeface getTypeface() {
        return typeface;
    }

    public Typeface setTypeface(Typeface typeface) {
        this.typeface = typeface == null ? Typeface.DEFAULT : typeface;
        return typeface;
    }

    public float measureText(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (float) font().getStringBounds(text, FRC).getWidth();
    }

    Font font() {
        return typeface.font(textSize);
    }

    java.awt.Color awtColor() {
        return new java.awt.Color(color, true);
    }

    BasicStroke stroke() {
        int cap = strokeCap == Cap.ROUND ? BasicStroke.CAP_ROUND : strokeCap == Cap.SQUARE ? BasicStroke.CAP_SQUARE : BasicStroke.CAP_BUTT;
        int join = strokeJoin == Join.ROUND ? BasicStroke.JOIN_ROUND : strokeJoin == Join.BEVEL ? BasicStroke.JOIN_BEVEL : BasicStroke.JOIN_MITER;
        // Android draws width 0 as a one-pixel hairline.
        return new BasicStroke(strokeWidth <= 0 ? 1f : strokeWidth, cap, join);
    }
}
