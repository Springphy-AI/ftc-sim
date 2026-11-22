package ftcsim.physics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import org.ode4j.math.DMatrix3;
import org.ode4j.math.DMatrix3C;
import org.ode4j.math.DVector3;
import org.ode4j.math.DVector3C;
import org.ode4j.ode.DBody;
import org.ode4j.ode.DGeom;
import org.ode4j.ode.DHingeJoint;
import org.ode4j.ode.DJoint;
import org.ode4j.ode.DMass;
import org.ode4j.ode.DSliderJoint;
import org.ode4j.ode.OdeHelper;

import ftcsim.cad.Geom3;
import ftcsim.cad.MeshMath;
import ftcsim.cad.ModelBuilder;
import ftcsim.cad.RobotModel;
import ftcsim.config.MotorModels;
import ftcsim.config.RobotConfig;

/**
 * The robot as rigid bodies: a chassis and one body per wheel on a hinge, with ground friction
 * set per wheel type (mecanum rollers grip only along their roller axis). A robot with a CAD
 * model also gets one body per moving link (an arm, a slide stage, a hood) on a hinge or slider,
 * and its chassis takes the CAD's mass, center of mass, inertia and collision shape.
 *
 * <p>Robot frame: +x forward, +y left, +z up, origin on the floor under the robot's center. The
 * chassis body's origin is its center of mass, at {@link #bodyOrigin()} in the robot frame.
 */
public final class RobotBody {

    /** A wheel body on a hinge. */
    public static final class Wheel {
        public final String key;
        public final double x, y;
        public DBody body;
        public DGeom geom;
        public DHingeJoint hinge;
        double mass;

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

    /** A CAD link on a revolute or prismatic joint. */
    public static final class LinkBody {
        public final RobotModel.Group group;
        public final int groupIndex;
        public final DBody body;
        public final DJoint joint;
        final List<DGeom> geoms = new ArrayList<>();
        /** This link and every link mounted on it, directly or through others. */
        final List<LinkBody> carried = new ArrayList<>();
        final DVector3 gravity = new DVector3();

        LinkBody(RobotModel.Group group, int groupIndex, DBody body, DJoint joint) {
            this.group = group;
            this.groupIndex = groupIndex;
            this.body = body;
            this.joint = joint;
        }

        /** Joint position from the CAD pose: radians or meters. */
        public double position() {
            return joint instanceof DHingeJoint h ? h.getAngle() : ((DSliderJoint) joint).getPosition();
        }

        /** Joint speed: rad/s or m/s. */
        public double rate() {
            return joint instanceof DHingeJoint h ? h.getAngleRate() : ((DSliderJoint) joint).getPositionRate();
        }

        /** Torque (N m) or force (N) along the joint, between this link and its parent, for one step. */
        public void effort(double f) {
            if (joint instanceof DHingeJoint h) h.addTorque(f);
            else ((DSliderJoint) joint).addForce(f);
        }

        /** The joint's built-in motor: drive toward speed {@code vel} with at most {@code fmax} (0 = off). */
        public void motor(double vel, double fmax) {
            if (joint instanceof DHingeJoint h) {
                h.setParamVel(vel);
                h.setParamFMax(fmax);
            } else {
                DSliderJoint s = (DSliderJoint) joint;
                s.setParamVel(vel);
                s.setParamFMax(fmax);
            }
        }

        public boolean revolute() {
            return joint instanceof DHingeJoint;
        }

        /** The mass the joint moves: this link and every link it carries (kg). */
        public double carriedMass() {
            double m = 0;
            for (LinkBody l : carried) m += l.body.getMass().getMass();
            return m;
        }

        /** A slide: gravity's pull on what it carries, along its axis (N, positive along +axis). */
        public double weightAlongAxis() {
            DVector3 a = new DVector3();
            ((DSliderJoint) joint).getAxis(a);
            return carriedMass() * (gravity.get0() * a.get0() + gravity.get1() * a.get1() + gravity.get2() * a.get2());
        }
    }

