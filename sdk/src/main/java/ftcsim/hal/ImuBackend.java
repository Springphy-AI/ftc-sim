package ftcsim.hal;

/**
 * True motion of an IMU chip, written by physics. Rotations use the FTC robot frame
 * (+X right, +Y forward, +Z up).
 */
public final class ImuBackend {

    public static final class State {
        /** Robot-to-world rotation. */
        public final double[][] robotToWorld;
        /** Robot angular velocity in robot coordinates, rad/s. */
        public final double[] robotAngularVelocity;
        public final long nanoTime;

        public State(double[][] robotToWorld, double[] robotAngularVelocity, long nanoTime) {
            this.robotToWorld = robotToWorld;
            this.robotAngularVelocity = robotAngularVelocity;
            this.nanoTime = nanoTime;
        }
    }

    /** How the hub is physically mounted (hub axes in robot coordinates). */
    public final double[][] hubToRobot;
    /**
     * IMU.resetYaw()'s offset (radians). The Control Hub's IMU object lives as long as the robot
     * app, so it carries over from one OpMode to the next (initialize() leaves it); a power cycle
     * clears it.
     */
    public volatile double yawOffsetRad = 0;
    private volatile State state = new State(Rotations.identity(), new double[]{0, 0, 0}, System.nanoTime());

    public ImuBackend(double[][] hubToRobot) {
        this.hubToRobot = hubToRobot;
    }

    public void set(State s) {
        state = s;
    }

    public State get() {
        return state;
    }
}
