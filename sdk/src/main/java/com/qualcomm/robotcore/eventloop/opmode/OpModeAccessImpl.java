package com.qualcomm.robotcore.eventloop.opmode;

import ftcsim.hal.OpModeAccess;
import ftcsim.hal.SimTelemetry;

/** Package-private bridge for the simulator; see {@link OpModeAccess}. Not part of the FTC API. */
final class OpModeAccessImpl extends OpModeAccess {

    @Override
    public void markStarted(OpMode opMode) {
        opMode.isStarted = true;
    }

    @Override
    public void markStopRequested(OpMode opMode) {
        opMode.stopRequested = true;
    }

    @Override
    public Object runningNotifier(OpMode opMode) {
        return opMode.runningNotifier;
    }

    @Override
    public void setStopListener(OpMode opMode, StopListener listener) {
        opMode.stopListener = listener;
    }

    @Override
    public SimTelemetry telemetryOf(OpMode opMode) {
        return opMode.simTelemetry;
    }
}
