package ftcsim.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.ode4j.math.DVector3;
import org.ode4j.math.DVector3C;
import org.ode4j.ode.DBody;
import org.ode4j.ode.DGeom;
import org.ode4j.ode.DMass;
import org.ode4j.ode.OdeHelper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ftcsim.cad.Geom3;
import ftcsim.cad.RobotModel;
import ftcsim.config.RobotConfig;
import ftcsim.physics.FieldModel;
import ftcsim.physics.Mechanism;
import ftcsim.physics.PhysicsWorld;
import ftcsim.physics.ServoUnit;

/**
 * The BIOBUZZ SCORING ELEMENTS: 40 yellow POLLEN (7.1 cm) and 8 red + 8 blue NECTAR (9.1 cm),
 * staged as Competition Manual section 10.3.1 describes, plus how each robot's intake, feeder and
 * launcher handle them. Every method must be called on the physics thread.
 *
 * <p>A ball is in one of three states:
 * <ul>
 *   <li>{@code FREE}: a rigid sphere in the physics world. It rolls, bounces, is pushed by the
 *       robot and other balls, and can land in (or bounce off) a HIVE CELL.</li>
 *   <li>{@code HELD}: inside the robot (pre-load or picked up by the intake). It rides along and
 *       does not collide. At most {@link #ROBOT_CAPACITY} at once (rule G407).</li>
 *   <li>{@code STAGED}: parked where the field holds it and the simulator does not model the holder
 *       as a container: stacked in a FLOWER, or in the ALLIANCE AREA waiting for the human
 *       player.</li>
 * </ul>
 */
public final class GameElements {

    /** G407: a ROBOT may not simultaneously CONTROL more than 4 SCORING ELEMENTS. */
    public static final int ROBOT_CAPACITY = 4;
    /** 10.3.1: every ROBOT starts the MATCH with 4 pre-loaded POLLEN. */
    public static final int PRELOAD = 4;

    public enum State {FREE, HELD, STAGED}

    /** One POLLEN or NECTAR. */
    public static final class Ball {
        public final int id;
        public final String kind;      // pollen, nectar
        public final String color;     // yellow, red, blue
        public final double radius;
        final double mass;
        /** 1/2 rho Cd A: air drag force = dragFactor * speed^2. */
        final double dragFactor;
        final DBody body;
        final DGeom geom;
        PhysicsWorld.Material material;
        State state = State.FREE;
        /** Where a STAGED ball sits (world frame). */
        final double[] stagedAt = new double[3];

        Ball(int id, String kind, String color, double radius, double mass, DBody body, DGeom geom) {
            this.id = id;
            this.kind = kind;
            this.color = color;
            this.radius = radius;
            this.mass = mass;
            this.dragFactor = 0.5 * AIR_DENSITY * DRAG_COEFFICIENT * Math.PI * radius * radius;
            this.body = body;
            this.geom = geom;
        }

        public State state() {
            return state;
        }

        public DVector3C position() {
            return body.getPosition();
        }

        public DVector3C velocity() {
            return body.getLinearVel();
        }
    }

    // In the air: quadratic drag. POLLEN and NECTAR are perforated plastic balls; Cd ~0.5 is an estimate.
    private static final double AIR_DENSITY = 1.2;               // kg/m^3
    private static final double DRAG_COEFFICIENT = 0.5;
    // On the foam tiles: rolling resistance, a force of ROLLING_RESISTANCE * m * g against the motion.
    private static final double ROLLING_RESISTANCE = 0.04;
    private static final double GRAVITY = 9.81;
    // Intake: a ball is pulled in when its center is in this box in front of the roller (robot frame).
    private static final double INTAKE_REACH_M = 0.07;
    private static final double INTAKE_MIN_OMEGA = 20;          // rad/s at the intake motor
    private static final double INTAKE_INTERVAL_S = 0.12;
    // Feeder: one ball per interval while the feeder turns forward.
    private static final double FEED_INTERVAL_S = 0.25;
    private static final double FEED_MIN_DEG_PER_SEC = 30;
    // Launcher: a single flywheel against a hood gives the ball about half the wheel's surface speed.
    private static final double LAUNCH_EFFICIENCY = 0.5;
    private static final double LAUNCH_MIN_SPEED = 0.5;          // m/s; below this the ball stalls at the wheel
    private static final double LAUNCH_ANGLE_AT_CENTER_DEG = 50; // hood servo at 0.5
    private static final double LAUNCH_ANGLE_PER_SERVO_DEG = 25.0 / 150.0;
    private static final double LAUNCH_ANGLE_MIN_DEG = 15, LAUNCH_ANGLE_MAX_DEG = 80;
    private static final double LAUNCHER_X_M = -0.08, LAUNCHER_ABOVE_TOP_M = 0.31;

