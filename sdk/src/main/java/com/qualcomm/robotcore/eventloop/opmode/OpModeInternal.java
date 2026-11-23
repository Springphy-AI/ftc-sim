package com.qualcomm.robotcore.eventloop.opmode;

import org.firstinspires.ftc.robotcore.external.Telemetry;

import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.HardwareMap;

import ftcsim.hal.OpModeAccess;
import ftcsim.hal.SimTelemetry;

/** Shared state of {@link OpMode} and {@link LinearOpMode}. */
abstract class OpModeInternal {
    static {
        OpModeAccess.install(new OpModeAccessImpl());
    }

    public static final int MS_BEFORE_FORCE_STOP_AFTER_STOP_REQUESTED = 900;

    /** Gamepad 1 */
    public volatile Gamepad gamepad1 = null;
    /** Gamepad 2 */
    public volatile Gamepad gamepad2 = null;
    /**
     * The OpMode's own telemetry, created with it as FIRST's OpModeInternal creates its TelemetryImpl,
     * so field initializers can use it. The engine shows what this one sends even if robot code puts
     * another object (a wrapper of it) in {@link #telemetry}.
     */
    final SimTelemetry simTelemetry = new SimTelemetry();
    /** The {@link #telemetry} field contains an object in which a user may accumulate data to send to the driver station. */
    public Telemetry telemetry = simTelemetry;
    /** Hardware Mappings */
    public volatile HardwareMap hardwareMap = null;

    public int msStuckDetectStop = MS_BEFORE_FORCE_STOP_AFTER_STOP_REQUESTED;

    volatile boolean isStarted = false;
    volatile boolean stopRequested = false;
    final Object runningNotifier = new Object();
    volatile OpModeAccess.StopListener stopListener = null;

    /**
     * Requests that this OpMode be shut down if it the currently active OpMode, much as if the
     * stop button had been pressed on the driver station.
     */
    public final void requestOpModeStop() {
        stopRequested = true;
        OpModeAccess.StopListener l = stopListener;
        if (l != null) {
            l.onRequestOpModeStop();
        }
    }
}
