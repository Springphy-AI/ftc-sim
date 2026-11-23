package com.qualcomm.robotcore.hardware;

import ftcsim.hal.GamepadAccess;

/** Package-private bridge for the simulator; see {@link GamepadAccess}. Not part of the FTC API. */
final class GamepadAccessImpl extends GamepadAccess {

    @Override
    public void update(Gamepad gamepad, Gamepad.Type type, float lx, float ly, float rx, float ry,
                       float lt, float rt, boolean[] buttons) {
        gamepad.simUpdate(type, lx, ly, rx, ry, lt, rt, buttons);
    }

    @Override
    public void setUser(Gamepad gamepad, int user) {
        gamepad.simUser = user;
    }

    @Override
    public void sendEffectsTo(Gamepad copy, Gamepad driverStationGamepad) {
        copy.simEffectsTarget = driverStationGamepad;
    }
}
