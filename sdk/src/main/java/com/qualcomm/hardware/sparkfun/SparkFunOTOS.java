package com.qualcomm.hardware.sparkfun;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

import com.qualcomm.robotcore.hardware.HardwareDevice;

import ftcsim.hal.Latency;
import ftcsim.hal.OtosBackend;

/**
 * SparkFun Optical Tracking Odometry Sensor. Coordinates: +X right, +Y forward, heading CCW.
 * Default units are inches and degrees.
 */
public class SparkFunOTOS implements HardwareDevice {
    public static final byte DEFAULT_ADDRESS = 0x17;
    public static final double MIN_SCALAR = 0.872;
    public static final double MAX_SCALAR = 1.127;

    public static class Pose2D {
        public double x;
        public double y;
        public double h;

        public Pose2D() {
            x = 0.0;
            y = 0.0;
            h = 0.0;
        }

        public Pose2D(double x, double y, double h) {
            this.x = x;
            this.y = y;
            this.h = h;
        }

        public void set(Pose2D pose) {
            this.x = pose.x;
            this.y = pose.y;
            this.h = pose.h;
        }

        @Override
        public String toString() {
            return String.format("(%.3f, %.3f, %.3f)", x, y, h);
        }
    }

    public static class Version {
        public byte minor;
        public byte major;

        public Version() {
        }

        public Version(byte value) {
            set(value);
        }

        public void set(byte value) {
            minor = (byte) (value & 0x0F);
            major = (byte) ((value >> 4) & 0x0F);
        }

        public byte get() {
            return (byte) ((major << 4) | (minor & 0x0F));
        }
    }

    public static class SignalProcessConfig {
        public boolean enLut;
        public boolean enAcc;
        public boolean enRot;
        public boolean enVar;

        public SignalProcessConfig() {
            enLut = enAcc = enRot = enVar = true;
        }

        public SignalProcessConfig(byte value) {
            enLut = (value & 1) != 0;
            enAcc = (value & 2) != 0;
            enRot = (value & 4) != 0;
            enVar = (value & 8) != 0;
        }

        public byte get() {
            return (byte) ((enLut ? 1 : 0) | (enAcc ? 2 : 0) | (enRot ? 4 : 0) | (enVar ? 8 : 0));
        }
    }

    public static class Status {
        public boolean warnTiltAngle;
        public boolean warnOpticalTracking;
        public boolean errorPaa;
        public boolean errorLsm;

        public Status() {
        }

        public Status(byte value) {
            warnTiltAngle = (value & 1) != 0;
            warnOpticalTracking = (value & 2) != 0;
            errorPaa = (value & 64) != 0;
            errorLsm = (value & -128) != 0;
        }

        public byte get() {
            return (byte) ((warnTiltAngle ? 1 : 0) | (warnOpticalTracking ? 2 : 0) | (errorPaa ? 64 : 0) | (errorLsm ? 128 : 0));
        }
    }

    private final OtosBackend backend;
    private DistanceUnit distanceUnit = DistanceUnit.INCH;
    private AngleUnit angularUnit = AngleUnit.DEGREES;
    private SignalProcessConfig signalProcessConfig = new SignalProcessConfig();
    private int calibrationSamplesRemaining = 0;

    public SparkFunOTOS(OtosBackend backend) {
        this.backend = backend;
    }

    public boolean begin() {
        return isConnected();
    }

    public boolean isConnected() {
        Latency.i2c();
        return true;
    }

    public void getVersionInfo(Version hwVersion, Version fwVersion) {
        hwVersion.major = 1;
        hwVersion.minor = 0;
        fwVersion.major = 1;
        fwVersion.minor = 0;
    }

    public boolean selfTest() {
        Latency.i2c();
        return true;
    }

    public boolean calibrateImu() {
        return calibrateImu(255, true);
    }

