package ftcsim.hal;

/**
 * Rotation math shared by the navigation classes and the simulated sensors.
 * Matrices are row-major double[3][3] and act on column vectors.
 */
public final class Rotations {
    private Rotations() {
    }

    public static double[][] identity() {
        return new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    }

    public static double[][] multiply(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return r;
    }

    public static double[][] transpose(double[][] a) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i][j] = a[j][i];
        return r;
    }

    public static double[] apply(double[][] m, double[] v) {
        return new double[]{
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    /** Quaternion (w, x, y, z) of a rotation matrix. */
    public static double[] toQuaternion(double[][] m) {
        double trace = m[0][0] + m[1][1] + m[2][2];
        double w, x, y, z;
        if (trace > 0) {
            double s = 0.5 / Math.sqrt(trace + 1.0);
            w = 0.25 / s;
            x = (m[2][1] - m[1][2]) * s;
            y = (m[0][2] - m[2][0]) * s;
            z = (m[1][0] - m[0][1]) * s;
        } else if (m[0][0] > m[1][1] && m[0][0] > m[2][2]) {
            double s = 2.0 * Math.sqrt(1.0 + m[0][0] - m[1][1] - m[2][2]);
            w = (m[2][1] - m[1][2]) / s;
            x = 0.25 * s;
            y = (m[0][1] + m[1][0]) / s;
            z = (m[0][2] + m[2][0]) / s;
        } else if (m[1][1] > m[2][2]) {
            double s = 2.0 * Math.sqrt(1.0 + m[1][1] - m[0][0] - m[2][2]);
            w = (m[0][2] - m[2][0]) / s;
            x = (m[0][1] + m[1][0]) / s;
            y = 0.25 * s;
            z = (m[1][2] + m[2][1]) / s;
        } else {
            double s = 2.0 * Math.sqrt(1.0 + m[2][2] - m[0][0] - m[1][1]);
            w = (m[1][0] - m[0][1]) / s;
            x = (m[0][2] + m[2][0]) / s;
            y = (m[1][2] + m[2][1]) / s;
            z = 0.25 * s;
        }
        double n = Math.sqrt(w * w + x * x + y * y + z * z);
        if (w < 0) n = -n;   // canonical form with w >= 0
        return new double[]{w / n, x / n, y / n, z / n};
    }
}
