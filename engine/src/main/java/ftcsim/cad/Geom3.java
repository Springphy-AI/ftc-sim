package ftcsim.cad;

/**
 * Small 3D helpers on plain arrays: vectors are {@code double[3]}, rotations and inertia tensors
 * are row-major {@code double[9]}, rigid transforms are {@code double[12]} (rotation, then
 * translation): {@code p' = R p + t}.
 */
public final class Geom3 {
    private Geom3() {
    }

    public static final double[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    public static double[] v(double x, double y, double z) {
        return new double[]{x, y, z};
    }

    public static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    public static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    /** Every element times {@code s} (vectors and matrices alike). */
    public static double[] scale(double[] a, double s) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] * s;
        return out;
    }

    public static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    public static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    public static double norm(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    public static double[] normalize(double[] a) {
        double n = norm(a);
        return n < 1e-12 ? new double[]{0, 0, 0} : scale(a, 1 / n);
    }

    /** {@code R v}. */
    public static double[] mul(double[] r, double[] v) {
        return new double[]{
                r[0] * v[0] + r[1] * v[1] + r[2] * v[2],
                r[3] * v[0] + r[4] * v[1] + r[5] * v[2],
                r[6] * v[0] + r[7] * v[1] + r[8] * v[2]};
    }

    /** {@code A B} for 3x3 matrices. */
    public static double[] matMul(double[] a, double[] b) {
        double[] out = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
        return out;
    }

    public static double[] transpose(double[] m) {
        return new double[]{m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]};
    }

    /** Rotation about a unit axis by {@code angle} radians (right-hand rule). */
    public static double[] axisAngle(double[] axis, double angle) {
        double x = axis[0], y = axis[1], z = axis[2];
        double c = Math.cos(angle), s = Math.sin(angle), t = 1 - c;
        return new double[]{
                t * x * x + c, t * x * y - s * z, t * x * z + s * y,
                t * x * y + s * z, t * y * y + c, t * y * z - s * x,
                t * x * z - s * y, t * y * z + s * x, t * z * z + c};
    }

    /** URDF roll-pitch-yaw: {@code Rz(yaw) Ry(pitch) Rx(roll)}. */
    public static double[] rpy(double roll, double pitch, double yaw) {
        double cr = Math.cos(roll), sr = Math.sin(roll);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        return new double[]{
                cy * cp, cy * sp * sr - sy * cr, cy * sp * cr + sy * sr,
                sy * cp, sy * sp * sr + cy * cr, sy * sp * cr - cy * sr,
                -sp, cp * sr, cp * cr};
    }

    // ---- rigid transforms ----------------------------------------------------------------------

    public static double[] transform(double[] r, double[] t) {
        double[] out = new double[12];
        System.arraycopy(r, 0, out, 0, 9);
        out[9] = t[0];
        out[10] = t[1];
        out[11] = t[2];
        return out;
    }

    public static double[] identityTransform() {
        return transform(IDENTITY, new double[3]);
    }

    public static double[] rot(double[] tf) {
        return java.util.Arrays.copyOf(tf, 9);
    }

    public static double[] trans(double[] tf) {
        return new double[]{tf[9], tf[10], tf[11]};
    }

    public static double[] apply(double[] tf, double[] p) {
        return new double[]{
                tf[0] * p[0] + tf[1] * p[1] + tf[2] * p[2] + tf[9],
                tf[3] * p[0] + tf[4] * p[1] + tf[5] * p[2] + tf[10],
                tf[6] * p[0] + tf[7] * p[1] + tf[8] * p[2] + tf[11]};
    }

    /** {@code a ∘ b}: first b, then a. */
    public static double[] compose(double[] a, double[] b) {
        return transform(matMul(rot(a), rot(b)), apply(a, trans(b)));
    }

    public static double[] inverse(double[] tf) {
        double[] rt = transpose(rot(tf));
        return transform(rt, scale(mul(rt, trans(tf)), -1));
    }

    /** Applies a rigid transform to packed positions (x, y, z, x, y, z...). */
    public static float[] apply(double[] tf, float[] positions) {
        float[] out = new float[positions.length];
        for (int i = 0; i < positions.length; i += 3) {
            double x = positions[i], y = positions[i + 1], z = positions[i + 2];
            out[i] = (float) (tf[0] * x + tf[1] * y + tf[2] * z + tf[9]);
            out[i + 1] = (float) (tf[3] * x + tf[4] * y + tf[5] * z + tf[10]);
            out[i + 2] = (float) (tf[6] * x + tf[7] * y + tf[8] * z + tf[11]);
        }
        return out;
    }

    /** Unit quaternion (w, x, y, z) of a rotation matrix. */
    public static double[] quaternion(double[] m) {
        double tr = m[0] + m[4] + m[8];
        double w, x, y, z;
        if (tr > 0) {
            double s = Math.sqrt(tr + 1) * 2;
            w = 0.25 * s;
            x = (m[7] - m[5]) / s;
            y = (m[2] - m[6]) / s;
            z = (m[3] - m[1]) / s;
        } else if (m[0] > m[4] && m[0] > m[8]) {
            double s = Math.sqrt(1 + m[0] - m[4] - m[8]) * 2;
            w = (m[7] - m[5]) / s;
            x = 0.25 * s;
            y = (m[1] + m[3]) / s;
            z = (m[2] + m[6]) / s;
        } else if (m[4] > m[8]) {
            double s = Math.sqrt(1 + m[4] - m[0] - m[8]) * 2;
            w = (m[2] - m[6]) / s;
            x = (m[1] + m[3]) / s;
            y = 0.25 * s;
            z = (m[5] + m[7]) / s;
        } else {
            double s = Math.sqrt(1 + m[8] - m[0] - m[4]) * 2;
            w = (m[3] - m[1]) / s;
            x = (m[2] + m[6]) / s;
            y = (m[5] + m[7]) / s;
            z = 0.25 * s;
        }
        double n = Math.sqrt(w * w + x * x + y * y + z * z);
        return new double[]{w / n, x / n, y / n, z / n};
    }

    // ---- symmetric eigen decomposition ----------------------------------------------------------

    /**
     * Eigen decomposition of a symmetric 3x3 matrix (Jacobi rotations). Returns the eigenvalues in
     * descending order in {@code values} and the matching unit eigenvectors as the columns of the
     * returned rotation matrix (right-handed).
     */
    public static double[] eigenSymmetric(double[] sym, double[] values) {
        double[][] a = {{sym[0], sym[1], sym[2]}, {sym[3], sym[4], sym[5]}, {sym[6], sym[7], sym[8]}};
        double[][] v = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int sweep = 0; sweep < 50; sweep++) {
            double off = Math.abs(a[0][1]) + Math.abs(a[0][2]) + Math.abs(a[1][2]);
            if (off < 1e-15 * (Math.abs(a[0][0]) + Math.abs(a[1][1]) + Math.abs(a[2][2]) + 1e-300)) break;
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < 3; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) continue;
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) t = 1;
                    double c = 1 / Math.sqrt(t * t + 1), s = t * c;
                    for (int k = 0; k < 3; k++) {
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < 3; k++) {
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < 3; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        Integer[] order = {0, 1, 2};
        java.util.Arrays.sort(order, (i, j) -> Double.compare(a[j][j], a[i][i]));
        double[] out = new double[9];
        for (int c = 0; c < 3; c++) {
            values[c] = a[order[c]][order[c]];
            for (int r = 0; r < 3; r++) out[r * 3 + c] = v[r][order[c]];
        }
        // Make it a proper rotation: third column = first x second.
        double[] e0 = {out[0], out[3], out[6]}, e1 = {out[1], out[4], out[7]};
        double[] e2 = cross(e0, e1);
        out[2] = e2[0];
        out[5] = e2[1];
        out[8] = e2[2];
        return out;
    }
}
