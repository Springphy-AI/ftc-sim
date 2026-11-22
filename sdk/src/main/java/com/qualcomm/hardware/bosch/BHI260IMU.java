package com.qualcomm.hardware.bosch;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;

import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;

import ftcsim.hal.ImuBackend;
import ftcsim.hal.Latency;
import ftcsim.hal.Rotations;

/**
 * The IMU inside a Control Hub. Reports orientation in the robot frame described by the
 * {@link IMU.Parameters} passed to {@link #initialize}: if that description does not match how
 * the hub is actually mounted (robot JSON "imu" section), the readings are wrong in the same
 * way they would be on the real robot.
 */
public class BHI260IMU implements IMU {
    private final ImuBackend backend;
    private volatile double[][] assumedHubToRobot = Rotations.identity();

    public BHI260IMU(ImuBackend backend) {
        this.backend = backend;
    }

    @Override
    public boolean initialize(Parameters parameters) {
        Latency.i2c();
        ImuOrientationOnRobot o = parameters.imuOrientationOnRobot;
        Quaternion q = o.imuCoordinateSystemOrientationFromPerspectiveOfRobot();
        assumedHubToRobot = Rotations.fromQuaternion(q.w, q.x, q.y, q.z);
        return true;   // (resetYaw's offset stays, as FIRST's initialize leaves it)
    }

    /** Robot orientation as the IMU believes it (before yaw reset). */
    private double[][] believedRobotToWorld(ImuBackend.State s) {
        double[][] hubToWorld = Rotations.multiply(s.robotToWorld, backend.hubToRobot);
        return Rotations.multiply(hubToWorld, Rotations.transpose(assumedHubToRobot));
    }

    private double[][] reportedRobotToWorld(ImuBackend.State s) {
        return Rotations.multiply(Rotations.axis(2, -backend.yawOffsetRad), believedRobotToWorld(s));
    }

    @Override
    public void resetYaw() {
        Latency.i2c();
        double[][] m = believedRobotToWorld(backend.get());
        double[] ypr = Rotations.toEuler(m, AxesReference.INTRINSIC, AxesOrder.ZXY);
        backend.yawOffsetRad = ypr[0];
    }

    @Override
    public YawPitchRollAngles getRobotYawPitchRollAngles() {
        Latency.i2c();
        ImuBackend.State s = backend.get();
        double[] zxy = Rotations.toEuler(reportedRobotToWorld(s), AxesReference.INTRINSIC, AxesOrder.ZXY);
        return new YawPitchRollAngles(AngleUnit.DEGREES,
                AngleUnit.DEGREES.fromRadians(zxy[0]),
                AngleUnit.DEGREES.fromRadians(zxy[1]),
                AngleUnit.DEGREES.fromRadians(zxy[2]),
                s.nanoTime);
    }

    @Override
    public Orientation getRobotOrientation(AxesReference reference, AxesOrder order, AngleUnit angleUnit) {
        Latency.i2c();
        ImuBackend.State s = backend.get();
        return Rotations.toOrientation(reportedRobotToWorld(s), reference, order, angleUnit, s.nanoTime);
    }

    @Override
    public Quaternion getRobotOrientationAsQuaternion() {
        Latency.i2c();
        ImuBackend.State s = backend.get();
        double[] q = Rotations.toQuaternion(reportedRobotToWorld(s));
        return new Quaternion((float) q[0], (float) q[1], (float) q[2], (float) q[3], s.nanoTime);
    }

    @Override
    public AngularVelocity getRobotAngularVelocity(AngleUnit angleUnit) {
        Latency.i2c();
        ImuBackend.State s = backend.get();
        double[] hubRate = Rotations.apply(Rotations.transpose(backend.hubToRobot), s.robotAngularVelocity);
        double[] believed = Rotations.apply(assumedHubToRobot, hubRate);
        return new AngularVelocity(AngleUnit.RADIANS,
                (float) believed[0], (float) believed[1], (float) believed[2], s.nanoTime).toAngleUnit(angleUnit);
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.Lynx;
    }

    @Override
    public String getDeviceName() {
        return "BHI260AP IMU";
    }

    @Override
    public String getConnectionInfo() {
        return "Control Hub I2C bus 0";
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
    }

    @Override
    public void close() {
    }
}
