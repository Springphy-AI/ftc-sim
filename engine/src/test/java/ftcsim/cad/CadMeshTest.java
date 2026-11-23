package ftcsim.cad;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The CAD model's triangles as the webcams and the 3D view get them. */
class CadMeshTest {

    @Test
    void theWebcamClearsEveryTriangleThatComesNearItsLensHoweverBig() {
        // A 40 cm panel whose corner passes 1 cm from the lens: its center is 20 cm away.
        float[] p = {0.21f, 0.01f, 0.08f, 0.61f, 0.01f, 0.08f, 0.21f, 0.41f, 0.08f,
                     0.30f, -0.30f, 0.30f, 0.35f, -0.30f, 0.30f, 0.30f, -0.35f, 0.30f};
        MeshPart panel = new MeshPart("panel", 0x808080, p, new int[]{0, 1, 2, 3, 4, 5});
        MeshPart cleared = ModelExport.clearAround(panel, List.of(new double[]{0.21, 0, 0.08}), RobotModel.CAMERA_CLEARANCE_M);
        assertEquals(1, cleared.triangleCount(), "the panel goes, the far triangle stays");
        assertEquals(3, cleared.indices()[0]);
    }

    @Test
    void simplifyingKeepsEveryDistinctTriangle() {
        // 101 vertices far apart, so none merge; triangles (0, 62, 100) and (1, 31, 100) differ.
        float[] p = new float[101 * 3];
        for (int i = 0; i <= 100; i++) {
            p[i * 3] = i * 0.1f;
            p[i * 3 + 1] = (i % 7) * 0.1f;
            p[i * 3 + 2] = (i % 3) * 0.1f;
        }
        MeshPart two = new MeshPart("x", 0, p, new int[]{0, 62, 100, 1, 31, 100});
        assertEquals(2, MeshMath.simplify(two, 0.001).triangleCount());
        MeshPart twice = new MeshPart("x", 0, p, new int[]{0, 62, 100, 100, 0, 62});
        assertEquals(1, MeshMath.simplify(twice, 0.001).triangleCount(), "the same triangle twice is kept once");
    }

    @Test
    void distanceToATriangleIsToItsNearestPoint() {
        double[] a = {0, 0, 0}, b = {1, 0, 0}, c = {0, 1, 0};
        assertEquals(4, Geom3.distanceSqToTriangle(new double[]{0.2, 0.2, 2}, a, b, c), 1e-12, "above the face");
        assertEquals(0.25, Geom3.distanceSqToTriangle(new double[]{0.5, -0.5, 0}, a, b, c), 1e-12, "beside an edge");
        assertEquals(2, Geom3.distanceSqToTriangle(new double[]{2, -1, 0}, a, b, c), 1e-12, "past a corner");
        assertEquals(0.5, Geom3.distanceSqToTriangle(new double[]{1, 1, 0}, a, b, c), 1e-12, "beyond the long edge");
    }
}