    /** One robot on the field: its intake, feeder and launcher, and the balls inside it. */
    private static final class Holder {
        final String alliance;
        RobotRig rig;
        /** Balls inside the robot, in the order they will leave it (first = next to the launcher). */
        final List<Ball> held = new ArrayList<>();
        Mechanism intake, launcher;
        ServoUnit feeder, hood;
        /** The CAD roller and flywheel, or null. */
        CadPart intakePart, launcherPart;
        double launcherWheelRadius;
        double sinceIntake = 1, sinceFeed = 1;
        int launched = 0;

        Holder(RobotRig rig, String alliance) {
            this.alliance = alliance;
            bind(rig);
        }

        /** Finds the mechanisms by their role in the robot description. */
        void bind(RobotRig r) {
            rig = r;
            intake = launcher = null;
            feeder = hood = null;
            for (Mechanism m : r.mechanisms) {
                if ("intake".equals(m.device.role)) intake = m;
                if ("launcher".equals(m.device.role)) launcher = m;
            }
            for (ServoUnit s : r.servos) {
                if ("feeder".equals(s.device.role)) feeder = s;
                if ("hood".equals(s.device.role)) hood = s;
            }
            launcherWheelRadius = launcher == null ? 0 : launcher.device.load.wheelDiameterM / 2;
            intakePart = intake == null ? null : cadPart(r, intake.device.name);
            launcherPart = launcher == null ? null : cadPart(r, launcher.device.name);
            CadPart fly = launcherPart;
            if (fly != null) {
                // The CAD flywheel's size across its axis.
                double[] a = fly.group.axis, fb = fly.bounds;
                double rad = 0;
                for (int k = 0; k < 3; k++) if (Math.abs(a[k]) < 0.7) rad = Math.max(rad, (fb[k + 3] - fb[k]) / 2);
                if (rad > 0.01) launcherWheelRadius = rad;
            }
        }
    }

    /** A CAD roller or flywheel that a device turns, with its rest-pose bounds (robot frame). */
    static final class CadPart {
        final RobotRig rig;
        final RobotModel.Group group;
        /** The group whose pose the part's place follows: a roller's or flywheel's own spin doesn't move it. */
        final int frame;
        final double[] bounds;

        CadPart(RobotRig rig, RobotModel.Group group, int index) {
            this.rig = rig;
            this.group = group;
            this.frame = group.kind == RobotModel.Kind.SPINNER ? group.parentIndex : index;
            this.bounds = group.bounds();
        }

        /** Where the part is now (world from rest geometry), however far it has turned on its axis. */
        double[] transform() {
            return rig.groupTransforms()[frame];
        }

        /** A world point in the group's rest-pose frame. */
        double[] local(DVector3C world) {
            return Geom3.apply(Geom3.inverse(transform()), new double[]{world.get0(), world.get1(), world.get2()});
        }

        DVector3 world(double[] rest) {
            double[] w = Geom3.apply(transform(), rest);
            return new DVector3(w[0], w[1], w[2]);
        }
    }

    /** The CAD part a device turns, or null when the robot has no CAD model or the device drives none. */
    static CadPart cadPart(RobotRig rig, String device) {
        RobotModel m = rig.config.model();
        if (m == null) return null;
        RobotModel.Group g = m.drivenBy(device);
        if (g == null || g.parts.isEmpty()) return null;
        return new CadPart(rig, g, m.index(g));
    }

