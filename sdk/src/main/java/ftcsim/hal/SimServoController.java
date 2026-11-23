package ftcsim.hal;

import com.qualcomm.robotcore.hardware.PwmControl;
import com.qualcomm.robotcore.hardware.ServoControllerEx;
import com.qualcomm.robotcore.util.Range;

/** The servo controller of one simulated hub, as seen by one OpMode run. */
public final class SimServoController implements ServoControllerEx {
    private final SimHub hub;
    private final HalSession session;

    public SimServoController(SimHub hub, HalSession session) {
        this.hub = hub;
        this.session = session;
    }

    private ServoPort port(int servo) {
        ServoPort p = (servo >= 0 && servo < SimHub.SERVOS) ? hub.servos[servo] : null;
        if (p == null) {
            throw new IllegalArgumentException(String.format("servo port %d of %s is not configured", servo, hub.name));
        }
        return p;
    }

    @Override
    public void pwmEnable() {
        if (!session.isActive()) return;
        Latency.command();
        for (ServoPort p : hub.servos) if (p != null && !Double.isNaN(p.position)) p.pwmEnabled = true;
    }

    @Override
    public void pwmDisable() {
        if (!session.isActive()) return;
        Latency.command();
        for (ServoPort p : hub.servos) if (p != null) p.pwmEnabled = false;
    }

    @Override
    public PwmStatus getPwmStatus() {
        boolean anyOn = false, anyOff = false;
        for (ServoPort p : hub.servos) {
            if (p == null) continue;
            if (p.pwmEnabled) anyOn = true;
            else anyOff = true;
        }
        if (anyOn && anyOff) return PwmStatus.MIXED;
        return anyOn ? PwmStatus.ENABLED : PwmStatus.DISABLED;
    }

    @Override
    public void setServoPosition(int servo, double position) {
        ServoPort p = port(servo);
        // As the hub's controller (validateApiServoPosition): NaN or a position outside [0, 1] is an
        // error. (Servo.setPosition clips to [0, 1] before it gets here, so NaN is what reaches it.)
        if (!(position >= 0.0 && position <= 1.0)) {
            throw new IllegalArgumentException(String.format("illegal servo position %f; must be in interval [%f,%f]", position, 0.0, 1.0));
        }
        if (!session.isActive()) return;
        if (!p.pwmEnabled || p.position != position) {
            Latency.command();
        }
        p.rawPulseUs = 0;
        p.position = position;
        p.pwmEnabled = true;
    }

    /**
     * As LynxServoController.setPulseWidth: the pulse is sent as it is (LynxSetServoPulseWidthCommand
     * takes 1..65535 µs), outside the PWM range too, and turns the port's PWM on.
     */
    @Override
    public void setPulseWidth(int servo, double usPulse) {
        ServoPort p = port(servo);
        int pulse = (int) usPulse;
        if (pulse < 1 || pulse > 65535) {
            throw new IllegalArgumentException(String.format("illegal pulse width: %d", pulse));
        }
        if (!session.isActive()) return;
        Latency.command();
        PwmControl.PwmRange r = p.pwmRange;
        p.rawPulseUs = pulse;
        p.position = Range.clip(Range.scale(pulse, r.usPulseLower, r.usPulseUpper, 0.0, 1.0), 0.0, 1.0);
        p.pwmEnabled = true;
    }

    /** As LynxServoController: the last pulse sent, a position's as the whole microseconds it was sent as. */
    @Override
    public double getPulseWidth(int servo) {
        ServoPort p = port(servo);
        if (p.rawPulseUs != 0) return p.rawPulseUs;
        if (Double.isNaN(p.position)) return 0;
        PwmControl.PwmRange r = p.pwmRange;
        return (int) Range.clip(Range.scale(p.position, 0.0, 1.0, r.usPulseLower, r.usPulseUpper), 1.0, 65535.0);
    }

    /** The last commanded position; 0 before the first one, as the hub reads it from a pulse width of 0. */
    @Override
    public double getServoPosition(int servo) {
        double position = port(servo).position;
        return Double.isNaN(position) ? 0.0 : position;
    }

    @Override
    public void forgetLastKnownPosition(int servo) {
        port(servo);
    }

    @Override
    public void setServoPwmRange(int servo, PwmControl.PwmRange range) {
        if (!session.isActive()) return;
        Latency.command();
        port(servo).pwmRange = range;
    }

    @Override
    public PwmControl.PwmRange getServoPwmRange(int servo) {
        return port(servo).pwmRange;
    }

    @Override
    public void setServoPwmEnable(int servo) {
        if (!session.isActive()) return;
        Latency.command();
        ServoPort p = port(servo);
        if (!Double.isNaN(p.position)) p.pwmEnabled = true;
    }

    @Override
    public void setServoPwmDisable(int servo) {
        if (!session.isActive()) return;
        Latency.command();
        port(servo).pwmEnabled = false;
    }

    @Override
    public boolean isServoPwmEnabled(int servo) {
        return port(servo).pwmEnabled;
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.Lynx;
    }

    @Override
    public String getDeviceName() {
        return hub.name + " Servo Controller";
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
