package ftcsim.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import com.qualcomm.hardware.rev.RevBlinkinLedDriver;
import com.qualcomm.robotcore.hardware.AnalogInput;
import com.qualcomm.robotcore.hardware.DigitalChannel;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.LED;
import com.qualcomm.robotcore.hardware.PwmControl;

import ftcsim.config.RobotConfig;
import ftcsim.hal.AnalogPort;
import ftcsim.hal.DigitalPort;
import ftcsim.hal.HalSession;
import ftcsim.hal.MotorPort;
import ftcsim.hal.ServoPort;
import ftcsim.hal.SimAnalogInputController;
import ftcsim.hal.SimDigitalChannelController;
import ftcsim.hal.SimHub;
import ftcsim.hal.SimServoController;
import ftcsim.physics.MotorUnit;
import ftcsim.physics.ServoUnit;

/**
 * Devices on hub ports that the simulator models only as signals, driven by FIRST's own device
 * classes (AnalogInput, LED, RevBlinkinLedDriver):
 * <ul>
 * <li>{@code "analog"}: an analog input (0 to 3.3 V): a constant {@code "volts"}, or with
 * {@code "encoder_of"} the absolute angle of a servo's horn or a motor's encoder shaft, 0 to 3.3 V
 * for one turn (as an absolute encoder such as an Axon servo's or a REV Through Bore's analog
 * output).</li>
 * <li>{@code "led"}: an LED on a digital channel; FIRST's LED lights it by driving the line low.</li>
 * <li>{@code "blinkin"}: a REV Blinkin LED driver on a servo port; its pattern is the pulse width
 * FIRST's driver sends (1005 µs + 10 µs per pattern, in the Blinkin's 500-2500 µs range).</li>
 * </ul>
 * What each shows is in the Driver Station's device readings.
 */
final class PortDevices {

    /** The Blinkin's servo type (its @ServoType in FIRST's Hardware module). */
    private static final PwmControl.PwmRange BLINKIN_RANGE = new PwmControl.PwmRange(500, 2500);
    private static final RevBlinkinLedDriver.BlinkinPattern[] PATTERNS = RevBlinkinLedDriver.BlinkinPattern.values();

    private record Analog(RobotConfig.Device device, AnalogPort port) {
    }

    private record Led(RobotConfig.Device device, DigitalPort port) {
    }

    private record Blinkin(RobotConfig.Device device, ServoPort port) {
    }

    private final List<Analog> analogs = new ArrayList<>();
    private final List<Led> leds = new ArrayList<>();
    private final List<Blinkin> blinkins = new ArrayList<>();

    void analog(RobotConfig.Device d, AnalogPort port) {
        port.volts = d.volts;
        analogs.add(new Analog(d, port));
    }

    void led(RobotConfig.Device d, DigitalPort port) {
        leds.add(new Led(d, port));
    }

    void blinkin(RobotConfig.Device d, ServoPort port) {
        port.pwmRange = BLINKIN_RANGE;
        blinkins.add(new Blinkin(d, port));
    }

    /** Builds FIRST's devices for one OpMode run, as the Robot Controller does at INIT. */
    void addTo(HardwareMap map, HalSession session, Function<SimHub, SimServoController> servoControllers,
               BiConsumer<RobotConfig.Device, HardwareDevice> put) {
        Map<SimHub, AnalogPort[]> analogPorts = new LinkedHashMap<>();
        for (Analog a : analogs) analogPorts.computeIfAbsent(a.port.hub, h -> new AnalogPort[RobotRig.ANALOG_PER_HUB])[a.port.channel] = a.port;
        Map<SimHub, SimAnalogInputController> analogControllers = new LinkedHashMap<>();
        for (Analog a : analogs) {
            SimAnalogInputController c = analogControllers.computeIfAbsent(a.port.hub, h -> new SimAnalogInputController(h, analogPorts.get(h)));
            AnalogInput input = new AnalogInput(c, a.port.channel);
            put.accept(a.device, input);
            map.analogInput.put(a.device.name, input);
        }
        Map<SimHub, SimDigitalChannelController> digitalControllers = new LinkedHashMap<>();
        for (Led l : leds) {
            SimDigitalChannelController c = digitalControllers.computeIfAbsent(l.port.hub, h -> new SimDigitalChannelController(h, session));
            LED led = new LED(c, l.port.channel);
            led.resetDeviceConfigurationForOpMode();   // as OpModeManagerImpl.resetHardwareForOpMode: an output
            put.accept(l.device, led);
            map.led.put(l.device.name, led);
        }
        for (Blinkin b : blinkins) {
            b.port.pwmRange = BLINKIN_RANGE;
            put.accept(b.device, new RevBlinkinLedDriver(servoControllers.apply(b.port.hub), b.port.port));
        }
    }

    /** Analog inputs that measure an angle follow it. */
    void step(Map<String, MotorUnit> motors, List<ServoUnit> servos) {
        for (Analog a : analogs) {
            String of = a.device.encoderOf;
            if (of == null) continue;
            double degrees = Double.NaN;
            for (ServoUnit s : servos) if (s.port.name.equals(of)) degrees = s.port.angleDeg;
            MotorUnit m = motors.get(of);
            if (m != null) {
                MotorPort p = m.port;
                degrees = p.encoderPosition() * 360.0 / p.physicalType.getTicksPerRev();
            }
            if (Double.isNaN(degrees)) continue;
            double turn = ((degrees % 360) + 360) % 360;
            a.port.volts = SimAnalogInputController.MAX_VOLTS * turn / 360;
        }
    }

    /** What the Driver Station's device panel shows for each. */
    void readings(Map<String, Object> out) {
        for (Analog a : analogs) out.put(a.device.name, String.format(Locale.US, "%.3f V", a.port.volts));
        for (Led l : leds) {
            boolean on = l.port.mode == DigitalChannel.Mode.OUTPUT && !l.port.outputLevel;
            out.put(l.device.name, on ? "LED on" : "LED off");
        }
        for (Blinkin b : blinkins) out.put(b.device.name, "Blinkin " + pattern(b.port));
    }

    /** The pattern a Blinkin shows for the pulse it gets, or "off" before any. */
    static String pattern(ServoPort port) {
        double pulse = port.pulseWidthUs();
        if (pulse <= 0) return "off";
        int i = (int) Math.round((pulse - 1005) / 10.0);
        return PATTERNS[Math.max(0, Math.min(PATTERNS.length - 1, i))].name();
    }
}
