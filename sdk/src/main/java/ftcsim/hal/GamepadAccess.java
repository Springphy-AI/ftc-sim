package ftcsim.hal;

import com.qualcomm.robotcore.hardware.Gamepad;

/**
 * Lets the simulator feed Driver Station input into {@link Gamepad}s (including edge detection)
 * without adding public methods to the FTC class. Installed by Gamepad's static initializer.
 */
public abstract class GamepadAccess {
    private static volatile GamepadAccess instance;

    public static void install(GamepadAccess access) {
        if (instance == null) instance = access;
    }

    public static GamepadAccess get() {
        if (instance == null) {
            try {
                Class.forName(Gamepad.class.getName(), true, Gamepad.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return instance;
    }

    /**
     * Updates a gamepad. {@code buttons} follows the W3C "standard" gamepad layout:
     * 0 A, 1 B, 2 X, 3 Y, 4 LB, 5 RB, 6 LT, 7 RT, 8 back, 9 start, 10 LS, 11 RS,
     * 12 up, 13 down, 14 left, 15 right, 16 guide, 17 touchpad.
     */
    public abstract void update(Gamepad gamepad, Gamepad.Type type, float lx, float ly, float rx, float ry,
                                float lt, float rt, boolean[] buttons);

    /** Which Driver Station slot (1 or 2) the gamepad is, for routing rumble and LED effects. */
    public abstract void setUser(Gamepad gamepad, int user);

    /** Rumble and LED effects asked of {@code copy} (an OpMode's own gamepad) go to the Driver Station gamepad it copies. */
    public abstract void sendEffectsTo(Gamepad copy, Gamepad driverStationGamepad);
}
