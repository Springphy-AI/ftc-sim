package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;
import org.junit.jupiter.api.Test;

import com.qualcomm.hardware.bosch.BHI260IMU;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot.LogoFacingDirection;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot.UsbFacingDirection;
import com.qualcomm.robotcore.hardware.IMU;

import ftcsim.hal.ImuBackend;
import ftcsim.hal.Latency;

/** The Control Hub's IMU against FIRST's own orientation math. */
class ImuTest {

    ImuTest() {
        Latency.i2cNanos = 0;
    }

    /** A random rotation (w, x, y, z), and its matrix. */
    private static double[] randomQuaternion(Random rnd) {
        double[] q = {rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()};
        double n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        for (int i = 0; i < 4; i++) q[i] /= n;
        return q;
    }

    private static double[][] matrix(double[] q) {
        double w = q[0], x = q[1], y = q[2], z = q[3];
        return new double[][]{
                {1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)},
                {2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)},
                {2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)}};
    }

    private static double angleDiff(double a, double b) {
        return Math.abs(Math.IEEEremainder(a - b, 360));
    }

    private static boolean valid(LogoFacingDirection logo, UsbFacingDirection usb) {
        String l = logo.name(), u = usb.name();
        if (l.equals(u)) return false;
        String[][] opposite = {{"UP", "DOWN"}, {"FORWARD", "BACKWARD"}, {"LEFT", "RIGHT"}};
        for (String[] o : opposite) if ((l.equals(o[0]) && u.equals(o[1])) || (l.equals(o[1]) && u.equals(o[0]))) return false;
        return true;
    }

    @Test
    void anglesAreTheTriplesFirstsOrientationMathGives() {
        // FIRST's BHI260IMU gives Quaternion.toOrientation (Orientation.getOrientation) of the robot's
        // orientation: for an order and a rotation there are two equivalent triples, and it is
        // FIRST's that robot code sees, beyond +-90 degrees and with the robot tilted too.
        RevHubOrientationOnRobot mount = new RevHubOrientationOnRobot(LogoFacingDirection.UP, UsbFacingDirection.FORWARD);
        ImuBackend backend = new ImuBackend(mount);
        BHI260IMU imu = new BHI260IMU(backend);
        imu.initialize(new IMU.Parameters(mount));
        Random rnd = new Random(1);
        int compared = 0;
        for (int i = 0; i < 300; i++) {
            double[] q = randomQuaternion(rnd);
            backend.set(new ImuBackend.State(matrix(q), new double[]{0, 0, 0}, 0));
            Quaternion truth = new Quaternion((float) q[0], (float) q[1], (float) q[2], (float) q[3], 0);
            for (AxesReference reference : AxesReference.values()) {
                for (AxesOrder order : AxesOrder.values()) {
                    Orientation expected = truth.toOrientation(reference, order, AngleUnit.DEGREES);
                    Orientation got = imu.getRobotOrientation(reference, order, AngleUnit.DEGREES);
                    // (near gimbal lock the first and third angles trade off; the rotation is what counts there)
                    if (Math.abs(Math.abs(expected.secondAngle) - 90) < 1 || Math.abs(expected.secondAngle) < 1
                            || Math.abs(Math.abs(expected.secondAngle) - 180) < 1) continue;
                    String what = reference + " " + order + " of " + truth.w + " " + truth.x + " " + truth.y + " " + truth.z;
                    assertTrue(angleDiff(expected.firstAngle, got.firstAngle) < 0.05, what + ": " + expected + " vs " + got);
                    assertTrue(angleDiff(expected.secondAngle, got.secondAngle) < 0.05, what + ": " + expected + " vs " + got);
                    assertTrue(angleDiff(expected.thirdAngle, got.thirdAngle) < 0.05, what + ": " + expected + " vs " + got);
                    compared++;
                }
            }
            Orientation zxy = truth.toOrientation(AxesReference.INTRINSIC, AxesOrder.ZXY, AngleUnit.DEGREES);
            YawPitchRollAngles ypr = imu.getRobotYawPitchRollAngles();
            if (Math.abs(Math.abs(zxy.secondAngle) - 90) > 1) {
                assertTrue(angleDiff(zxy.firstAngle, ypr.getYaw(AngleUnit.DEGREES)) < 0.05, "yaw " + zxy + " vs " + ypr);
                assertTrue(angleDiff(zxy.secondAngle, ypr.getPitch(AngleUnit.DEGREES)) < 0.05, "pitch " + zxy + " vs " + ypr);
                assertTrue(angleDiff(zxy.thirdAngle, ypr.getRoll(AngleUnit.DEGREES)) < 0.05, "roll " + zxy + " vs " + ypr);
            }
        }
        assertTrue(compared > 5000, "compared " + compared);
    }

    @Test
    void hubOrientationsAreFirstsAndTheReadingsFollowTheRobot() {
        // RevHubOrientationOnRobot is FIRST's own class (the BHI260's -90 degree chip offset, split
        // into a yaw-only and a tilt part), with the values FIRST's gives.
        String where = RevHubOrientationOnRobot.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(where.contains("ftc-hardware-12.0.0-pure.jar"), where);
        assertQuaternion(new float[]{0.707107f, 0, 0, -0.707107f},
                new RevHubOrientationOnRobot(LogoFacingDirection.UP, UsbFacingDirection.FORWARD).imuCoordinateSystemOrientationFromPerspectiveOfRobot());
        RevHubOrientationOnRobot leftUp = new RevHubOrientationOnRobot(LogoFacingDirection.LEFT, UsbFacingDirection.UP);
        assertQuaternion(new float[]{0, 0, 0, 1}, leftUp.imuCoordinateSystemOrientationFromPerspectiveOfRobot());
        assertQuaternion(new float[]{0.707107f, 0, 0.707107f, 0}, leftUp.imuRotationOffset());
        assertQuaternion(new float[]{0, -0.707107f, 0, 0.707107f}, leftUp.angularVelocityTransform());

        // Whatever the mounting, described as it is, the IMU reads the robot's own orientation and rates.
        Random rnd = new Random(2);
        int mountings = 0;
        for (LogoFacingDirection logo : LogoFacingDirection.values()) {
            for (UsbFacingDirection usb : UsbFacingDirection.values()) {
                if (!valid(logo, usb)) continue;
                mountings++;
                RevHubOrientationOnRobot mount = new RevHubOrientationOnRobot(logo, usb);
                ImuBackend backend = new ImuBackend(mount);
                BHI260IMU imu = new BHI260IMU(backend);
                imu.initialize(new IMU.Parameters(mount));
                for (int i = 0; i < 20; i++) {
                    double[] q = randomQuaternion(rnd);
                    double[] rates = {rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()};
                    backend.set(new ImuBackend.State(matrix(q), rates, 0));
                    Quaternion got = imu.getRobotOrientationAsQuaternion();
                    double dot = got.w * q[0] + got.x * q[1] + got.y * q[2] + got.z * q[3];
                    assertEquals(1, Math.abs(dot), 1e-4, logo + "/" + usb + ": the robot's orientation");
                    var w = imu.getRobotAngularVelocity(AngleUnit.RADIANS);
                    assertEquals(rates[0], w.xRotationRate, 1e-4, logo + "/" + usb + " x rate");
                    assertEquals(rates[1], w.yRotationRate, 1e-4, logo + "/" + usb + " y rate");
                    assertEquals(rates[2], w.zRotationRate, 1e-4, logo + "/" + usb + " z rate");
                }
            }
        }
        assertEquals(24, mountings);
    }

    private static void assertQuaternion(float[] expected, Quaternion q) {
        assertEquals(expected[0], q.w, 1e-5, "w of " + q);
        assertEquals(expected[1], q.x, 1e-5, "x of " + q);
        assertEquals(expected[2], q.y, 1e-5, "y of " + q);
        assertEquals(expected[3], q.z, 1e-5, "z of " + q);
    }
}
