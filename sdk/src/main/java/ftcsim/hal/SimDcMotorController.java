package ftcsim.hal;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;

import com.qualcomm.robotcore.exception.TargetPositionNotSetException;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorControllerEx;
import com.qualcomm.robotcore.hardware.PIDCoefficients;
import com.qualcomm.robotcore.hardware.PIDFCoefficients;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;
import com.qualcomm.robotcore.util.Range;

/** The motor controller of one simulated hub, as seen by one OpMode run. */
public final class SimDcMotorController implements DcMotorControllerEx {
    private final SimHub hub;
    private final HalSession session;

    public SimDcMotorController(SimHub hub, HalSession session) {
        this.hub = hub;
        this.session = session;
    }

    private MotorPort port(int motor) {
        MotorPort p = (motor >= 0 && motor < SimHub.MOTORS) ? hub.motors[motor] : null;
        if (p == null) {
            throw new IllegalArgumentException(String.format("motor port %d of %s is not configured", motor, hub.name));
        }
        return p;
    }

    private boolean writable() {
        return session.isActive();
    }

    @Override
    public void setMotorType(int motor, MotorConfigurationType motorType) {
        if (!writable()) return;
        port(motor).configuredType = motorType.clone();
    }

    @Override
    public MotorConfigurationType getMotorType(int motor) {
        return port(motor).configuredType.clone();
    }

    @Override
    public void setMotorMode(int motor, DcMotor.RunMode mode) {
        MotorPort p = port(motor);
        if (!writable()) return;
        mode = mode.migrate();
        Latency.command();
        if (mode == DcMotor.RunMode.RUN_TO_POSITION && !p.targetPositionSet) {
            throw new TargetPositionNotSetException();
        }
        if (mode == DcMotor.RunMode.STOP_AND_RESET_ENCODER) {
            p.resetEncoder();
        }
        if (p.mode != mode) {
            p.mode = mode;
            p.modeGeneration++;
            // The hub's controller sends the last power again after a mode change, so a velocity
            // command doesn't carry over into the new mode.
            p.velocityCommand = false;
        }
    }

    @Override
    public DcMotor.RunMode getMotorMode(int motor) {
        return port(motor).mode;
    }

    @Override
    public void setMotorPower(int motor, double power) {
        MotorPort p = port(motor);
        if (!writable()) return;
        power = Range.clip(power, -1.0, 1.0);
        if (p.velocityCommand || p.power != power) {
            Latency.command();
        }
        p.velocityCommand = false;
        p.power = power;
    }

    @Override
    public double getMotorPower(int motor) {
        MotorPort p = port(motor);
        if (p.velocityCommand) {
            double max = p.configuredType.getAchieveableMaxTicksPerSecond();
            return max > 0 ? Range.clip(p.velocityTarget / max, -1, 1) : 0;
        }
        return p.power;
    }

    @Override
    public boolean isBusy(int motor) {
        port(motor);
        return hub.readMotorBusy(motor);
    }

    @Override
    public void setMotorZeroPowerBehavior(int motor, DcMotor.ZeroPowerBehavior zeroPowerBehavior) {
        if (zeroPowerBehavior == DcMotor.ZeroPowerBehavior.UNKNOWN) {
            throw new IllegalArgumentException("zeroPowerBehavior may not be UNKNOWN");
        }
        MotorPort p = port(motor);
        if (!writable()) return;
        if (p.zeroPowerBehavior != zeroPowerBehavior) {
            Latency.command();
            p.zeroPowerBehavior = zeroPowerBehavior;
        }
    }

    @Override
    public DcMotor.ZeroPowerBehavior getMotorZeroPowerBehavior(int motor) {
        return port(motor).zeroPowerBehavior;
    }

    @Override
    public boolean getMotorPowerFloat(int motor) {
        MotorPort p = port(motor);
        return p.zeroPowerBehavior == DcMotor.ZeroPowerBehavior.FLOAT && p.power == 0;
    }

    @Override
    public void setMotorTargetPosition(int motor, int position) {
        setMotorTargetPosition(motor, position, port(motor).targetTolerance);
    }

    @Override
    public void setMotorTargetPosition(int motor, int position, int tolerance) {
        MotorPort p = port(motor);
        if (!writable()) return;
        Latency.command();
        p.targetTolerance = Math.max(0, tolerance);
        p.targetPosition = position;
        p.targetPositionSet = true;
    }

    @Override
    public int getMotorTargetPosition(int motor) {
        return port(motor).targetPosition;
    }

    @Override
    public int getMotorCurrentPosition(int motor) {
        port(motor);
        return hub.readMotorPosition(motor);
    }

    @Override
    public void resetDeviceConfigurationForOpMode(int motor) {
    }

