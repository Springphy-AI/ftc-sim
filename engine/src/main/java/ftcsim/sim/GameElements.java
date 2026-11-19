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

import com.google.gson.JsonObject;

import ftcsim.cad.Geom3;
import ftcsim.cad.RobotModel;
import ftcsim.config.RobotConfig;
import ftcsim.physics.FieldModel;
import ftcsim.physics.Mechanism;
import ftcsim.physics.PhysicsWorld;
import ftcsim.physics.RobotBody;
import ftcsim.physics.ServoUnit;
import ftcsim.season.SeasonCode;
import ftcsim.season.SeasonGame;

/**
 * The season's game pieces (season.json "game_pieces"), and how each robot's intake, feeder and
 * launcher handle them. Where the pieces start a match, what the human player does with them and
 * what is counted belong to the season ({@link SeasonGame}). Every method must be called on the
 * physics thread.
 *
 * <p>A piece is in one of three states:
 * <ul>
 *   <li>{@code FREE}: a rigid sphere in the physics world. It rolls, bounces, is pushed by the
 *       robots and other pieces, and lands in (or bounces off) the field's structures.</li>
 *   <li>{@code HELD}: inside a robot (pre-load or picked up by the intake). It rides along and
 *       does not collide. At most the season's robot capacity at once.</li>
 *   <li>{@code STAGED}: parked where the simulator does not model what holds it, such as an
 *       ALLIANCE AREA where it waits for the human player ({@link Ball#area()}).</li>
 * </ul>
 */
public final class GameElements {

    public enum State {FREE, HELD, STAGED}

    /** One game piece: a ball. */
    public static final class Ball {
        public final int id;
        /** The kind of game piece (season.json "game_pieces" key): "pollen", "artifact"... */
        public final String kind;
        /** Its color name ("yellow", "purple"...). */
        public final String color;
        /** Its color as 0xRRGGBB, for drawing. */
        public final int rgb;
        public final double radius;
        final double mass;
        /** 1/2 rho Cd A: air drag force = dragFactor * speed^2. */
        final double dragFactor;
        final double rollingResistance;
        /** Drawn as a perforated (wiffle-style) shell. */
        final boolean perforated;
        final DBody body;
        final DGeom geom;
        PhysicsWorld.Material material;
        State state = State.FREE;
        /** Where a STAGED ball sits (world frame). */
        final double[] stagedAt = new double[3];
        /** The alliance whose ALLIANCE AREA a STAGED ball waits in, or null. */
        String area;

        Ball(int id, PieceType t, String color, int rgb, DBody body, DGeom geom) {
            this.id = id;
            this.kind = t.name;
            this.color = color;
            this.rgb = rgb;
            this.radius = t.radius;
            this.mass = t.mass;
            this.dragFactor = 0.5 * AIR_DENSITY * t.dragCoefficient * Math.PI * radius * radius;
            this.rollingResistance = t.rollingResistance;
            this.perforated = t.perforated;
            this.body = body;
            this.geom = geom;
        }

        public State state() {
            return state;
        }

        /** A free ball the physics engine is moving; false while it rests asleep (or is not free). */
        public boolean awake() {
            return state == State.FREE && body.isEnabled();
        }

        /** The alliance whose ALLIANCE AREA this ball waits in for the human player, or null. */
        public String area() {
            return state == State.STAGED ? area : null;
        }

        public DVector3C position() {
            return body.getPosition();
        }

        public DVector3C velocity() {
            return body.getLinearVel();
        }
    }

    /** One kind of game piece, from season.json "game_pieces". */
    private record PieceType(String name, double radius, double mass, double bounce, double friction,
                             double dragCoefficient, double rollingResistance, boolean perforated) {
    }