    private final FieldModel field;
    private final PhysicsWorld world;
    private final List<Ball> balls = new ArrayList<>();
    private final List<Holder> robots = new ArrayList<>();

    /** The alliance of a single simulated robot (the field file's {@code alliance}, red by default). */
    public static String homeAlliance(FieldModel field) {
        return field.json.has("alliance") ? field.json.get("alliance").getAsString() : "red";
    }

    /** Game pieces for a field with one robot, on the home alliance. */
    public GameElements(FieldModel field, RobotRig rig) {
        this(field, List.of(rig), List.of(homeAlliance(field)));
    }

    /** Game pieces for a field with these robots (all in the same world), on these alliances ("red", "blue"). */
    public GameElements(FieldModel field, List<RobotRig> rigs, List<String> alliances) {
        this.field = field;
        this.world = rigs.get(0).world;
        for (int i = 0; i < rigs.size(); i++) robots.add(new Holder(rigs.get(i), alliances.get(i)));

        JsonObject se = field.json.getAsJsonObject("scoring_elements");
        JsonObject pollen = se.getAsJsonObject("pollen");
        JsonObject nectar = se.getAsJsonObject("nectar");
        double pr = pollen.get("diameter_m").getAsDouble() / 2, pm = pollen.get("mass_kg").getAsDouble();
        double nr = nectar.get("diameter_m").getAsDouble() / 2, nm = nectar.get("mass_kg").getAsDouble();
        int pollenCount = pollen.get("count").getAsInt();
        int nectarPerColor = nectar.get("count_per_color").getAsInt();
        for (int i = 0; i < pollenCount; i++) balls.add(create("pollen", "yellow", pr, pm));
        for (String c : new String[]{"red", "blue"}) {
            for (int i = 0; i < nectarPerColor; i++) balls.add(create("nectar", c, nr, nm));
        }
        restage();
    }

    private Ball create(String kind, String color, double r, double mass) {
        DBody body = OdeHelper.createBody(world.world);
        DMass m = OdeHelper.createMass();
        // Hollow polyethylene shell: I = 2/3 m r^2.
        double i = 2.0 / 3.0 * mass * r * r;
        m.setParameters(mass, 0, 0, 0, i, i, i, 0, 0, 0);
        body.setMass(m);
        // No velocity damping: it would also slow balls in flight. Air drag and rolling resistance
        // are applied as forces in applyAirAndRolling(); only spin decays here.
        body.setLinearDamping(0);
        body.setAngularDamping(0.0015);
        body.setAutoDisableFlag(true);
        body.setAutoDisableLinearThreshold(0.003);
        body.setAutoDisableAngularThreshold(0.03);
        body.setAutoDisableSteps(200);
        DGeom geom = OdeHelper.createSphere(world.space, r);
        geom.setBody(body);
        PhysicsWorld.Material mat = new PhysicsWorld.Material("ball");
        mat.mu = mat.mu2 = 0.6;
        mat.bounce = 0.35;
        Ball b = new Ball(balls.size(), kind, color, r, mass, body, geom);
        mat.owner = b;
        b.material = mat;
        world.register(geom, mat, PhysicsWorld.CAT_BALL, PhysicsWorld.CAT_GROUND | PhysicsWorld.CAT_STATIC
                | PhysicsWorld.CAT_CHASSIS | PhysicsWorld.CAT_WHEEL | PhysicsWorld.CAT_BALL);
        return b;
    }

    // ---- staging (Competition Manual 10.3.1) ---------------------------------------------------

