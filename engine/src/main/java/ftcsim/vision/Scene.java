package ftcsim.vision;

import java.util.ArrayList;
import java.util.List;

/**
 * Triangles in world coordinates (field frame: +x to the blue wall, +y away from the audience,
 * +z up; meters) with their materials, plus helpers that build boxes, beams, cylinders and
 * textured quads out of them.
 */
final class Scene {

    /** One triangle. {@code uv} (6 values) is set for textured triangles. */
    static final class Tri {
        final double[] v;
        final double[] uv;
        final Material material;
        /** Lit color for FLAT triangles: the material color under the arena lights. */
        final int shaded;

        Tri(double[] v, double[] uv, Material material) {
            this.v = v;
            this.uv = uv;
            this.material = material;
            this.shaded = material.kind == Material.Kind.FLAT ? Lighting.shade(material.rgb, v) : material.rgb;
        }
    }

    final List<Tri> tris = new ArrayList<>();

    void tri(double[] a, double[] b, double[] c, Material m) {
        tris.add(new Tri(new double[]{a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]}, null, m));
    }

    /** A quad a-b-c-d (in order around its edge). */
    void quad(double[] a, double[] b, double[] c, double[] d, Material m) {
        tri(a, b, c, m);
        tri(a, c, d, m);
    }

    /**
     * A textured quad: corners top-left, top-right, bottom-right, bottom-left of the image
     * (texture coordinates (0,0), (1,0), (1,1), (0,1)).
     */
    void texturedQuad(double[] tl, double[] tr, double[] br, double[] bl, Material m) {
        tris.add(new Tri(new double[]{tl[0], tl[1], tl[2], tr[0], tr[1], tr[2], br[0], br[1], br[2]},
                new double[]{0, 0, 1, 0, 1, 1}, m));
        tris.add(new Tri(new double[]{tl[0], tl[1], tl[2], br[0], br[1], br[2], bl[0], bl[1], bl[2]},
                new double[]{0, 0, 1, 1, 0, 1}, m));
    }

    /** A box of size (sx, sy, sz) centered at c, turned by the row-major 3x3 {@code rot} (null = none). */
    void box(double[] c, double sx, double sy, double sz, double[] rot, Material m) {
        double[][] p = new double[8][];
        int k = 0;
        for (int ix = -1; ix <= 1; ix += 2) {
            for (int iy = -1; iy <= 1; iy += 2) {
                for (int iz = -1; iz <= 1; iz += 2) {
                    double[] local = {ix * sx / 2, iy * sy / 2, iz * sz / 2};
                    double[] w = rot == null ? local : mul(rot, local);
                    p[k++] = new double[]{c[0] + w[0], c[1] + w[1], c[2] + w[2]};
                }
            }
        }
        // index = (ix>0)*4 + (iy>0)*2 + (iz>0)
        quad(p[0], p[2], p[3], p[1], m);   // -x
        quad(p[4], p[5], p[7], p[6], m);   // +x
        quad(p[0], p[1], p[5], p[4], m);   // -y
        quad(p[2], p[6], p[7], p[3], m);   // +y
        quad(p[0], p[4], p[6], p[2], m);   // -z
        quad(p[1], p[3], p[7], p[5], m);   // +z
    }

    /** A square beam of thickness t from a to b. */
    void beam(double[] a, double[] b, double t, Material m) {
        double[] d = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        double[] z = {d[0] / len, d[1] / len, d[2] / len};
        double[] x = perpendicular(z);
        double[] y = cross(z, x);
        double[] rot = {x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2]};
        box(new double[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2}, t, t, len, rot, m);
    }

    /** A closed cylinder of radius r from a to b, with {@code sides} flat sides. */
    void cylinder(double[] a, double[] b, double r, int sides, Material m) {
        double[] d = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        double[] z = {d[0] / len, d[1] / len, d[2] / len};
        double[] x = perpendicular(z);
        double[] y = cross(z, x);
        double[][] ra = new double[sides][], rb = new double[sides][];
        for (int i = 0; i < sides; i++) {
            double t = 2 * Math.PI * i / sides, c = Math.cos(t) * r, s = Math.sin(t) * r;
            double[] o = {x[0] * c + y[0] * s, x[1] * c + y[1] * s, x[2] * c + y[2] * s};
            ra[i] = new double[]{a[0] + o[0], a[1] + o[1], a[2] + o[2]};
            rb[i] = new double[]{b[0] + o[0], b[1] + o[1], b[2] + o[2]};
        }
        for (int i = 0; i < sides; i++) {
            int j = (i + 1) % sides;
            quad(ra[i], ra[j], rb[j], rb[i], m);
            tri(a, ra[j], ra[i], m);
            tri(b, rb[i], rb[j], m);
        }
    }

    // ---- small vector helpers -------------------------------------------------------------------

    static double[] mul(double[] m, double[] v) {
        return new double[]{
                m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
                m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] normalize(double[] a) {
        double n = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[]{a[0] / n, a[1] / n, a[2] / n};
    }

    private static double[] perpendicular(double[] z) {
        double[] helper = Math.abs(z[2]) < 0.9 ? new double[]{0, 0, 1} : new double[]{1, 0, 0};
        return normalize(cross(helper, z));
    }
}
