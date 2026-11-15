package ftcsim.vision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A small software renderer for the simulated webcams: z-buffered triangles with flat, textured
 * or procedural-floor coloring, see-through surfaces blended back to front, and spheres.
 *
 * <p>The camera is an ideal pinhole with OpenCV's conventions, the same model FIRST's AprilTag
 * pose solver assumes: camera x right, y down, z forward, and a point (X, Y, Z) lands on pixel
 * (fx X/Z + cx, fy Y/Z + cy), where integer pixel coordinates are pixel centers.
 */
final class Rasterizer {

    /** Colors the floor at a world position (already lit). */
    interface FloorShader {
        int color(double x, double y);
    }

    private static final double NEAR = 0.01;

    final int w, h;
    final int[] color;
    private final float[] invDepth;
    private final FloorShader floor;

    // world -> camera rotation (rows) and translation
    private double r00, r01, r02, r10, r11, r12, r20, r21, r22, tx, ty, tz;
    private double fx, fy, cx, cy;

    private record Deferred(Scene.Tri tri, double depth) {
    }

    Rasterizer(int w, int h, FloorShader floor) {
        this.w = w;
        this.h = h;
        this.color = new int[w * h];
        this.invDepth = new float[w * h];
        this.floor = floor;
    }

    /**
     * @param worldToCamera row-major 3x3 rotation taking world directions into camera axes
     * @param position      camera center in world coordinates
     */
    void setCamera(double[] worldToCamera, double[] position, double fx, double fy, double cx, double cy) {
        r00 = worldToCamera[0]; r01 = worldToCamera[1]; r02 = worldToCamera[2];
        r10 = worldToCamera[3]; r11 = worldToCamera[4]; r12 = worldToCamera[5];
        r20 = worldToCamera[6]; r21 = worldToCamera[7]; r22 = worldToCamera[8];
        tx = -(r00 * position[0] + r01 * position[1] + r02 * position[2]);
        ty = -(r10 * position[0] + r11 * position[1] + r12 * position[2]);
        tz = -(r20 * position[0] + r21 * position[1] + r22 * position[2]);
        this.fx = fx;
        this.fy = fy;
        this.cx = cx;
        this.cy = cy;
    }

    /**
     * A triangle on the screen, ready to fill: corner pixel positions, 1/Z, and two attributes
     * divided by Z (texture u, v or floor x, y), so they interpolate perspective-correctly.
     */
    private static final class ScreenTri {
        double x0, y0, x1, y1, x2, y2, iz0, iz1, iz2, a0, a1, a2, b0, b1, b2;
        int minY, maxY;
        Material material;
        int flat;
    }

    private List<ScreenTri> opaque = new ArrayList<>();
    private List<ScreenTri> seeThrough = new ArrayList<>();

