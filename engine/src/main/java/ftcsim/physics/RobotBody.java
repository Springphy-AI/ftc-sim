package ftcsim.physics;

import java.util.ArrayList;
import java.util.List;

import org.ode4j.math.DMatrix3;
import org.ode4j.math.DMatrix3C;
import org.ode4j.math.DVector3;
import org.ode4j.math.DVector3C;
import org.ode4j.ode.DBody;
import org.ode4j.ode.DGeom;
import org.ode4j.ode.DHingeJoint;
import org.ode4j.ode.DMass;
import org.ode4j.ode.OdeHelper;

import ftcsim.config.MotorModels;
import ftcsim.config.RobotConfig;

/**
 * The robot as rigid bodies: a chassis box and one body per wheel on a hinge, with ground
 * friction set per wheel type (mecanum rollers grip only along their roller axis).
 * Robot frame: +x forward, +y left, +z up.
 */
public final class RobotBody {

    /** A wheel body on a hinge. */
    public static final class Wheel {
        public final String key;
        public final double x, y;
        public DBody body;
        public DGeom geom;
        public DHingeJoint hinge;

        Wheel(String key, double x, double y) {
            this.key = key;
            this.x = x;
            this.y = y;
        }

        /** Spin rate about the axle (rad/s); positive rolls the robot forward. */
        public double rate() {
            return hinge.getAngleRate();
        }

        public double angle() {
            return hinge.getAngle();
        }
    }

    /** A motor driving one or more wheels. */
    public static final class DriveMotor {
        public final MotorUnit motor;
        public final List<Wheel> wheels = new ArrayList<>();
        /** +1 if FORWARD power rolls these wheels forward, -1 if the motor is mounted mirrored. */
        public final int mountSign;
        public final double gear;

        DriveMotor(MotorUnit motor, int mountSign, double gear) {
            this.motor = motor;
            this.mountSign = mountSign;
            this.gear = gear;
        }
    }

    public final RobotConfig config;
    public final PhysicsWorld world;
    public final DBody chassis;
    public final DGeom chassisGeom;
    public final List<Wheel> wheels = new ArrayList<>();
    public final List<DriveMotor> driveMotors = new ArrayList<>();
    public final double wheelRadius;
    private final double chassisZ;

