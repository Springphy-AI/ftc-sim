package com.qualcomm.robotcore.hardware;

import java.util.ArrayList;
import java.util.Locale;

import ftcsim.hal.GamepadAccess;
import ftcsim.hal.GamepadEffects;

/**
 * Monitor a hardware gamepad. The buttons, analog sticks, and triggers are represented as public
 * member variables that can be read from or written to directly. In the simulator these are fed
 * by the browser Driver Station (a USB/Bluetooth controller through the Gamepad API, or the keyboard).
 */
public class Gamepad {
    static {
        GamepadAccess.install(new GamepadAccessImpl());
    }

    public static final int ID_UNASSOCIATED = -1;
    public static final int ID_SYNTHETIC = -2;

    public enum Type {
        UNKNOWN, LOGITECH_F310, XBOX_360, SONY_PS4, SONY_PS4_SUPPORTED_BY_KERNEL
    }

    public volatile Type type = Type.UNKNOWN;

    public volatile float left_stick_x = 0f;
    public volatile float left_stick_y = 0f;
    public volatile float right_stick_x = 0f;
    public volatile float right_stick_y = 0f;
    public volatile boolean dpad_up = false;
    public volatile boolean dpad_down = false;
    public volatile boolean dpad_left = false;
    public volatile boolean dpad_right = false;
    public volatile boolean a = false;
    public volatile boolean b = false;
    public volatile boolean x = false;
    public volatile boolean y = false;
    public volatile boolean guide = false;
    public volatile boolean start = false;
    public volatile boolean back = false;
    public volatile boolean left_bumper = false;
    public volatile boolean right_bumper = false;
    public volatile boolean left_stick_button = false;
    public volatile boolean right_stick_button = false;
    public volatile float left_trigger = 0f;
    public volatile float right_trigger = 0f;

    public static final float DEFAULT_TRIGGER_THRESHOLD = 0.5f;
    public volatile boolean left_trigger_pressed = false;
    public volatile boolean right_trigger_pressed = false;

    // PlayStation aliases
    public volatile boolean circle = false;
    public volatile boolean cross = false;
    public volatile boolean triangle = false;
    public volatile boolean square = false;
    public volatile boolean share = false;
    public volatile boolean options = false;
    public volatile boolean touchpad = false;
    public volatile boolean touchpad_finger_1 = false;
    public volatile boolean touchpad_finger_2 = false;
    public volatile float touchpad_finger_1_x = 0f;
    public volatile float touchpad_finger_1_y = 0f;
    public volatile float touchpad_finger_2_x = 0f;
    public volatile float touchpad_finger_2_y = 0f;
    public volatile boolean ps = false;

    public volatile int id = ID_UNASSOCIATED;
    public volatile long timestamp = 0;

    public static final int LED_DURATION_CONTINUOUS = -1;
    public static final int RUMBLE_DURATION_CONTINUOUS = -1;
    public long nextRumbleApproxFinishTime = 0;

    private volatile float triggerThreshold = DEFAULT_TRIGGER_THRESHOLD;

    /** Which Driver Station slot this gamepad is (1 or 2); used to route rumble/LED effects. */
    volatile int simUser = 0;
    /** The Driver Station gamepad this one is a copy of, whose rumble and LED it drives; null for itself. */
    volatile Gamepad simEffectsTarget = null;

    // ---- edge detection --------------------------------------------------------------------
    // As FIRST's GamepadStateChanges: one monitor per button, the PlayStation names included (cross
    // and a are separate monitors of the same button, so aWasPressed() doesn't use up crossWasPressed()).
    private static final int E_DPAD_UP = 0, E_DPAD_DOWN = 1, E_DPAD_LEFT = 2, E_DPAD_RIGHT = 3,
            E_A = 4, E_B = 5, E_X = 6, E_Y = 7, E_GUIDE = 8, E_START = 9, E_BACK = 10,
            E_LEFT_BUMPER = 11, E_RIGHT_BUMPER = 12, E_LEFT_STICK = 13, E_RIGHT_STICK = 14,
            E_CIRCLE = 15, E_CROSS = 16, E_TRIANGLE = 17, E_SQUARE = 18, E_SHARE = 19, E_OPTIONS = 20,
            E_TOUCHPAD = 21, E_PS = 22, E_LEFT_TRIGGER = 23, E_RIGHT_TRIGGER = 24, NUM_EDGES = 25;

    private final boolean[] lastPressed = new boolean[NUM_EDGES];
    private final boolean[] pressNotify = new boolean[NUM_EDGES];
    private final boolean[] releaseNotify = new boolean[NUM_EDGES];

