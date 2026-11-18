package biobuzz;

/**
 * One alliance's HIVE: two CELLS on an arm that pivots about the x axis through (px, py, pz).
 * Hive frame (season.json "hive_frame"): origin on the pivot, x along the pivot axis, at phi = 0
 * the arm is level and +y points to the rear CELL. {@code phi} is the tilt: the start position in
 * {@link HiveLayout#hives}, the live one from {@link #at(double)}.
 */
public record Hive(String alliance, double px, double py, double pz, double phi, String upCell) {

    /** The same HIVE tilted to phi. */
    public Hive at(double newPhi) {
        return new Hive(alliance, px, py, pz, newPhi, newPhi < 0 ? "audience" : "rear");
    }

    /** Hive-frame point to world. */
    public double[] toWorld(double x, double y, double z) {
        double c = Math.cos(phi), s = Math.sin(phi);
        return new double[]{px + x, py + y * c - z * s, pz + y * s + z * c};
    }

    /** World point to hive frame. */
    public double[] toHive(double wx, double wy, double wz) {
        double c = Math.cos(phi), s = Math.sin(phi), dy = wy - py, dz = wz - pz;
        return new double[]{wx - px, dy * c + dz * s, -dy * s + dz * c};
    }
}
