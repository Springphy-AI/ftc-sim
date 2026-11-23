package ftcsim.hal;

/** An analog input on a hub (0 to 3.3 V). Its voltage is set by the simulator. */
public final class AnalogPort {
    public final String name;
    public final SimHub hub;
    public final int channel;

    public volatile double volts = 0;

    public AnalogPort(String name, SimHub hub, int channel) {
        this.name = name;
        this.hub = hub;
        this.channel = channel;
    }
}
