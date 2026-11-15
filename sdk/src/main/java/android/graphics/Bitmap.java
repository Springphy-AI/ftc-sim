package android.graphics;

import java.awt.image.BufferedImage;

/**
 * Stand-in for Android's Bitmap, backed by a Java2D image. A {@link Canvas} draws into it, and
 * pixels move in and out with the usual {@link #getPixels}/{@link #setPixels} (ARGB ints).
 */
public final class Bitmap {

    public enum Config {
        ALPHA_8, RGB_565, ARGB_4444, ARGB_8888
    }

    final BufferedImage image;
    private final Config config;

    private Bitmap(int width, int height, Config config) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("width and height must be > 0");
        this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        this.config = config;
    }

    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap(width, height, config);
    }

    public int getWidth() {
        return image.getWidth();
    }

    public int getHeight() {
        return image.getHeight();
    }

    public Config getConfig() {
        return config;
    }

    public int getPixel(int x, int y) {
        return image.getRGB(x, y);
    }

    public void setPixel(int x, int y, int color) {
        image.setRGB(x, y, color);
    }

    public void getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        checkRegion(x, y, width, height);
        int[] data = data();
        int w = getWidth();
        for (int row = 0; row < height; row++) System.arraycopy(data, (y + row) * w + x, pixels, offset + row * stride, width);
    }

    public void setPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        checkRegion(x, y, width, height);
        int[] data = data();
        int w = getWidth();
        for (int row = 0; row < height; row++) System.arraycopy(pixels, offset + row * stride, data, (y + row) * w + x, width);
    }

    /** The pixels themselves: TYPE_INT_ARGB stores exactly Android's non-premultiplied ARGB ints. */
    private int[] data() {
        return ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }

    private void checkRegion(int x, int y, int width, int height) {
        if (x < 0 || y < 0 || width < 0 || height < 0 || x + width > getWidth() || y + height > getHeight()) {
            throw new IllegalArgumentException("region outside the bitmap");
        }
    }

    public void eraseColor(int color) {
        int w = getWidth(), h = getHeight();
        int[] row = new int[w];
        java.util.Arrays.fill(row, color);
        for (int y = 0; y < h; y++) image.setRGB(0, y, w, 1, row, 0, w);
    }

    public void recycle() {
    }

    public boolean isRecycled() {
        return false;
    }
}