    private static final double AIR_DENSITY = 1.2;               // kg/m^3
    private static final double GRAVITY = 9.81;
    // Intake, CAD roller: a ball is pulled in when its center is within this margin of the roller's box.
    private static final double INTAKE_REACH_M = 0.07;
    // Intake, the robot file's roller (solid, RobotBody): the spinning roller pulls in a ball that
    // touches it (within this gap of its surface), across most of the robot's width. Balls in front
    // of it get pushed until they touch; through a FLOWER's Retrieval Opening it reaches the bottom
    // POLLEN, and each one above as it drops, but not balls higher up the tube.
    private static final double ROLLER_REACH_M = 0.05;
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
    /** At most this many pieces inside a robot at once (season.json "robot": "capacity"). */
    private final int capacity;
    /** The season's staging, human player and counting. */
    private final SeasonGame game;

    /** The alliance of a single simulated robot (season.json {@code alliance}, red by default). */
    public static String homeAlliance(FieldModel field) {
        return field.season.homeAlliance;
    }

    /** Game pieces for a field with one robot, on the home alliance. */
    public GameElements(FieldModel field, RobotRig rig) {
        this(field, List.of(rig), List.of(homeAlliance(field)));
    }

    /** Game pieces for a field with these robots (all in the same world), on these alliances ("red", "blue"). */
    public GameElements(FieldModel field, List<RobotRig> rigs, List<String> alliances) {
        this.field = field;
        this.world = rigs.get(0).world;
        this.capacity = field.season.robotCapacity;
        for (int i = 0; i < rigs.size(); i++) robots.add(new Holder(rigs.get(i), alliances.get(i)));

        // "game_pieces": {"pollen": {"shape": "sphere", "diameter_m": .., "mass_kg": .., "colors": {"yellow": 40}}}
        JsonObject pieces = field.json.has("game_pieces") ? field.json.getAsJsonObject("game_pieces") : new JsonObject();
        for (String name : pieces.keySet()) {
            JsonObject p = pieces.getAsJsonObject(name);
            String shape = p.has("shape") ? p.get("shape").getAsString() : "sphere";
            if (!"sphere".equals(shape)) {
                throw new IllegalArgumentException("game piece \"" + name + "\" is a " + shape + "; only spheres are simulated so far");
            }
            PieceType t = new PieceType(name, p.get("diameter_m").getAsDouble() / 2, p.get("mass_kg").getAsDouble(),
                    p.has("bounce") ? p.get("bounce").getAsDouble() : 0.35, p.has("friction") ? p.get("friction").getAsDouble() : 0.6,
                    p.has("drag_coefficient") ? p.get("drag_coefficient").getAsDouble() : 0.5,
                    p.has("rolling_resistance") ? p.get("rolling_resistance").getAsDouble() : 0.04,
                    p.has("perforated") && p.get("perforated").getAsBoolean());
            JsonObject colors = p.getAsJsonObject("colors");
            // "rgb": {"red": "#d8343a"}: this piece's own shade of a color, if not the palette's.
            JsonObject rgb = p.has("rgb") ? p.getAsJsonObject("rgb") : new JsonObject();
            for (String c : colors.keySet()) {
                int n = colors.get(c).getAsInt();
                int shade = field.color(rgb.has(c) ? rgb.get(c).getAsString() : c);
                for (int i = 0; i < n; i++) balls.add(create(t, c, shade));
            }
        }
        this.game = field.season.code.newGame(this);
        restage();
    }

    private Ball create(PieceType t, String color, int rgb) {
        double r = t.radius, mass = t.mass;
        DBody body = OdeHelper.createBody(world.world);
        DMass m = OdeHelper.createMass();
        // Hollow plastic shell: I = 2/3 m r^2.
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
        mat.mu = mat.mu2 = t.friction;
        mat.bounce = t.bounce;
        Ball b = new Ball(balls.size(), t, color, rgb, body, geom);
        mat.owner = b;
        b.material = mat;
        world.register(geom, mat, PhysicsWorld.CAT_BALL, PhysicsWorld.CAT_GROUND | PhysicsWorld.CAT_STATIC
                | PhysicsWorld.CAT_CHASSIS | PhysicsWorld.CAT_WHEEL | PhysicsWorld.CAT_BALL);
        return b;
    }