    public final RobotConfig config;
    public final PhysicsWorld world;
    /** The CAD model, or null for a robot built from the robot file's numbers. */
    public final RobotModel model;
    public final DBody chassis;
    private final List<DGeom> chassisGeoms = new ArrayList<>();
    public final List<Wheel> wheels = new ArrayList<>();
    public final List<DriveMotor> driveMotors = new ArrayList<>();
    /** One per CAD link on a revolute or prismatic joint, parents first. */
    public final List<LinkBody> links = new ArrayList<>();
    private final LinkBody[] linkOfGroup;
    public final double wheelRadius;
    /** Chassis body origin (its center of mass) in the robot frame. */
    private final double ox, oy, oz;

    /**
     * The intake roller of a robot built from the robot file's numbers, where the 3D view draws it:
     * across the front, {@code ROLLER_AHEAD_M} ahead of the chassis, its axis {@code ROLLER_HEIGHT_M}
     * above the tiles. (A CAD robot's roller is its own part.)
     */
    public static final double ROLLER_RADIUS_M = 0.03, ROLLER_AHEAD_M = 0.04, ROLLER_HEIGHT_M = 0.045;

    public RobotBody(PhysicsWorld world, RobotConfig config, java.util.function.Function<String, MotorUnit> motorByName) {
        this.world = world;
        this.config = config;
        this.model = config.model();
        RobotConfig.Chassis c = config.chassis;
        RobotConfig.Drive d = config.drive;
        wheelRadius = d.wheelDiameterMm / 2000.0;
        MotorModels.Spec spec = MotorModels.get(d.motor);
        double gear = d.externalGearRatio <= 0 ? 1 : d.externalGearRatio;

        int nWheels = "tank".equals(d.type) ? 6 : 4;
        try {
            chassis = OdeHelper.createBody(world.world);
            DMass m = OdeHelper.createMass();
            PhysicsWorld.Material cm = new PhysicsWorld.Material("chassis");
            cm.mu = cm.mu2 = 0.3;
            cm.bounce = 0.05;
            cm.owner = this;
            if (model == null) {
                double chassisMass = Math.max(1.0, c.massKg - nWheels * ModelBuilder.DEFAULT_WHEEL_MASS_KG);
                m.setBoxTotal(chassisMass, c.lengthM, c.widthM, c.frameHeightM);
                ox = 0;
                oy = 0;
                oz = c.groundClearanceM + c.frameHeightM / 2;
                DGeom box = OdeHelper.createBox(world.space, c.lengthM, c.widthM, c.frameHeightM);
                box.setBody(chassis);
                chassisGeoms.add(box);
            } else {
                RobotModel.Group base = model.base();
                setMass(m, Math.max(0.5, base.mass), base.inertia);
                ox = base.com[0];
                oy = base.com[1];
                oz = base.com[2];
                for (MeshMath.Box b : base.boxes) chassisGeoms.add(boxGeom(chassis, b, base.com));
                if (chassisGeoms.isEmpty()) {   // a CAD with nothing but wheels: keep a small box so it can be pushed
                    DGeom box = OdeHelper.createBox(world.space, 0.1, 0.1, 0.05);
                    box.setBody(chassis);
                    chassisGeoms.add(box);
                }
            }
            chassis.setMass(m);
            chassis.setPosition(ox, oy, oz);
            chassis.setAutoDisableFlag(false);
            // Other robots' chassis and wheels too; the world skips pairs that belong to the same robot.
            for (DGeom g : chassisGeoms) {
                world.register(g, cm, PhysicsWorld.CAT_CHASSIS, PhysicsWorld.CAT_GROUND | PhysicsWorld.CAT_STATIC
                        | PhysicsWorld.CAT_BALL | PhysicsWorld.CAT_CHASSIS | PhysicsWorld.CAT_WHEEL);
            }
            world.addChassis(chassis);
            if (model == null) addIntakeRoller(c);

            double a = d.wheelbaseM / 2, b = d.trackWidthM / 2;
            double s = Math.sqrt(0.5);
            switch (d.type) {
                case "tank": {
                    // Six-wheel drop-center drive: traction wheels in the middle, omni wheels at the
                    // corners, all chained to one motor per side. It turns in place about its center.
                    Wheel lf = addWheel("left_front", a, b, new double[]{1, 0}, d.tractionMu, d.rollerMu, spec, gear);
                    Wheel rf = addWheel("right_front", a, -b, new double[]{1, 0}, d.tractionMu, d.rollerMu, spec, gear);
                    Wheel lm = addWheel("left_middle", 0, b, null, d.tractionMu, d.tractionMu, spec, gear);
                    Wheel rm = addWheel("right_middle", 0, -b, null, d.tractionMu, d.tractionMu, spec, gear);
                    Wheel lb = addWheel("left_back", -a, b, new double[]{1, 0}, d.tractionMu, d.rollerMu, spec, gear);
                    Wheel rb = addWheel("right_back", -a, -b, new double[]{1, 0}, d.tractionMu, d.rollerMu, spec, gear);
                    drive(motorByName, config.driveMotorName("left"), spec, "left", gear, lf, lm, lb);
                    drive(motorByName, config.driveMotorName("right"), spec, "right", gear, rf, rm, rb);
                    break;
                }
                case "tank4": {
                    // Four-motor skid steer: traction wheels at the back, omni wheels at the front.
                    Wheel lf = addWheel("left_front", a, b, new double[]{1, 0}, d.tractionMu, d.rollerMu, spec, gear);
                    Wheel rf = addWheel("right_front", a, -b, new double[]{1, 0}, d.tractionMu, d.rollerMu, spec, gear);
                    Wheel lb = addWheel("left_back", -a, b, null, d.tractionMu, d.tractionMu, spec, gear);
                    Wheel rb = addWheel("right_back", -a, -b, null, d.tractionMu, d.tractionMu, spec, gear);
                    {
                        drive(motorByName, config.driveMotorName("front_left"), spec, "left", gear, lf);
                        drive(motorByName, config.driveMotorName("back_left"), spec, "left", gear, lb);
                        drive(motorByName, config.driveMotorName("front_right"), spec, "right", gear, rf);
                        drive(motorByName, config.driveMotorName("back_right"), spec, "right", gear, rb);
                    }
                    break;
                }
                case "mecanum":
                default: {
                    // Rollers in an "X" seen from above: spinning the front-left wheel forward pushes forward-right.
                    Wheel fl = addWheel("front_left", a, b, new double[]{s, -s}, d.tractionMu, d.rollerMu, spec, gear);
                    Wheel fr = addWheel("front_right", a, -b, new double[]{s, s}, d.tractionMu, d.rollerMu, spec, gear);
                    Wheel bl = addWheel("back_left", -a, b, new double[]{s, s}, d.tractionMu, d.rollerMu, spec, gear);
                    Wheel br = addWheel("back_right", -a, -b, new double[]{s, -s}, d.tractionMu, d.rollerMu, spec, gear);
                    drive(motorByName, config.driveMotorName("front_left"), spec, "left", gear, fl);
                    drive(motorByName, config.driveMotorName("back_left"), spec, "left", gear, bl);
                    drive(motorByName, config.driveMotorName("front_right"), spec, "right", gear, fr);
                    drive(motorByName, config.driveMotorName("back_right"), spec, "right", gear, br);
                }
            }
            // A motor that drives several wheels (a chain) has one rotor: its wheels share its inertia.
            for (DriveMotor dm : driveMotors) {
                for (Wheel w : dm.wheels) setWheelMass(w, spec.reflectedInertia() * gear * gear / dm.wheels.size());
            }

            linkOfGroup = new LinkBody[model == null ? 0 : model.groups.size()];
            if (model != null) {
                for (int i = 0; i < model.groups.size(); i++) {
                    RobotModel.Group g = model.groups.get(i);
                    if (g.kind == RobotModel.Kind.LINK) addLink(i, g, cm);
                }
                for (LinkBody lb : links) {
                    world.world.getGravity(lb.gravity);
                    for (LinkBody other : links) if (carries(lb, other)) lb.carried.add(other);
                }
            }
        } catch (RuntimeException | Error e) {
            // Whatever was built so far must not stay in the world, where other robots and the game
            // pieces would still collide with it.
            destroy();
            throw e;
        }
    }

