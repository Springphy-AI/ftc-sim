package ftcsim.physics;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.ode4j.math.DMatrix3;
import org.ode4j.math.DMatrix3C;
import org.ode4j.math.DVector3;
import org.ode4j.math.DVector3C;
import org.ode4j.ode.DBody;
import org.ode4j.ode.DContact;
import org.ode4j.ode.DContactBuffer;
import org.ode4j.ode.DContactJoint;
import org.ode4j.ode.DGeom;
import org.ode4j.ode.DJoint;
import org.ode4j.ode.DJointGroup;
import org.ode4j.ode.DRay;
import org.ode4j.ode.DSpace;
import org.ode4j.ode.DWorld;
import org.ode4j.ode.OdeConstants;
import org.ode4j.ode.OdeHelper;

/**
 * The rigid-body world (ODE4j): floor, perimeter walls, the HIVE structure (A-frames, axle, sign
 * board), the two HIVES on their pivots ({@link HiveBody}), the FLOWERS, plus whatever bodies are
 * added (one or more robots, the game pieces). Every method must be called on the physics thread.
 */
public final class PhysicsWorld {

    /** Collision categories. */
    public static final long CAT_GROUND = 1, CAT_STATIC = 2, CAT_CHASSIS = 4, CAT_WHEEL = 8, CAT_BALL = 16;

    /** How a geom behaves in contacts. */
    public static final class Material {
        public final String kind;           // ground, wall, chassis, wheel, ball
        /** For wheels: traction direction in the robot frame (null = isotropic). */
        public double[] wheelTractionDir;
        /** For wheels: the chassis whose frame {@link #wheelTractionDir} is in. */
        public DBody tractionFrame;
        public double mu = 0.5;
        public double mu2 = 0.5;
        public double bounce = 0.05;
        /** What the geom belongs to (a robot, a ball). Geoms with the same owner never collide. */
        public Object owner;
        /** Until world step {@link #passThroughUntil}, this geom goes through geoms owned by {@code passThrough}. */
        public Object passThrough;
        public long passThroughUntil;

        public Material(String kind) {
            this.kind = kind;
        }
    }

    /** A contact on a robot's chassis during the last step, in world coordinates. */
    public record ChassisContact(double x, double y, double z) {
    }

    private static boolean initialized = false;

    public final DWorld world;
    public final DSpace space;
    public final FieldModel field;
    private final DJointGroup contactGroup;
    private static final int MAX_CONTACTS = 8;
    /** Wheel creep per newton of traction force, (m/s)/N. */
    private static final double WHEEL_SLIP = 0.002;
    private final DContactBuffer contacts = new DContactBuffer(MAX_CONTACTS);
    private final Map<DGeom, Material> materials = new IdentityHashMap<>();
    /** Contacts on each robot's chassis during the last step (for touch sensors). */
    private final Map<DBody, List<ChassisContact>> chassisContacts = new IdentityHashMap<>();
    /** The red and blue HIVES, in biobuzz-field.json order. */
    public final List<HiveBody> hives = new ArrayList<>();

    public PhysicsWorld(FieldModel field) {
        synchronized (PhysicsWorld.class) {
            if (!initialized) {
                OdeHelper.initODE2(0);
                initialized = true;
            }
        }
        this.field = field;
        world = OdeHelper.createWorld();
        world.setGravity(0, 0, -9.81);
        world.setERP(0.2);
        world.setCFM(1e-6);
        world.setQuickStepNumIterations(40);
        world.setContactSurfaceLayer(0.0005);
        world.setContactMaxCorrectingVel(1.0);
        space = OdeHelper.createHashSpace();
        contactGroup = OdeHelper.createJointGroup();
        buildField();
    }

