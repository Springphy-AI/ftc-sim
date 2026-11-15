package ftcsim.hal;

import java.util.concurrent.locks.LockSupport;

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

    private final String name;
    private final OpMode opMode;
    private final RobotContext robot;
    private final boolean linear;
    private final Listener listener;

    private volatile State state = State.INIT;
    private volatile boolean startRequested = false;
    private volatile boolean stopRequested = false;
    private volatile Throwable error = null;
    private volatile boolean stuck = false;
    private Thread thread;

    public OpModeSession(String name, OpMode opMode, HardwareMap hardwareMap, Gamepad gamepad1, Gamepad gamepad2,
                         Telemetry telemetry, Listener listener) {
        this(null, name, opMode, hardwareMap, gamepad1, gamepad2, telemetry, listener);
    }

    /**
     * @param robot the simulated robot this OpMode runs on; its thread, and any thread the OpMode
     *              starts, see that robot's blackboard, global messages and bus latency. May be null.
     */
    public OpModeSession(RobotContext robot, String name, OpMode opMode, HardwareMap hardwareMap, Gamepad gamepad1,
                         Gamepad gamepad2, Telemetry telemetry, Listener listener) {
        this.robot = robot;
        this.name = name;
        this.opMode = opMode;
        this.linear = opMode instanceof LinearOpMode;
        this.listener = listener;
        opMode.hardwareMap = hardwareMap;
        opMode.gamepad1 = gamepad1;
        opMode.gamepad2 = gamepad2;
        opMode.telemetry = telemetry;
        ACCESS.setStopListener(opMode, new OpModeAccess.StopListener() {
            @Override
            public void onRequestOpModeStop() {
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
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (robot != null) robot.enter();
                runOpModeThread();
            }
        }, "OpMode " + name);
        thread.setDaemon(true);
        thread.start();
    }

    /** START (the play button). */
    public void start() {
        if (stopRequested || startRequested || state == State.STOPPED) return;
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
            return false;
        }
        return true;
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
            opMode.resetRuntime();
            opMode.internalPreInit();
            if (linear) {
                try {
                    ((LinearOpMode) opMode).runOpMode();
                } catch (InterruptedException e) {
                    // Normal: the OpMode was stopped while sleeping or waiting.
                }
            } else {
                runIterative();
            }
        } catch (OpModeManagerImpl.ForceStopException e) {
            // terminateOpModeNow(): nothing more of the OpMode runs.
        } catch (Throwable t) {
            if (!(stopRequested && causedByInterrupt(t))) {
                error = t;
                listener.onError(this, t);
            }
        } finally {
            state = State.STOPPED;
            listener.onStateChanged(this, State.STOPPED);
        }
    }

    private void runIterative() {
        opMode.time = opMode.getRuntime();
        opMode.init();
        opMode.internalPostInitLoop();
        while (!startRequested && !stopRequested) {
            long t0 = System.nanoTime();
            opMode.time = opMode.getRuntime();
            opMode.init_loop();
            opMode.internalPostInitLoop();
            pace(t0);
        }
        if (!stopRequested) {
            opMode.time = opMode.getRuntime();
            opMode.start();
            opMode.internalPostLoop();
            while (!stopRequested) {
                long t0 = System.nanoTime();
                opMode.time = opMode.getRuntime();
                opMode.loop();
                opMode.internalPostLoop();
                pace(t0);
            }
        }
        opMode.time = opMode.getRuntime();
        opMode.stop();
        opMode.internalPostLoop();
    }

    /** The Robot Controller's event loop never spins faster than about once a millisecond. */
    private static void pace(long loopStartNanos) {
        long elapsed = System.nanoTime() - loopStartNanos;
        if (elapsed < 1000000L) {
            LockSupport.parkNanos(1000000L - elapsed);
        } else {
            Thread.yield();
        }
    }

    private static boolean causedByInterrupt(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof InterruptedException) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }
}
