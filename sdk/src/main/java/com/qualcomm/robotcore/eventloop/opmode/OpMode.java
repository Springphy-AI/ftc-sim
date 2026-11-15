package com.qualcomm.robotcore.eventloop.opmode;

import java.util.HashMap;

import org.firstinspires.ftc.robotcore.external.Telemetry;

import ftcsim.hal.ContextBlackboard;
import ftcsim.hal.SimTelemetry;

/**
 * Base class for user defined iterative OpModes: init() once, init_loop() repeatedly until START,
 * start() once, loop() repeatedly until STOP, then stop().
 */
public abstract class OpMode extends OpModeInternal {

    /**
     * A place to share data between OpModes (for example from Autonomous to TeleOp). In the
     * simulator each robot has its own, as each real robot runs its own Robot Controller app.
     */
    public static final HashMap<String, Object> blackboard = new ContextBlackboard();

    /** Number of seconds this OpMode has been running; updated before every call to init_loop() and loop(). */
    public volatile double time = 0.0;

    public int msStuckDetectInit = 5000;
    public int msStuckDetectInitLoop = 5000;
    public int msStuckDetectStart = 5000;
    public int msStuckDetectLoop = 5000;

    private volatile long startTime;

    public OpMode() {
        startTime = System.nanoTime();
    }

    /** User defined init method; called once when INIT is pressed. */
    public abstract void init();

    /** User defined init_loop method; called repeatedly after INIT until START is pressed. */
    public void init_loop() {
    }

    /** User defined start method; called once when START is pressed. */
    public void start() {
    }

    /** User defined loop method; called repeatedly after START until STOP is pressed. */
    public abstract void loop();

    /** User defined stop method; called once when STOP is pressed. */
    public void stop() {
    }

    /** Immediately stops execution of the calling OpMode and transitions to the STOP state. */
    public final void terminateOpModeNow() {
        throw new OpModeManagerImpl.ForceStopException();
    }

    /** Get the number of seconds this OpMode has been running (sub-millisecond accuracy). */
    public double getRuntime() {
        final double NANOSECONDS_PER_SECOND = 1000000000.0;
        return (System.nanoTime() - startTime) / NANOSECONDS_PER_SECOND;
    }

    /** Reset the start time to zero. */
    public void resetRuntime() {
        startTime = System.nanoTime();
    }

    /** Refreshes the user's telemetry on the driver station with the contents of the provided telemetry object. */
    public void updateTelemetry(Telemetry telemetry) {
        telemetry.update();
    }

    public void internalPreInit() {
    }

    public void internalPostInitLoop() {
        SimTelemetry.autoUpdate(telemetry);
    }

    public void internalPostLoop() {
        SimTelemetry.autoUpdate(telemetry);
    }
}