    public RobotBody(PhysicsWorld world, RobotConfig config, java.util.function.Function<String, MotorUnit> motorByName) {
        this.world = world;
        this.config = config;
        RobotConfig.Chassis c = config.chassis;
        RobotConfig.Drive d = config.drive;
        wheelRadius = d.wheelDiameterMm / 2000.0;
        MotorModels.Spec spec = MotorModels.get(d.motor);
        double gear = d.externalGearRatio <= 0 ? 1 : d.externalGearRatio;

        double wheelMass = 0.25;
        int nWheels = "tank".equals(d.type) ? 6 : 4;
        double chassisMass = Math.max(1.0, c.massKg - nWheels * wheelMass);

        chassis = OdeHelper.createBody(world.world);
        DMass m = OdeHelper.createMass();
        m.setBoxTotal(chassisMass, c.lengthM, c.widthM, c.frameHeightM);
        chassis.setMass(m);
        chassisZ = c.groundClearanceM + c.frameHeightM / 2;
        chassis.setPosition(0, 0, chassisZ);
        chassis.setAutoDisableFlag(false);
        chassisGeom = OdeHelper.createBox(world.space, c.lengthM, c.widthM, c.frameHeightM);
        chassisGeom.setBody(chassis);
        PhysicsWorld.Material cm = new PhysicsWorld.Material("chassis");
        cm.mu = cm.mu2 = 0.3;
        cm.bounce = 0.05;
        cm.owner = this;
        // Other robots' chassis and wheels too; the world skips pairs that belong to the same robot.
        world.register(chassisGeom, cm, PhysicsWorld.CAT_CHASSIS, PhysicsWorld.CAT_GROUND | PhysicsWorld.CAT_STATIC
                | PhysicsWorld.CAT_BALL | PhysicsWorld.CAT_CHASSIS | PhysicsWorld.CAT_WHEEL);
        world.addChassis(chassis);

        double a = d.wheelbaseM / 2, b = d.trackWidthM / 2;
        double s = Math.sqrt(0.5);
        switch (d.type) {
            case "tank": {
                // Six-wheel drop-center drive: traction wheels in the middle, omni wheels at the
                // corners, all chained to one motor per side. It turns in place about its center.
                Wheel lf = addWheel("left_front", a, b, new double[]{1, 0}, d.tractionMu, d.rollerMu, wheelMass, spec, gear);
                Wheel rf = addWheel("right_front", a, -b, new double[]{1, 0}, d.tractionMu, d.rollerMu, wheelMass, spec, gear);
                Wheel lm = addWheel("left_middle", 0, b, null, d.tractionMu, d.tractionMu, wheelMass, spec, gear);
                Wheel rm = addWheel("right_middle", 0, -b, null, d.tractionMu, d.tractionMu, wheelMass, spec, gear);
                Wheel lb = addWheel("left_back", -a, b, new double[]{1, 0}, d.tractionMu, d.rollerMu, wheelMass, spec, gear);
                Wheel rb = addWheel("right_back", -a, -b, new double[]{1, 0}, d.tractionMu, d.rollerMu, wheelMass, spec, gear);
                drive(motorByName, config.driveMotorName("left"), spec, -1, gear, lf, lm, lb);
                drive(motorByName, config.driveMotorName("right"), spec, +1, gear, rf, rm, rb);
                break;
            }
            case "tank4": {
                // Four-motor skid steer: traction wheels at the back, omni wheels at the front.
                Wheel lf = addWheel("left_front", a, b, new double[]{1, 0}, d.tractionMu, d.rollerMu, wheelMass, spec, gear);
                Wheel rf = addWheel("right_front", a, -b, new double[]{1, 0}, d.tractionMu, d.rollerMu, wheelMass, spec, gear);
                Wheel lb = addWheel("left_back", -a, b, null, d.tractionMu, d.tractionMu, wheelMass, spec, gear);
                Wheel rb = addWheel("right_back", -a, -b, null, d.tractionMu, d.tractionMu, wheelMass, spec, gear);
                {
                    drive(motorByName, config.driveMotorName("front_left"), spec, -1, gear, lf);
                    drive(motorByName, config.driveMotorName("back_left"), spec, -1, gear, lb);
                    drive(motorByName, config.driveMotorName("front_right"), spec, +1, gear, rf);
                    drive(motorByName, config.driveMotorName("back_right"), spec, +1, gear, rb);
                }
                break;
            }
            case "mecanum":
            default: {
                // Rollers in an "X" seen from above: spinning the front-left wheel forward pushes forward-right.
                Wheel fl = addWheel("front_left", a, b, new double[]{s, -s}, d.tractionMu, d.rollerMu, wheelMass, spec, gear);
                Wheel fr = addWheel("front_right", a, -b, new double[]{s, s}, d.tractionMu, d.rollerMu, wheelMass, spec, gear);
                Wheel bl = addWheel("back_left", -a, b, new double[]{s, s}, d.tractionMu, d.rollerMu, wheelMass, spec, gear);
                Wheel br = addWheel("back_right", -a, -b, new double[]{s, -s}, d.tractionMu, d.rollerMu, wheelMass, spec, gear);
                drive(motorByName, config.driveMotorName("front_left"), spec, -1, gear, fl);
                drive(motorByName, config.driveMotorName("back_left"), spec, -1, gear, bl);
                drive(motorByName, config.driveMotorName("front_right"), spec, +1, gear, fr);
                drive(motorByName, config.driveMotorName("back_right"), spec, +1, gear, br);
            }
        }
    }

    private Wheel addWheel(String key, double x, double y, double[] tractionDir, double mu, double mu2,
                           double mass, MotorModels.Spec spec, double gear) {
        Wheel w = new Wheel(key, x, y);
        w.body = OdeHelper.createBody(world.world);
        // Axle inertia includes the motor rotor reflected through the gearbox, which dominates.
        double r = wheelRadius;
        double axle = 0.5 * mass * r * r + spec.reflectedInertia() * gear * gear;
        double other = 0.25 * mass * r * r + 0.5 * axle;
        DMass m = OdeHelper.createMass();
        m.setParameters(mass, 0, 0, 0, other, axle, other, 0, 0, 0);
        w.body.setMass(m);
        w.body.setPosition(x, y, r);
        w.body.setAutoDisableFlag(false);
        w.geom = OdeHelper.createSphere(world.space, r);
        w.geom.setBody(w.body);
        PhysicsWorld.Material wm = new PhysicsWorld.Material("wheel");
        wm.wheelTractionDir = tractionDir;
        wm.tractionFrame = chassis;
        wm.mu = mu;
        wm.mu2 = tractionDir == null ? mu : mu2;
        wm.owner = this;
        world.register(w.geom, wm, PhysicsWorld.CAT_WHEEL, PhysicsWorld.CAT_GROUND | PhysicsWorld.CAT_STATIC
                | PhysicsWorld.CAT_BALL | PhysicsWorld.CAT_CHASSIS | PhysicsWorld.CAT_WHEEL);
        w.hinge = OdeHelper.createHingeJoint(world.world);
        w.hinge.attach(w.body, chassis);
        w.hinge.setAnchor(x, y, r);
        w.hinge.setAxis(0, 1, 0);
        wheels.add(w);
        return w;
    }