    /**
     * Projects and clips the frame's triangles once; {@link #renderBand} then only fills pixels.
     * See-through triangles are kept if any corner is in front of the camera and are blended
     * farthest first (by their mean depth).
     */
    void prepare(List<Scene.Tri> tris) {
        List<ScreenTri> solid = new ArrayList<>(tris.size());
        List<Deferred> deferred = new ArrayList<>();
        for (Scene.Tri t : tris) {
            if (t.material.opaque()) {
                project(t, solid);
            } else {
                double[] v = t.v;
                double sum = 0, nearest = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < 9; i += 3) {
                    double z = r20 * v[i] + r21 * v[i + 1] + r22 * v[i + 2] + tz;
                    sum += z;
                    nearest = Math.max(nearest, z);
                }
                if (nearest > NEAR) deferred.add(new Deferred(t, sum / 3));
            }
        }
        deferred.sort(Comparator.comparingDouble(Deferred::depth).reversed());
        List<ScreenTri> blended = new ArrayList<>(deferred.size());
        for (Deferred d : deferred) project(d.tri, blended);
        opaque = solid;
        seeThrough = blended;
    }

    /**
     * Renders rows [y0, y1) of the prepared frame: background, opaque triangles, spheres (x, y, z,
     * r, rgb per sphere), then see-through triangles. Bands of rows can be rendered on different
     * threads at once; they touch disjoint pixels.
     */
    void renderBand(double[] spheres, int background, int y0, int y1) {
        java.util.Arrays.fill(color, y0 * w, y1 * w, background);
        java.util.Arrays.fill(invDepth, y0 * w, y1 * w, 0f);
        for (ScreenTri t : opaque) if (t.maxY >= y0 && t.minY < y1) raster(t, false, y0, y1);
        for (int i = 0; i + 4 < spheres.length; i += 5) {
            sphere(spheres[i], spheres[i + 1], spheres[i + 2], spheres[i + 3], (int) spheres[i + 4], y0, y1);
        }
        for (ScreenTri t : seeThrough) if (t.maxY >= y0 && t.minY < y1) raster(t, true, y0, y1);
    }

    // ---- triangles -----------------------------------------------------------------------------

    /** Transforms a triangle into the camera, clips it at the near plane and adds what is on screen to {@code out}. */
    private void project(Scene.Tri t, List<ScreenTri> out) {
        double[] v = t.v;
        Material m = t.material;
        // Per vertex: camera X, Y, Z and two attributes (texture u, v or floor world x, y).
        double[] p = new double[15];
        boolean anyInFront = false;
        for (int i = 0, j = 0; i < 9; i += 3, j += 5) {
            double x = v[i], y = v[i + 1], z = v[i + 2];
            p[j] = r00 * x + r01 * y + r02 * z + tx;
            p[j + 1] = r10 * x + r11 * y + r12 * z + ty;
            p[j + 2] = r20 * x + r21 * y + r22 * z + tz;
            if (m.kind == Material.Kind.TEXTURE) {
                p[j + 3] = t.uv[(i / 3) * 2];
                p[j + 4] = t.uv[(i / 3) * 2 + 1];
            } else {
                p[j + 3] = x;
                p[j + 4] = y;
            }
            if (p[j + 2] > NEAR) anyInFront = true;
        }
        if (!anyInFront) return;
        double[] poly = clipNear(p);
        int n = poly.length / 5;
        if (n < 3) return;
        double[] sx = new double[n], sy = new double[n], iz = new double[n], az = new double[n], bz = new double[n];
        for (int k = 0; k < n; k++) {
            double inv = 1.0 / poly[k * 5 + 2];
            sx[k] = fx * poly[k * 5] * inv + cx;
            sy[k] = fy * poly[k * 5 + 1] * inv + cy;
            iz[k] = inv;
            az[k] = poly[k * 5 + 3] * inv;
            bz[k] = poly[k * 5 + 4] * inv;
        }
        for (int k = 1; k + 1 < n; k++) {
            ScreenTri s = new ScreenTri();
            s.x0 = sx[0]; s.y0 = sy[0]; s.iz0 = iz[0]; s.a0 = az[0]; s.b0 = bz[0];
            s.x1 = sx[k]; s.y1 = sy[k]; s.iz1 = iz[k]; s.a1 = az[k]; s.b1 = bz[k];
            s.x2 = sx[k + 1]; s.y2 = sy[k + 1]; s.iz2 = iz[k + 1]; s.a2 = az[k + 1]; s.b2 = bz[k + 1];
            double minX = Math.min(s.x0, Math.min(s.x1, s.x2)), maxX = Math.max(s.x0, Math.max(s.x1, s.x2));
            double top = Math.min(s.y0, Math.min(s.y1, s.y2)), bottom = Math.max(s.y0, Math.max(s.y1, s.y2));
            if (maxX < 0 || minX > w - 1 || bottom < 0 || top > h - 1) continue;   // off screen
            s.minY = (int) Math.max(0, Math.ceil(top));
            s.maxY = (int) Math.min(h - 1, Math.floor(bottom));
            s.material = m;
            s.flat = t.shaded;
            out.add(s);
        }
    }

    /** Clips a triangle (5 values per vertex: X, Y, Z, a, b) to Z >= NEAR. */
    private static double[] clipNear(double[] p) {
        if (p[2] >= NEAR && p[7] >= NEAR && p[12] >= NEAR) return p;
        double[] out = new double[4 * 5];
        int n = 0;
        for (int i = 0; i < 3; i++) {
            int a = i * 5, b = ((i + 1) % 3) * 5;
            boolean ina = p[a + 2] >= NEAR, inb = p[b + 2] >= NEAR;
            if (ina) {
                System.arraycopy(p, a, out, n * 5, 5);
                n++;
            }
            if (ina != inb) {
                double t = (NEAR - p[a + 2]) / (p[b + 2] - p[a + 2]);
                for (int k = 0; k < 5; k++) out[n * 5 + k] = p[a + k] + t * (p[b + k] - p[a + k]);
                out[n * 5 + 2] = NEAR;
                n++;
            }
        }
        return java.util.Arrays.copyOf(out, n * 5);
    }

    private void raster(ScreenTri t, boolean blend, int bandY0, int bandY1) {
        double x0 = t.x0, y0 = t.y0, x1 = t.x1, y1 = t.y1, x2 = t.x2, y2 = t.y2;
        double area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-12) return;
        int minX = (int) Math.max(0, Math.ceil(Math.min(x0, Math.min(x1, x2))));
        int maxX = (int) Math.min(w - 1, Math.floor(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(bandY0, t.minY);
        int maxY = Math.min(bandY1 - 1, t.maxY);
        if (minX > maxX || minY > maxY) return;

        double inv = 1.0 / area;
        // barycentric weights as linear functions of (x, y): w0 for vertex 0, w1 for vertex 1
        double w0dx = (y1 - y2) * inv, w0dy = (x2 - x1) * inv;
        double w1dx = (y2 - y0) * inv, w1dy = (x0 - x2) * inv;
        double w0c = ((x1 - minX) * (y2 - minY) - (x2 - minX) * (y1 - minY)) * inv;
        double w1c = ((x2 - minX) * (y0 - minY) - (x0 - minX) * (y2 - minY)) * inv;
        double iz0 = t.iz0, iz1 = t.iz1, iz2 = t.iz2;
        double a0 = t.a0, a1 = t.a1, a2 = t.a2;
        double b0 = t.b0, b1 = t.b1, b2 = t.b2;

        Material m = t.material;
        Material.Kind kind = m.kind;
        int flat = t.flat;
        float alpha = m.alpha;
        final double eps = -1e-9;
        for (int py = minY; py <= maxY; py++) {
            double r0 = w0c + w0dy * (py - minY), r1 = w1c + w1dy * (py - minY);
            int row = py * w;
            for (int px = minX; px <= maxX; px++) {
                double dx = px - minX;
                double l0 = r0 + w0dx * dx, l1 = r1 + w1dx * dx, l2 = 1 - l0 - l1;
                if (l0 < eps || l1 < eps || l2 < eps) continue;
                double z = l0 * iz0 + l1 * iz1 + l2 * iz2;
                int idx = row + px;
                if (z <= invDepth[idx]) continue;
                int c;
                if (kind == Material.Kind.FLAT) {
                    c = flat;
                } else {
                    double zi = 1.0 / z;
                    double a = (l0 * a0 + l1 * a1 + l2 * a2) * zi;
                    double b = (l0 * b0 + l1 * b1 + l2 * b2) * zi;
                    c = kind == Material.Kind.FLOOR ? floor.color(a, b) : texel(m, a, b);
                }
                if (blend) {
                    color[idx] = mix(color[idx], c, alpha);
                } else {
                    color[idx] = c;
                    invDepth[idx] = (float) z;
                }
            }
        }
    }

    private static int texel(Material m, double u, double v) {
        int n = m.textureSize;
        int x = (int) (u * n), y = (int) (v * n);
        if (x < 0) x = 0;
        else if (x >= n) x = n - 1;
        if (y < 0) y = 0;
        else if (y >= n) y = n - 1;
        return m.texture[y * n + x] & 0xffffff;
    }

    private static int mix(int under, int over, float a) {
        int r = (int) (((under >> 16) & 0xff) * (1 - a) + ((over >> 16) & 0xff) * a);
        int g = (int) (((under >> 8) & 0xff) * (1 - a) + ((over >> 8) & 0xff) * a);
        int b = (int) ((under & 0xff) * (1 - a) + (over & 0xff) * a);
        return (r << 16) | (g << 8) | b;
    }

    // ---- spheres -------------------------------------------------------------------------------

    /** A shaded sphere of radius r centered at (x, y, z) in the world, within rows [bandY0, bandY1). */
    private void sphere(double x, double y, double z, double r, int rgb, int bandY0, int bandY1) {
        double X = r00 * x + r01 * y + r02 * z + tx;
        double Y = r10 * x + r11 * y + r12 * z + ty;
        double Z = r20 * x + r21 * y + r22 * z + tz;
        if (Z < r + NEAR) return;
        double u = fx * X / Z + cx, v = fy * Y / Z + cy;
        double ru = fx * r / Z, rv = fy * r / Z;
        int minX = (int) Math.max(0, Math.ceil(u - ru)), maxX = (int) Math.min(w - 1, Math.floor(u + ru));
        int minY = (int) Math.max(bandY0, Math.ceil(v - rv)), maxY = (int) Math.min(bandY1 - 1, Math.floor(v + rv));
        for (int py = minY; py <= maxY; py++) {
            double dy = (py - v) / rv;
            for (int px = minX; px <= maxX; px++) {
                double dx = (px - u) / ru;
                double d2 = dx * dx + dy * dy;
                if (d2 > 1) continue;
                double dz = Math.sqrt(1 - d2);
                float depth = (float) (1.0 / (Z - r * dz));
                int idx = py * w + px;
                if (depth <= invDepth[idx]) continue;
                // surface normal in camera axes (dx, dy, -dz), back to world axes with R^T
                double nx = r00 * dx + r10 * dy - r20 * dz;
                double ny = r01 * dx + r11 * dy - r21 * dz;
                double nz = r02 * dx + r12 * dy - r22 * dz;
                color[idx] = Lighting.scale(rgb, Lighting.sphere(nx, ny, nz));
                invDepth[idx] = depth;
            }
        }
    }

    // ---- output --------------------------------------------------------------------------------

    /** Averages s x s blocks into an RGBA image of (w / s) x (h / s). */
    void resolve(int s, byte[] rgba) {
        int ow = w / s, oh = h / s, n = s * s;
        for (int oy = 0; oy < oh; oy++) {
            for (int ox = 0; ox < ow; ox++) {
                int r = 0, g = 0, b = 0;
                for (int dy = 0; dy < s; dy++) {
                    int row = (oy * s + dy) * w + ox * s;
                    for (int dx = 0; dx < s; dx++) {
                        int c = color[row + dx];
                        r += (c >> 16) & 0xff;
                        g += (c >> 8) & 0xff;
                        b += c & 0xff;
                    }
                }
                int o = (oy * ow + ox) * 4;
                rgba[o] = (byte) (r / n);
                rgba[o + 1] = (byte) (g / n);
                rgba[o + 2] = (byte) (b / n);
                rgba[o + 3] = (byte) 255;
            }
        }
    }
}
