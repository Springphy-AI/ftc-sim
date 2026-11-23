package ftcsim.hal;

import com.qualcomm.robotcore.hardware.AnalogInputController;
import com.qualcomm.robotcore.util.SerialNumber;

/** The analog inputs of one simulated hub, for FIRST's AnalogInput. A hub reads 0 to 3.3 V. */
public final class SimAnalogInputController implements AnalogInputController {
    public static final double MAX_VOLTS = 3.3;

    private final SimHub hub;
    private final AnalogPort[] ports;

    /** @param ports the hub's analog ports by channel (null where nothing is configured) */
    public SimAnalogInputController(SimHub hub, AnalogPort[] ports) {
        this.hub = hub;
        this.ports = ports;
    }

    @Override
    public double getAnalogInputVoltage(int channel) {
        if (channel < 0 || channel >= ports.length) {
            throw new IllegalArgumentException(String.format("channel %d is invalid; valid channels are 0..%d", channel, ports.length - 1));
        }
        Latency.command();
        AnalogPort p = ports[channel];
        return p == null ? 0 : Math.max(0, Math.min(MAX_VOLTS, p.volts));
    }

    @Override
    public double getMaxAnalogInputVoltage() {
        return MAX_VOLTS;
    }

    @Override
    public SerialNumber getSerialNumber() {
        return hub.serialNumber;
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.Lynx;
    }

    @Override
    public String getDeviceName() {
        return hub.name + " Analog Input Controller";
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
}
