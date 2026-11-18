package ftcsim.physics;

import java.util.ArrayList;
import java.util.List;

import org.ode4j.math.DMatrix3;
import org.ode4j.ode.DBody;
import org.ode4j.ode.DGeom;
import org.ode4j.ode.DHingeJoint;
import org.ode4j.ode.DMass;
import org.ode4j.ode.OdeHelper;

/**
 * A {@link FieldModel.Mover} in the physics world: a part of the field that turns on a hinge.
 *
 * <p>A dynamic one is a rigid body hinged to the field between its angle limits. Gravity acts at
 * its center of mass, robots and game pieces push it, and a damper (a viscous torque against the
 * swing) slows it, so where its mass sits decides where it rests and how hard it is to move. A
 * kinematic one has no mass; it stays at whatever angle {@link #setAngle} gives it.
 * Every method must be called on the physics thread.
 */
public final class HingeBody {

    public final FieldModel.Mover mover;
    private final DBody body;
    private final DHingeJoint hinge;
    private final PhysicsWorld world;
    /** Its geoms; for a kinematic mover, with the parts they are (in the mover's frame). */
    private final List<DGeom> geoms = new ArrayList<>();
    private final List<FieldModel.Solid> parts = new ArrayList<>();
    private double angle;
    private double lastAngle = Double.NaN;
    /** Where its geoms were at the last step (their bounding box), or null. */
    private double[] lastBox;

    HingeBody(PhysicsWorld w, FieldModel.Mover m) {
        this.mover = m;
        this.world = w;
        if (m.dynamic()) {
            body = OdeHelper.createBody(w.world);
            DMass mass = OdeHelper.createMass();
            double[] i = m.inertia();
            mass.setParameters(m.mass(), 0, 0, 0, i[0], i[1], i[2], 0, 0, 0);
            body.setMass(mass);
            body.setAutoDisableFlag(false);
            body.setLinearDamping(0);
            body.setAngularDamping(0);
            setPose(0);
            // Body origin = center of mass; the parts sit around it.
            double[] com = m.com();
            for (FieldModel.Solid p : m.parts()) {
                if (!p.collide()) continue;
                DGeom g = PhysicsWorld.geom(w.space, p);
                g.setBody(body);
                g.setOffsetPosition(p.center()[0] - com[0], p.center()[1] - com[1], p.center()[2] - com[2]);
                double[] r = p.rot();
                g.setOffsetRotation(new DMatrix3(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]));
                register(w, g, p);
                geoms.add(g);
            }
            // The hinge measures its angle from the pose it is created in, so create it at angle 0.
            hinge = OdeHelper.createHingeJoint(w.world);
            hinge.attach(body, null);
            hinge.setAnchor(m.pivot()[0], m.pivot()[1], m.pivot()[2]);
            hinge.setAxis(m.axis()[0], m.axis()[1], m.axis()[2]);
            hinge.setParamLoStop(m.minAngle());
            hinge.setParamHiStop(m.maxAngle());
            hinge.setParamBounce(0);
        } else {
            body = null;
            hinge = null;
            for (FieldModel.Solid p : m.parts()) {
                if (!p.collide()) continue;
                DGeom g = PhysicsWorld.geom(w.space, p);
                register(w, g, p);
                geoms.add(g);
                parts.add(p);
            }
        }
        reset();
    }

    private void register(PhysicsWorld w, DGeom g, FieldModel.Solid p) {
        PhysicsWorld.Material mat = new PhysicsWorld.Material("mover");
        mat.mu = mat.mu2 = p.friction();
        mat.owner = this;
        w.register(g, mat, PhysicsWorld.CAT_STATIC, PhysicsWorld.CAT_CHASSIS | PhysicsWorld.CAT_WHEEL | PhysicsWorld.CAT_BALL);
    }

    /** Back to the start angle, at rest. */
    public void reset() {
        setAngle(mover.startAngle());
    }

    /** Puts the mover at angle {@code a}, at rest. */
    public void setAngle(double a) {
        if (body != null) {
            setPose(a);
            return;
        }
        angle = a;
        double[] r = mover.rotation(a);
        for (int i = 0; i < geoms.size(); i++) {
            FieldModel.Solid p = parts.get(i).transformed(r, mover.pivot());
            DGeom g = geoms.get(i);
            g.setPosition(p.center()[0], p.center()[1], p.center()[2]);
            double[] q = p.rot();
            g.setRotation(new DMatrix3(q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7], q[8]));
        }
    }

    private void setPose(double a) {
        double[] p = mover.toWorld(a, mover.com());
        double[] r = mover.rotation(a);
        body.setPosition(p[0], p[1], p[2]);
        body.setRotation(new DMatrix3(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]));
        body.setLinearVel(0, 0, 0);
        body.setAngularVel(0, 0, 0);
    }

    /** The damper, and waking what it moved away from; call before each physics step. */
    void beforeStep() {
        // A game piece at rest against it sleeps (ODE stops simulating it). When it swings away,
        // say a GATE that held balls, nothing would touch them to wake them: wake them here.
        // Search from where it was as well as where it is: season code can move it a long way at once.
        double a = angle();
        double[] box = geoms.isEmpty() ? null : PhysicsWorld.bounds(geoms, 0);
        if (box != null && lastBox != null && !Double.isNaN(lastAngle) && Math.abs(a - lastAngle) > 1e-5) {
            double[] both = box.clone();
            for (int k = 0; k < 6; k += 2) {
                both[k] = Math.min(box[k], lastBox[k]);
                both[k + 1] = Math.max(box[k + 1], lastBox[k + 1]);
            }
            world.wakeNear(both, body, 0.15);
        }
        lastAngle = a;
        lastBox = box;
        if (body == null || mover.damping() == 0) return;
        double t = -mover.damping() * hinge.getAngleRate();
        double[] u = mover.axis();
        body.addTorque(t * u[0], t * u[1], t * u[2]);
    }

    /** The angle about the hinge axis, radians (0 = the pose its parts are described in). */
    public double angle() {
        return hinge != null ? hinge.getAngle() : angle;
    }
}