    /**
     * Puts every SCORING ELEMENT where it starts a MATCH:
     * <ul>
     *   <li>POLLEN: 4 in each FLOWER, 4 in each GARDEN, 4 pre-loaded in each simulated robot. The
     *       pre-loads of absent ROBOTS (each ALLIANCE has two) go in the center of their LOADING
     *       ZONE against the perimeter wall (10.3.4).</li>
     *   <li>NECTAR: 3 in each upward-facing CELL of its color (free balls, held by the CELL's
     *       walls), 5 in each ALLIANCE AREA.</li>
     * </ul>
     * Both HIVES go back to their start position.
     */
    public void restage() {
        world.resetHives();
        for (Holder r : robots) {
            r.held.clear();
            r.launched = 0;
            r.sinceIntake = r.sinceFeed = 1;
        }
        List<Ball> pollen = new ArrayList<>(), red = new ArrayList<>(), blue = new ArrayList<>();
        for (Ball b : balls) {
            if ("pollen".equals(b.kind)) pollen.add(b);
            else if ("red".equals(b.color)) red.add(b);
            else blue.add(b);
        }
        int p = 0;
        // FLOWERS: 4 stacked in each. The lowest sits in the 2.79 in hole of the bottom ring, on the
        // tiles (9.7); the others rest on top of it. (The 1.25 in backstop is the guide at the top.)
        JsonObject flowers = field.json.getAsJsonObject("flowers");
        for (String name : flowers.keySet()) {
            JsonObject f = flowers.getAsJsonObject(name);
            JsonArray axis = f.getAsJsonArray("axis_m");
            for (int i = 0; i < 4; i++) {
                Ball b = pollen.get(p++);
                stage(b, axis.get(0).getAsDouble(), axis.get(1).getAsDouble(), b.radius + i * 2 * b.radius);
            }
        }
        // GARDENS: 4 in a row along the wall.
        JsonObject gardens = field.json.getAsJsonObject("gardens");
        for (String alliance : gardens.keySet()) {
            JsonObject g = gardens.getAsJsonObject(alliance);
            double x0 = g.getAsJsonArray("x_m").get(0).getAsDouble(), x1 = g.getAsJsonArray("x_m").get(1).getAsDouble();
            double y0 = g.getAsJsonArray("y_m").get(0).getAsDouble(), y1 = g.getAsJsonArray("y_m").get(1).getAsDouble();
            boolean wallAtLowY = Math.abs(y0 + field.half) < Math.abs(y1 - field.half);
            for (int i = 0; i < 4; i++) {
                Ball b = pollen.get(p++);
                double x = x0 + (x1 - x0) * (i + 0.5) / 4;
                double y = wallAtLowY ? -field.half + b.radius + 0.002 : field.half - b.radius - 0.002;
                free(b, x, y, b.radius, 0, 0, 0);
            }
        }
        // Each simulated robot's pre-load.
        for (Holder r : robots) {
            for (int i = 0; i < PRELOAD; i++) hold(r, pollen.get(p++));
        }
        // Absent robots' pre-loads: two ROBOTS per ALLIANCE, minus the ones simulated.
        JsonObject zones = field.json.getAsJsonObject("loading_zones");
        for (String alliance : zones.keySet()) {
            int absent = 2 - (int) robots.stream().filter(r -> r.alliance.equals(alliance)).count();
            if (absent <= 0) continue;
            JsonObject z = zones.getAsJsonObject(alliance);
            double x0 = z.getAsJsonArray("x_m").get(0).getAsDouble(), x1 = z.getAsJsonArray("x_m").get(1).getAsDouble();
            double yc = (z.getAsJsonArray("y_m").get(0).getAsDouble() + z.getAsJsonArray("y_m").get(1).getAsDouble()) / 2;
            double wallSign = Math.abs(x0 + field.half) < Math.abs(x1 - field.half) ? -1 : 1;
            int n = absent * PRELOAD, rows = n / 2;
            for (int i = 0; i < n; i++) {
                Ball b = pollen.get(p++);
                double d = 2 * b.radius + 0.002;
                double x = wallSign * (field.half - b.radius - 0.002 - (i % 2) * d);
                double y = yc + (i / 2 - (rows - 1) / 2.0) * d;
                free(b, x, y, b.radius, 0, 0, 0);
            }
        }
        // Any POLLEN left over (a field file with a different count) waits beside the field.
        for (int k = 0; p < pollen.size(); k++) stage(pollen.get(p++), 0, -field.half - 0.25 - 0.08 * k, 0.04);

        // NECTAR: 3 in each upward-facing CELL of its color, the rest in the ALLIANCE AREA. The CELL
        // is a physical container: its NECTAR lie side by side against the closed inner end.
        for (String alliance : new String[]{"red", "blue"}) {
            List<Ball> nec = "red".equals(alliance) ? red : blue;
            int n = 0;
            FieldModel.Hive hive = field.hives.stream().filter(h -> h.alliance().equals(alliance)).findFirst().orElseThrow();
            // Side by side against the closed inner end, resting on the CELL floor.
            double sign = "rear".equals(hive.upCell()) ? 1 : -1;
            for (int i = 0; i < 3 && n < nec.size(); i++) {
                Ball b = nec.get(n++);
                double[] w = hive.toWorld((i - 1) * (2 * b.radius + 0.01), sign * (field.cellInner + b.radius + 0.002),
                        field.cellFloor + b.radius + 0.001);
                free(b, w[0], w[1], w[2], 0, 0, 0);
            }
            double wall = "red".equals(alliance) ? -1 : 1;
            for (int i = 0; n < nec.size(); i++) {
                Ball b = nec.get(n++);
                stage(b, wall * (field.half + 0.35), wall * (-0.4 + i * 0.12), b.radius);
            }
        }
    }

