package biobuzz;

import ftcsim.physics.HingeBody;

/**
 * One HIVE on its pivot: the two CELLS and their arm, a {@link HingeBody} turning about the x axis
 * with stops at the two stable positions (+-30 degrees), plus which CELL faces up and how many
 * times it has tipped.
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
 * the field. At 0.0345 m, HiveLaunchSweep (144 launcher power / hood / distance settings, run
 * before the battery and loop-timing changes) found both of 2 launched POLLEN in the CELL at 46
 * settings: the HIVE held at 41 of them and tipped one ball early at 5, and where a 3rd POLLEN
 * also landed it tipped the HIVE at 30 of 31. Near the threshold the damper makes a tip slow: the
 * HIVE can creep off its stop for several seconds before it swings over. HiveTipTest checks the
 * table's rows and two launch settings. Every method must be called on the physics thread.
 */
public final class HiveBody {

    /** One HIVE: two polycarbonate CELLS, aluminum arm and bearing plates, ballast. An estimate. */
    static final double MASS_KG = 2.5;
    /** How far the center of mass sits above the pivot. Sets the holding torque; see the class doc. */
    static final double COM_ABOVE_PIVOT_M = 0.0345;
    /** The damper, as a viscous torque against the swing, N m per rad/s. */
    static final double DAMPING_NMS = 0.35;
    /** Radius of gyration about the pivot axis, for the moment of inertia (cells ~0.39 m out). */
    static final double GYRATION_M = 0.40;

    /** The HIVE at its start position (season.json "start_up"). */
    public final Hive start;
    private final HingeBody body;
    private final double stop;
    private String upCell;
    private int tips;

    HiveBody(HingeBody body, Hive start) {
        this.body = body;
        this.start = start;
        this.stop = Math.abs(start.phi());
        reset();
    }

    /** Tip count cleared, the start CELL up (the body itself is put back with the field's other moving parts). */
    void reset() {
        upCell = start.upCell();
        tips = 0;
    }

    /** Tip bookkeeping; call before each physics step. */
    void beforeStep() {
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
        return body.angle();
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
    public Hive current() {
        return start.at(angle());
    }
}