    private void drive(java.util.function.Function<String, MotorUnit> motorByName, String name, MotorModels.Spec spec,
                       int mirroredSign, double gear, Wheel... driven) {
        if (name == null) return;
        MotorUnit mu = motorByName.apply(name);
        int sign = config.drive.leftSideMirrored ? mirroredSign : 1;
        DriveMotor dm = new DriveMotor(mu, sign, gear);
        for (Wheel w : driven) dm.wheels.add(w);
        driveMotors.add(dm);
    }

    /** Applies motor torques for this step (before the world steps). */
    public void applyDrive(double batteryVolts, double dt) {
        for (DriveMotor dm : driveMotors) {
            double wheelRate = 0;
            for (Wheel w : dm.wheels) wheelRate += w.rate();
            wheelRate /= dm.wheels.size();
            double motorOmega = wheelRate * dm.gear * dm.mountSign;
            double tau = dm.motor.torque(motorOmega, batteryVolts, dt);
            double wheelTorque = tau * dm.gear * dm.mountSign / dm.wheels.size();
            for (Wheel w : dm.wheels) w.hinge.addTorque(wheelTorque);
        }
    }

    /** Updates encoders after the world stepped. */
    public void afterStep(double dt) {
        for (DriveMotor dm : driveMotors) {
            double wheelRate = 0;
            for (Wheel w : dm.wheels) wheelRate += w.rate();
            wheelRate /= dm.wheels.size();
            dm.motor.integrate(wheelRate * dm.gear * dm.mountSign, dt);
        }
    }

    /** Places the robot at rest at (x, y) facing {@code headingRad}. */
    public void place(double x, double y, double headingRad) {
        DMatrix3 rot = new DMatrix3();
        double ch = Math.cos(headingRad), sh = Math.sin(headingRad);
        rot.set(ch, -sh, 0, sh, ch, 0, 0, 0, 1);
        chassis.setPosition(x, y, chassisZ);
        chassis.setRotation(rot);
        chassis.setLinearVel(0, 0, 0);
        chassis.setAngularVel(0, 0, 0);
        for (Wheel w : wheels) {
            DVector3 p = new DVector3();
            chassis.getRelPointPos(w.x, w.y, wheelRadius - chassisZ, p);
            w.body.setPosition(p);
            w.body.setRotation(rot);
            w.body.setLinearVel(0, 0, 0);
            w.body.setAngularVel(0, 0, 0);
            w.hinge.setAnchor(p);
            DVector3 axis = new DVector3();
            chassis.vectorToWorld(0, 1, 0, axis);
            w.hinge.setAxis(axis);
        }
    }

    /** Takes the robot out of the world (when it is swapped for another robot description). */
    public void destroy() {
        for (Wheel w : wheels) {
            w.hinge.destroy();
            world.unregister(w.geom);
            w.geom.destroy();
            w.body.destroy();
        }
        world.removeChassis(chassis);
        world.unregister(chassisGeom);
        chassisGeom.destroy();
        chassis.destroy();
    }

    // ---- state ---------------------------------------------------------------------------------

    public DVector3C position() {
        return chassis.getPosition();
    }

    public DMatrix3C rotation() {
        return chassis.getRotation();
    }

    /** Heading: angle of the robot's +x axis in the field's x-y plane, CCW from +x. */
    public double heading() {
        DMatrix3C r = chassis.getRotation();
        return Math.atan2(r.get10(), r.get00());
    }

    /** Linear velocity in the robot frame (x forward, y left, z up). */
    public double[] bodyVelocity() {
        DVector3 v = new DVector3();
        chassis.vectorFromWorld(chassis.getLinearVel(), v);
        return new double[]{v.get0(), v.get1(), v.get2()};
    }

    /** Angular velocity in the robot frame. */
    public double[] bodyAngularVelocity() {
        DVector3 w = new DVector3();
        chassis.vectorFromWorld(chassis.getAngularVel(), w);
        return new double[]{w.get0(), w.get1(), w.get2()};
    }

    public DVector3 pointToWorld(double x, double y, double z) {
        DVector3 out = new DVector3();
        chassis.getRelPointPos(x, y, z - chassisZ, out);
        return out;
    }

    public DVector3 vectorToWorld(double x, double y, double z) {
        DVector3 out = new DVector3();
        chassis.vectorToWorld(x, y, z, out);
        return out;
    }

    /** Height of the chassis body origin above the floor when resting. */
    public double chassisRestZ() {
        return chassisZ;
    }
}
