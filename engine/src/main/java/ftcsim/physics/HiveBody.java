package ftcsim.physics;

import java.util.List;

import org.ode4j.math.DMatrix3;
import org.ode4j.ode.DBody;
import org.ode4j.ode.DGeom;
import org.ode4j.ode.DHingeJoint;
import org.ode4j.ode.DMass;
import org.ode4j.ode.OdeHelper;

/**
 * One HIVE as a rigid body on its pivot: the two CELLS and their arm, hinged about the x axis
 * with stops at the two stable positions (+-30 degrees).
 *
 * <p>The real HIVE is bi-stable (Competition Manual 9.6.2): it holds either position until enough
 * POLLEN or NECTAR land in the upward-facing CELL. Here that comes from where its mass sits. The
 * center of mass is a little above the pivot, so gravity pushes the HIVE away from level and
 * holds it against whichever stop it is on, like a ball balanced on a hill. Balls resting at the
 * back of the upward-facing CELL pull the other way. When their weight times their lever arm
 * beats the HIVE's own holding torque, it tips. A damper slows the swing, as on the field.
 *
 * <p>{@link #COM_ABOVE_PIVOT_M} sets the tipping point. It meets every "Necessary" row of the
 * calibration table in the Event Field Setup Guide, section 12.3: a POLLEN tossed onto 3 NECTAR +
 * 1 POLLEN or onto 6 POLLEN does not tip it; one tossed onto 3 NECTAR + 2 POLLEN or onto 7 POLLEN
 * does. It is set a little stiffer than the guide's "Preferred" rows (a gently placed 3rd POLLEN
 * with 3 NECTAR, or 8th POLLEN, does not tip it), because at 0.0323 m, where those rows pass, 3
 * NECTAR + 2 POLLEN launched by a robot tipped the HIVE from most shooting positions, unlike on
 * the field. At 0.0345 m, across 16 launcher speed / hood / distance combinations, 3 NECTAR + 2
 * launched POLLEN never tipped it and a 3rd launched POLLEN always did. Near the threshold the
 * damper makes a tip slow: the HIVE can creep off its stop for several seconds before it swings
 * over. HiveTipTest checks all of this. Every method must be called on the physics thread.
 */
public final class HiveBody {

    /** One HIVE: two polycarbonate CELLS, aluminum arm and bearing plates, ballast. An estimate. */
    static final double MASS_KG = 2.5;
    /** How far the center of mass sits above the pivot. Sets the holding torque; see the class doc. */
    static final double COM_ABOVE_PIVOT_M = 0.0345;
    /** The damper, as a viscous torque against the swing, N m per rad/s. */
    static final double DAMPING_NMS = 0.35;
    /** Radius of gyration about the pivot axis, for the moment of inertia (cells ~0.39 m out). */
    private static final double GYRATION_M = 0.40;

    /** The HIVE at its start position (biobuzz-field.json "start_up"). */
    public final FieldModel.Hive start;
    private final double stop;
    private final DBody body;
    private final DHingeJoint hinge;
    private String upCell;
    private int tips;

    HiveBody(PhysicsWorld w, FieldModel.Hive start, List<FieldModel.Panel> panels) {
        this.start = start;
        this.stop = Math.abs(start.phi());
        body = OdeHelper.createBody(w.world);
        DMass m = OdeHelper.createMass();
        double ixx = MASS_KG * GYRATION_M * GYRATION_M;
        m.setParameters(MASS_KG, 0, 0, 0, ixx, 0.3 * ixx, ixx, 0, 0, 0);
        body.setMass(m);
        body.setAutoDisableFlag(false);
        body.setLinearDamping(0);
        body.setAngularDamping(0);
        setPose(0);

        // Body origin = center of mass, COM_ABOVE_PIVOT_M above the pivot in the hive frame.
        for (FieldModel.Panel p : panels) {
            DGeom g = OdeHelper.createBox(w.space, p.size()[0], p.size()[1], p.size()[2]);
            g.setBody(body);
            g.setOffsetPosition(p.center()[0], p.center()[1], p.center()[2] - COM_ABOVE_PIVOT_M);
            double[] r = p.rot();
            g.setOffsetRotation(new DMatrix3(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]));
            PhysicsWorld.Material mat = new PhysicsWorld.Material("hive");
            mat.mu = mat.mu2 = 0.25;
            mat.owner = this;
            w.register(g, mat, PhysicsWorld.CAT_STATIC, PhysicsWorld.CAT_CHASSIS | PhysicsWorld.CAT_WHEEL | PhysicsWorld.CAT_BALL);
        }

        // The hinge measures its angle from the pose it is created in, so create it level.
        hinge = OdeHelper.createHingeJoint(w.world);
        hinge.attach(body, null);
        hinge.setAnchor(start.px(), start.py(), start.pz());
        hinge.setAxis(1, 0, 0);
        hinge.setParamLoStop(-stop);
        hinge.setParamHiStop(stop);
        hinge.setParamBounce(0);
        reset();
    }

    /** Back to the start position, at rest, tip count cleared. */
    public void reset() {
        setPose(start.phi());
        upCell = start.upCell();
        tips = 0;
    }

    private void setPose(double phi) {
        double[] p = start.at(phi).toWorld(0, 0, COM_ABOVE_PIVOT_M);
        double c = Math.cos(phi), s = Math.sin(phi);
        body.setPosition(p[0], p[1], p[2]);
        body.setRotation(new DMatrix3(1, 0, 0, 0, c, -s, 0, s, c));
        body.setLinearVel(0, 0, 0);
        body.setAngularVel(0, 0, 0);
    }

    /** Damper torque and tip bookkeeping; call before each physics step. */
    void beforeStep() {
        body.addTorque(-DAMPING_NMS * hinge.getAngleRate(), 0, 0);
        double phi = angle();
        // A TIP: the HIVE has swung all the way over and settled against the other stop.
        if ("audience".equals(upCell) && phi > stop - 0.02) {
            upCell = "rear";
            tips++;
        } else if ("rear".equals(upCell) && phi < -stop + 0.02) {
            upCell = "audience";
            tips++;
        }
    }

    /** Tilt about the pivot, radians: negative = audience CELL up, positive = rear CELL up. */
    public double angle() {
        return hinge.getAngle();
    }

    /** "audience" or "rear": the CELL facing up, as of the last stop the HIVE settled on. */
    public String upCell() {
        return upCell;
    }

    /** How many times this HIVE has tipped since the last reset. */
    public int tips() {
        return tips;
    }

    /** The HIVE at its current tilt, for geometry (where the CELLS are now). */
    public FieldModel.Hive current() {
        return start.at(angle());
    }
}