    private void stage(Ball b, double x, double y, double z) {
        b.state = State.STAGED;
        b.stagedAt[0] = x;
        b.stagedAt[1] = y;
        b.stagedAt[2] = z;
        b.body.setPosition(x, y, z);
        b.body.setLinearVel(0, 0, 0);
        b.body.setAngularVel(0, 0, 0);
        b.body.disable();
        b.geom.disable();
    }

    private void hold(Holder r, Ball b) {
        b.state = State.HELD;
        b.body.setLinearVel(0, 0, 0);
        b.body.setAngularVel(0, 0, 0);
        b.body.disable();
        b.geom.disable();
        r.held.add(b);
    }

    /** For a moment, the ball goes through the robot that let it go (its hood, ramps, side plates). */
    private void passThrough(Ball b, RobotRig rig) {
        b.material.passThrough = rig.body;
        b.material.passThroughUntil = world.steps() + 300;
    }

    private void free(Ball b, double x, double y, double z, double vx, double vy, double vz) {
        b.state = State.FREE;
        b.body.setPosition(x, y, z);
        b.body.setLinearVel(vx, vy, vz);
        b.body.setAngularVel(0, 0, 0);
        b.geom.enable();
        b.body.enable();
    }

    // ---- the robots' intakes, feeders and launchers --------------------------------------------

    /** Runs before each physics step: intake pick-up and eject, feeding the launcher. */
    public void beforeStep(double dt) {
        applyAirAndRolling();
        for (Holder r : robots) {
            r.sinceIntake += dt;
            r.sinceFeed += dt;
            if (r.intake != null) runIntake(r);
            if (r.feeder != null && r.launcher != null) runFeeder(r);
        }
    }

    /** After robot {@code index} was swapped for another description: the balls inside stay inside. */
    public void replaceRobot(int index, RobotRig rig) {
        robots.get(index).bind(rig);
    }

    /** Air drag on every moving free ball, plus rolling resistance while it is on the tiles. */
    private void applyAirAndRolling() {
        for (Ball b : balls) {
            if (b.state != State.FREE || !b.body.isEnabled()) continue;
            DVector3C v = b.body.getLinearVel();
            double vx = v.get0(), vy = v.get1(), vz = v.get2();
            double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (speed > 1e-4) {
                double k = b.dragFactor * speed;
                b.body.addForce(-k * vx, -k * vy, -k * vz);
            }
            double horizontal = Math.hypot(vx, vy);
            if (b.body.getPosition().get2() < b.radius + 0.003 && horizontal > 1e-4) {
                // Full force above 2 cm/s, fading to zero below so a ball comes to rest without jitter.
                double k = ROLLING_RESISTANCE * b.mass * GRAVITY / Math.max(horizontal, 0.02);
                b.body.addForce(-k * vx, -k * vy, 0);
            }
        }
    }

