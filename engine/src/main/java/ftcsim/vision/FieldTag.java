package ftcsim.vision;

/**
 * One AprilTag as printed on the field, in world coordinates: its center, the directions of its
 * image's right and up, the way it faces, and {@code size}, the side of its black square (m).
 */
public record FieldTag(int id, double[] center, double[] right, double[] up, double[] normal, double size) {
}
