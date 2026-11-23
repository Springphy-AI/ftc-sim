package ftcsim.hal;

import java.util.List;

import org.firstinspires.ftc.robotcore.external.Telemetry;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.HardwareMap;

/**
 * Stand-in for the SDK's OpModeManager: runs one OpMode through INIT, START and STOP on its own
 * thread, following the same rules as the Robot Controller app.
 */
public final class OpModeSession {

    public enum State {INIT, RUNNING, STOPPED}

    public interface Listener {
        void onStateChanged(OpModeSession session, State state);

        /** The OpMode called requestOpModeStop(); the engine should call {@link #stop(long)} from another thread. */
        void onStopRequestedByOpMode(OpModeSession session);

        void onError(OpModeSession session, Throwable error);
    }

    private static final OpModeAccess ACCESS = OpModeAccess.get();

    /** The session whose OpMode the calling thread belongs to: its OpMode thread and every thread that starts. */
    private static final InheritableThreadLocal<OpModeSession> CURRENT = new InheritableThreadLocal<OpModeSession>();

    /**
     * Called by every hardware call ({@link Latency}). In an OpMode STOP gave up on, it throws the
     * SDK's ForceStopException, which unwinds a loop that ignores isStopRequested() as soon as it
     * touches the hardware (as the SDK unwinds a stuck OpMode's hardware calls).
     */
    static void checkStillRunning() {
        OpModeSession s = CURRENT.get();
        if (s != null && s.abandoned) throw new OpModeManagerImpl.ForceStopException();
    }

    private final String name;
    private final OpMode opMode;
    private final RobotContext robot;
    private final boolean linear;
    private final Listener listener;
    /** The Driver Station's gamepads, which its input updates as it comes. */
    private final Gamepad driverStation1, driverStation2;
    /**
     * An iterative OpMode's own gamepads, copied from the Driver Station's before each callback (as
     * OpMode.internalPreUserCode does), so they don't change while init_loop() or loop() runs. A
     * LinearOpMode reads the Driver Station's directly: the SDK copies new input into its gamepads
     * from the event loop's thread whenever it arrives, whatever the OpMode is doing. Null for those.
     */
    private final Gamepad own1, own2;

    private volatile State state = State.INIT;
    private volatile boolean startRequested = false;
    private volatile boolean stopRequested = false;
    private volatile Throwable error = null;
    private volatile boolean stuck = false;
    /** STOP gave up on this OpMode's thread (see {@link #abandon()}). */
    private volatile boolean abandoned = false;
    private Thread thread;
    /** This OpMode's own cleanups (VisionPortals...), run when its thread ends. */
    private List<Runnable> stopHooks;

    public OpModeSession(String name, OpMode opMode, HardwareMap hardwareMap, Gamepad gamepad1, Gamepad gamepad2,
                         Listener listener) {
        this(null, name, opMode, hardwareMap, gamepad1, gamepad2, listener);
    }

    /**
     * @param robot the simulated robot this OpMode runs on; its thread, and any thread the OpMode
     *              starts, see that robot's blackboard, global messages and bus latency. May be null.
     */
    public OpModeSession(RobotContext robot, String name, OpMode opMode, HardwareMap hardwareMap, Gamepad gamepad1,
                         Gamepad gamepad2, Listener listener) {
        this.robot = robot;
        this.name = name;
        this.opMode = opMode;
        this.linear = opMode instanceof LinearOpMode;
        this.listener = listener;
        this.driverStation1 = gamepad1;
        this.driverStation2 = gamepad2;
        if (linear) {
            own1 = own2 = null;
            opMode.gamepad1 = gamepad1;
            opMode.gamepad2 = gamepad2;
        } else {
            own1 = new Gamepad();
            own2 = new Gamepad();
            GamepadAccess.get().sendEffectsTo(own1, gamepad1);
            GamepadAccess.get().sendEffectsTo(own2, gamepad2);
            opMode.gamepad1 = own1;
            opMode.gamepad2 = own2;
        }
        opMode.hardwareMap = hardwareMap;
        ACCESS.setStopListener(opMode, new OpModeAccess.StopListener() {
            @Override
            public void onRequestOpModeStop() {
                if (state == State.STOPPED) return;   // from a thread this OpMode left behind: it is over
                markStopRequested();
                if (linear) Thread.currentThread().interrupt();
                OpModeSession.this.listener.onStopRequestedByOpMode(OpModeSession.this);
            }
        });
    }

    public String getName() {
        return name;
    }

    public OpMode getOpMode() {
        return opMode;
    }

    public boolean isLinear() {
        return linear;
    }

    public State getState() {
        return state;
    }

