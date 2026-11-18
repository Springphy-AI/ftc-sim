package ftcsim.cad;

/**
 * One CAD part as triangles: {@code positions} packs x, y, z in meters; {@code indices} has three
 * vertex indices per triangle. {@code path} is the part's place in the assembly, with names
 * separated by "/" (for example "Robot/Arm Assembly/Arm Bar"). {@code rgb} is an sRGB color.
 */
public record MeshPart(String path, int rgb, float[] positions, int[] indices) {

    public int triangleCount() {
        return indices.length / 3;
    }

    /** The last name in the path: the part's own name. */
    public String name() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** The same part moved by a rigid transform ({@link Geom3}). */
    public MeshPart transformed(double[] tf) {
        return new MeshPart(path, rgb, Geom3.apply(tf, positions), indices);
    }

    /** Axis-aligned bounds: {minX, minY, minZ, maxX, maxY, maxZ}. */
    public double[] bounds() {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i < positions.length; i += 3) {
            for (int a = 0; a < 3; a++) {
                b[a] = Math.min(b[a], positions[i + a]);
                b[a + 3] = Math.max(b[a + 3], positions[i + a]);
            }
        }
        return b;
    }

    /** Grows {@code b} ({minX..maxZ}) to include this part. */
    public void addBounds(double[] b) {
        double[] own = bounds();
        for (int a = 0; a < 3; a++) {
            b[a] = Math.min(b[a], own[a]);
            b[a + 3] = Math.max(b[a + 3], own[a + 3]);
        }
    }

    public static double[] emptyBounds() {
        return new double[]{Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
    }

    public static boolean isEmpty(double[] bounds) {
        return bounds[0] > bounds[3];
    }
}
