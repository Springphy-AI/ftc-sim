package ftcsim.physics;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.PIDFCoefficients;

import ftcsim.config.MotorModels;
import ftcsim.hal.MotorPort;

/**
 * One motor port: the REV hub's motor-controller firmware (run modes, velocity PIDF,
 * RUN_TO_POSITION) driving a DC gearmotor modelled by its datasheet torque-speed line.
 * All shaft quantities are at the gearbox output.
 */
public final class MotorUnit {
    /** PIDF integral and derivative terms are per hub control cycle, as on the REV hub. */
    private static final double CONTROL_CYCLE_S = 0.05;
    private static final double NOMINAL_VOLTS = 12.0;

    public final MotorPort port;
    public final MotorModels.Spec spec;

    private double angle = 0;          // output shaft, rad
    private double integral = 0;
    private double lastError = 0;
    private int seenModeGeneration = -1;
    private double lastCurrent = 0;
    private double lastBatteryCurrent = 0;

    public MotorUnit(MotorPort port, MotorModels.Spec spec) {
        this.port = port;
        this.spec = spec;
    }

    public double angle() {
        return angle;
    }

    /** The motor's own current (what the hub reports for it). */
    public double currentAmps() {
        return lastCurrent;
    }

    /**
     * What the motor draws from the battery. The hub's H-bridge passes duty x the winding current
     * (and the motor's losses), so a braking motor, whose current only circulates through the
     * bridge, draws nothing; a motor fed back faster than its duty would push current back.
     */
    public double batteryAmps() {
        return lastBatteryCurrent;
    }

    /**
     * Output-shaft torque (N m) for the present shaft speed, running the firmware for one step.
     * {@code omega} is the output-shaft speed in rad/s in the motor's own (FORWARD) direction.
     */
    public double torque(double omega, double batteryVolts, double dt) {
        double duty = firmware(omega, dt);
        double stall = spec.stallTorqueNm();
        double free = spec.freeSpeedRadPerSec();
        double tau;
        double current;
        double battery;
        if (Double.isNaN(duty)) {
            // Coasting (FLOAT or disabled): windings open, only gearbox friction.
            tau = 0;
            current = 0;
            battery = 0;
            port.appliedDuty = 0;
        } else {
            double d = clamp(duty, -1, 1);
            double volts = d * batteryVolts;
            tau = stall * (volts / NOMINAL_VOLTS) - (stall / free) * omega;
            double winding = spec.stallCurrentA() * (volts / NOMINAL_VOLTS - omega / free);
            current = Math.abs(winding);
            battery = d * winding;
            if (volts != 0) {
                current += spec.freeCurrentA();
                battery += Math.abs(d) * spec.freeCurrentA();
            }
            port.appliedDuty = d;
        }
        // Gearbox friction: a little viscous drag plus smoothed Coulomb friction.
        tau -= 0.01 * (stall / free) * omega + 0.01 * stall * Math.tanh(omega / 0.5);
        lastCurrent = current;
        lastBatteryCurrent = battery;
        port.currentAmps = current;
        return tau;
    }

    /** Advances the encoder with the shaft speed the physics produced. */
    public void integrate(double omega, double dt) {
        angle += omega * dt;
        port.rawTicks = angle * spec.ticksPerRadian();
        port.velocityTicksPerSecond = omega * spec.ticksPerRadian();
    }

    /** Duty cycle in [-1, 1], or NaN to let the motor coast. */
    private double firmware(double omega, double dt) {
        if (port.modeGeneration != seenModeGeneration) {
            seenModeGeneration = port.modeGeneration;
            integral = 0;
            lastError = 0;
        }
        if (!port.enabled) return Double.NaN;
        DcMotor.RunMode mode = port.mode;
        double maxTps = port.configuredType.getAchieveableMaxTicksPerSecond();
        double measuredTps = omega * spec.ticksPerRadian();
        boolean velocity = port.velocityCommand;
        double power = port.power;
        switch (mode) {
            case STOP_AND_RESET_ENCODER:
                return zeroPower();
            case RUN_TO_POSITION: {
                if (!port.targetPositionSet) return zeroPower();
                double limit = velocity ? Math.abs(port.velocityTarget) : Math.abs(power) * maxTps;
                if (limit == 0) return zeroPower();
                double error = port.targetPosition - port.encoderPosition();
                double target = clamp(port.positionPidf.p * error, -limit, limit);
                return pidf(port.velocityPidf, target, measuredTps, dt);
            }
            case RUN_USING_ENCODER: {
                double target = velocity ? port.velocityTarget : power * maxTps;
                if (!velocity && power == 0) return zeroPower();
                return pidf(port.velocityPidf, target, measuredTps, dt);
            }
            case RUN_WITHOUT_ENCODER:
            default:
                // (no velocity command here: setVelocity switches to RUN_USING_ENCODER, and a mode change drops it)
                return power == 0 ? zeroPower() : power;
        }
    }

    private double zeroPower() {
        integral = 0;
        return port.zeroPowerBehavior == DcMotor.ZeroPowerBehavior.FLOAT ? Double.NaN : 0.0;
    }

    private double pidf(PIDFCoefficients k, double target, double measured, double dt) {
        double error = target - measured;
        double cycles = dt / CONTROL_CYCLE_S;
        double derivative = (error - lastError) / Math.max(cycles, 1e-9);
        lastError = error;
        double withoutNewIntegral = k.p * error + k.i * integral + k.d * derivative + k.f * target;
        // Anti-windup: don't integrate while the output is already saturated in the error's direction,
        // and never let the integral alone command more than half power.
        boolean saturated = Math.abs(withoutNewIntegral) >= 32767 && Math.signum(withoutNewIntegral) == Math.signum(error);
        if (!saturated) integral += error * cycles;
        if (k.i > 0) integral = clamp(integral, -0.5 * 32767 / k.i, 0.5 * 32767 / k.i);
        double out = k.p * error + k.i * integral + k.d * derivative + k.f * target;
        return clamp(out / 32767.0, -1, 1);
    }

    /** Power cycle: the encoder keeps counting from where the shaft is (offset handled by the port). */
    public void resetState() {
        integral = 0;
        lastError = 0;
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
