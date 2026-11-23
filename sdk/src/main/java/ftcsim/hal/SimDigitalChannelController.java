package ftcsim.hal;

import com.qualcomm.robotcore.hardware.DigitalChannel;
import com.qualcomm.robotcore.hardware.DigitalChannelController;

/** The digital channels of one simulated hub, for devices that take a controller (FIRST's LED). */
public final class SimDigitalChannelController implements DigitalChannelController {
    private final SimHub hub;
    private final HalSession session;

    public SimDigitalChannelController(SimHub hub, HalSession session) {
        this.hub = hub;
        this.session = session;
    }

    private DigitalPort port(int channel) {
        DigitalPort p = channel >= 0 && channel < SimHub.DIGITAL ? hub.digital[channel] : null;
        if (p == null) {
            throw new IllegalArgumentException(String.format("digital channel %d of %s is not configured", channel, hub.name));
        }
        return p;
    }

    @Override
    public DigitalChannel.Mode getDigitalChannelMode(int channel) {
        return port(channel).mode;
    }

    @Override
    public void setDigitalChannelMode(int channel, DigitalChannel.Mode mode) {
        DigitalPort p = port(channel);
        if (!session.isActive()) return;
        Latency.command();
        p.mode = mode;
    }

    @Override
    public boolean getDigitalChannelState(int channel) {
        port(channel);
        return hub.readDigital(channel);
    }

    @Override
    public void setDigitalChannelState(int channel, boolean state) {
        DigitalPort p = port(channel);
        if (!session.isActive()) return;
        if (p.mode == DigitalChannel.Mode.OUTPUT) {
            Latency.command();
            p.outputLevel = state;
        }
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.Lynx;
    }

    @Override
    public String getDeviceName() {
        return hub.name + " Digital Channel Controller";
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
