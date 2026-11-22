package ftcsim.hal;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.util.SerialNumber;

/**
 * A REV Control Hub or Expansion Hub: 4 motor ports, 6 servo ports and 8 digital channels,
 * with the SDK's bulk-read caching modes (OFF, AUTO, MANUAL).
 */
public final class SimHub {
    public static final int MOTORS = 4;
    public static final int SERVOS = 6;
    public static final int DIGITAL = 8;

    public final String name;
    public final int moduleAddress;
    public final boolean isParent;
    public final SerialNumber serialNumber;

    public final MotorPort[] motors = new MotorPort[MOTORS];
    public final ServoPort[] servos = new ServoPort[SERVOS];
    public final DigitalPort[] digital = new DigitalPort[DIGITAL];

    /** Battery voltage seen by the hub (written by physics). */
    public volatile double inputVoltage = 12.8;
    /** Total current drawn through the hub (written by physics). */
    public volatile double totalCurrentAmps = 0;
    /** Status LED color (0xRRGGBB), settable with LynxModule.setConstant(). */
    public volatile int ledColor = 0x00FF00;

    private volatile LynxModule.BulkCachingMode cachingMode = LynxModule.BulkCachingMode.OFF;
    private final Object cacheLock = new Object();
    private BulkSnapshot cache = null;
    private final boolean[] readSinceRefresh = new boolean[MOTORS * 3 + DIGITAL];

    public SimHub(String name, int moduleAddress, boolean isParent) {
        this.name = name;
        this.moduleAddress = moduleAddress;
        this.isParent = isParent;
        this.serialNumber = SerialNumber.fromString("SIM-HUB-" + moduleAddress);
    }

    public String connectionInfo() {
        return "USB " + serialNumber + "; module " + moduleAddress;
    }

    // ---- bulk caching ------------------------------------------------------------------------

    public LynxModule.BulkCachingMode getBulkCachingMode() {
        return cachingMode;
    }

    public void setBulkCachingMode(LynxModule.BulkCachingMode mode) {
        synchronized (cacheLock) {
            cachingMode = mode;
            cache = null;
        }
    }

    public void clearBulkCache() {
        synchronized (cacheLock) {
            cache = null;
        }
    }

    /** Snapshot of every bulk-readable value, taken in one bus transaction. */
    public static final class BulkSnapshot {
        public final int[] positions = new int[MOTORS];
        public final double[] velocities = new double[MOTORS];
        public final boolean[] busy = new boolean[MOTORS];
        public final boolean[] digital = new boolean[DIGITAL];
        public final long nanoTime = System.nanoTime();
    }

    public BulkSnapshot takeSnapshot() {
        Latency.command();
        BulkSnapshot s = new BulkSnapshot();
        for (int i = 0; i < MOTORS; i++) {
            MotorPort p = motors[i];
            if (p != null) {
                s.positions[i] = p.encoderPosition();
                s.velocities[i] = p.velocityTicksPerSecond;
                s.busy[i] = p.isBusyNow();
            }
        }
        for (int i = 0; i < DIGITAL; i++) {
            DigitalPort d = digital[i];
            s.digital[i] = d != null && d.level();
        }
        return s;
    }

    /**
     * LynxModule.getBulkData(): a bulk read now, which also becomes the cache the next reads use,
     * as on the hub (so MANUAL-mode code may refresh with getBulkData() instead of clearBulkCache()).
     */
    public BulkSnapshot bulkRead() {
        synchronized (cacheLock) {
            cache = takeSnapshot();
            java.util.Arrays.fill(readSinceRefresh, false);
            return cache;
        }
    }

    private BulkSnapshot cached(int key) {
        synchronized (cacheLock) {
            if (cachingMode == LynxModule.BulkCachingMode.AUTO) {
                if (cache == null || readSinceRefresh[key]) {
                    cache = takeSnapshot();
                    java.util.Arrays.fill(readSinceRefresh, false);
                }
                readSinceRefresh[key] = true;
            } else if (cache == null) {
                cache = takeSnapshot();
            }
            return cache;
        }
    }

    public int readMotorPosition(int port) {
        if (cachingMode == LynxModule.BulkCachingMode.OFF) {
            Latency.command();
            return motors[port].encoderPosition();
        }
        return cached(port).positions[port];
    }

    public double readMotorVelocity(int port) {
        if (cachingMode == LynxModule.BulkCachingMode.OFF) {
            Latency.command();
            return motors[port].velocityTicksPerSecond;
        }
        return cached(MOTORS + port).velocities[port];
    }

    public boolean readMotorBusy(int port) {
        if (cachingMode == LynxModule.BulkCachingMode.OFF) {
            Latency.command();
            return motors[port].isBusyNow();
        }
        return cached(2 * MOTORS + port).busy[port];
    }

    public boolean readDigital(int channel) {
        if (cachingMode == LynxModule.BulkCachingMode.OFF) {
            Latency.command();
            return digital[channel].level();
        }
        return cached(3 * MOTORS + channel).digital[channel];
    }

    public void powerCycle() {
        setBulkCachingMode(LynxModule.BulkCachingMode.OFF);
        ledColor = 0x00FF00;
        for (MotorPort p : motors) if (p != null) p.powerCycle();
        for (ServoPort p : servos) if (p != null) p.powerCycle();
    }
}