    // ---- staging -------------------------------------------------------------------------------

    /**
     * Puts every game piece where it starts a MATCH, empties the robots and puts the field's
     * moving parts back at their start ({@link SeasonGame#restage}).
     */
    public void restage() {
        world.resetMovers();
        for (Holder r : robots) {
            r.held.clear();
            r.launched = 0;
            r.sinceIntake = r.sinceFeed = 1;
        }
        for (Ball b : balls) b.area = null;
        game.restage();
    }

    /** Parks a ball where nothing simulated holds it (it doesn't collide), waiting in {@code area}'s ALLIANCE AREA or null. */
    public void stage(Ball b, double x, double y, double z, String area) {
        for (Holder r : robots) r.held.remove(b);
        b.state = State.STAGED;
        b.area = area;
        b.stagedAt[0] = x;
        b.stagedAt[1] = y;
        b.stagedAt[2] = z;
        b.body.setPosition(x, y, z);
        b.body.setLinearVel(0, 0, 0);
        b.body.setAngularVel(0, 0, 0);
        b.body.disable();
        b.geom.disable();
    }

    /** Parks a ball where nothing simulated holds it. */
    public void stage(Ball b, double x, double y, double z) {
        stage(b, x, y, z, null);
    }

    /** Puts a ball inside robot {@code robot} (a pre-load), behind the ones already there. */
    public void hold(int robot, Ball b) {
        hold(robots.get(robot), b);
    }