    @Override
    public void setMotorEnable(int motor) {
        if (!writable()) return;
        Latency.command();
        port(motor).enabled = true;
    }

    @Override
    public void setMotorDisable(int motor) {
        if (!writable()) return;
        Latency.command();
        port(motor).enabled = false;
    }

    @Override
    public boolean isMotorEnabled(int motor) {
        return port(motor).enabled;
    }

    @Override
    public void setMotorVelocity(int motor, double ticksPerSecond) {
        MotorPort p = port(motor);
        if (!writable()) return;
        // As the hub's controller does (LynxDcMotorController.setMotorVelocity): a velocity is held
        // with the encoder, so a motor in RUN_WITHOUT_ENCODER or STOP_AND_RESET_ENCODER switches to
        // RUN_USING_ENCODER first, and the command enables a disabled motor.
        if (p.mode != DcMotor.RunMode.RUN_USING_ENCODER && p.mode != DcMotor.RunMode.RUN_TO_POSITION) {
            setMotorMode(motor, DcMotor.RunMode.RUN_USING_ENCODER);
        }
        Latency.command();
        p.velocityTarget = ticksPerSecond;
        p.velocityCommand = true;
        p.enabled = true;
    }

    @Override
    public void setMotorVelocity(int motor, double angularRate, AngleUnit unit) {
        double degreesPerSecond = unit.getUnnormalized().toDegrees(angularRate);
        setMotorVelocity(motor, degreesPerSecond / 360.0 * port(motor).configuredType.getTicksPerRev());
    }

    @Override
    public double getMotorVelocity(int motor) {
        port(motor);
        return hub.readMotorVelocity(motor);
    }

    @Override
    public double getMotorVelocity(int motor, AngleUnit unit) {
        double ticksPerSecond = getMotorVelocity(motor);
        double degreesPerSecond = ticksPerSecond / port(motor).configuredType.getTicksPerRev() * 360.0;
        return unit.getUnnormalized().fromDegrees(degreesPerSecond);
    }

    @Override
    @Deprecated
    public void setPIDCoefficients(int motor, DcMotor.RunMode mode, PIDCoefficients pid) {
        PIDFCoefficients existing = getPIDFCoefficients(motor, mode);
        setPIDFCoefficients(motor, mode, new PIDFCoefficients(pid.p, pid.i, pid.d, existing.f));
    }

    @Override
    public void setPIDFCoefficients(int motor, DcMotor.RunMode mode, PIDFCoefficients pidf) throws UnsupportedOperationException {
        MotorPort p = port(motor);
        mode = mode.migrate();
        if (!writable()) return;
        Latency.command();
        if (mode == DcMotor.RunMode.RUN_USING_ENCODER) {
            p.velocityPidf = new PIDFCoefficients(pidf);
        } else if (mode == DcMotor.RunMode.RUN_TO_POSITION) {
            p.positionPidf = new PIDFCoefficients(pidf);
        } else {
            throw new UnsupportedOperationException(String.format("setPIDFCoefficients(%s) is not supported", mode));
        }
    }

    @Override
    @Deprecated
    public PIDCoefficients getPIDCoefficients(int motor, DcMotor.RunMode mode) {
        PIDFCoefficients pidf = getPIDFCoefficients(motor, mode);
        return new PIDCoefficients(pidf.p, pidf.i, pidf.d);
    }

    @Override
    public PIDFCoefficients getPIDFCoefficients(int motor, DcMotor.RunMode mode) {
        MotorPort p = port(motor);
        mode = mode.migrate();
        if (mode == DcMotor.RunMode.RUN_TO_POSITION) {
            return new PIDFCoefficients(p.positionPidf);
        }
        return new PIDFCoefficients(p.velocityPidf);
    }

    @Override
    public double getMotorCurrent(int motor, CurrentUnit unit) {
        MotorPort p = port(motor);
        Latency.command();
        return unit.convert(p.currentAmps, CurrentUnit.AMPS);
    }

    @Override
    public double getMotorCurrentAlert(int motor, CurrentUnit unit) {
        return unit.convert(port(motor).currentAlertAmps, CurrentUnit.AMPS);
    }

    @Override
    public void setMotorCurrentAlert(int motor, double current, CurrentUnit unit) {
        if (!writable()) return;
        port(motor).currentAlertAmps = CurrentUnit.AMPS.convert(current, unit);
    }

    @Override
    public boolean isMotorOverCurrent(int motor) {
        MotorPort p = port(motor);
        Latency.command();
        return p.currentAmps > p.currentAlertAmps;
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.Lynx;
    }

    @Override
    public String getDeviceName() {
        return hub.name + " Motor Controller";
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