    /**
     * A robot with an "intake" device gets a roller across its front that is solid to game pieces,
     * so it pushes and bumps them: it can knock the bottom POLLEN out of a FLOWER even when the robot
     * is full. Picking a piece up is GameElements' work, while the roller spins. Pieces only: the
     * chassis already meets walls and structures, and a rounded roller end touching a wall first
     * would twist the robot.
     */
    private void addIntakeRoller(RobotConfig.Chassis c) {
        boolean hasIntake = config.devices.stream().anyMatch(dv -> "intake".equals(dv.role));
        if (!hasIntake) return;
        double len = c.widthM * 0.82 - 2 * ROLLER_RADIUS_M;
        DGeom roller = OdeHelper.createCapsule(world.space, ROLLER_RADIUS_M, len);
        roller.setBody(chassis);
        roller.setOffsetPosition(c.lengthM / 2 + ROLLER_AHEAD_M - ox, -oy, ROLLER_HEIGHT_M - oz);
        roller.setOffsetRotation(new DMatrix3(1, 0, 0, 0, 0, -1, 0, 1, 0));   // the capsule's axis (z) across the robot (y)
        PhysicsWorld.Material rm = new PhysicsWorld.Material("roller");
        rm.mu = rm.mu2 = 0.6;
        rm.bounce = 0.1;
        rm.owner = this;
        world.register(roller, rm, PhysicsWorld.CAT_ROLLER, PhysicsWorld.CAT_BALL);
        intakeRoller = roller;
    }