    private void hold(Holder r, Ball b) {
        if (b.state == State.FREE) wakeNeighbors(b);
        b.state = State.HELD;
        b.area = null;
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

    /**
     * Wakes resting balls near this one before it leaves. The physics engine puts still balls to
     * sleep and does not wake them when what they rest on disappears: without this, taking the
     * bottom ball out of a stack (a FLOWER's) would leave the ones above it floating.
     */
    private void wakeNeighbors(Ball b) {
        DVector3C p = b.body.getPosition();
        for (Ball o : balls) {
            if (o == b || o.state != State.FREE || o.body.isEnabled()) continue;
            DVector3C q = o.body.getPosition();
            double dx = q.get0() - p.get0(), dy = q.get1() - p.get1(), dz = q.get2() - p.get2();
            if (dx * dx + dy * dy + dz * dz < 0.5 * 0.5) o.body.enable();
        }
    }

    /** Sets a free ball's velocity (the season's own rules: a ball rolling out of a holder, say). */
    public void setVelocity(Ball b, double vx, double vy, double vz) {
        if (b.state != State.FREE) return;
        b.body.enable();
        b.body.setLinearVel(vx, vy, vz);
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

    /** Runs before each physics step: intake pick-up and eject, feeding the launcher, the season's own work. */
    public void beforeStep(double dt) {
        applyAirAndRolling();
        for (Holder r : robots) {
            r.sinceIntake += dt;
            r.sinceFeed += dt;
            if (r.intake != null) runIntake(r);
            if (r.feeder != null && r.launcher != null) runFeeder(r);
        }
        game.beforeStep(dt);
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
                double k = b.rollingResistance * b.mass * GRAVITY / Math.max(horizontal, 0.02);
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
        double front = c.lengthM / 2 + RobotBody.ROLLER_AHEAD_M;   // the roller's axis
        CadPart roller = r.intakePart;
        if (omega > 0) {
            if (held.size() >= capacity) return;
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
                    // Touching the spinning roller.
                    double[] local = rig.body.toRobotFrame(b.body.getPosition());
                    double dx = local[0] - front, dz = b.body.getPosition().get2() - RobotBody.ROLLER_HEIGHT_M;
                    caught = Math.hypot(dx, dz) < RobotBody.ROLLER_RADIUS_M + b.radius + ROLLER_REACH_M
                            && local[0] > c.lengthM / 2 - 0.02 && Math.abs(local[1]) < c.widthM * 0.41;
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
            double x = front + RobotBody.ROLLER_RADIUS_M + b.radius + 0.01;   // just clear of the roller
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
        if (b.state == State.FREE) wakeNeighbors(b);
        for (Holder r : robots) r.held.remove(b);
        free(b, x, y, z, vx, vy, vz);
    }

    // ---- the human player ------------------------------------------------------------------------

    /**
     * The first ball waiting in {@code alliance}'s ALLIANCE AREA of this kind and color (null
     * for any), or null.
     */
    public Ball waiting(String alliance, String kind, String color) {
        for (Ball b : balls) if (waits(b, alliance, kind, color)) return b;
        return null;
    }

    private static boolean waits(Ball b, String alliance, String kind, String color) {
        return b.state == State.STAGED && alliance.equals(b.area) && (kind == null || kind.equals(b.kind))
                && (color == null || color.equals(b.color));
    }

    /** How many balls of this kind and color (null for any) wait in {@code alliance}'s ALLIANCE AREA. */
    public int waitingCount(String alliance, String kind, String color) {
        int n = 0;
        for (Ball b : balls) if (waits(b, alliance, kind, color)) n++;
        return n;
    }

    /**
     * The human player puts a ball onto the field, at rest in the zone x0..x1, y0..y1: the first
     * free spot along the zone's {@code wall} side ("+x", "-x", "+y" or "-y", normally the
     * perimeter wall it touches), clear of other balls and the robots, working inward; past the
     * zone's inner edge if the zone is full.
     *
     * @return false if there is no free spot
     */
    public boolean introduce(Ball b, double x0, double x1, double y0, double y1, String wall) {
        boolean alongY = wall.endsWith("x");        // columns run along y, stepping in from an x wall
        double sign = wall.startsWith("-") ? -1 : 1;
        double a0 = alongY ? y0 : x0, a1 = alongY ? y1 : x1;
        double edge = alongY ? (sign > 0 ? x1 : x0) : (sign > 0 ? y1 : y0);
        double ac = (a0 + a1) / 2, halfLen = (a1 - a0) / 2, r = b.radius, step = 2 * r + 0.01;
        // Columns from the wall inward; in each, spots from the zone's middle outward.
        for (int col = 0; col < 8; col++) {
            double across = edge - sign * (r + 0.003 + col * step);
            for (int k = 0; k <= 2 * (int) (halfLen / step) + 1; k++) {
                double along = ac + (k % 2 == 0 ? 1 : -1) * ((k + 1) / 2) * step;
                if (Math.abs(along - ac) > halfLen - r) continue;
                double x = alongY ? across : along, y = alongY ? along : across;
                if (spotIsFree(x, y, r)) {
                    throwBall(b, x, y, r, 0, 0, 0);
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a ball of radius r could rest at (x, y) on the tiles without touching another ball or a robot. */
    public boolean spotIsFree(double x, double y, double r) {
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

    // ---- views ---------------------------------------------------------------------------------

    public List<Ball> balls() {
        return balls;
    }

    public FieldModel field() {
        return field;
    }

    public PhysicsWorld world() {
        return world;
    }

    /** The season's game on this field ({@link SeasonCode#newGame}). */
    public SeasonGame game() {
        return game;
    }

    /** At most this many pieces inside a robot at once. */
    public int capacity() {
        return capacity;
    }

    /** How many robots are on the field. */
    public int robotCount() {
        return robots.size();
    }

    /** Robot {@code robot}'s alliance, "red" or "blue". */
    public String alliance(int robot) {
        return robots.get(robot).alliance;
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
            m.put("rgb", b.rgb);
            m.put("r", b.radius);
            if (b.perforated) m.put("perforated", true);
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