    private void buildField() {
        DGeom ground = OdeHelper.createPlane(space, 0, 0, 1, 0);
        register(ground, new Material("ground"), CAT_GROUND, CAT_CHASSIS | CAT_WHEEL | CAT_BALL);

        double h = field.half, t = 0.05, wh = field.wallHeight, len = 2 * h + 2 * t;
        addStaticBox("wall", h + t / 2, 0, wh / 2, t, len, wh);
        addStaticBox("wall", -h - t / 2, 0, wh / 2, t, len, wh);
        addStaticBox("wall", 0, h + t / 2, wh / 2, len, t, wh);
        addStaticBox("wall", 0, -h - t / 2, wh / 2, len, t, wh);
        for (FieldModel.Box b : field.boxes) {
            addStaticBox("structure", b.cx(), b.cy(), b.cz(), b.sx(), b.sy(), b.sz());
        }
        for (FieldModel.Panel p : field.panels) {
            DGeom box = OdeHelper.createBox(space, p.size()[0], p.size()[1], p.size()[2]);
            box.setPosition(p.center()[0], p.center()[1], p.center()[2]);
            double[] m = p.rot();
            box.setRotation(new DMatrix3(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8]));
            // Aluminum and polycarbonate: smoother than the tiles. The ball sets the bounce.
            Material mat = new Material("structure");
            mat.mu = mat.mu2 = 0.25;
            register(box, mat, CAT_STATIC, CAT_CHASSIS | CAT_WHEEL | CAT_BALL);
        }
        for (FieldModel.Hive hive : field.hives) hives.add(new HiveBody(this, hive, field.hivePanels));
        for (FieldModel.Post p : field.posts) {
            DGeom c = OdeHelper.createCylinder(space, p.radius(), p.height());
            c.setPosition(p.cx(), p.cy(), p.height() / 2);
            Material m = new Material("structure");
            m.mu = m.mu2 = 0.3;
            register(c, m, CAT_STATIC, CAT_CHASSIS | CAT_WHEEL | CAT_BALL);
        }
    }

    private void addStaticBox(String kind, double x, double y, double z, double sx, double sy, double sz) {
        DGeom box = OdeHelper.createBox(space, sx, sy, sz);
        box.setPosition(x, y, z);
        Material m = new Material(kind);
        m.mu = m.mu2 = 0.3;
        register(box, m, CAT_STATIC, CAT_CHASSIS | CAT_WHEEL | CAT_BALL);
    }

    public void register(DGeom geom, Material material, long category, long collide) {
        materials.put(geom, material);
        geom.setCategoryBits(category);
        geom.setCollideBits(collide);
    }

    public void unregister(DGeom geom) {
        materials.remove(geom);
    }

    /** Starts recording contacts on a robot's chassis. */
    public void addChassis(DBody chassis) {
        chassisContacts.put(chassis, new ArrayList<>());
    }

    public void removeChassis(DBody chassis) {
        chassisContacts.remove(chassis);
    }

    /** Contacts on this chassis during the last step, with anything: the field, balls, other robots. */
    public List<ChassisContact> chassisContacts(DBody chassis) {
        List<ChassisContact> l = chassisContacts.get(chassis);
        return l == null ? List.of() : l;
    }

    /** Both HIVES back at their start position. */
    public void resetHives() {
        for (HiveBody h : hives) h.reset();
    }

    /** Both HIVES at their current tilt, for CELL geometry. */
    public List<FieldModel.Hive> currentHives() {
        List<FieldModel.Hive> out = new ArrayList<>();
        for (HiveBody h : hives) out.add(h.current());
        return out;
    }

    /** World steps taken so far. */
    public long steps() {
        return steps;
    }

    private long steps;

    public void step(double dt) {
        steps++;
        for (List<ChassisContact> l : chassisContacts.values()) l.clear();
        for (HiveBody h : hives) h.beforeStep();
        space.collide(null, this::nearCallback);
        world.quickStep(dt);
        contactGroup.empty();
    }

    private void nearCallback(Object data, DGeom g1, DGeom g2) {
        DBody b1 = g1.getBody(), b2 = g2.getBody();
        if (b1 == null && b2 == null) return;
        // Skip bodies joined by a real joint (a wheel and its chassis). Contact joints made earlier in
        // this step don't count: a ball touching a HIVE's floor must still hit the same HIVE's walls.
        if (b1 != null && b2 != null && joinedByRealJoint(b1, b2)) return;
        Material m1 = materials.get(g1), m2 = materials.get(g2);
        if (m1 == null || m2 == null) return;
        if (m1.owner != null && m1.owner == m2.owner) return;   // two parts of the same robot
        // A ball the robot just launched or spat out leaves through the robot's own hood and ramps.
        if (m1.passThrough != null && m1.passThrough == m2.owner && steps < m1.passThroughUntil) return;
        if (m2.passThrough != null && m2.passThrough == m1.owner && steps < m2.passThroughUntil) return;
        int n = OdeHelper.collide(g1, g2, MAX_CONTACTS, contacts.getGeomBuffer());
        if (n == 0) return;
        List<ChassisContact> on1 = "chassis".equals(m1.kind) ? chassisContacts.get(b1) : null;
        List<ChassisContact> on2 = "chassis".equals(m2.kind) ? chassisContacts.get(b2) : null;
        for (int i = 0; i < n; i++) {
            DContact c = contacts.get(i);
            configureSurface(c, g1, m1, m2);
            DContactJoint joint = OdeHelper.createContactJoint(world, contactGroup, c);
            joint.attach(b1, b2);
            if (on1 != null || on2 != null) {
                DVector3C p = c.geom.pos;
                ChassisContact cc = new ChassisContact(p.get0(), p.get1(), p.get2());
                if (on1 != null) on1.add(cc);
                if (on2 != null) on2.add(cc);
            }
        }
    }

    private static boolean joinedByRealJoint(DBody a, DBody b) {
        for (int i = 0; i < a.getNumJoints(); i++) {
            DJoint j = a.getJoint(i);
            if (j instanceof DContactJoint) continue;
            if (j.getBody(0) == b || j.getBody(1) == b) return true;
        }
        return false;
    }

    private void configureSurface(DContact c, DGeom g1, Material m1, Material m2) {
        Material wheel = "wheel".equals(m1.kind) ? m1 : ("wheel".equals(m2.kind) ? m2 : null);
        Material other = wheel == m1 ? m2 : m1;
        if (wheel != null && ("ground".equals(other.kind))) {
            // Force-dependent slip models tread/roller compliance: wheels creep slightly under load,
            // as real wheels do, instead of locking rigidly against each other.
            c.surface.mode = OdeConstants.dContactApprox1 | OdeConstants.dContactSoftCFM | OdeConstants.dContactSoftERP
                    | OdeConstants.dContactSlip1 | OdeConstants.dContactSlip2;
            c.surface.soft_cfm = 1e-5;
            c.surface.soft_erp = 0.3;
            c.surface.slip1 = WHEEL_SLIP;
            c.surface.slip2 = WHEEL_SLIP;
            c.surface.mu = wheel.mu;
            if (wheel.wheelTractionDir != null && wheel.tractionFrame != null) {
                // Mecanum/omni rollers: grip along the roller axis (fdir1), roll freely across it.
                double rx = wheel.wheelTractionDir[0], ry = wheel.wheelTractionDir[1];
                DMatrix3C r = wheel.tractionFrame.getRotation();
                double wx = r.get00() * rx + r.get01() * ry;
                double wy = r.get10() * rx + r.get11() * ry;
                double wz = r.get20() * rx + r.get21() * ry;
                DVector3C nrm = c.geom.normal;
                double d = wx * nrm.get0() + wy * nrm.get1() + wz * nrm.get2();
                wx -= d * nrm.get0();
                wy -= d * nrm.get1();
                wz -= d * nrm.get2();
                double len = Math.sqrt(wx * wx + wy * wy + wz * wz);
                if (len > 1e-6) {
                    c.fdir1.set(wx / len, wy / len, wz / len);
                    c.surface.mode |= OdeConstants.dContactFDir1 | OdeConstants.dContactMu2;
                    c.surface.mu2 = wheel.mu2;
                }
            }
            return;
        }
        c.surface.mode = OdeConstants.dContactApprox1 | OdeConstants.dContactBounce
                | OdeConstants.dContactSoftCFM | OdeConstants.dContactSoftERP;
        c.surface.mu = Math.sqrt(m1.mu * m2.mu);
        c.surface.bounce = Math.max(m1.bounce, m2.bounce);
        c.surface.bounce_vel = 0.1;
        c.surface.soft_cfm = 1e-5;
        c.surface.soft_erp = 0.3;
    }

    /**
     * Casts a ray and returns the distance to the first surface hit, ignoring geoms owned by
     * {@code ignoreOwner}; {@link Double#MAX_VALUE} if nothing is within {@code maxRange}.
     */
    public double raycast(double ox, double oy, double oz, double dx, double dy, double dz,
                          double maxRange, Object ignoreOwner) {
        DRay ray = OdeHelper.createRay(null, maxRange);
        try {
            ray.set(ox, oy, oz, dx, dy, dz);
            final double[] best = {Double.MAX_VALUE};
            final DContactBuffer buf = new DContactBuffer(4);
            ray.collide2(space, null, (d, a, b) -> {
                DGeom other = a == ray ? b : a;
                Material m = materials.get(other);
                if (m == null || (ignoreOwner != null && m.owner == ignoreOwner)) return;
                int n = OdeHelper.collide(ray, other, 4, buf.getGeomBuffer());
                for (int i = 0; i < n; i++) {
                    double depth = buf.get(i).geom.depth;
                    if (depth < best[0]) best[0] = depth;
                }
            });
            return best[0] <= maxRange ? best[0] : Double.MAX_VALUE;
        } finally {
            ray.destroy();
        }
    }

    /** Body-frame point to world (helper). */
    public static DVector3 toWorld(DBody body, double x, double y, double z) {
        DVector3 out = new DVector3();
        body.getRelPointPos(x, y, z, out);
        return out;
    }
}