    private void runIntake(Holder r) {
        double omega = r.intake.motorOmega();
        if (r.sinceIntake < INTAKE_INTERVAL_S || Math.abs(omega) < INTAKE_MIN_OMEGA) return;
        RobotRig rig = r.rig;
        List<Ball> held = r.held;
        RobotConfig.Chassis c = rig.config.chassis;
        double front = c.lengthM / 2 + 0.04;   // the roller, as drawn in the 3D view
        CadPart roller = r.intakePart;
        if (omega > 0) {
            if (held.size() >= ROBOT_CAPACITY) return;
            double[] toRoller = roller == null ? null : Geom3.inverse(roller.transform());   // once per step
            for (Ball b : balls) {
                if (b.state != State.FREE) continue;
                boolean caught;
                if (roller != null) {
                    // Within reach of the CAD roller, wherever it is now.
                    DVector3C bp = b.body.getPosition();
                    double[] q = Geom3.apply(toRoller, new double[]{bp.get0(), bp.get1(), bp.get2()});
                    double m = b.radius + INTAKE_REACH_M;
                    double[] rb = roller.bounds;
                    caught = q[0] > rb[0] - m && q[0] < rb[3] + m && q[1] > rb[1] - m
                            && q[1] < rb[4] + m && q[2] > rb[2] - m && q[2] < rb[5] + m;
                } else {
                    double[] local = rig.body.toRobotFrame(b.body.getPosition());
                    double heightAboveFloor = b.body.getPosition().get2();
                    caught = local[0] > c.lengthM / 2 - 0.02 && local[0] < front + b.radius + INTAKE_REACH_M
                            && Math.abs(local[1]) < c.widthM * 0.41 && heightAboveFloor < 0.15;
                }
                if (caught) {
                    hold(r, b);
                    r.sinceIntake = 0;
                    return;
                }
            }
        } else if (!held.isEmpty()) {
            // Reversed: spit the last ball in back out over the roller.
            Ball b = held.remove(held.size() - 1);
            double x = front + b.radius + 0.03;
            DVector3 p;
            if (roller != null) {
                double[] rb = roller.bounds;
                p = roller.world(new double[]{rb[3] + b.radius + 0.03, (rb[1] + rb[4]) / 2, (rb[2] + rb[5]) / 2});
            } else {
                p = rig.body.pointToWorld(x, 0, b.radius + 0.005);
            }
            DVector3 v = rig.body.vectorToWorld(1.0, 0, 0);
            DVector3C rv = rig.body.chassis.getLinearVel();
            free(b, p.get0(), p.get1(), p.get2(), v.get0() + rv.get0(), v.get1() + rv.get1(), 0);
            passThrough(b, rig);
            r.sinceIntake = 0;
        }
    }

    private void runFeeder(Holder r) {
        if (r.held.isEmpty() || r.sinceFeed < FEED_INTERVAL_S || r.feeder.port.speedDegPerSec < FEED_MIN_DEG_PER_SEC) return;
        double gear = r.launcher.device.externalGearRatio <= 0 ? 1 : r.launcher.device.externalGearRatio;
        double speed = Math.abs(r.launcher.motorOmega()) / gear * r.launcherWheelRadius * LAUNCH_EFFICIENCY;
        if (speed < LAUNCH_MIN_SPEED) return;   // the ball sits against a stopped flywheel
        RobotRig rig = r.rig;
        Ball b = r.held.remove(0);
        double elev = Math.toRadians(launchAngle(r));
        RobotConfig.Chassis c = rig.config.chassis;
        double top = c.groundClearanceM + c.frameHeightM;
        double ex = LAUNCHER_X_M + 0.07 * Math.cos(elev), ez = top + LAUNCHER_ABOVE_TOP_M + 0.07 * Math.sin(elev);
        CadPart flywheel = r.launcherPart;
        DVector3 p;
        if (flywheel != null) {
            // Leaves the CAD flywheel's rim along the launch direction.
            double[] fb = flywheel.bounds;
            double out = r.launcherWheelRadius + b.radius + 0.005;
            p = flywheel.world(new double[]{(fb[0] + fb[3]) / 2 + out * Math.cos(elev), (fb[1] + fb[4]) / 2, (fb[2] + fb[5]) / 2 + out * Math.sin(elev)});
        } else {
            p = rig.body.pointToWorld(ex, 0, ez);
        }
        DVector3 v = rig.body.vectorToWorld(speed * Math.cos(elev), 0, speed * Math.sin(elev));
        DVector3C rv = rig.body.chassis.getLinearVel();
        free(b, p.get0(), p.get1(), p.get2(), v.get0() + rv.get0(), v.get1() + rv.get1(), v.get2() + rv.get2());
        passThrough(b, rig);
        r.sinceFeed = 0;
        r.launched++;
    }