    public boolean calibrateImu(int numSamples, boolean waitUntilDone) {
        Latency.i2c();
        calibrationSamplesRemaining = 0;
        if (waitUntilDone) {
            try {
                Thread.sleep(Math.max(1, numSamples * 2L / 3));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    public int getImuCalibrationProgress() {
        return calibrationSamplesRemaining;
    }

    public DistanceUnit getLinearUnit() {
        return distanceUnit;
    }

    /** Any unit, as FIRST's driver takes it (MM and CM included). */
    public void setLinearUnit(DistanceUnit unit) {
        distanceUnit = unit;
    }

    public AngleUnit getAngularUnit() {
        return angularUnit;
    }

    public void setAngularUnit(AngleUnit unit) {
        angularUnit = unit;
    }

    public double getLinearScalar() {
        return backend.getLinearScalar();
    }

    public boolean setLinearScalar(double scalar) {
        if (scalar < MIN_SCALAR || scalar > MAX_SCALAR) return false;
        Latency.i2c();
        backend.setLinearScalar(scalar);
        return true;
    }

    public double getAngularScalar() {
        return backend.getAngularScalar();
    }

    public boolean setAngularScalar(double scalar) {
        if (scalar < MIN_SCALAR || scalar > MAX_SCALAR) return false;
        Latency.i2c();
        backend.setAngularScalar(scalar);
        return true;
    }

    public void resetTracking() {
        Latency.i2c();
        backend.resetTracking();
    }

    public SignalProcessConfig getSignalProcessConfig() {
        return signalProcessConfig;
    }

    public void setSignalProcessConfig(SignalProcessConfig config) {
        signalProcessConfig = config;
    }

    public Status getStatus() {
        return new Status();
    }

    /**
     * Into the user's units, as FIRST's driver converts every pose it reads (regsToPose), rates
     * included: its angle unit normalizes, so a heading rate over 180 deg/s wraps on the robot too.
     */
    private Pose2D toUser(double x, double y, double h) {
        return new Pose2D(distanceUnit.fromMeters(x), distanceUnit.fromMeters(y), angularUnit.fromRadians(h));
    }

    public Pose2D getOffset() {
        double[] o = backend.offset();
        return toUser(o[0], o[1], o[2]);
    }

    public void setOffset(Pose2D pose) {
        Latency.i2c();
        backend.setOffset(distanceUnit.toMeters(pose.x), distanceUnit.toMeters(pose.y), angularUnit.toRadians(pose.h));
    }

    public Pose2D getPosition() {
        Latency.i2c();
        double[] p = backend.robotPose();
        return toUser(p[0], p[1], p[2]);
    }

    public void setPosition(Pose2D pose) {
        Latency.i2c();
        backend.setRobotPose(distanceUnit.toMeters(pose.x), distanceUnit.toMeters(pose.y), angularUnit.toRadians(pose.h));
    }

    public Pose2D getVelocity() {
        Latency.i2c();
        double[] v = backend.velocity();
        return toUser(v[0], v[1], v[2]);
    }

    public Pose2D getAcceleration() {
        Latency.i2c();
        double[] a = backend.acceleration();
        return toUser(a[0], a[1], a[2]);
    }

    public Pose2D getPositionStdDev() {
        return toUser(0.0005, 0.0005, 0.0005);
    }

    public Pose2D getVelocityStdDev() {
        return toUser(0.001, 0.001, 0.001);
    }

    public Pose2D getAccelerationStdDev() {
        return toUser(0.01, 0.01, 0.01);
    }

    public void getPosVelAcc(Pose2D pos, Pose2D vel, Pose2D acc) {
        pos.set(getPosition());
        vel.set(getVelocity());
        acc.set(getAcceleration());
    }

    public void getPosVelAccStdDev(Pose2D pos, Pose2D vel, Pose2D acc) {
        pos.set(getPositionStdDev());
        vel.set(getVelocityStdDev());
        acc.set(getAccelerationStdDev());
    }

    public void getPosVelAccAndStdDev(Pose2D pos, Pose2D vel, Pose2D acc, Pose2D posStdDev, Pose2D velStdDev, Pose2D accStdDev) {
        getPosVelAcc(pos, vel, acc);
        getPosVelAccStdDev(posStdDev, velStdDev, accStdDev);
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.SparkFun;
    }

    @Override
    public String getDeviceName() {
        return "SparkFun Optical Tracking Odometry Sensor";
    }

    @Override
    public String getConnectionInfo() {
        return "I2C bus 2, address 0x17";
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