    public Throwable getError() {
        return error;
    }

    public boolean isStuck() {
        return stuck;
    }

    public boolean isStartRequested() {
        return startRequested;
    }

    public boolean isStopRequested() {
        return stopRequested;
    }

    /** INIT: starts the OpMode thread, which runs init()/init_loop() or runOpMode(). */
    public synchronized void init() {
        if (thread != null) return;
        // As OpModeInternal.internalInit: fresh edge detection, and the trigger threshold an earlier
        // OpMode may have changed back to its default, on the OpMode's gamepads.
        for (Gamepad gp : new Gamepad[]{opMode.gamepad1, opMode.gamepad2}) {
            gp.resetEdgeDetection();
            gp.setTriggerThreshold(Gamepad.DEFAULT_TRIGGER_THRESHOLD);
        }
        // ... and TelemetryInternal.resetTelemetryForOpMode, if the OpMode's telemetry is the SDK's.
        if (opMode.telemetry instanceof SimTelemetry) ((SimTelemetry) opMode.telemetry).resetForOpMode();
        stopHooks = robot != null ? robot.newOpModeStopHooks() : null;
        opMode.resetRuntime();   // as OpModeManagerImpl, before the OpMode's thread starts
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (robot != null) robot.enter();
                CURRENT.set(OpModeSession.this);
                runOpModeThread();
            }
        }, "OpMode " + name);
        thread.setDaemon(true);
        thread.start();
        if (linear) {
            Thread eventLoop = new Thread(new Runnable() {
                @Override
                public void run() {
                    if (robot != null) robot.enter();
                    CURRENT.set(OpModeSession.this);
                    runEventLoop();
                }
            }, "OpMode " + name + " event loop");
            eventLoop.setDaemon(true);
            eventLoop.start();
        }
    }

    /** How often the Robot Controller's event loop runs (EventLoopRunnable sleeps 5 ms between iterations). */
    static final long EVENT_LOOP_MS = 5;

    /**
     * What the Robot Controller's event loop does for a LinearOpMode on each iteration while it is in
     * INIT or running (LinearOpMode.internalOnEventLoopIteration): sets {@code time} to getRuntime(),
     * wakes whatever waits on the OpMode's running notifier, and sends the telemetry an update()
     * asked for once the transmission interval allows (tryUpdateIfDirty), which runs telemetry
     * actions and Funcs on this thread, as on the robot. An iterative OpMode's time is set before
     * each of its callbacks instead, and its telemetry sent after each.
     */
    private void runEventLoop() {
        Object notifier = ACCESS.runningNotifier(opMode);
        try {
            while (state != State.STOPPED && !abandoned) {
                opMode.time = opMode.getRuntime();
                synchronized (notifier) {
                    notifier.notifyAll();
                }
                Telemetry t = opMode.telemetry;
                if (t instanceof SimTelemetry) ((SimTelemetry) t).tryUpdateIfDirty();
                Thread.sleep(EVENT_LOOP_MS);
            }
        } catch (InterruptedException e) {
            // the simulator is shutting down
        } catch (RuntimeException e) {
            // A telemetry action or Func threw on the event loop's thread: an error in the OpMode.
            if (state == State.STOPPED || abandoned || stopRequested) return;
            error = e;
            listener.onError(this, e);
            markStopRequested();
            thread.interrupt();
            listener.onStopRequestedByOpMode(this);
        }
    }

    /** START (the play button). */
    public void start() {
        if (stopRequested || startRequested || state == State.STOPPED) return;
        // As OpModeInternal.internalStart: a button pressed during INIT doesn't count as pressed in
        // the first loop after START (one still held does, at the next update).
        opMode.gamepad1.resetEdgeDetection();
        opMode.gamepad2.resetEdgeDetection();
        startRequested = true;
        ACCESS.markStarted(opMode);
        Object notifier = ACCESS.runningNotifier(opMode);
        synchronized (notifier) {
            notifier.notifyAll();
        }
        setState(State.RUNNING);
    }

    /**
     * STOP: asks the OpMode to stop and waits for its thread to finish. A LinearOpMode's thread is
     * interrupted right away, as on the robot. Returns false if the OpMode is stuck and would not end.
     */
    public boolean stop(long timeoutMs) {
        markStopRequested();
        if (linear && thread != null) {
            thread.interrupt();
        }
        if (thread == null || thread == Thread.currentThread()) {
            return true;
        }
        try {
            thread.join(timeoutMs);
            if (thread.isAlive()) {
                thread.interrupt();
                thread.join(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            stuck = true;
            abandon();
            return false;
        }
        return true;
    }

    /**
     * The OpMode's thread ignores STOP and Java can't kill it, so it lets go of the robot instead:
     * its next hardware call throws ({@link #checkStillRunning()}), its cleanups run now (a
     * VisionPortal's camera stops), and it gets gamepads of its own, so a loop still polling
     * gamepad1.aWasPressed() doesn't take the presses meant for the next OpMode.
     */
    private void abandon() {
        abandoned = true;
        opMode.gamepad1 = new Gamepad();
        opMode.gamepad2 = new Gamepad();
        if (stopHooks != null) RobotContext.runOpModeStopHooks(stopHooks);
    }

    private void markStopRequested() {
        stopRequested = true;
        ACCESS.markStopRequested(opMode);
        Object notifier = ACCESS.runningNotifier(opMode);
        synchronized (notifier) {
            notifier.notifyAll();
        }
    }

    private void setState(State s) {
        if (state == s || state == State.STOPPED) return;
        state = s;
        listener.onStateChanged(this, s);
    }

    private void runOpModeThread() {
        try {
            opMode.internalPreInit();
            try {
                if (linear) {
                    ((LinearOpMode) opMode).runOpMode();
                } else {
                    runIterative();
                }
            } catch (InterruptedException e) {
                // Normal, as in the SDK's OpModeInternal ("OpMode received an InterruptedException;
                // shutting down"): the OpMode was stopped while sleeping or waiting.
            }
        } catch (OpModeManagerImpl.ForceStopException e) {
            // terminateOpModeNow(): nothing more of the OpMode runs.
        } catch (Throwable t) {
            if (!(stopRequested && causedByInterrupt(t))) {
                error = t;
                listener.onError(this, t);
            }
        } finally {
            try {
                sendLastTelemetry();
                if (stopHooks != null) RobotContext.runOpModeStopHooks(stopHooks);
            } finally {
                state = State.STOPPED;
                listener.onStateChanged(this, State.STOPPED);
            }
        }
    }

    /** As the SDK's OpMode.internalRunOpMode. */
    private void runIterative() throws InterruptedException {
        opMode.init();
        while (!startRequested && !stopRequested) {
            preUserCode();
            opMode.init_loop();
            postUserCode();
            opMode.internalPostInitLoop();
            sleepAfterLoop();
        }
        if (startRequested) {
            preUserCode();
            opMode.start();
            postUserCode();
            while (!stopRequested) {
                preUserCode();
                opMode.loop();
                postUserCode();
                opMode.internalPostLoop();
                sleepAfterLoop();
            }
        }
        preUserCode();
        opMode.stop();
        postUserCode();
    }

    /** As OpMode.internalPostUserCode, after init_loop(), start(), loop() and stop(): telemetry.update(). */
    private void postUserCode() {
        opMode.telemetry.update();
    }

    /**
     * As the SDK when an OpMode's thread ends: if its telemetry is the SDK's, what an update() asked
     * for is sent now, whatever the interval.
     */
    private void sendLastTelemetry() {
        Telemetry t = opMode.telemetry;
        if (!(t instanceof SimTelemetry)) return;
        try {
            t.setMsTransmissionInterval(0);
            ((SimTelemetry) t).tryUpdateIfDirty();
        } catch (RuntimeException e) {
            SimLog.log('W', "Telemetry", "the last telemetry of " + name + " could not be composed: " + e);
        }
    }

    /**
     * As OpMode.internalPreUserCode, before init_loop(), start(), loop() and stop() (not init()):
     * the time, and the gamepads as the Driver Station has them now. Gamepad.copy() updates the
     * copy's edge detection, so a press seen by one loop() is aWasPressed() in that loop().
     */
    private void preUserCode() {
        opMode.time = opMode.getRuntime();
        copyGamepad(driverStation1, own1);
        copyGamepad(driverStation2, own2);
    }

    private static void copyGamepad(Gamepad from, Gamepad to) {
        synchronized (from) {   // one Driver Station update, not half of one
            to.copy(from);
        }
    }

    /**
     * As the SDK's OpMode.internalRunOpMode, Thread.sleep(1) after every init_loop() and loop(): a
     * loop takes its own time plus about a millisecond. Not LockSupport.parkNanos: on Windows the JVM
     * raises the system timer to 1 ms resolution for a sleep but not for a park, which waits for the
     * next tick of the default 15.6 ms timer, so a 3 ms loop() ran about every 15 ms (65 times a
     * second, where the robot loops about 250 times).
     */
    private static void sleepAfterLoop() throws InterruptedException {
        Thread.sleep(1);
    }

    private static boolean causedByInterrupt(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof InterruptedException) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }
}