    /** Launch elevation above horizontal set by the hood servo (50° at position 0.5). */
    private static double launchAngle(Holder r) {
        double a = LAUNCH_ANGLE_AT_CENTER_DEG + (r.hood == null ? 0 : r.hood.angleDeg() * LAUNCH_ANGLE_PER_SERVO_DEG);
        return Math.max(LAUNCH_ANGLE_MIN_DEG, Math.min(LAUNCH_ANGLE_MAX_DEG, a));
    }

    /** The first robot's launch elevation. */
    public double launchAngleDeg() {
        return launchAngle(robots.get(0));
    }

    /** Puts one ball at rest on the tiles at (x, y), out of a robot or its staging spot. */
    public void placeBall(Ball b, double x, double y) {
        throwBall(b, x, y, b.radius, 0, 0, 0);
    }

    /** Sets one ball loose at (x, y, z) moving at (vx, vy, vz), out of a robot or its staging spot. */
    public void throwBall(Ball b, double x, double y, double z, double vx, double vy, double vz) {
        for (Holder r : robots) r.held.remove(b);
        free(b, x, y, z, vx, vy, vz);
    }

    // ---- the human player ------------------------------------------------------------------------

    /** NECTAR of this color still waiting in its ALLIANCE AREA (outside the field). */
    public int waitingNectar(String color) {
        int n = 0;
        for (Ball b : balls) if ("nectar".equals(b.kind) && color.equals(b.color) && b.state == State.STAGED) n++;
        return n;
    }

