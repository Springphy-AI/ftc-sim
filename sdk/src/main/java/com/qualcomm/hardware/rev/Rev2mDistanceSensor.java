package com.qualcomm.hardware.rev;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

import com.qualcomm.robotcore.hardware.DistanceSensor;

import ftcsim.hal.Latency;
import ftcsim.hal.SensorBackends;

/** REV 2m time-of-flight distance sensor (VL53L0X). */
public class Rev2mDistanceSensor implements DistanceSensor {
    private final SensorBackends.Range backend;

    public Rev2mDistanceSensor(SensorBackends.Range backend) {
        this.backend = backend;
    }

    /** What the VL53L0X reads when nothing is in range, in millimeters. */
    private static final int OUT_OF_RANGE_MM = 8190;

    /**
     * As FIRST's VL53L0X driver: the whole millimeters the chip reads, in the unit asked for. It has
     * no special value for "nothing in range": the chip then reads 8190 mm.
     */
    @Override
    public double getDistance(DistanceUnit unit) {
        Latency.i2c();
        double m = backend.meters;
        int mm = m == Double.MAX_VALUE ? OUT_OF_RANGE_MM : (int) Math.round(m * 1000);
        return unit.fromUnit(DistanceUnit.MM, mm);
    }

    public byte getModelID() {
        return (byte) 0xEE;
    }

    public boolean didTimeoutOccur() {
        return false;
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.STMicroelectronics;
    }

    @Override
    public String getDeviceName() {
        return "REV 2M ToF Distance Sensor";
    }

    @Override
    public String getConnectionInfo() {
        return "I2C bus 3, address 0x29";
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
