package ftcsim.hal;

/**
 * Destination for RobotLog output; the engine installs a listener that forwards lines to the browser.
 * {@code robot} is the index of the robot whose thread logged ({@link RobotContext}), or -1.
 */
public final class SimLog {
    public interface Listener {
        void onLog(int robot, char level, String tag, String message);

        void onGlobalError(int robot, String message);

        void onGlobalWarning(int robot, String message);
    }

    private static volatile Listener listener;

    private SimLog() {
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void log(char level, String tag, String message) {
        Listener l = listener;
        if (l != null) {
            l.onLog(RobotContext.currentIndex(), level, tag, message);
        } else {
            System.out.println(level + "/" + tag + ": " + message);
        }
    }

    public static void globalError(String message) {
        Listener l = listener;
        if (l != null) l.onGlobalError(RobotContext.currentIndex(), message);
    }

    public static void globalWarning(String message) {
        Listener l = listener;
        if (l != null) l.onGlobalWarning(RobotContext.currentIndex(), message);
    }
}