    /**
     * The human player puts one NECTAR of this color ("red" or "blue") from its ALLIANCE AREA onto
     * the field, at rest in that alliance's LOADING ZONE: the first free spot along the alliance
     * wall, clear of other balls and the robots; past the zone's inner edge if the zone is full.
     *
     * @return false if none of that color is waiting or there is no free spot
     */
    public boolean introduceNectar(String color) {
        Ball b = null;
        for (Ball x : balls) {
            if ("nectar".equals(x.kind) && color.equals(x.color) && x.state == State.STAGED) {
                b = x;
                break;
            }
        }
        if (b == null) return false;
        JsonObject z = field.json.getAsJsonObject("loading_zones").getAsJsonObject(color);
        if (z == null) return false;
        double x0 = z.getAsJsonArray("x_m").get(0).getAsDouble(), x1 = z.getAsJsonArray("x_m").get(1).getAsDouble();
        double y0 = z.getAsJsonArray("y_m").get(0).getAsDouble(), y1 = z.getAsJsonArray("y_m").get(1).getAsDouble();
        double wallSign = Math.abs(x0 + field.half) < Math.abs(x1 - field.half) ? -1 : 1;
        double yc = (y0 + y1) / 2, halfLen = (y1 - y0) / 2, r = b.radius, step = 2 * r + 0.01;
        // Columns from the wall inward; in each, spots from the zone's middle outward.
        for (int col = 0; col < 8; col++) {
            double x = wallSign * (field.half - r - 0.003 - col * step);
            for (int k = 0; k <= 2 * (int) (halfLen / step) + 1; k++) {
                double y = yc + (k % 2 == 0 ? 1 : -1) * ((k + 1) / 2) * step;
                if (Math.abs(y - yc) > halfLen - r) continue;
                if (spotIsFree(x, y, r)) {
                    free(b, x, y, r, 0, 0, 0);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean spotIsFree(double x, double y, double r) {
        for (Ball o : balls) {
            if (o.state != State.FREE) continue;
            DVector3C p = o.body.getPosition();
            if (p.get2() < 0.3 && Math.hypot(p.get0() - x, p.get1() - y) < r + o.radius + 0.005) return false;
        }
        // Every robot: test the spot in its chassis frame against its footprint plus a margin.
        for (Holder h : robots) {
            double[] local = h.rig.body.toRobotFrame(new DVector3(x, y, r));
            RobotConfig.Chassis c = h.rig.config.chassis;
            if (Math.abs(local[0]) < c.lengthM / 2 + 0.06 + r && Math.abs(local[1]) < c.widthM / 2 + 0.03 + r) return false;
        }
        return true;
    }

    /**
     * Free balls inside each CELL, keyed "red rear", "red audience", "blue rear", "blue audience".
     * This is just where the balls are, not a MATCH score.
     */
    public Map<String, Integer> cellCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (FieldModel.Hive h : field.hives) {
            out.put(h.alliance() + " rear", 0);
            out.put(h.alliance() + " audience", 0);
        }
        List<FieldModel.Hive> hives = world.currentHives();
        for (Ball b : balls) {
            if (b.state != State.FREE) continue;
            DVector3C p = b.body.getPosition();
            String cell = field.cellAt(p.get0(), p.get1(), p.get2(), hives);
            if (cell != null) out.merge(cell, 1, Integer::sum);
        }
        return out;
    }

    /** The CELL a ball is in ("red audience"...), or null. */
    public String cellOf(Ball b) {
        if (b.state != State.FREE) return null;
        DVector3C p = b.body.getPosition();
        List<FieldModel.Hive> hives = world.currentHives();
        return field.cellAt(p.get0(), p.get1(), p.get2(), hives);
    }

    // ---- views ---------------------------------------------------------------------------------

    public List<Ball> balls() {
        return balls;
    }

    /** Balls inside the first robot. */
    public int heldCount() {
        return heldCount(0);
    }

    public int heldCount(int robot) {
        return robots.get(robot).held.size();
    }

    /** Balls the first robot has launched since the last restage. */
    public int launchedCount() {
        return launchedCount(0);
    }

    public int launchedCount(int robot) {
        return robots.get(robot).launched;
    }

    /** Ball kinds for the browser, once per connection. */
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Ball b : balls) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", b.kind);
            m.put("color", b.color);
            m.put("r", b.radius);
            out.add(m);
        }
        return out;
    }

    /** x, y, z of every ball (mm resolution), in {@link #balls()} order. HELD balls ride on the robot. */
    public double[] positions() {
        double[] out = new double[balls.size() * 3];
        for (Ball b : balls) {
            double x, y, z;
            Holder r = b.state == State.HELD ? holderOf(b) : null;
            if (r != null) {
                RobotConfig.Chassis c = r.rig.config.chassis;
                double top = c.groundClearanceM + c.frameHeightM;
                int i = r.held.indexOf(b);
                DVector3 p = r.rig.body.pointToWorld(0.12 - i * 0.075, 0, top + b.radius + 0.01 + i * 0.03);
                x = p.get0();
                y = p.get1();
                z = p.get2();
            } else if (b.state != State.FREE) {
                x = b.stagedAt[0];
                y = b.stagedAt[1];
                z = b.stagedAt[2];
            } else {
                DVector3C p = b.body.getPosition();
                x = p.get0();
                y = p.get1();
                z = p.get2();
            }
            out[b.id * 3] = Math.round(x * 1000) / 1000.0;
            out[b.id * 3 + 1] = Math.round(y * 1000) / 1000.0;
            out[b.id * 3 + 2] = Math.round(z * 1000) / 1000.0;
        }
        return out;
    }

    private Holder holderOf(Ball b) {
        for (Holder r : robots) if (r.held.contains(b)) return r;
        return null;
    }
}
