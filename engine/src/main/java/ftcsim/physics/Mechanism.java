package ftcsim.physics;

import ftcsim.config.RobotConfig;

/**
 * A non-drivetrain motor and what it turns. Without a CAD joint it is a one-degree-of-freedom
 * mechanism integrated on its own (a roller, flywheel, arm with gravity and hard stops, or slide),
 * since it only moves relative to the robot. With a CAD joint (an arm or slide that is a rigid
 * body in the physics) the motor pushes on that joint and the encoder follows it.
 */
public final class Mechanism {
    private static final double G = 9.81;

    public final RobotConfig.Device device;
    public final MotorUnit motor;
    private final RobotConfig.Load load;
    private final double gear;          // external reduction, motor output : mechanism
    private final double inertiaAtMotor;
    /** The CAD joint this motor drives, or null. */
    private final RobotBody.LinkBody joint;
    /** Motor output radians per joint unit (radian or meter). */
    private final double jointRatio;
    /** A CAD slide: the motor's rotor as mass at the slide (kg). */
    private final double rotorMass;

    private double omegaMotor = 0;      // rad/s at the motor output, FORWARD direction
    private double position;            // mechanism coordinate: rad (rotary/arm) or m (slide)

    public Mechanism(RobotConfig.Device device, MotorUnit motor) {
        this(device, motor, null, Double.NaN);
    }

    /**
     * @param joint       the CAD joint the motor drives, or null
     * @param cadInertia  for a CAD roller or flywheel: its moment of inertia about its axis
     *                    (kg m²), which replaces the robot file's {@code load.inertia}; NaN if none
     */
    public Mechanism(RobotConfig.Device device, MotorUnit motor, RobotBody.LinkBody joint, double cadInertia) {
        this.device = device;
        this.motor = motor;
        this.load = device.load;
        this.gear = device.externalGearRatio <= 0 ? 1 : device.externalGearRatio;
        this.joint = joint;
        if (joint != null) {
            jointRatio = joint.revolute() ? gear : gear / joint.group.spoolRadius;
            inertiaAtMotor = motor.spec.reflectedInertia();
            rotorMass = inertiaAtMotor * jointRatio * jointRatio;
            position = joint.position();
            return;
        }
        jointRatio = 1;
        rotorMass = 0;
        double jLoad;
        switch (load.kind) {
            case "arm":
                jLoad = load.massKg * load.lengthM * load.lengthM / 3.0;   // uniform rod about its end
                position = Math.toRadians(load.startDeg);
                break;
            case "slide": {
                double r = load.spoolDiameterM / 2;
                jLoad = load.massKg * r * r;
                position = 0;
                break;
            }
            default:
                jLoad = Double.isNaN(cadInertia) ? load.inertia : cadInertia;
                position = 0;
        }
        this.inertiaAtMotor = motor.spec.reflectedInertia() + jLoad / (gear * gear);
    }

    /** Runs the motor for one step: before the world steps. */
    public void step(double batteryVolts, double dt) {
        if (joint != null) {
            omegaMotor = joint.rate() * jointRatio;
            double tau = motor.torque(omegaMotor, batteryVolts, dt);
            if (joint.revolute()) {
                joint.effort(tau * jointRatio);   // the rotor is in the arm's inertia (RobotBody)
                return;
            }
            // A slide: the rotor, geared up through the spool, is often many kilograms at the
            // slide, far more than a light stage. Motor, rotor and the stages it carries move as
            // one here, and the joint's own motor holds the slide to that speed: stable however
            // light the stage, and the rotor's mass isn't weight the robot carries.
            double force = tau * jointRatio;
            double weight = joint.weightAlongAxis();
            double v = joint.rate() + (force + weight) / (joint.carriedMass() + rotorMass) * dt;
            joint.motor(v, Math.max(Math.abs(force), Math.abs(weight)) + 1e-3);
            return;
        }
        double tauMotor = motor.torque(omegaMotor, batteryVolts, dt);
        double tauLoad = 0;     // load torque at the motor output, opposing positive motion
        switch (load.kind) {
            case "arm":
                // Arm angle measured up from horizontal; gravity pulls it down.
                tauLoad = load.massKg * G * (load.lengthM / 2) * Math.cos(position) / gear;
                break;
            case "slide":
                tauLoad = load.massKg * G * (load.spoolDiameterM / 2) / gear;
                break;
            default:
                tauLoad = 0;   // rollers and flywheels: only the gearbox friction in MotorUnit
        }
        omegaMotor += (tauMotor - tauLoad) / inertiaAtMotor * dt;
        double mechRate = omegaMotor / gear * ("slide".equals(load.kind) ? load.spoolDiameterM / 2 : 1);
        double next = position + mechRate * dt;
        double lo, hi;
        if ("slide".equals(load.kind)) {
            lo = 0;
            hi = load.maxExtensionM;
        } else {
            lo = Math.toRadians(load.minDeg);
            hi = Math.toRadians(load.maxDeg);
        }
        if (next < lo || next > hi) {
            // Hard stop: the mechanism stops dead and the motor stalls against it.
            next = Math.max(lo, Math.min(hi, next));
            omegaMotor = 0;
        }
        position = next;
        motor.integrate(omegaMotor, dt);
    }

    /** The encoder catches up with what the joint did during the world step. */
    public void afterWorldStep(double dt) {
        if (joint == null) return;
        omegaMotor = joint.rate() * jointRatio;
        motor.integrate(omegaMotor, dt);
        position = joint.position();
    }

    /** Mechanism coordinate: radians (roller/flywheel/arm) or meters (slide). */
    public double position() {
        return position;
    }

    public double motorOmega() {
        return omegaMotor;
    }

    public String kind() {
        return joint != null ? (joint.revolute() ? "arm" : "slide") : load.kind;
    }

    /** The CAD joint this motor drives, or null. */
    public RobotBody.LinkBody joint() {
        return joint;
    }
}
