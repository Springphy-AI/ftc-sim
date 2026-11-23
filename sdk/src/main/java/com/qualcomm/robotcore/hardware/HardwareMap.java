package com.qualcomm.robotcore.hardware;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.firstinspires.ftc.robotcore.internal.system.AppUtil;

import android.content.Context;

import com.qualcomm.robotcore.util.SerialNumber;

/**
 * HardwareMap provides a means of retrieving runtime HardwareDevice instances according to the
 * names with which the corresponding physical devices were associated during robot configuration.
 * In the simulator the configuration comes from the robot JSON file (robots/*.json).
 */
public class HardwareMap implements Iterable<HardwareDevice> {

    public DeviceMapping<DcMotorController> dcMotorController = new DeviceMapping<DcMotorController>(DcMotorController.class);
    public DeviceMapping<DcMotor> dcMotor = new DeviceMapping<DcMotor>(DcMotor.class);
    public DeviceMapping<ServoController> servoController = new DeviceMapping<ServoController>(ServoController.class);
    public DeviceMapping<Servo> servo = new DeviceMapping<Servo>(Servo.class);
    public DeviceMapping<CRServo> crservo = new DeviceMapping<CRServo>(CRServo.class);
    public DeviceMapping<TouchSensorMultiplexer> touchSensorMultiplexer = new DeviceMapping<TouchSensorMultiplexer>(TouchSensorMultiplexer.class);
    public DeviceMapping<AnalogInput> analogInput = new DeviceMapping<AnalogInput>(AnalogInput.class);
    public DeviceMapping<DigitalChannel> digitalChannel = new DeviceMapping<DigitalChannel>(DigitalChannel.class);
    public DeviceMapping<OpticalDistanceSensor> opticalDistanceSensor = new DeviceMapping<OpticalDistanceSensor>(OpticalDistanceSensor.class);
    public DeviceMapping<TouchSensor> touchSensor = new DeviceMapping<TouchSensor>(TouchSensor.class);
    public DeviceMapping<PWMOutput> pwmOutput = new DeviceMapping<PWMOutput>(PWMOutput.class);
    public DeviceMapping<I2cDevice> i2cDevice = new DeviceMapping<I2cDevice>(I2cDevice.class);
    public DeviceMapping<I2cDeviceSynch> i2cDeviceSynch = new DeviceMapping<I2cDeviceSynch>(I2cDeviceSynch.class);
    public DeviceMapping<ColorSensor> colorSensor = new DeviceMapping<ColorSensor>(ColorSensor.class);
    public DeviceMapping<LED> led = new DeviceMapping<LED>(LED.class);
    public DeviceMapping<AccelerationSensor> accelerationSensor = new DeviceMapping<AccelerationSensor>(AccelerationSensor.class);
    public DeviceMapping<CompassSensor> compassSensor = new DeviceMapping<CompassSensor>(CompassSensor.class);
    public DeviceMapping<GyroSensor> gyroSensor = new DeviceMapping<GyroSensor>(GyroSensor.class);
    public DeviceMapping<IrSeekerSensor> irSeekerSensor = new DeviceMapping<IrSeekerSensor>(IrSeekerSensor.class);
    public DeviceMapping<LightSensor> lightSensor = new DeviceMapping<LightSensor>(LightSensor.class);
    public DeviceMapping<UltrasonicSensor> ultrasonicSensor = new DeviceMapping<UltrasonicSensor>(UltrasonicSensor.class);
    public DeviceMapping<VoltageSensor> voltageSensor = new DeviceMapping<VoltageSensor>(VoltageSensor.class);

    public final List<DeviceMapping<? extends HardwareDevice>> allDeviceMappings;
    /** The Robot Controller app (for its package name and resources). */
    public final Context appContext = AppUtil.getDefContext();

    protected final Map<String, List<HardwareDevice>> allDevicesMap = new HashMap<String, List<HardwareDevice>>();
    protected final List<HardwareDevice> allDevicesList = new ArrayList<HardwareDevice>();
    protected final Map<HardwareDevice, Set<String>> deviceNames = new HashMap<HardwareDevice, Set<String>>();
    protected final Map<SerialNumber, HardwareDevice> serialNumberMap = new HashMap<SerialNumber, HardwareDevice>();
    protected final Object lock = new Object();