    /** The solid intake roller, or null. */
    private DGeom intakeRoller;

    /** Whether link {@code b} is {@code a} or rides on it. */
    private boolean carries(LinkBody a, LinkBody b) {
        for (int i = b.groupIndex; i > 0; i = model.groups.get(i).parentIndex) if (i == a.groupIndex) return true;
        return false;
    }

    private static void setMass(DMass m, double mass, double[] in) {
        m.setParameters(mass, 0, 0, 0, in[0], in[4], in[8], in[1], in[2], in[5]);
    }

    /** A collision box on {@code body}, whose origin is at {@code bodyOrigin} in the robot frame at rest. */
    private DGeom boxGeom(DBody body, MeshMath.Box b, double[] bodyOrigin) {
        DGeom g = OdeHelper.createBox(world.space, b.size()[0], b.size()[1], b.size()[2]);
        g.setBody(body);
        double[] off = Geom3.sub(b.center(), bodyOrigin);
        g.setOffsetPosition(off[0], off[1], off[2]);
        double[] r = b.rotation();
        g.setOffsetRotation(new DMatrix3(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]));
        return g;
    }

    private void addLink(int index, RobotModel.Group g, PhysicsWorld.Material material) {
        DBody body = OdeHelper.createBody(world.world);
        double[] inertia = g.inertia.clone();
        // A motor-driven arm also turns the motor's rotor through the gearbox, which often
        // outweighs the arm itself: add it about the joint axis.
        RobotConfig.Device dev = device(g.device);
        if (dev != null && "motor".equals(dev.type) && g.revolute()) {
            MotorModels.Spec spec = MotorModels.get(dev.motor);
            double gear = dev.externalGearRatio <= 0 ? 1 : dev.externalGearRatio;
            double j = spec.reflectedInertia() * gear * gear;
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) inertia[r * 3 + c] += j * g.axis[r] * g.axis[c];
        }
        DMass m = OdeHelper.createMass();
        setMass(m, Math.max(0.02, g.mass), inertia);
        body.setMass(m);
        body.setPosition(g.com[0], g.com[1], g.com[2]);
        body.setAutoDisableFlag(false);
        DBody parent = g.parentIndex == 0 ? chassis : linkOfGroup[g.parentIndex].body;
        DJoint joint;
        // A stage that follows another (mimic) needs no stops of its own: its target is clamped
        // instead, since ODE pushes with a motor's full force when a powered joint sits on a stop.
        boolean stops = !Double.isNaN(g.lower) && g.mimic < 0;
        if (g.revolute()) {
            DHingeJoint h = OdeHelper.createHingeJoint(world.world);
            h.attach(body, parent);
            h.setAnchor(g.anchor[0], g.anchor[1], g.anchor[2]);
            h.setAxis(g.axis[0], g.axis[1], g.axis[2]);
            if (stops) {
                h.setParamLoStop(g.lower);
                h.setParamHiStop(g.upper);
                h.setParamBounce(0);
            }
            joint = h;
        } else {
            DSliderJoint sl = OdeHelper.createSliderJoint(world.world);
            sl.attach(body, parent);
            sl.setAxis(g.axis[0], g.axis[1], g.axis[2]);
            if (stops) {
                sl.setParamLoStop(g.lower);
                sl.setParamHiStop(g.upper);
                sl.setParamBounce(0);
            }
            joint = sl;
        }
        // A servo driving a joint away from its stop must not kick it with its whole torque.
        joint.setParam(DJoint.PARAM_N.dParamFudgeFactor1, 0.1);
        LinkBody lb = new LinkBody(g, index, body, joint);
        for (MeshMath.Box b : g.boxes) {
            DGeom geom = boxGeom(body, b, g.com);
            lb.geoms.add(geom);
            world.register(geom, material, PhysicsWorld.CAT_CHASSIS, PhysicsWorld.CAT_GROUND | PhysicsWorld.CAT_STATIC
                    | PhysicsWorld.CAT_BALL | PhysicsWorld.CAT_CHASSIS | PhysicsWorld.CAT_WHEEL);
        }
        // Joints nothing drives get a little friction, like a real pivot.
        if (g.device == null && g.mimic < 0) lb.motor(0, g.revolute() ? 0.02 : 0.5);
        links.add(lb);
        linkOfGroup[index] = lb;
    }

    private RobotConfig.Device device(String name) {
        if (name == null) return null;
        for (RobotConfig.Device d : config.devices) if (name.equals(d.name)) return d;
        return null;
    }

    /** The physics body of a CAD link group, or null for groups without one. */
    public LinkBody link(int groupIndex) {
        return groupIndex >= 0 && groupIndex < linkOfGroup.length ? linkOfGroup[groupIndex] : null;
    }

    private Wheel addWheel(String key, double x, double y, double[] tractionDir, double mu, double mu2,
                           MotorModels.Spec spec, double gear) {
        double mass = ModelBuilder.DEFAULT_WHEEL_MASS_KG;
        RobotModel.Group cadWheel = model == null ? null : model.wheel(key);
        if (cadWheel != null) {
            // Where the CAD puts this wheel.
            x = cadWheel.anchor[0];
            y = cadWheel.anchor[1];
            if (cadWheel.mass > 0) mass = cadWheel.mass;
        }
        Wheel w = new Wheel(key, x, y);
        wheels.add(w);   // from the start, so destroy() takes it apart if building it fails
        w.mass = mass;
        w.body = OdeHelper.createBody(world.world);
        double r = wheelRadius;
        setWheelMass(w, spec.reflectedInertia() * gear * gear);   // a rotor of its own until drive() says otherwise
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
        return w;
    }

    /**
     * A wheel's mass and inertia. Its axle inertia includes {@code rotorInertia}, its share of the
     * driving motor's rotor reflected through the gearbox, which dominates.
     */
    private void setWheelMass(Wheel w, double rotorInertia) {
        double r = wheelRadius;
        double axle = 0.5 * w.mass * r * r + rotorInertia;
        double other = 0.25 * w.mass * r * r + 0.5 * axle;
        DMass m = OdeHelper.createMass();
        m.setParameters(w.mass, 0, 0, 0, other, axle, other, 0, 0, 0);
        w.body.setMass(m);
    }

    private void drive(java.util.function.Function<String, MotorUnit> motorByName, String name, MotorModels.Spec spec,
                       String side, double gear, Wheel... driven) {
        if (name == null) return;
        MotorUnit mu = motorByName.apply(name);
        for (DriveMotor dm : driveMotors) {
            if (dm.motor == mu) {   // one motor chained to several wheels (a 2-motor tank4): it drives them together
                java.util.Collections.addAll(dm.wheels, driven);
                return;
            }
        }
        int sign = side.equals(config.drive.mirroredSide()) ? -1 : 1;
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
        // Linked stages (a cascade slide) follow the stage they mimic, stiffly.
        for (LinkBody lb : links) {
            RobotModel.Group g = lb.group;
            if (g.mimic < 0) continue;
            LinkBody master = linkOfGroup[g.mimic];
            double target = g.mimicRatio * master.position() + g.mimicOffset;
            if (!Double.isNaN(g.lower)) target = Math.max(g.lower, Math.min(g.upper, target));
            double err = target - lb.position();
            double vel = g.mimicRatio * master.rate() + err / 0.01;
            lb.motor(vel, g.revolute() ? 200 : 2000);
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

    /** Places the robot at rest at (x, y) facing {@code headingRad}, its CAD links at their start positions. */
    public void place(double x, double y, double headingRad) {
        DMatrix3 rot = new DMatrix3();
        double ch = Math.cos(headingRad), sh = Math.sin(headingRad);
        rot.set(ch, -sh, 0, sh, ch, 0, 0, 0, 1);
        chassis.setPosition(x + ch * ox - sh * oy, y + sh * ox + ch * oy, oz);
        chassis.setRotation(rot);
        chassis.setLinearVel(0, 0, 0);
        chassis.setAngularVel(0, 0, 0);
        for (Wheel w : wheels) {
            DVector3 p = new DVector3();
            chassis.getRelPointPos(w.x - ox, w.y - oy, wheelRadius - oz, p);
            w.body.setPosition(p);
            w.body.setRotation(rot);
            w.body.setLinearVel(0, 0, 0);
            w.body.setAngularVel(0, 0, 0);
            w.hinge.setAnchor(p);
            DVector3 axis = new DVector3();
            chassis.vectorToWorld(0, 1, 0, axis);
            w.hinge.setAxis(axis);
        }
        if (links.isEmpty()) return;
        // Each link at its start position relative to its parent, all on the robot's new pose.
        double[] robot = Geom3.transform(new double[]{ch, -sh, 0, sh, ch, 0, 0, 0, 1}, new double[]{x, y, 0});
        double[][] world = new double[model.groups.size()][];
        world[0] = robot;
        for (int i = 1; i < model.groups.size(); i++) {
            RobotModel.Group g = model.groups.get(i);
            if (g.kind != RobotModel.Kind.LINK) continue;
            double q = g.start;
            if (g.mimic >= 0) q = g.mimicRatio * model.groups.get(g.mimic).start + g.mimicOffset;
            world[i] = Geom3.compose(world[g.parentIndex], g.motion(q));
            LinkBody lb = linkOfGroup[i];
            double[] c = Geom3.apply(world[i], g.com);
            double[] r = Geom3.rot(world[i]);
            lb.body.setPosition(c[0], c[1], c[2]);
            lb.body.setRotation(new DMatrix3(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]));
            lb.body.setLinearVel(0, 0, 0);
            lb.body.setAngularVel(0, 0, 0);
        }
    }

    /** Takes the robot out of the world (when it is swapped for another robot description). */
    public void destroy() {
        for (LinkBody lb : links) {
            lb.joint.destroy();
            for (DGeom g : lb.geoms) {
                world.unregister(g);
                g.destroy();
            }
            lb.body.destroy();
        }
        for (Wheel w : wheels) {   // (a wheel whose building failed has only some of its parts)
            if (w.hinge != null) w.hinge.destroy();
            if (w.geom != null) {
                world.unregister(w.geom);
                w.geom.destroy();
            }
            if (w.body != null) w.body.destroy();
        }
        for (DGeom g : chassisGeoms) {
            world.unregister(g);
            g.destroy();
        }
        if (intakeRoller != null) {
            world.unregister(intakeRoller);
            intakeRoller.destroy();
        }
        if (chassis != null) {   // (null when building it is what failed)
            world.removeChassis(chassis);
            chassis.destroy();
        }
    }

    // ---- state ---------------------------------------------------------------------------------

    /** World position of the chassis body (its center of mass). */
    public DVector3C position() {
        return chassis.getPosition();
    }

    public DMatrix3C rotation() {
        return chassis.getRotation();
    }

    /**
     * World position of the point {@link #chassisRestZ()} above the robot-frame origin: the
     * chassis box's center for a robot built from numbers, the reference the 3D view and the
     * webcam renderer hang the robot's drawing on.
     */
    public DVector3 centerPosition() {
        return pointToWorld(0, 0, oz);
    }

    /** Heading: angle of the robot's +x axis in the field's x-y plane, CCW from +x. */
    public double heading() {
        DMatrix3C r = chassis.getRotation();
        return Math.atan2(r.get10(), r.get00());
    }

    /**
     * Linear velocity in the robot frame (x forward, y left, z up) of the point above the robot
     * frame's origin, which odometry pod and sensor offsets are measured from. (A CAD robot's
     * body is centered on its center of mass, which a turn in place moves sideways.)
     */
    public double[] bodyVelocity() {
        DVector3 world = new DVector3(), v = new DVector3();
        chassis.getRelPointVel(-ox, -oy, 0, world);
        chassis.vectorFromWorld(world, v);
        return new double[]{v.get0(), v.get1(), v.get2()};
    }

    /** Angular velocity in the robot frame. */
    public double[] bodyAngularVelocity() {
        DVector3 w = new DVector3();
        chassis.vectorFromWorld(chassis.getAngularVel(), w);
        return new double[]{w.get0(), w.get1(), w.get2()};
    }

    /** A robot-frame point in the world. */
    public DVector3 pointToWorld(double x, double y, double z) {
        DVector3 out = new DVector3();
        chassis.getRelPointPos(x - ox, y - oy, z - oz, out);
        return out;
    }

    /** A world point in the robot frame. */
    public double[] toRobotFrame(DVector3C world) {
        DVector3 local = new DVector3();
        chassis.getPosRelPoint(world, local);
        return new double[]{local.get0() + ox, local.get1() + oy, local.get2() + oz};
    }

    public DVector3 vectorToWorld(double x, double y, double z) {
        DVector3 out = new DVector3();
        chassis.vectorToWorld(x, y, z, out);
        return out;
    }

    /** Height of the chassis body origin above the floor when resting. */
    public double chassisRestZ() {
        return oz;
    }

    /** The chassis body's origin (its center of mass) in the robot frame. */
    public double[] bodyOrigin() {
        return new double[]{ox, oy, oz};
    }

    /** The robot frame in the world, as a rigid transform ({@link Geom3}). */
    public double[] robotTransform() {
        DMatrix3C r = chassis.getRotation();
        double[] rr = {r.get00(), r.get01(), r.get02(), r.get10(), r.get11(), r.get12(), r.get20(), r.get21(), r.get22()};
        DVector3C p = chassis.getPosition();
        double[] o = Geom3.mul(rr, new double[]{ox, oy, oz});
        return Geom3.transform(rr, new double[]{p.get0() - o[0], p.get1() - o[1], p.get2() - o[2]});
    }

    /**
     * Where each CAD group is: transforms that take its rest-pose geometry (robot frame) into the
     * world. Spinners turn by {@code spinnerAngle} (radians, by group index) about their axis;
     * wheels by their hinge angle. Null without a CAD model.
     */
    public double[][] groupTransforms(IntToDoubleFunction spinnerAngle) {
        if (model == null) return null;
        int n = model.groups.size();
        double[][] out = new double[n][];
        out[0] = robotTransform();
        for (int i = 1; i < n; i++) {
            RobotModel.Group g = model.groups.get(i);
            switch (g.kind) {
                case LINK -> {
                    DBody b = linkOfGroup[i].body;
                    DMatrix3C r = b.getRotation();
                    double[] rr = {r.get00(), r.get01(), r.get02(), r.get10(), r.get11(), r.get12(), r.get20(), r.get21(), r.get22()};
                    DVector3C p = b.getPosition();
                    double[] o = Geom3.mul(rr, g.com);
                    out[i] = Geom3.transform(rr, new double[]{p.get0() - o[0], p.get1() - o[1], p.get2() - o[2]});
                }
                case SPINNER -> out[i] = Geom3.compose(out[g.parentIndex], g.motion(spinnerAngle.applyAsDouble(i)));
                case WHEEL -> {
                    double angle = 0;
                    for (Wheel w : wheels) if (w.key.equals(g.wheelKey)) angle = w.angle();
                    out[i] = Geom3.compose(out[0], g.motion(angle));
                }
                default -> out[i] = out[0];
            }
        }
        return out;
    }
}
