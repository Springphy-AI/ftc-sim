package ftcsim.hal;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;

/**
 * Gives the simulator access to OpMode state that the FTC API keeps package-private, without
 * adding any public methods to the FTC classes. The implementation is installed by
 * OpModeInternal's static initializer (the same "shared secrets" pattern the JDK uses).
 */
public abstract class OpModeAccess {

    /** Called on the OpMode's own thread when robot code calls requestOpModeStop(). */
    public interface StopListener {
        void onRequestOpModeStop();
    }

    private static volatile OpModeAccess instance;

    public static void install(OpModeAccess access) {
        if (instance == null) instance = access;
    }

    public static OpModeAccess get() {
        if (instance == null) {
            try {
                Class.forName(OpMode.class.getName(), true, OpMode.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return instance;
    }

    public abstract void markStarted(OpMode opMode);

    public abstract void markStopRequested(OpMode opMode);

    /** The monitor LinearOpMode.waitForStart() waits on. */
    public abstract Object runningNotifier(OpMode opMode);

    public abstract void setStopListener(OpMode opMode, StopListener listener);

    /** The telemetry the OpMode was created with, whatever its telemetry field holds now. */
    public abstract SimTelemetry telemetryOf(OpMode opMode);
}
