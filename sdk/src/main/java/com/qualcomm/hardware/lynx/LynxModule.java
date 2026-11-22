package com.qualcomm.hardware.lynx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;
import org.firstinspires.ftc.robotcore.external.navigation.TempUnit;
import org.firstinspires.ftc.robotcore.external.navigation.VoltageUnit;

import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.util.SerialNumber;

import ftcsim.hal.HalSession;
import ftcsim.hal.Latency;
import ftcsim.hal.SimHub;

/** A REV Control Hub / Expansion Hub, including bulk-read caching. */
public class LynxModule implements HardwareDevice {
    public static final String TAG = "LynxModule";

    /** Determines how sensor data is cached between bus transactions. */
    public enum BulkCachingMode {
        /** Every read is its own transaction. */
        OFF,
        /** Values are cached until clearBulkCache() is called. */
        MANUAL,
        /** Values are cached until the same value is read a second time. */
        AUTO
    }

    /** Data from a single bulk read. */
    public static class BulkData {
        private final SimHub.BulkSnapshot snapshot;

        BulkData(SimHub.BulkSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        public int getMotorCurrentPosition(int motorNum) {
            return snapshot.positions[motorNum];
        }

        public int getMotorVelocity(int motorNum) {
            return (int) Math.round(snapshot.velocities[motorNum]);
        }

        public boolean isMotorBusy(int motorNum) {
            return snapshot.busy[motorNum];
        }

        public boolean isMotorOverCurrent(int motorNum) {
            return false;
        }

        public boolean getDigitalChannelState(int digitalInputNum) {
            return snapshot.digital[digitalInputNum];
        }

        public double getAnalogInputVoltage(int inputNum) {
            return 0;
        }

        public boolean isFake() {
            return false;
        }
    }

    private final SimHub hub;
    private final HalSession session;

    /** Created by the simulator; robot code obtains hubs with hardwareMap.getAll(LynxModule.class). */
    public LynxModule(SimHub hub, HalSession session) {
        this.hub = hub;
        this.session = session;
    }

    public BulkCachingMode getBulkCachingMode() {
        return hub.getBulkCachingMode();
    }

    public void setBulkCachingMode(BulkCachingMode mode) {
        if (!session.isActive()) return;
        hub.setBulkCachingMode(mode);
    }

    public void clearBulkCache() {
        hub.clearBulkCache();
    }

    /** Performs a bulk read right away and returns the result, which the bulk cache then holds. */
    public BulkData getBulkData() {
        return new BulkData(hub.bulkRead());
    }

    public boolean isParent() {
        return hub.isParent;
    }

    public int getModuleAddress() {
        return hub.moduleAddress;
    }

    public SerialNumber getSerialNumber() {
        return hub.serialNumber;
    }

    public SerialNumber getModuleSerialNumber() {
        return hub.serialNumber;
    }

    public int getRevProductNumber() {
        return hub.isParent ? 0x311152 : 0x311153;
    }

    public String getFirmwareVersionString() {
        return "HW: 20, Maj: 1, Min: 8, Eng: 2 (simulated)";
    }

    public String getNullableFirmwareVersionString() {
        return getFirmwareVersionString();
    }

    public List<String> getGlobalWarnings() {
        return new ArrayList<String>();
    }

    /** Sets the color of the hub's status LED. */
    public void setConstant(int color) {
        if (!session.isActive()) return;
        hub.ledColor = color & 0xFFFFFF;
    }

    public void stopBlinking() {
        setConstant(0x00FF00);
    }

    public void visuallyIdentify(boolean shouldIdentify) {
    }

    public double getCurrent(CurrentUnit unit) {
        Latency.command();
        return unit.convert(hub.totalCurrentAmps, CurrentUnit.AMPS);
    }

    public double getGpioBusCurrent(CurrentUnit unit) {
        return unit.convert(0.05, CurrentUnit.AMPS);
    }

    public double getI2cBusCurrent(CurrentUnit unit) {
        return unit.convert(0.08, CurrentUnit.AMPS);
    }

    public double getInputVoltage(VoltageUnit unit) {
        Latency.command();
        return unit.convert(hub.inputVoltage, VoltageUnit.VOLTS);
    }

    public double getAuxiliaryVoltage(VoltageUnit unit) {
        return unit.convert(5.0, VoltageUnit.VOLTS);
    }

    public double getTemperature(TempUnit unit) {
        return unit.fromCelsius(34.0);
    }

    public boolean isNotResponding() {
        return false;
    }

    public boolean isEngaged() {
        return true;
    }

    public void engage() {
    }

    public void disengage() {
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.Lynx;
    }

    @Override
    public String getDeviceName() {
        return hub.name;
    }

    @Override
    public String getConnectionInfo() {
        return hub.connectionInfo();
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

    @Override
    public String toString() {
        return "LynxModule(" + hub.name + ")";
    }

    static List<String> none() {
        return Collections.emptyList();
    }
}