    public Gamepad() {
    }

    /** As FIRST's: any value is taken; the SDK sets it back to the default at every INIT. */
    public void setTriggerThreshold(float threshold) {
        triggerThreshold = threshold;
    }

    public float getTriggerThreshold() {
        return triggerThreshold;
    }

    public void setGamepadId(int id) {
        this.id = id;
    }

    public int getGamepadId() {
        return this.id;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public void refreshTimestamp() {
        setTimestamp(System.nanoTime());
    }

    /** Copies the state of another gamepad into this one. */
    public void copy(Gamepad gamepad) {
        synchronized (this) {
            type = gamepad.type;
            left_stick_x = gamepad.left_stick_x;
            left_stick_y = gamepad.left_stick_y;
            right_stick_x = gamepad.right_stick_x;
            right_stick_y = gamepad.right_stick_y;
            dpad_up = gamepad.dpad_up;
            dpad_down = gamepad.dpad_down;
            dpad_left = gamepad.dpad_left;
            dpad_right = gamepad.dpad_right;
            a = gamepad.a;
            b = gamepad.b;
            x = gamepad.x;
            y = gamepad.y;
            guide = gamepad.guide;
            start = gamepad.start;
            back = gamepad.back;
            left_bumper = gamepad.left_bumper;
            right_bumper = gamepad.right_bumper;
            left_stick_button = gamepad.left_stick_button;
            right_stick_button = gamepad.right_stick_button;
            left_trigger = gamepad.left_trigger;
            right_trigger = gamepad.right_trigger;
            touchpad = gamepad.touchpad;
            touchpad_finger_1 = gamepad.touchpad_finger_1;
            touchpad_finger_2 = gamepad.touchpad_finger_2;
            touchpad_finger_1_x = gamepad.touchpad_finger_1_x;
            touchpad_finger_1_y = gamepad.touchpad_finger_1_y;
            touchpad_finger_2_x = gamepad.touchpad_finger_2_x;
            touchpad_finger_2_y = gamepad.touchpad_finger_2_y;
            id = gamepad.id;
            timestamp = gamepad.timestamp;
            updateDerivedFields();
            updateEdgeDetection();
        }
    }

    /** Resets this gamepad to its initial state, as if nothing were pressed. */
    public void reset() {
        copy(new Gamepad());
    }

    public boolean atRest() {
        return left_stick_x == 0f && left_stick_y == 0f && right_stick_x == 0f && right_stick_y == 0f
                && left_trigger == 0f && right_trigger == 0f;
    }

    public Type type() {
        return type;
    }

    /** Updates the gamepad from the Driver Station (simulator entry point; see ftcsim.hal.GamepadAccess). */
    void simUpdate(Type newType, float lx, float ly, float rx, float ry, float lt, float rt, boolean[] buttons) {
        synchronized (this) {
            type = newType;
            left_stick_x = cleanStick(lx);
            left_stick_y = cleanStick(ly);
            right_stick_x = cleanStick(rx);
            right_stick_y = cleanStick(ry);
            left_trigger = clip01(lt);
            right_trigger = clip01(rt);
            a = buttons[0];
            b = buttons[1];
            x = buttons[2];
            y = buttons[3];
            left_bumper = buttons[4];
            right_bumper = buttons[5];
            back = buttons[8];
            start = buttons[9];
            left_stick_button = buttons[10];
            right_stick_button = buttons[11];
            dpad_up = buttons[12];
            dpad_down = buttons[13];
            dpad_left = buttons[14];
            dpad_right = buttons[15];
            guide = buttons[16];
            touchpad = buttons.length > 17 && buttons[17];
            timestamp = System.nanoTime();
            updateDerivedFields();
            updateEdgeDetection();
        }
    }

    private static float cleanStick(float v) {
        if (Float.isNaN(v)) return 0f;
        return Math.max(-1f, Math.min(1f, v));
    }

    private static float clip01(float v) {
        if (Float.isNaN(v)) return 0f;
        return Math.max(0f, Math.min(1f, v));
    }

    private void updateDerivedFields() {
        left_trigger_pressed = left_trigger >= triggerThreshold;
        right_trigger_pressed = right_trigger >= triggerThreshold;
        circle = b;
        cross = a;
        triangle = y;
        square = x;
        share = back;
        options = start;
        ps = guide;
    }

    private void updateEdgeDetection() {
        boolean[] now = new boolean[NUM_EDGES];
        now[E_DPAD_UP] = dpad_up;
        now[E_DPAD_DOWN] = dpad_down;
        now[E_DPAD_LEFT] = dpad_left;
        now[E_DPAD_RIGHT] = dpad_right;
        now[E_A] = a;
        now[E_B] = b;
        now[E_X] = x;
        now[E_Y] = y;
        now[E_GUIDE] = guide;
        now[E_START] = start;
        now[E_BACK] = back;
        now[E_LEFT_BUMPER] = left_bumper;
        now[E_RIGHT_BUMPER] = right_bumper;
        now[E_LEFT_STICK] = left_stick_button;
        now[E_RIGHT_STICK] = right_stick_button;
        now[E_CIRCLE] = circle;
        now[E_CROSS] = cross;
        now[E_TRIANGLE] = triangle;
        now[E_SQUARE] = square;
        now[E_SHARE] = share;
        now[E_OPTIONS] = options;
        now[E_TOUCHPAD] = touchpad;
        now[E_PS] = ps;
        now[E_LEFT_TRIGGER] = left_trigger_pressed;
        now[E_RIGHT_TRIGGER] = right_trigger_pressed;
        for (int i = 0; i < NUM_EDGES; i++) {
            if (!lastPressed[i] && now[i]) pressNotify[i] = true;
            if (lastPressed[i] && !now[i]) releaseNotify[i] = true;
            lastPressed[i] = now[i];
        }
    }

    private boolean takePressed(int i) {
        synchronized (this) {
            boolean v = pressNotify[i];
            pressNotify[i] = false;
            return v;
        }
    }

    private boolean takeReleased(int i) {
        synchronized (this) {
            boolean v = releaseNotify[i];
            releaseNotify[i] = false;
            return v;
        }
    }

    /**
     * Starts edge detection afresh, as FIRST's (a new GamepadStateChanges): nothing pressed or
     * released is pending, and every button counts as up, so one still held reports
     * *WasPressed() again at the next update.
     */
    public void resetEdgeDetection() {
        synchronized (this) {
            for (int i = 0; i < NUM_EDGES; i++) {
                lastPressed[i] = false;
                pressNotify[i] = false;
                releaseNotify[i] = false;
            }
        }
    }

    public boolean dpadUpWasPressed() { return takePressed(E_DPAD_UP); }
    public boolean dpadUpWasReleased() { return takeReleased(E_DPAD_UP); }
    public boolean dpadDownWasPressed() { return takePressed(E_DPAD_DOWN); }
    public boolean dpadDownWasReleased() { return takeReleased(E_DPAD_DOWN); }
    public boolean dpadLeftWasPressed() { return takePressed(E_DPAD_LEFT); }
    public boolean dpadLeftWasReleased() { return takeReleased(E_DPAD_LEFT); }
    public boolean dpadRightWasPressed() { return takePressed(E_DPAD_RIGHT); }
    public boolean dpadRightWasReleased() { return takeReleased(E_DPAD_RIGHT); }
    public boolean aWasPressed() { return takePressed(E_A); }
    public boolean aWasReleased() { return takeReleased(E_A); }
    public boolean bWasPressed() { return takePressed(E_B); }
    public boolean bWasReleased() { return takeReleased(E_B); }
    public boolean xWasPressed() { return takePressed(E_X); }
    public boolean xWasReleased() { return takeReleased(E_X); }
    public boolean yWasPressed() { return takePressed(E_Y); }
    public boolean yWasReleased() { return takeReleased(E_Y); }
    public boolean guideWasPressed() { return takePressed(E_GUIDE); }
    public boolean guideWasReleased() { return takeReleased(E_GUIDE); }
    public boolean startWasPressed() { return takePressed(E_START); }
    public boolean startWasReleased() { return takeReleased(E_START); }
    public boolean backWasPressed() { return takePressed(E_BACK); }
    public boolean backWasReleased() { return takeReleased(E_BACK); }
    public boolean leftBumperWasPressed() { return takePressed(E_LEFT_BUMPER); }
    public boolean leftBumperWasReleased() { return takeReleased(E_LEFT_BUMPER); }
    public boolean rightBumperWasPressed() { return takePressed(E_RIGHT_BUMPER); }
    public boolean rightBumperWasReleased() { return takeReleased(E_RIGHT_BUMPER); }
    public boolean leftStickButtonWasPressed() { return takePressed(E_LEFT_STICK); }
    public boolean leftStickButtonWasReleased() { return takeReleased(E_LEFT_STICK); }
    public boolean rightStickButtonWasPressed() { return takePressed(E_RIGHT_STICK); }
    public boolean rightStickButtonWasReleased() { return takeReleased(E_RIGHT_STICK); }
    public boolean circleWasPressed() { return takePressed(E_CIRCLE); }
    public boolean circleWasReleased() { return takeReleased(E_CIRCLE); }
    public boolean crossWasPressed() { return takePressed(E_CROSS); }
    public boolean crossWasReleased() { return takeReleased(E_CROSS); }
    public boolean triangleWasPressed() { return takePressed(E_TRIANGLE); }
    public boolean triangleWasReleased() { return takeReleased(E_TRIANGLE); }
    public boolean squareWasPressed() { return takePressed(E_SQUARE); }
    public boolean squareWasReleased() { return takeReleased(E_SQUARE); }
    public boolean shareWasPressed() { return takePressed(E_SHARE); }
    public boolean shareWasReleased() { return takeReleased(E_SHARE); }
    public boolean optionsWasPressed() { return takePressed(E_OPTIONS); }
    public boolean optionsWasReleased() { return takeReleased(E_OPTIONS); }
    public boolean touchpadWasPressed() { return takePressed(E_TOUCHPAD); }
    public boolean touchpadWasReleased() { return takeReleased(E_TOUCHPAD); }
    public boolean psWasPressed() { return takePressed(E_PS); }
    public boolean psWasReleased() { return takeReleased(E_PS); }
    public boolean leftTriggerWasPressed() { return takePressed(E_LEFT_TRIGGER); }
    public boolean leftTriggerWasReleased() { return takeReleased(E_LEFT_TRIGGER); }
    public boolean rightTriggerWasPressed() { return takePressed(E_RIGHT_TRIGGER); }
    public boolean rightTriggerWasReleased() { return takeReleased(E_RIGHT_TRIGGER); }

    // ---- LED & rumble ----------------------------------------------------------------------

    /** The gamepad whose controller rumbles or lights up: the Driver Station's, for an OpMode's copy. */
    private Gamepad effects() {
        Gamepad target = simEffectsTarget;
        return target != null ? target : this;
    }

    public void setLedColor(double r, double g, double b, int durationMs) {
        Gamepad t = effects();
        GamepadEffects.led(t, t.simUser, r, g, b, durationMs);
    }

    public void runLedEffect(LedEffect effect) {
        if (effect != null && !effect.steps.isEmpty()) {
            LedEffect.Step s = effect.steps.get(0);
            Gamepad t = effects();
            GamepadEffects.led(t, t.simUser, s.r, s.g, s.b, effect.repeating ? LED_DURATION_CONTINUOUS : s.duration);
        }
    }

    public void runRumbleEffect(RumbleEffect effect) {
        if (effect == null) return;
        long total = 0;
        double[] strong = new double[effect.steps.size()];
        double[] weak = new double[effect.steps.size()];
        int[] durations = new int[effect.steps.size()];
        for (int i = 0; i < effect.steps.size(); i++) {
            RumbleEffect.Step s = effect.steps.get(i);
            strong[i] = s.large / 255.0;
            weak[i] = s.small / 255.0;
            durations[i] = s.duration;
            total += s.duration;
        }
        nextRumbleApproxFinishTime = System.currentTimeMillis() + total;
        Gamepad t = effects();
        GamepadEffects.rumble(t, t.simUser, strong, weak, durations);
    }

    public void rumble(int durationMs) {
        rumble(1.0, 1.0, durationMs);
    }

    public void rumble(double rumble1, double rumble2, int durationMs) {
        if (durationMs == RUMBLE_DURATION_CONTINUOUS) durationMs = 60000;
        runRumbleEffect(new RumbleEffect.Builder().addStep(rumble1, rumble2, durationMs).build());
    }

    public void stopRumble() {
        nextRumbleApproxFinishTime = 0;
        Gamepad t = effects();
        GamepadEffects.rumble(t, t.simUser, new double[]{0}, new double[]{0}, new int[]{0});
    }

    public void rumbleBlips(int count) {
        RumbleEffect.Builder builder = new RumbleEffect.Builder();
        for (int i = 0; i < count; i++) {
            builder.addStep(0, 1.0, 250).addStep(0, 0, 100);
        }
        runRumbleEffect(builder.build());
    }

    public boolean isRumbling() {
        return System.currentTimeMillis() < nextRumbleApproxFinishTime;
    }

    public static class RumbleEffect {
        public static class Step {
            public int large;
            public int small;
            public int duration;
        }

        public int user;
        public final ArrayList<Step> steps;

        private RumbleEffect(ArrayList<Step> steps) {
            this.steps = steps;
        }

        public String serialize() {
            StringBuilder sb = new StringBuilder();
            for (Step s : steps) sb.append(s.large).append(',').append(s.small).append(',').append(s.duration).append(';');
            return sb.toString();
        }

        public static RumbleEffect deserialize(String serialized) {
            ArrayList<Step> steps = new ArrayList<>();
            for (String part : serialized.split(";")) {
                String[] f = part.split(",");
                if (f.length != 3) continue;
                Step s = new Step();
                s.large = Integer.parseInt(f[0].trim());
                s.small = Integer.parseInt(f[1].trim());
                s.duration = Integer.parseInt(f[2].trim());
                steps.add(s);
            }
            return new RumbleEffect(steps);
        }

        public static class Builder {
            private final ArrayList<Step> steps = new ArrayList<>();

            /** Adds a step: rumble1 (left, strong) and rumble2 (right, weak) in [0, 1], for durationMs. */
            public Builder addStep(double rumble1, double rumble2, int durationMs) {
                Step s = new Step();
                s.large = (int) Math.round(Math.max(0, Math.min(1, rumble1)) * 255);
                s.small = (int) Math.round(Math.max(0, Math.min(1, rumble2)) * 255);
                s.duration = durationMs;
                steps.add(s);
                return this;
            }

            public RumbleEffect build() {
                return new RumbleEffect(steps);
            }
        }
    }

    public static class LedEffect {
        public static class Step {
            public int r;
            public int g;
            public int b;
            public int duration;
        }

        public final ArrayList<Step> steps;
        public final boolean repeating;
        public int user;

        private LedEffect(ArrayList<Step> steps, boolean repeating) {
            this.steps = steps;
            this.repeating = repeating;
        }

        public String serialize() {
            StringBuilder sb = new StringBuilder(repeating ? "R;" : "N;");
            for (Step s : steps) sb.append(s.r).append(',').append(s.g).append(',').append(s.b).append(',').append(s.duration).append(';');
            return sb.toString();
        }

        public static LedEffect deserialize(String serialized) {
            ArrayList<Step> steps = new ArrayList<>();
            String[] parts = serialized.split(";");
            boolean repeating = parts.length > 0 && parts[0].equals("R");
            for (int i = 1; i < parts.length; i++) {
                String[] f = parts[i].split(",");
                if (f.length != 4) continue;
                Step s = new Step();
                s.r = Integer.parseInt(f[0].trim());
                s.g = Integer.parseInt(f[1].trim());
                s.b = Integer.parseInt(f[2].trim());
                s.duration = Integer.parseInt(f[3].trim());
                steps.add(s);
            }
            return new LedEffect(steps, repeating);
        }

        public static class Builder {
            private final ArrayList<Step> steps = new ArrayList<>();
            private boolean repeating = false;

            public Builder addStep(double r, double g, double b, int durationMs) {
                Step s = new Step();
                s.r = (int) Math.round(Math.max(0, Math.min(1, r)) * 255);
                s.g = (int) Math.round(Math.max(0, Math.min(1, g)) * 255);
                s.b = (int) Math.round(Math.max(0, Math.min(1, b)) * 255);
                s.duration = durationMs;
                steps.add(s);
                return this;
            }

            public Builder setRepeating(boolean repeating) {
                this.repeating = repeating;
                return this;
            }

            public LedEffect build() {
                return new LedEffect(steps, repeating);
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder buttons = new StringBuilder();
        if (dpad_up) buttons.append("dpad_up ");
        if (dpad_down) buttons.append("dpad_down ");
        if (dpad_left) buttons.append("dpad_left ");
        if (dpad_right) buttons.append("dpad_right ");
        if (a) buttons.append("a ");
        if (b) buttons.append("b ");
        if (x) buttons.append("x ");
        if (y) buttons.append("y ");
        if (guide) buttons.append("guide ");
        if (start) buttons.append("start ");
        if (back) buttons.append("back ");
        if (left_bumper) buttons.append("left_bumper ");
        if (right_bumper) buttons.append("right_bumper ");
        if (left_stick_button) buttons.append("left stick button ");
        if (right_stick_button) buttons.append("right stick button ");
        return String.format(Locale.US,
                "ID: %2d user: %2d lx: % 1.2f ly: % 1.2f rx: % 1.2f ry: % 1.2f lt: %1.2f rt: %1.2f %s",
                id, simUser, left_stick_x, left_stick_y, right_stick_x, right_stick_y, left_trigger, right_trigger,
                buttons.toString().trim());
    }
}
