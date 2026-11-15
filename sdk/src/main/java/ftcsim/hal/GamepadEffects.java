package ftcsim.hal;

import com.qualcomm.robotcore.hardware.Gamepad;

/** Routes gamepad rumble and LED requests from robot code to the browser Driver Station. */
public final class GamepadEffects {
    public interface Listener {
        void onRumble(Gamepad gamepad, int user, double[] strong, double[] weak, int[] durationsMs);

        void onLed(Gamepad gamepad, int user, double r, double g, double b, int durationMs);
    }

    private static volatile Listener listener;

    private GamepadEffects() {
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void rumble(Gamepad gamepad, int user, double[] strong, double[] weak, int[] durationsMs) {
        Listener l = listener;
        if (l != null) l.onRumble(gamepad, user, strong, weak, durationsMs);
    }

    public static void led(Gamepad gamepad, int user, double r, double g, double b, int durationMs) {
        Listener l = listener;
        if (l != null) l.onLed(gamepad, user, r, g, b, durationMs);
    }
}
