package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.firstinspires.ftc.robotcore.external.matrices.OpenGLMatrix;
import org.firstinspires.ftc.robotcore.external.matrices.VectorF;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.junit.jupiter.api.Test;

import android.opengl.Matrix;

/**
 * The stand-in for Android's android.opengl.Matrix, which FIRST's OpenGLMatrix (camera and tag
 * poses) is built on: column-major 4x4 matrices, OpenGL conventions.
 */
class AndroidMatrixTest {

    private static float[] random(Random rnd) {
        float[] m = new float[16];
        for (int i = 0; i < 16; i++) m[i] = (float) (rnd.nextDouble() * 4 - 2);
        return m;
    }

    @Test
    void inverseTimesMatrixIsIdentity() {
        Random rnd = new Random(42);
        for (int n = 0; n < 200; n++) {
            float[] m = random(rnd), inv = new float[16], product = new float[16];
            assertTrue(Matrix.invertM(inv, 0, m, 0));
            Matrix.multiplyMM(product, 0, m, 0, inv, 0);
            for (int i = 0; i < 16; i++) assertEquals(i % 5 == 0 ? 1f : 0f, product[i], 2e-3f, "element " + i);
        }
        float[] singular = new float[16];
        assertFalse(Matrix.invertM(new float[16], 0, singular, 0));
    }

    @Test
    void rotationsAndTranslationsFollowOpenGl() {
        float[] r = new float[16];
        Matrix.setRotateM(r, 0, 90, 0, 0, 1);          // +90 deg about z takes x to y
        float[] v = {1, 0, 0, 1}, out = new float[4];
        Matrix.multiplyMV(out, 0, r, 0, v, 0);
        assertEquals(0, out[0], 1e-6);
        assertEquals(1, out[1], 1e-6);
        float[] general = new float[16];
        Matrix.setRotateM(general, 0, 90, 0, 0, 2);    // same axis, not unit length: the general path
        for (int i = 0; i < 16; i++) assertEquals(r[i], general[i], 1e-6, "element " + i);

        float[] m = new float[16];
        Matrix.setIdentityM(m, 0);
        Matrix.translateM(m, 0, 1, 2, 3);
        Matrix.rotateM(m, 0, 90, 0, 0, 1);             // m = T * R
        Matrix.multiplyMV(out, 0, m, 0, v, 0);
        assertEquals(1, out[0], 1e-6);
        assertEquals(3, out[1], 1e-6);
        assertEquals(3, out[2], 1e-6);
    }

    @Test
    void firstsOpenGlMatrixWorksOnTop() {
        OpenGLMatrix pose = OpenGLMatrix.translation(10, 20, 30)
                .multiplied(Orientation.getRotationMatrix(AxesReference.EXTRINSIC, AxesOrder.XYZ, AngleUnit.DEGREES, 30, -45, 60));
        OpenGLMatrix back = pose.inverted().multiplied(pose);
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) assertEquals(r == c ? 1f : 0f, back.get(r, c), 1e-4f, "(" + r + "," + c + ")");
        }
        VectorF t = pose.getTranslation();
        assertEquals(10, t.get(0), 1e-5);
        assertEquals(30, t.get(2), 1e-5);
        Orientation o = Orientation.getOrientation(pose, AxesReference.EXTRINSIC, AxesOrder.XYZ, AngleUnit.DEGREES);
        assertEquals(30, o.firstAngle, 1e-3);
        assertEquals(-45, o.secondAngle, 1e-3);
        assertEquals(60, o.thirdAngle, 1e-3);
    }
}