    public HardwareMap() {
        List<DeviceMapping<? extends HardwareDevice>> all = new ArrayList<DeviceMapping<? extends HardwareDevice>>();
        all.add(dcMotorController);
        all.add(dcMotor);
        all.add(servoController);
        all.add(servo);
        all.add(crservo);
        all.add(touchSensorMultiplexer);
        all.add(analogInput);
        all.add(digitalChannel);
        all.add(opticalDistanceSensor);
        all.add(touchSensor);
        all.add(pwmOutput);
        all.add(i2cDevice);
        all.add(i2cDeviceSynch);
        all.add(colorSensor);
        all.add(led);
        all.add(accelerationSensor);
        all.add(compassSensor);
        all.add(gyroSensor);
        all.add(irSeekerSensor);
        all.add(lightSensor);
        all.add(ultrasonicSensor);
        all.add(voltageSensor);
        allDeviceMappings = Collections.unmodifiableList(all);
    }

    /**
     * Retrieves the (first) device with the indicated name which is also an instance of the
     * indicated class or interface. If no such device is found, an exception is thrown.
     */
    public <T> T get(Class<? extends T> classOrInterface, String deviceName) {
        T result = tryGet(classOrInterface, deviceName);
        if (result == null) {
            throw new IllegalArgumentException(String.format("Unable to find a hardware device with name \"%s\" and type %s",
                    deviceName, classOrInterface.getSimpleName()));
        }
        return result;
    }

    /** Like {@link #get(Class, String)}, but returns null instead of throwing. */
    public <T> T tryGet(Class<? extends T> classOrInterface, String deviceName) {
        synchronized (lock) {
            deviceName = deviceName.trim();
            List<HardwareDevice> list = allDevicesMap.get(deviceName);
            if (list != null) {
                for (HardwareDevice device : list) {
                    if (classOrInterface.isInstance(device)) {
                        return classOrInterface.cast(device);
                    }
                }
            }
            return null;
        }
    }

    public <T> T get(Class<? extends T> classOrInterface, SerialNumber serialNumber) {
        synchronized (lock) {
            HardwareDevice device = serialNumberMap.get(serialNumber);
            if (device != null && classOrInterface.isInstance(device)) {
                return classOrInterface.cast(device);
            }
            return null;
        }
    }

    /** Returns the first device with the indicated name. */
    public HardwareDevice get(String deviceName) {
        synchronized (lock) {
            deviceName = deviceName.trim();
            List<HardwareDevice> list = allDevicesMap.get(deviceName);
            if (list != null && !list.isEmpty()) {
                return list.get(0);
            }
            throw new IllegalArgumentException(String.format("No device with name \"%s\" found", deviceName));
        }
    }

    /** Returns all the devices which are instances of the indicated class or interface. */
    public <T> List<T> getAll(Class<? extends T> classOrInterface) {
        synchronized (lock) {
            List<T> result = new LinkedList<T>();
            for (HardwareDevice device : allDevicesList) {
                if (classOrInterface.isInstance(device)) {
                    result.add(classOrInterface.cast(device));
                }
            }
            return result;
        }
    }

    public SortedSet<String> getAllNames(Class<? extends HardwareDevice> classOrInterface) {
        synchronized (lock) {
            SortedSet<String> result = new TreeSet<String>();
            for (Map.Entry<String, List<HardwareDevice>> entry : allDevicesMap.entrySet()) {
                for (HardwareDevice device : entry.getValue()) {
                    if (classOrInterface.isInstance(device)) {
                        result.add(entry.getKey());
                        break;
                    }
                }
            }
            return result;
        }
    }

    public void put(String deviceName, HardwareDevice device) {
        internalPut(null, deviceName, device);
    }

    public void put(String deviceName, List<HardwareDevice> devices) {
        for (HardwareDevice device : devices) {
            internalPut(null, deviceName, device);
        }
    }

    public void put(SerialNumber serialNumber, String deviceName, HardwareDevice device) {
        internalPut(serialNumber, deviceName, device);
    }

    protected void internalPut(SerialNumber serialNumber, String deviceName, HardwareDevice device) {
        synchronized (lock) {
            deviceName = deviceName.trim();
            List<HardwareDevice> list = allDevicesMap.get(deviceName);
            if (list == null) {
                list = new ArrayList<HardwareDevice>(1);
                allDevicesMap.put(deviceName, list);
            }
            if (!list.contains(device)) {
                list.add(device);
            }
            if (!allDevicesList.contains(device)) {
                allDevicesList.add(device);
            }
            Set<String> names = deviceNames.get(device);
            if (names == null) {
                names = new HashSet<String>();
                deviceNames.put(device, names);
            }
            names.add(deviceName);
            if (serialNumber != null) {
                serialNumberMap.put(serialNumber, device);
            }
        }
    }

