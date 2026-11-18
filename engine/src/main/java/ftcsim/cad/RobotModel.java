package ftcsim.cad;

import java.util.ArrayList;
import java.util.List;

/**
 * A robot read from CAD, in the robot frame (meters; +x forward, +y left, +z up, origin on the
 * floor under the robot's center) with every joint at its CAD position ("rest").
 *
 * <p>It is a set of groups that move as one: the base (chassis), links on revolute or prismatic
 * joints (rigid bodies in the physics), spinners on continuous joints (rollers, flywheels: they
 * turn with their mechanism but are part of their parent's body), and drivetrain wheels.
 */
public final class RobotModel {

    public enum Kind { BASE, LINK, SPINNER, WHEEL }

    public static final class Group {
        public final String name;
        public final Kind kind;
        /** Triangles in the robot frame at rest. */
        public final List<MeshPart> parts = new ArrayList<>();
        /** What it is mounted on: null for the base and for groups on the base... see {@link #parentIndex}. */
        public int parentIndex = 0;

        // ---- joint (LINK, SPINNER, WHEEL) ----
        /** A point on the joint axis and its unit direction, robot frame at rest. */
        public double[] anchor = new double[3];
        public double[] axis = {0, 1, 0};
        /** revolute, prismatic, continuous. */
        public String jointType = "fixed";
        /** Hard stops (rad or m from the CAD pose); NaN = none. */
        public double lower = Double.NaN, upper = Double.NaN;
        /** Position at reset (rad or m from the CAD pose). */
        public double start;
        /** The motor, servo or CR servo that drives it; null if none. */
        public String device;
        /** Servo-driven: joint travel per servo travel, and joint position at servo 0.5. */
        public double ratio = 1, servoCenter = 0;
        public double spoolRadius = 0.019;
        public double torqueNm = 2.5;
        /** Index of the group whose position this one follows, or -1. */
        public int mimic = -1;
        public double mimicRatio = 1, mimicOffset = 0;

        // ---- mass (BASE and LINK; a spinner's mass is part of its parent's) ----
        public double mass;
        public double[] com = new double[3];
        /** Inertia about the center of mass, robot axes, row-major. */
        public double[] inertia = new double[9];
        /** SPINNER: moment of inertia about its own axis (kg m²); NaN: the robot file's load inertia. */
        public double axisInertia;
        /** Found by its motor's name rather than listed in the robot file's cad.links. */
        public boolean found;
        /** Collision boxes, robot frame at rest. */
        public final List<MeshMath.Box> boxes = new ArrayList<>();

        // ---- WHEEL ----
        public String wheelKey;
        public double wheelRadius, wheelWidth;

        Group(String name, Kind kind) {
            this.name = name;
            this.kind = kind;
        }

        public boolean revolute() {
            return !"prismatic".equals(jointType);
        }

        /** Rigid transform (robot frame at rest to robot frame) for joint position {@code q} relative to the parent. */
        public double[] motion(double q) {
            if ("prismatic".equals(jointType)) return Geom3.transform(Geom3.IDENTITY, Geom3.scale(axis, q));
            double[] r = Geom3.axisAngle(axis, q);
            // rotate about the anchor: p' = R (p - a) + a
            return Geom3.transform(r, Geom3.sub(anchor, Geom3.mul(r, anchor)));
        }

        public double[] bounds() {
            double[] b = MeshPart.emptyBounds();
            for (MeshPart p : parts) p.addBounds(b);
            return b;
        }
    }

    /** Group 0 is the base; parents always come before their children. */
    public final List<Group> groups;
    /** Total mass, as simulated (kg). */
    public final double totalMass;
    /** What was assumed or left out while reading the CAD, for the log. */
    public final List<String> notes;
    /** Short description for the Driver Station ("model.step: 312 parts, 4 links"). */
    public final String summary;

    RobotModel(List<Group> groups, double totalMass, List<String> notes, String summary) {
        this.groups = groups;
        this.totalMass = totalMass;
        this.notes = notes;
        this.summary = summary;
    }

    public Group base() {
        return groups.get(0);
    }

    public Group wheel(String key) {
        for (Group g : groups) if (g.kind == Kind.WHEEL && key.equals(g.wheelKey)) return g;
        return null;
    }

    public Group group(String name) {
        for (Group g : groups) if (g.name.equals(name)) return g;
        return null;
    }

    /** The group a device drives, or null. */
    public Group drivenBy(String device) {
        for (Group g : groups) if (device.equals(g.device)) return g;
        return null;
    }

    public int index(Group g) {
        return groups.indexOf(g);
    }

    private volatile byte[] browserModel;
    private volatile List<List<MeshPart>> visionMesh;

    /** The model as the browser's 3D view loads it (see {@link ModelExport#browserModel}). */
    public byte[] browserModel() {
        byte[] b = browserModel;
        if (b == null) browserModel = b = ModelExport.browserModel(this);
        return b;
    }

    /**
     * Simplified triangles per group, merged by color, for the webcam renderer (robot frame at
     * rest). Triangles within 5 cm of {@code cameras} (robot-frame points) are left out, so a
     * webcam modeled in the CAD doesn't look at the inside of its own housing.
     */
    public List<List<MeshPart>> visionMesh(List<double[]> cameras) {
        List<List<MeshPart>> v = visionMesh;
        if (v == null) visionMesh = v = ModelExport.visionMesh(this, cameras);
        return v;
    }
}
