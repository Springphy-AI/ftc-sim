package ftcsim.physics;

import ftcsim.config.RobotConfig;
import ftcsim.hal.ServoPort;

/**
 * A hobby servo on a hub servo port. A positional servo slews toward the commanded angle at its
 * rated speed; a continuous-rotation servo turns at a speed proportional to its command.
 * Default PWM range 600-2400 µs maps to the full rated range.
 */
public final class ServoUnit {
    public final RobotConfig.Device device;
    public final ServoPort port;
    private double angleDeg = 0;

    public ServoUnit(RobotConfig.Device device, ServoPort port) {
        this.device = device;
        this.port = port;
    }

    public void step(double dt) {
        double pulse = port.pulseWidthUs();
        if (pulse == 0) {   // limp: PWM off, or no pulse sent yet
            port.speedDegPerSec = 0;
            port.angleDeg = angleDeg;
            return;
        }
        if (port.continuous) {
            double speed = (pulse - 1500.0) / 900.0;       // -1..1 for the default range
            if (Math.abs(speed) < 0.02) speed = 0;         // deadband around neutral
            speed = Math.max(-1, Math.min(1, speed));
            double degPerSec = speed * device.rpm * 6.0;
            angleDeg += degPerSec * dt;
            port.speedDegPerSec = degPerSec;
        } else {
            // A pulse outside the range (setPulseWidth) takes the horn only as far as it goes.
            double half = device.rangeDeg / 2.0;
            double target = Math.max(-half, Math.min(half, (pulse - 1500.0) / 900.0 * half));
            double maxStep = device.speedDegPerSec * dt;
            double delta = Math.max(-maxStep, Math.min(maxStep, target - angleDeg));
            angleDeg += delta;
            port.speedDegPerSec = delta / dt;
        }
        port.angleDeg = angleDeg;
    }

    public double angleDeg() {
        return angleDeg;
    }

    /** Where the horn is (degrees from center) when something other than the servo moved it. */
    public void setAngleDeg(double deg) {
        angleDeg = deg;
        port.angleDeg = deg;
    }
}
