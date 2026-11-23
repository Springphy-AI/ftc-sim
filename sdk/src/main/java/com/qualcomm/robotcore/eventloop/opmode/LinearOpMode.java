package com.qualcomm.robotcore.eventloop.opmode;

/**
 * Base class for user defined linear OpModes: all of the robot logic lives in runOpMode(), which
 * runs on its own thread from INIT until it returns or STOP is pressed.
 */
public abstract class LinearOpMode extends OpMode {

    public LinearOpMode() {
    }

    /** Override this method and place your code here. Please do not catch {@link InterruptedException}s. */
    public abstract void runOpMode() throws InterruptedException;

    /** Pauses the Linear OpMode until START has been pressed, or until the current thread is interrupted. */
    public void waitForStart() {
        while (!isStarted()) {
            synchronized (runningNotifier) {
                if (isStarted() || isStopRequested()) {
                    return;
                }
                try {
                    runningNotifier.wait(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Puts the current thread to sleep for a bit as it has nothing better to do. */
    public final void idle() {
        Thread.yield();
    }

    /** Sleeps for the given amount of milliseconds, or until the thread is interrupted (usually by STOP). */
    public final void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Answer as to whether this OpMode is active and the robot should continue onwards. */
    public final boolean opModeIsActive() {
        boolean isActive = !this.isStopRequested() && this.isStarted();
        if (isActive) {
            idle();
        }
        return isActive;
    }

    /** Can be used to break out of an Init-Loop when false is returned. */
    public final boolean opModeInInit() {
        return !isStarted() && !isStopRequested();
    }

    /** Has the OpMode been started? */
    public final boolean isStarted() {
        return this.isStarted || Thread.currentThread().isInterrupted();
    }

    /** Has the the stopping of the OpMode been requested? */
    public final boolean isStopRequested() {
        return this.stopRequested || Thread.currentThread().isInterrupted();
    }

    // The iterative hooks are driven by the runner for LinearOpModes; user code never overrides them.
    @Override
    public final void init() {
    }

    @Override
    public final void init_loop() {
    }

    @Override
    public final void start() {
    }

    @Override
    public final void loop() {
    }

    @Override
    public final void stop() {
    }
}
