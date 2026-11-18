package ftcsim.cad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Mass properties, principal axes, collision boxes and simplification of triangle meshes. */
public final class MeshMath {
    private MeshMath() {
    }

    // ---- mass properties ------------------------------------------------------------------------

    /** Volume, center of mass and inertia tensor (per unit density, about the center of mass). */
    public record MassProps(double volume, double[] com, double[] inertia) {
        static final MassProps EMPTY = new MassProps(0, new double[3], new double[9]);

        /** The same body with mass {@code mass} instead of its density-1 mass. */
        public double[] inertiaForMass(double mass) {
            return volume <= 0 ? new double[9] : Geom3.scale(inertia, mass / volume);
        }
    }

    /**
     * Mass properties of a closed mesh by summing signed tetrahedra to the origin. A part whose
     * triangles face inward counts as positive anyway; open meshes give approximate results.
     */
    public static MassProps massProps(MeshPart part) {
        float[] p = part.positions();
        int[] ix = part.indices();
        double vol = 0;
        double[] first = new double[3];
        double[] second = new double[9];   // ∫ x x^T dV
        for (int t = 0; t < ix.length; t += 3) {
            double[] a = vertex(p, ix[t]), b = vertex(p, ix[t + 1]), c = vertex(p, ix[t + 2]);
            double v = Geom3.dot(a, Geom3.cross(b, c)) / 6.0;
            vol += v;
            for (int k = 0; k < 3; k++) first[k] += v * (a[k] + b[k] + c[k]) / 4.0;
            double[] s = {a[0] + b[0] + c[0], a[1] + b[1] + c[1], a[2] + b[2] + c[2]};
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    second[i * 3 + j] += v / 20.0 * (a[i] * a[j] + b[i] * b[j] + c[i] * c[j] + s[i] * s[j]);
                }
            }
        }
        if (vol < 0) {
            vol = -vol;
            for (int k = 0; k < 3; k++) first[k] = -first[k];
            for (int k = 0; k < 9; k++) second[k] = -second[k];
        }
        if (vol < 1e-12) return MassProps.EMPTY;
        double[] com = Geom3.scale(first, 1 / vol);
        // Covariance about the center of mass, then inertia = tr(C) I - C.
        double[] cov = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) cov[i * 3 + j] = second[i * 3 + j] - vol * com[i] * com[j];
        return new MassProps(vol, com, covarianceToInertia(cov));
    }

    /** Combines bodies (each with its own mass) into one: total mass, center of mass, inertia about it. */
    public static double[][] combine(List<double[]> masses, List<double[]> coms, List<double[]> inertias) {
        double m = 0;
        double[] c = new double[3];
        for (int i = 0; i < masses.size(); i++) {
            m += masses.get(i)[0];
            c = Geom3.add(c, Geom3.scale(coms.get(i), masses.get(i)[0]));
        }
        if (m <= 0) return new double[][]{{0}, new double[3], new double[9]};
        c = Geom3.scale(c, 1 / m);
        double[] inertia = new double[9];
        for (int i = 0; i < masses.size(); i++) {
            double mi = masses.get(i)[0];
            double[] d = Geom3.sub(coms.get(i), c);
            double[] in = inertias.get(i);
            for (int k = 0; k < 9; k++) inertia[k] += in[k];
            // parallel axis: m (|d|² I - d dᵀ)
            double dd = Geom3.dot(d, d);
            for (int r = 0; r < 3; r++) {
                for (int s = 0; s < 3; s++) {
                    inertia[r * 3 + s] += mi * ((r == s ? dd : 0) - d[r] * d[s]);
                }
            }
        }
        return new double[][]{{m}, c, inertia};
    }

    /** A solid box's inertia (row-major, about its center, box axes). */
    public static double[] boxInertia(double mass, double sx, double sy, double sz) {
        return new double[]{mass * (sy * sy + sz * sz) / 12, 0, 0, 0, mass * (sx * sx + sz * sz) / 12, 0, 0, 0, mass * (sx * sx + sy * sy) / 12};
    }

    private static double[] covarianceToInertia(double[] cov) {
        double tr = cov[0] + cov[4] + cov[8];
        double[] in = new double[9];
        for (int i = 0; i < 9; i++) in[i] = -cov[i];
        in[0] += tr;
        in[4] += tr;
        in[8] += tr;
        return in;
    }

    private static double[] vertex(float[] p, int i) {
        return new double[]{p[i * 3], p[i * 3 + 1], p[i * 3 + 2]};
    }

    // ---- principal axes ---------------------------------------------------------------------------

    /** A part's centroid (area-weighted) and principal directions, longest spread first. */
    public record Axes(double[] center, double[] rotation, double[] spread) {
        public double[] axis(int k) {
            return new double[]{rotation[k], rotation[3 + k], rotation[6 + k]};
        }
    }

    /** Principal axes of the surface of {@code triangles} (vertex indices into {@code p}). */
    public static Axes principalAxes(float[] p, int[] triangles) {
        double area = 0;
        double[] c = new double[3];
        double[] m2 = new double[9];
        for (int t = 0; t < triangles.length; t += 3) {
            double[] a = vertex(p, triangles[t]), b = vertex(p, triangles[t + 1]), d = vertex(p, triangles[t + 2]);
            double ar = Geom3.norm(Geom3.cross(Geom3.sub(b, a), Geom3.sub(d, a))) / 2;
            if (ar <= 0) continue;
            double[] g = Geom3.scale(Geom3.add(Geom3.add(a, b), d), 1 / 3.0);
            area += ar;
            c = Geom3.add(c, Geom3.scale(g, ar));
            // second moment of a triangle: A/12 (Σ v vᵀ + 9 g gᵀ)
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    m2[i * 3 + j] += ar / 12 * (a[i] * a[j] + b[i] * b[j] + d[i] * d[j] + 9 * g[i] * g[j]);
                }
            }
        }
        if (area <= 0) {
            double[] b = new MeshPart("", 0, p, triangles).bounds();
            return new Axes(new double[]{(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2}, Geom3.IDENTITY.clone(), new double[3]);
        }
        c = Geom3.scale(c, 1 / area);
        double[] cov = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) cov[i * 3 + j] = m2[i * 3 + j] / area - c[i] * c[j];
        double[] vals = new double[3];
        double[] rot = Geom3.eigenSymmetric(cov, vals);
        return new Axes(c, rot, vals);
    }

    /**
     * The axis a part turns or slides along: a shaft or rail's long axis, or a disk's (a servo
     * horn, a gear) short axis. Returns {point, unit direction}.
     */
    public static double[][] partAxis(MeshPart part) {
        Axes ax = principalAxes(part.positions(), part.indices());
        double[] s = ax.spread();
        double s0 = Math.max(s[0], 1e-12), s1 = Math.max(s[1], 1e-12), s2 = Math.max(s[2], 1e-12);
        boolean rod = s0 / s1 >= s1 / s2;
        double[] dir = ax.axis(rod ? 0 : 2);
        return new double[][]{ax.center(), dir};
    }

    // ---- collision boxes --------------------------------------------------------------------------

    /** An oriented box: center, rotation (box axes as columns) and full edge lengths. */
    public record Box(double[] center, double[] rotation, double[] size) {
        public double volume() {
            return size[0] * size[1] * size[2];
        }
    }

    /**
     * Boxes that cover a part: one oriented box when it fits the part well, or smaller boxes for
     * the pieces of a bent or curved part (an intake ramp, a hood), so balls can still go where
     * the real part leaves room.
     */
    public static List<Box> fitBoxes(MeshPart part, double minThickness) {
        List<Box> out = new ArrayList<>();
        int[] tris = new int[part.indices().length / 3];
        for (int i = 0; i < tris.length; i++) tris[i] = i;
        fit(part.positions(), part.indices(), tris, minThickness, 0, out);
        return out;
    }

    private static void fit(float[] p, int[] ix, int[] tris, double minThickness, int depth, List<Box> out) {
        int[] sub = new int[tris.length * 3];
        for (int i = 0; i < tris.length; i++) System.arraycopy(ix, tris[i] * 3, sub, i * 3, 3);
        Box box = obb(p, sub, minThickness);
        if (depth < 3 && tris.length >= 8) {
            // Split along the longest axis at the median triangle and keep the halves if they
            // hug the part clearly better than the whole box does.
            int longest = 0;
            for (int k = 1; k < 3; k++) if (box.size()[k] > box.size()[longest]) longest = k;
            double[] axis = {box.rotation()[longest], box.rotation()[3 + longest], box.rotation()[6 + longest]};
            double[] keys = new double[tris.length];
            Integer[] order = new Integer[tris.length];
            for (int i = 0; i < tris.length; i++) {
                int t = tris[i];
                double[] g = Geom3.scale(Geom3.add(Geom3.add(vertex(p, ix[t * 3]), vertex(p, ix[t * 3 + 1])), vertex(p, ix[t * 3 + 2])), 1 / 3.0);
                keys[i] = Geom3.dot(g, axis);
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Double.compare(keys[a], keys[b]));
            int half = tris.length / 2;
            int[] left = new int[half], right = new int[tris.length - half];
            for (int i = 0; i < half; i++) left[i] = tris[order[i]];
            for (int i = half; i < tris.length; i++) right[i - half] = tris[order[i]];
            Box bl = obb(p, gather(ix, left), minThickness), br = obb(p, gather(ix, right), minThickness);
            if (bl.volume() + br.volume() < 0.6 * box.volume()) {
                fit(p, ix, left, minThickness, depth + 1, out);
                fit(p, ix, right, minThickness, depth + 1, out);
                return;
            }
        }
        out.add(box);
    }

    private static int[] gather(int[] ix, int[] tris) {
        int[] sub = new int[tris.length * 3];
        for (int i = 0; i < tris.length; i++) System.arraycopy(ix, tris[i] * 3, sub, i * 3, 3);
        return sub;
    }

    /** The oriented bounding box of the vertices the triangles use, along their principal axes. */
    public static Box obb(float[] p, int[] triangles, double minThickness) {
        Axes ax = principalAxes(p, triangles);
        double[] r = ax.rotation();
        double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i : triangles) {
            double x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            for (int k = 0; k < 3; k++) {
                double d = r[k] * x + r[3 + k] * y + r[6 + k] * z;
                lo[k] = Math.min(lo[k], d);
                hi[k] = Math.max(hi[k], d);
            }
        }
        double[] size = new double[3];
        double[] mid = new double[3];
        for (int k = 0; k < 3; k++) {
            size[k] = Math.max(minThickness, hi[k] - lo[k]);
            mid[k] = (lo[k] + hi[k]) / 2;
        }
        double[] center = Geom3.mul(r, mid);
        return new Box(center, r, size);
    }

    // ---- simplification --------------------------------------------------------------------------

    /**
     * Vertex clustering: merges vertices that fall in the same {@code cell}-sized grid cube and
     * drops triangles that collapse. Good enough for the webcam renderer, which only needs the
     * silhouette and colors of other robots.
     */
    public static MeshPart simplify(MeshPart part, double cell) {
        float[] p = part.positions();
        int[] ix = part.indices();
        Map<Long, Integer> cells = new HashMap<>();
        int[] remap = new int[p.length / 3];
        List<float[]> sums = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < remap.length; i++) {
            long cx = (long) Math.floor(p[i * 3] / cell), cy = (long) Math.floor(p[i * 3 + 1] / cell), cz = (long) Math.floor(p[i * 3 + 2] / cell);
            long key = (cx & 0x1fffff) | ((cy & 0x1fffff) << 21) | ((cz & 0x1fffff) << 42);
            Integer id = cells.get(key);
            if (id == null) {
                id = sums.size();
                cells.put(key, id);
                sums.add(new float[3]);
                counts.add(0);
            }
            float[] s = sums.get(id);
            s[0] += p[i * 3];
            s[1] += p[i * 3 + 1];
            s[2] += p[i * 3 + 2];
            counts.set(id, counts.get(id) + 1);
            remap[i] = id;
        }
        float[] pos = new float[sums.size() * 3];
        for (int i = 0; i < sums.size(); i++) {
            float n = counts.get(i);
            pos[i * 3] = sums.get(i)[0] / n;
            pos[i * 3 + 1] = sums.get(i)[1] / n;
            pos[i * 3 + 2] = sums.get(i)[2] / n;
        }
        int[] out = new int[ix.length];
        int n = 0;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int t = 0; t < ix.length; t += 3) {
            int a = remap[ix[t]], b = remap[ix[t + 1]], c = remap[ix[t + 2]];
            if (a == b || b == c || a == c) continue;
            // drop exact duplicates (same three corners in any rotation)
            int lo = Math.min(a, Math.min(b, c)), hi = Math.max(a, Math.max(b, c)), mid = a + b + c - lo - hi;
            long key = ((long) lo * 31 + mid) * 1_000_003L + hi;
            if (!seen.add(key)) continue;
            out[n++] = a;
            out[n++] = b;
            out[n++] = c;
        }
        return new MeshPart(part.path(), part.rgb(), pos, Arrays.copyOf(out, n));
    }
}