    public boolean remove(String deviceName, HardwareDevice device) {
        return remove(null, deviceName, device);
    }

    public boolean remove(SerialNumber serialNumber, String deviceName, HardwareDevice device) {
        synchronized (lock) {
            deviceName = deviceName.trim();
            List<HardwareDevice> list = allDevicesMap.get(deviceName);
            if (list != null) {
                list.remove(device);
                if (list.isEmpty()) {
                    allDevicesMap.remove(deviceName);
                }
                allDevicesList.remove(device);
                deviceNames.remove(device);
                if (serialNumber != null) {
                    serialNumberMap.remove(serialNumber);
                }
                return true;
            }
            return false;
        }
    }

    /** Returns all the names by which the device is known. */
    public Set<String> getNamesOf(HardwareDevice device) {
        synchronized (lock) {
            Set<String> result = deviceNames.get(device);
            if (result == null) {
                result = new HashSet<String>();
            }
            return result;
        }
    }

    public int size() {
        synchronized (lock) {
            return allDevicesList.size();
        }
    }

    @Override
    public Iterator<HardwareDevice> iterator() {
        synchronized (lock) {
            return new ArrayList<HardwareDevice>(allDevicesList).iterator();
        }
    }

    public Iterable<HardwareDevice> unsafeIterable() {
        return allDevicesList;
    }

    public void logDevices() {
        synchronized (lock) {
            for (Map.Entry<String, List<HardwareDevice>> entry : allDevicesMap.entrySet()) {
                for (HardwareDevice device : entry.getValue()) {
                    com.qualcomm.robotcore.util.RobotLog.i("%s: %s", entry.getKey(), device.getDeviceName());
                }
            }
        }
    }

    /** A DeviceMapping contains a subcollection of the devices registered in a {@link HardwareMap}. */
    public static class DeviceMapping<DEVICE_TYPE extends HardwareDevice> implements Iterable<DEVICE_TYPE> {
        private final Map<String, DEVICE_TYPE> map = new LinkedHashMap<String, DEVICE_TYPE>();
        private final Class<DEVICE_TYPE> deviceTypeClass;

        public DeviceMapping(Class<DEVICE_TYPE> deviceTypeClass) {
            this.deviceTypeClass = deviceTypeClass;
        }

        public Class<DEVICE_TYPE> getDeviceTypeClass() {
            return this.deviceTypeClass;
        }

        public DEVICE_TYPE cast(Object obj) {
            return this.deviceTypeClass.cast(obj);
        }

        public DEVICE_TYPE get(String deviceName) {
            synchronized (map) {
                deviceName = deviceName.trim();
                DEVICE_TYPE device = map.get(deviceName);
                if (device == null) {
                    throw new IllegalArgumentException(String.format("Unable to find a hardware device with the name \"%s\"", deviceName));
                }
                return device;
            }
        }

        public void put(String deviceName, DEVICE_TYPE device) {
            synchronized (map) {
                map.put(deviceName.trim(), device);
            }
        }

        public void put(SerialNumber serialNumber, String deviceName, DEVICE_TYPE device) {
            put(deviceName, device);
        }

        public void putLocal(String deviceName, DEVICE_TYPE device) {
            put(deviceName, device);
        }

        public boolean contains(String deviceName) {
            synchronized (map) {
                return map.containsKey(deviceName.trim());
            }
        }

        public boolean remove(String deviceName) {
            synchronized (map) {
                return map.remove(deviceName.trim()) != null;
            }
        }

        public boolean remove(SerialNumber serialNumber, String deviceName) {
            return remove(deviceName);
        }

        @Override
        public Iterator<DEVICE_TYPE> iterator() {
            synchronized (map) {
                return new ArrayList<DEVICE_TYPE>(map.values()).iterator();
            }
        }

        public Set<Map.Entry<String, DEVICE_TYPE>> entrySet() {
            synchronized (map) {
                return new HashSet<Map.Entry<String, DEVICE_TYPE>>(map.entrySet());
            }
        }

        public int size() {
            synchronized (map) {
                return map.size();
            }
        }
    }
}
