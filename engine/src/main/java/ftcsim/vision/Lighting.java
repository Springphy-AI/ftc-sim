package ftcsim.vision;

/** The arena lights: bright and mostly overhead, with plenty of light bouncing around. */
final class Lighting {
    private static final double[] SUN = Scene.normalize(new double[]{0.35, -0.45, 1.0});
    private static final double AMBIENT = 0.55, DIFFUSE = 0.45;

    private Lighting() {
    }

    /** {@code rgb} on a flat triangle (9 vertex coordinates), lit from either side. */
    static int shade(int rgb, double[] v) {
        double[] e1 = {v[3] - v[0], v[4] - v[1], v[5] - v[2]};
        double[] e2 = {v[6] - v[0], v[7] - v[1], v[8] - v[2]};
        double[] n = Scene.cross(e1, e2);
        double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        double k = len < 1e-12 ? AMBIENT + DIFFUSE * 0.5
                : AMBIENT + DIFFUSE * Math.abs((n[0] * SUN[0] + n[1] * SUN[1] + n[2] * SUN[2]) / len);
        return scale(rgb, k);
    }

    /** Light on a sphere's surface with normal (nx, ny, nz) in world coordinates. */
    static double sphere(double nx, double ny, double nz) {
        return AMBIENT * 0.9 + DIFFUSE * Math.max(0, nx * SUN[0] + ny * SUN[1] + nz * SUN[2]);
    }

    static int scale(int rgb, double k) {
        int r = (int) Math.min(255, ((rgb >> 16) & 0xff) * k);
        int g = (int) Math.min(255, ((rgb >> 8) & 0xff) * k);
        int b = (int) Math.min(255, (rgb & 0xff) * k);
        return (r << 16) | (g << 8) | b;
    }
}
