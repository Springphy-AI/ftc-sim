package com.qualcomm.hardware.bosch;

import org.firstinspires.ftc.robotcore.external.function.ThrowingSupplier;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;

import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.QuaternionBasedImuHelper;

import ftcsim.hal.ImuBackend;
import ftcsim.hal.Latency;

/**
 * The IMU inside a Control Hub. As FIRST's BHI260IMU, it turns the chip's quaternion and gyro rates
 * into the robot's orientation with FIRST's own QuaternionBasedImuHelper, for the mounting given
 * to {@link #initialize}: if that description does not match how the hub is actually mounted
 * (robot JSON "imu" section), the readings are wrong in the same way they would be on the robot.
 */
public class BHI260IMU implements IMU {
    private static final String TAG = "BHI260IMU";

    private final ImuBackend backend;
    private final ThrowingSupplier<Quaternion, QuaternionBasedImuHelper.FailedToRetrieveQuaternionException> rawQuaternion;

    public BHI260IMU(final ImuBackend backend) {
        this.backend = backend;
        this.rawQuaternion = new ThrowingSupplier<Quaternion, QuaternionBasedImuHelper.FailedToRetrieveQuaternionException>() {
            @Override
            public Quaternion get() {
                return backend.rawQuaternion();
            }
        };
    }

    @Override
    public boolean initialize(Parameters parameters) {
        Latency.i2c();
        backend.helper().setImuOrientationOnRobot(parameters.imuOrientationOnRobot);
        return true;   // (resetYaw's offset stays, as FIRST's initialize leaves it)
    }

    @Override
    public void resetYaw() {
        Latency.i2c();
        backend.helper().resetYaw(TAG, rawQuaternion, 50);
    }

    @Override
    public YawPitchRollAngles getRobotYawPitchRollAngles() {
        Latency.i2c();
        return backend.helper().getRobotYawPitchRollAngles(TAG, rawQuaternion);
    }

    @Override
    public Orientation getRobotOrientation(AxesReference reference, AxesOrder order, AngleUnit angleUnit) {
        Latency.i2c();
        return backend.helper().getRobotOrientation(TAG, rawQuaternion, reference, order, angleUnit);
    }

    @Override
    public Quaternion getRobotOrientationAsQuaternion() {
        Latency.i2c();
        return backend.helper().getRobotOrientationAsQuaternion(TAG, rawQuaternion, true);
    }

    @Override
    public AngularVelocity getRobotAngularVelocity(AngleUnit angleUnit) {
        Latency.i2c();
        return backend.helper().getRobotAngularVelocity(backend.rawAngularVelocity(), angleUnit);
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
