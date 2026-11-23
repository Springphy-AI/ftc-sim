package android.graphics;

/**
 * Desktop stand-in for the parts of android.graphics.Color that robot code commonly uses
 * (packing/unpacking ARGB ints and RGB/HSV conversion).
 */
public class Color {
    public static final int BLACK = 0xFF000000;
    public static final int DKGRAY = 0xFF444444;
    public static final int GRAY = 0xFF888888;
    public static final int LTGRAY = 0xFFCCCCCC;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int RED = 0xFFFF0000;
    public static final int GREEN = 0xFF00FF00;
    public static final int BLUE = 0xFF0000FF;
    public static final int YELLOW = 0xFFFFFF00;
    public static final int CYAN = 0xFF00FFFF;
    public static final int MAGENTA = 0xFFFF00FF;
    public static final int TRANSPARENT = 0;

    public static int alpha(int color) {
        return color >>> 24;
    }

    public static int red(int color) {
        return (color >> 16) & 0xFF;
    }

    public static int green(int color) {
        return (color >> 8) & 0xFF;
    }

    public static int blue(int color) {
        return color & 0xFF;
    }

    public static int rgb(int red, int green, int blue) {
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    public static int argb(int alpha, int red, int green, int blue) {
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    public static void RGBToHSV(int red, int green, int blue, float[] hsv) {
        float r = red / 255f, g = green / 255f, b = blue / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float h;
        if (delta == 0) {
            h = 0;
        } else if (max == r) {
            h = 60 * (((g - b) / delta) % 6);
        } else if (max == g) {
            h = 60 * (((b - r) / delta) + 2);
        } else {
            h = 60 * (((r - g) / delta) + 4);
        }
        if (h < 0) h += 360;
        hsv[0] = h;
        hsv[1] = max == 0 ? 0 : delta / max;
        hsv[2] = max;
    }

    public static void colorToHSV(int color, float[] hsv) {
        RGBToHSV(red(color), green(color), blue(color), hsv);
    }

    public static int HSVToColor(float[] hsv) {
        return HSVToColor(0xFF, hsv);
    }

    public static int HSVToColor(int alpha, float[] hsv) {
        float h = ((hsv[0] % 360) + 360) % 360, s = hsv[1], v = hsv[2];
        float c = v * s;
        float x = c * (1 - Math.abs((h / 60f) % 2 - 1));
        float m = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return argb(alpha, Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
    }

    /** "#RRGGBB", "#AARRGGBB", or one of Android's color names (any case). */
    public static int parseColor(String colorString) {
        if (colorString.charAt(0) == '#') {
            long color = Long.parseLong(colorString.substring(1), 16);
            if (colorString.length() == 7) {
                color |= 0x00000000ff000000L;
            } else if (colorString.length() != 9) {
                throw new IllegalArgumentException("Unknown color");
            }
            return (int) color;
        }
        Integer color = NAMES.get(colorString.toLowerCase(java.util.Locale.ROOT));
        if (color != null) return color;
        throw new IllegalArgumentException("Unknown color");
    }

    /** Android's Color.sColorNameMap. */
    private static final java.util.Map<String, Integer> NAMES = new java.util.HashMap<String, Integer>();

    static {
        NAMES.put("black", BLACK);
        NAMES.put("darkgray", DKGRAY);
        NAMES.put("gray", GRAY);
        NAMES.put("lightgray", LTGRAY);
        NAMES.put("white", WHITE);
        NAMES.put("red", RED);
        NAMES.put("green", GREEN);
        NAMES.put("blue", BLUE);
        NAMES.put("yellow", YELLOW);
        NAMES.put("cyan", CYAN);
        NAMES.put("magenta", MAGENTA);
        NAMES.put("aqua", 0xFF00FFFF);
        NAMES.put("fuchsia", 0xFFFF00FF);
        NAMES.put("darkgrey", DKGRAY);
        NAMES.put("grey", GRAY);
        NAMES.put("lightgrey", LTGRAY);
        NAMES.put("lime", 0xFF00FF00);
        NAMES.put("maroon", 0xFF800000);
        NAMES.put("navy", 0xFF000080);
        NAMES.put("olive", 0xFF808000);
        NAMES.put("purple", 0xFF800080);
        NAMES.put("silver", 0xFFC0C0C0);
        NAMES.put("teal", 0xFF008080);
    }
}
