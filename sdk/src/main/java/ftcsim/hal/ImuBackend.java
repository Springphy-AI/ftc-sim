package ftcsim.hal;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.matrices.VectorF;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;
import com.qualcomm.robotcore.hardware.QuaternionBasedImuHelper;

/**
 * True motion of a Control Hub's IMU chip, written by physics. Rotations use the FTC robot frame
 * (+X right, +Y forward, +Z up).
 *
 * <p>The chip's own readings are worked out from the robot's motion and how the hub is really
 * mounted, as FIRST's {@link QuaternionBasedImuHelper} would need them to report that motion for
 * that mounting; the IMU then reports them through FIRST's helper and the mounting robot code
 * gives it, so a wrong description gives the readings it gives on the robot.
 */
public final class ImuBackend {

    public static final class State {
        /** Robot-to-world rotation. */
        public final double[][] robotToWorld;
        /** Robot angular velocity in robot coordinates, rad/s. */
        public final double[] robotAngularVelocity;
        public final long nanoTime;

        public State(double[][] robotToWorld, double[] robotAngularVelocity, long nanoTime) {
            this.robotToWorld = robotToWorld;
            this.robotAngularVelocity = robotAngularVelocity;
            this.nanoTime = nanoTime;
        }
    }

    /** How the hub is physically mounted (the robot file's "imu" section). */
    public final ImuOrientationOnRobot mount;
    private volatile State state = new State(Rotations.identity(), new double[]{0, 0, 0}, System.nanoTime());
    /**
     * FIRST's helper, with the mounting robot code last gave and resetYaw()'s offset. The Control
     * Hub's IMU object lives as long as the robot app, so both carry over from one OpMode to the
     * next (initialize() sets the mounting and leaves the offset); a power cycle starts afresh.
     */
    private volatile QuaternionBasedImuHelper helper = newHelper();

    public ImuBackend(ImuOrientationOnRobot mount) {
        this.mount = mount;
    }

    /** As BHI260IMU's constructor: the default parameters' mounting, logo up and USB forward. */
    private static QuaternionBasedImuHelper newHelper() {
        return new QuaternionBasedImuHelper(new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP, RevHubOrientationOnRobot.UsbFacingDirection.FORWARD));
    }

    public QuaternionBasedImuHelper helper() {
        return helper;
    }

    /** Turning the robot off and on: a new IMU object, no yaw offset. */
    public void powerCycle() {
        helper = newHelper();
    }

    public void set(State s) {
        state = s;
    }

    public State get() {
        return state;
    }

    /**
     * The chip's game rotation vector: the quaternion q for which FIRST's helper, given the real
     * mounting (C = imuCoordinateSystemOrientationFromPerspectiveOfRobot, O = imuRotationOffset),
     * reports the robot's orientation R: C q C⁻¹ O = R, so q = C⁻¹ R O⁻¹ C.
     */
    public Quaternion rawQuaternion() {
        State s = state;
        double[] r = Rotations.toQuaternion(s.robotToWorld);
        Quaternion robot = new Quaternion((float) r[0], (float) r[1], (float) r[2], (float) r[3], s.nanoTime);
        Quaternion c = mount.imuCoordinateSystemOrientationFromPerspectiveOfRobot();
        return c.inverse().multiply(robot, s.nanoTime).multiply(mount.imuRotationOffset().inverse(), s.nanoTime)
                .multiply(c, s.nanoTime).normalized();
    }

    /** The chip's gyro rates: what angularVelocityTransform turns into the robot's rates for the real mounting. */
    public AngularVelocity rawAngularVelocity() {
        State s = state;
        double[] w = s.robotAngularVelocity;
        VectorF chip = mount.angularVelocityTransform().inverse().applyToVector(new VectorF((float) w[0], (float) w[1], (float) w[2]));
        return new AngularVelocity(AngleUnit.RADIANS, chip.get(0), chip.get(1), chip.get(2), s.nanoTime);
    }
}
