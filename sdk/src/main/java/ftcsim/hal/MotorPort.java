package ftcsim.hal;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.PIDFCoefficients;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;

/**
 * A motor port on a hub, in the motor's raw (FORWARD) frame. Robot code writes the command
 * fields through the motor controller; the physics thread runs the hub's motor controller
 * against them and writes back the encoder, velocity and current.
 */
public final class MotorPort {
    public final String name;
    public final SimHub hub;
    public final int port;
    /** The motor model actually installed (drives the physics and the encoder). */
    public final MotorConfigurationType physicalType;

    // ---- commands (robot code -> physics) ----
    public volatile double power = 0;
    public volatile DcMotor.RunMode mode = DcMotor.RunMode.RUN_WITHOUT_ENCODER;
    public volatile DcMotor.ZeroPowerBehavior zeroPowerBehavior = DcMotor.ZeroPowerBehavior.BRAKE;
    public volatile int targetPosition = 0;
    public volatile boolean targetPositionSet = false;
    public volatile int targetTolerance = 5;
    public volatile double velocityTarget = 0;
    public volatile boolean velocityCommand = false;
    public volatile boolean enabled = true;
    public volatile PIDFCoefficients velocityPidf;
    public volatile PIDFCoefficients positionPidf;
    public volatile double currentAlertAmps = 5.0;
    /** Motor type as configured/overridden by robot code; scales RUN_USING_ENCODER targets. */
    public volatile MotorConfigurationType configuredType;
    /** Incremented on every mode change so the controller can reset its integrators. */
    public volatile int modeGeneration = 0;

    // ---- measurements (physics -> robot code) ----
    public volatile double rawTicks = 0;
    public volatile double encoderOffset = 0;
    public volatile double velocityTicksPerSecond = 0;
    public volatile double currentAmps = 0;
    /** Fraction of battery voltage currently applied, for the UI. */
    public volatile double appliedDuty = 0;

    public MotorPort(String name, SimHub hub, int port, MotorConfigurationType physicalType) {
        this.name = name;
        this.hub = hub;
        this.port = port;
        this.physicalType = physicalType;
        this.configuredType = physicalType.clone();
        this.velocityPidf = defaultVelocityPidf(physicalType);
        this.positionPidf = defaultPositionPidf();
    }

    /** The hub's RUN_TO_POSITION coefficients until robot code sets its own. */
    public static PIDFCoefficients defaultPositionPidf() {
        return new PIDFCoefficients(10, 0, 0, 0);
    }

    public static PIDFCoefficients defaultVelocityPidf(MotorConfigurationType type) {
        double maxTps = type.getMaxRPM() * type.getTicksPerRev() / 60.0;
        return new PIDFCoefficients(10, 3, 0, 32767.0 / maxTps);
    }

    /** Encoder count as the hub reports it. */
    public int encoderPosition() {
        return (int) Math.round(rawTicks - encoderOffset);
    }

    public void resetEncoder() {
        encoderOffset = rawTicks;
    }

    public boolean isBusyNow() {
        if (mode != DcMotor.RunMode.RUN_TO_POSITION || !targetPositionSet) return false;
        return Math.abs(targetPosition - encoderPosition()) > targetTolerance;
    }

    /** Called by the engine when the robot is power-cycled (encoders and modes reset). */
    public void powerCycle() {
        power = 0;
        mode = DcMotor.RunMode.RUN_WITHOUT_ENCODER;
        zeroPowerBehavior = DcMotor.ZeroPowerBehavior.BRAKE;
        targetPosition = 0;
        targetPositionSet = false;
        targetTolerance = 5;
        velocityTarget = 0;
        velocityCommand = false;
        enabled = true;
        velocityPidf = defaultVelocityPidf(physicalType);
        positionPidf = defaultPositionPidf();
        configuredType = physicalType.clone();
        encoderOffset = rawTicks;
        modeGeneration++;
    }
}
