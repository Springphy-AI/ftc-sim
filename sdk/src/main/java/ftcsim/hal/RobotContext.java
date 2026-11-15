package ftcsim.hal;

import java.util.HashMap;

/**
 * One simulated Robot Controller's share of what the FTC SDK keeps in static fields: the
 * {@code OpMode.blackboard}, the global error and warning messages, and the bus latency of its
 * hubs. With several robots in one simulator, each robot's OpMode thread (and every thread it
 * starts) carries its robot's context, so robots never see each other's statics.
 *
 * <p>Code running outside any robot (the physics thread, the web server, tests) has no context:
 * {@link #current()} returns null and callers fall back to process-wide defaults.
 */
public final class RobotContext {

    private static final InheritableThreadLocal<RobotContext> CURRENT = new InheritableThreadLocal<RobotContext>();

    /** 0-based robot number. */
    public final int index;
    /** Simulated bus latency for this robot's hubs; see {@link Latency}. */
    public volatile long commandNanos = 1000000L;
    public volatile long i2cNanos = 2500000L;

    /** RobotLog's global error and warning messages, which the Driver Station shows. */
    public volatile String globalError = "";
    public volatile String globalWarning = "";

    final HashMap<String, Object> blackboard = new HashMap<String, Object>();

    public RobotContext(int index) {
        this.index = index;
    }

    /** The robot the calling thread belongs to, or null. */
    public static RobotContext current() {
        return CURRENT.get();
    }

    /** Makes the calling thread (and threads it starts from now on) part of this robot. */
    public void enter() {
        CURRENT.set(this);
    }

    /** Runs {@code task} on the calling thread as part of this robot. */
    public void run(Runnable task) {
        RobotContext previous = CURRENT.get();
        CURRENT.set(this);
        try {
            task.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** The calling thread's robot index, or -1 outside any robot. */
    public static int currentIndex() {
        RobotContext c = CURRENT.get();
        return c == null ? -1 : c.index;
    }
}
