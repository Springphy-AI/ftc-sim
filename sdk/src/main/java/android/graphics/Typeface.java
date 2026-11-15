package android.graphics;

import java.awt.Font;

/** Stand-in for Android's Typeface: picks a Java2D font family and style. */
public class Typeface {
    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int BOLD_ITALIC = 3;

    public static final Typeface DEFAULT = new Typeface(Font.SANS_SERIF, NORMAL);
    public static final Typeface DEFAULT_BOLD = new Typeface(Font.SANS_SERIF, BOLD);
    public static final Typeface SANS_SERIF = new Typeface(Font.SANS_SERIF, NORMAL);
    public static final Typeface SERIF = new Typeface(Font.SERIF, NORMAL);
    public static final Typeface MONOSPACE = new Typeface(Font.MONOSPACED, NORMAL);

    final String family;
    private final int style;

    private Typeface(String family, int style) {
        this.family = family;
        this.style = style;
    }

    public static Typeface create(Typeface family, int style) {
        return new Typeface(family == null ? Font.SANS_SERIF : family.family, style);
    }

    public int getStyle() {
        return style;
    }

    public final boolean isBold() {
        return (style & BOLD) != 0;
    }

    public final boolean isItalic() {
        return (style & ITALIC) != 0;
    }

    Font font(float size) {
        int awt = (isBold() ? Font.BOLD : 0) | (isItalic() ? Font.ITALIC : 0);
        return new Font(family, awt, 1).deriveFont(size);
    }
}
