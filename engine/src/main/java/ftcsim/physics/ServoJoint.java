package ftcsim.physics;

import ftcsim.cad.RobotModel;

/**
 * A servo driving a CAD joint (a hood, a claw finger, a wrist). A positional servo pulls the
 * joint toward where its horn is, with at most its stall torque, so a heavy or blocked link lags
 * or stalls as it would on the robot; a limp servo only adds a little friction. A
 * continuous-rotation servo turns the joint at its speed.
 */
public final class ServoJoint {
    /** How quickly the servo's position loop closes the gap to the horn angle (s). */
    private static final double TRACK_TIME_S = 0.015;

    public final ServoUnit servo;
    public final RobotBody.LinkBody link;
    private final RobotModel.Group group;

    public ServoJoint(ServoUnit servo, RobotBody.LinkBody link) {
        this.servo = servo;
        this.link = link;
        this.group = link.group;
    }

    /** Joint units (rad or m) per servo degree. */
    private double perDegree() {
        return link.revolute() ? Math.toRadians(group.ratio) : group.ratio / 1000.0;
    }

    /** Sets the joint motor for this step (after the servo stepped, before the world steps). */
    public void step() {
        double fmax = link.revolute() ? group.torqueNm : group.torqueNm / 0.01;
        if (!servo.port.pwmEnabled) {
            link.motor(0, link.revolute() ? 0.3 : 3.0);   // an unpowered servo's gearbox still holds a little
            return;
        }
        if (servo.port.continuous) {
            link.motor(servo.port.speedDegPerSec * perDegree(), fmax);
            return;
        }
        double target = group.servoCenter + servo.angleDeg() * perDegree();
        double feedForward = servo.port.speedDegPerSec * perDegree();
        double vel = feedForward + (target - link.position()) / TRACK_TIME_S;
        double maxSpeed = Math.abs(servo.device.speedDegPerSec * perDegree()) * 1.5;
        link.motor(Math.max(-maxSpeed, Math.min(maxSpeed, vel)), fmax);
    }

    /**
     * The link is back at its start (a Reset): the horn goes there too, as it would turning with
     * the link while the servo is off. Otherwise the next command would pull the link from the
     * horn's old angle instead of from where the link is.
     */
    public void hornAtLinkStart() {
        if (!servo.port.continuous) servo.setAngleDeg((group.start - group.servoCenter) / perDegree());
    }

    /** The joint position the servo is commanding, from the CAD pose. */
    public double target() {
        return group.servoCenter + servo.angleDeg() * perDegree();
    }
}
