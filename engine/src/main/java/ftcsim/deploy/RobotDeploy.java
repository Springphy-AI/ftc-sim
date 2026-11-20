package ftcsim.deploy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Puts a robot project's code on the real robot, as Android Studio's Run button does: the project's
 * own Gradle wrapper builds the Robot Controller app with its TeamCode and installs it over ADB on
 * the Control Hub or Robot Controller phone (USB, or Wi-Fi at {@link #CONTROL_HUB}), then the app is
 * started again. The simulator only runs the project's build; it changes nothing in the project.
 */
public final class RobotDeploy {

    /** The Robot Controller app every FTC project builds. */
    public static final String RC_PACKAGE = "com.qualcomm.ftcrobotcontroller";
    /** Where a Control Hub's ADB listens on its own Wi-Fi network. */
    public static final String CONTROL_HUB = "192.168.43.1:5555";

    /** A robot ADB sees: its serial, "device" when usable (else "unauthorized", "offline"...), and its model. */
    public record Device(String serial, String state, String model) {
    }

    /**
     * What deploying a project needs, found on this computer: problem says what is missing (null
     * when nothing); jdk is the JDK Gradle runs on.
     */
    public record Tools(Path project, Path gradlew, Path sdk, Path adb, Path jdk, String problem) {
    }

    /** How programs here (adb, Gradle) write their output when it doesn't go to a console. */
    static final Charset OUTPUT = outputCharset();

    /** One step of a deploy, for the page: state is "running", "ok", "failed" or "canceled". */
    public record Event(int robot, String state, String message, List<String> lines) {
    }

    private final Object lock = new Object();
    private Process process;
    private int busyRobot = -1;
    private boolean cancelled;

    // ---- finding the tools ----------------------------------------------------------------------------

    /**
     * The tools for the project whose TeamCode source folder is {@code teamCodeSources}
     * (PROJECT/TeamCode/src/main/java).
     */
    public static Tools tools(Path teamCodeSources) {
        Path project = projectOf(teamCodeSources);
        if (project == null) {
            return new Tools(null, null, null, findAdb(findSdk(null)), null,
                    "Deploying needs an FtcRobotController project: start the simulator with --project and the folder"
                            + " of your robot's FtcRobotController (the bundled examples aren't one).");
        }
        Path gradlew = project.resolve(windows() ? "gradlew.bat" : "gradlew");
        Path sdk = findSdk(project);
        Path adb = findAdb(sdk);
        String problem = null;
        if (!Files.isRegularFile(gradlew)) problem = "The project has no Gradle wrapper (" + gradlew.getFileName() + ") to build it with.";
        else if (sdk == null) {
            problem = "No Android SDK found. Install Android Studio (it installs the SDK), or set ANDROID_HOME to the SDK's folder.";
        } else if (adb == null) problem = "The Android SDK at " + sdk + " has no platform-tools (adb). Install them with Android Studio's SDK Manager.";
        return new Tools(project, gradlew, sdk, adb, gradleJdk(), problem);
    }

    /**
     * The JDK to run the project's Gradle on, the one Android Studio would use: its own bundled JDK
     * (jbr), else JAVA_HOME, else the simulator's. A project's Gradle may not run on a JDK newer than
     * it knows, so the simulator's own (possibly newest) JDK comes last. Only JDK 17 or newer, as the
     * Android build of FTC SDK 10 and later needs.
     */
    static Path gradleJdk() {
        List<Path> candidates = new ArrayList<>();
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (windows()) {
            for (String env : new String[]{"ProgramFiles", "LOCALAPPDATA"}) {
                String dir = System.getenv(env);
                if (dir == null) continue;
                candidates.add(Paths.get(dir, "Android", "Android Studio", "jbr"));
                candidates.add(Paths.get(dir, "Programs", "Android Studio", "jbr"));
            }
        } else if (os.contains("mac")) {
            candidates.add(Paths.get("/Applications/Android Studio.app/Contents/jbr/Contents/Home"));
            candidates.add(Paths.get(home, "Applications", "Android Studio.app", "Contents", "jbr", "Contents", "Home"));
        } else {
            candidates.add(Paths.get(home, "android-studio", "jbr"));
            candidates.add(Paths.get("/opt/android-studio/jbr"));
            candidates.add(Paths.get("/usr/local/android-studio/jbr"));
            candidates.add(Paths.get("/snap/android-studio/current/jbr"));
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) candidates.add(Paths.get(javaHome));
        Path own = Paths.get(System.getProperty("java.home"));
        for (Path c : candidates) {
            if (Files.isRegularFile(c.resolve("bin").resolve(windows() ? "java.exe" : "java")) && jdkMajor(c) >= 17) return c;
        }
        return own;
    }

    /** A JDK's major version from its release file (0 if unknown). */
    static int jdkMajor(Path jdk) {
        try {
            for (String line : Files.readAllLines(jdk.resolve("release"), StandardCharsets.UTF_8)) {
                if (!line.startsWith("JAVA_VERSION=")) continue;
                String v = line.substring("JAVA_VERSION=".length()).replace("\"", "");
                if (v.startsWith("1.")) v = v.substring(2);
                return Integer.parseInt(v.split("[.+-]")[0]);
            }
        } catch (IOException | RuntimeException ignored) {
            // not a JDK we can read
        }
        return 0;
    }

    private static Charset outputCharset() {
        try {
            String name = System.getProperty("native.encoding");
            return name != null ? Charset.forName(name) : Charset.defaultCharset();
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    static Path projectOf(Path teamCodeSources) {
        if (teamCodeSources == null) return null;
        Path p = teamCodeSources.toAbsolutePath().normalize();
        if (!p.endsWith(Paths.get("TeamCode", "src", "main", "java"))) return null;
        Path project = p.getParent().getParent().getParent().getParent();
        if (project == null) return null;
        boolean gradleProject = Files.isRegularFile(project.resolve("settings.gradle")) || Files.isRegularFile(project.resolve("settings.gradle.kts"));
        return gradleProject ? project : null;
    }

    /** The Android SDK: the project's local.properties (as Android Studio writes it), ANDROID_HOME, or the usual places. */
    static Path findSdk(Path project) {
        List<Path> candidates = new ArrayList<>();
        if (project != null) {
            Path local = project.resolve("local.properties");
            if (Files.isRegularFile(local)) {
                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(local, StandardCharsets.UTF_8)) {
                    p.load(r);
                    if (p.getProperty("sdk.dir") != null) candidates.add(Paths.get(p.getProperty("sdk.dir")));
                } catch (IOException | RuntimeException ignored) {
                    // unreadable: look elsewhere
                }
            }
        }
        for (String env : new String[]{"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) candidates.add(Paths.get(v));
        }
        String home = System.getProperty("user.home");
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) candidates.add(Paths.get(localAppData, "Android", "Sdk"));
        candidates.add(Paths.get(home, "Library", "Android", "sdk"));     // macOS
        candidates.add(Paths.get(home, "Android", "Sdk"));                // Linux
        for (Path c : candidates) {
            if (Files.isDirectory(c.resolve("platform-tools")) || Files.isDirectory(c.resolve("platforms"))) return c;
        }
        return null;
    }

    /** adb: the system property ftcsim.adb (tests), the SDK's platform-tools, or the PATH. */
    static Path findAdb(Path sdk) {
        String override = System.getProperty("ftcsim.adb");
        if (override != null) return Paths.get(override);
        String exe = windows() ? "adb.exe" : "adb";
        if (sdk != null && Files.isRegularFile(sdk.resolve("platform-tools").resolve(exe))) return sdk.resolve("platform-tools").resolve(exe);
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                try {
                    Path p = Paths.get(dir, exe);
                    if (Files.isRegularFile(p)) return p;
                } catch (RuntimeException ignored) {
                    // a malformed PATH entry
                }
            }
        }
        return null;
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ---- ADB ------------------------------------------------------------------------------------------

    /** The robots ADB sees now. */
    public static List<Device> devices(Path adb) throws IOException {
        List<Device> out = new ArrayList<>();
        boolean list = false;
        for (String line : run(List.of(adb.toString(), "devices", "-l"), 15).split("\\R")) {
            if (line.startsWith("List of devices")) {
                list = true;
                continue;
            }
            if (!list || line.isBlank() || line.startsWith("*")) continue;
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) continue;
            String model = null;
            for (int i = 2; i < parts.length; i++) if (parts[i].startsWith("model:")) model = parts[i].substring(6).replace('_', ' ');
            out.add(new Device(parts[0], parts[1], model));
        }
        return out;
    }

    /** {@code adb connect address}; returns what adb said. */
    public static String connect(Path adb, String address) throws IOException {
        return run(List.of(adb.toString(), "connect", address), 20).trim();
    }

    /** Runs a short command and returns its output (stdout and stderr), or throws if it takes too long. */
    static String run(List<String> command, int timeoutSeconds) throws IOException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), OUTPUT))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (out) {
                        out.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // the process ended
            }
        }, "adb-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException(String.join(" ", command.subList(1, command.size())) + " took longer than " + timeoutSeconds + " s");
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        synchronized (out) {
            return out.toString();
        }
    }

    // ---- building and installing --------------------------------------------------------------------

    /** The robot whose deploy is running, or -1. */
    public int busyRobot() {
        synchronized (lock) {
            return busyRobot;
        }
    }

    /**
     * Starts building the project and, with a device, installing it there; events go to
     * {@code listener} from another thread. Returns false (and does nothing) while another deploy runs.
     *
     * @param serial the robot to install on, or null to only build the app (to check that it builds)
     */
    public boolean start(int robot, Tools tools, String serial, Consumer<Event> listener) {
        synchronized (lock) {
            if (busyRobot >= 0) return false;
            busyRobot = robot;
            cancelled = false;
        }
        Thread t = new Thread(() -> {
            try {
                deploy(robot, tools, serial, listener);
            } catch (Exception e) {
                listener.accept(new Event(robot, "failed", "Deploy failed: " + e.getMessage(), List.of()));
            } finally {
                synchronized (lock) {
                    busyRobot = -1;
                    process = null;
                }
            }
        }, "deploy-robot-" + (robot + 1));
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** Stops the running deploy's build. */
    public void cancel() {
        Process p;
        synchronized (lock) {
            cancelled = true;
            p = process;
        }
        if (p != null) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
        }
    }

    private void deploy(int robot, Tools tools, String serial, Consumer<Event> listener) throws IOException, InterruptedException {
        String task = serial == null ? ":TeamCode:assembleDebug" : ":TeamCode:installDebug";
        List<String> command = new ArrayList<>();
        if (!windows()) command.add("sh");        // gradlew may have lost its execute bit
        command.add(tools.gradlew().toString());
        command.add("--console=plain");
        command.add(task);
        String javaHome = tools.jdk().toString();
        listener.accept(new Event(robot, "running", serial == null ? "Building the Robot Controller app…"
                : "Building the Robot Controller app and installing it on " + serial + "…",
                List.of("> JAVA_HOME=" + javaHome, "> " + String.join(" ", command))));

        ProcessBuilder pb = new ProcessBuilder(command).directory(tools.project().toFile()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("JAVA_HOME", javaHome);
        env.merge("PATH", Paths.get(javaHome, "bin") + java.io.File.pathSeparator, (old, add) -> add + old);
        if (tools.sdk() != null) env.put("ANDROID_HOME", tools.sdk().toString());
        if (serial != null) env.put("ANDROID_SERIAL", serial);
        Process p = pb.start();
        synchronized (lock) {
            process = p;
            if (cancelled) cancel();
        }
        List<String> tail = new ArrayList<>();
        List<String> batch = new ArrayList<>();
        long lastSent = System.nanoTime();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), OUTPUT))) {
            String line;
            while ((line = r.readLine()) != null) {
                batch.add(line);
                tail.add(line);
                if (tail.size() > 400) tail.remove(0);
                if (System.nanoTime() - lastSent > 250_000_000L || batch.size() >= 200) {
                    listener.accept(new Event(robot, "running", null, List.copyOf(batch)));
                    batch.clear();
                    lastSent = System.nanoTime();
                }
            }
        }
        int exit = p.waitFor();
        if (!batch.isEmpty()) listener.accept(new Event(robot, "running", null, List.copyOf(batch)));
        boolean wasCancelled;
        synchronized (lock) {
            wasCancelled = cancelled;
        }
        // A build that finished before the cancel took hold counts: go on as if it hadn't been asked.
        if (wasCancelled && exit != 0) {
            boolean installed = tail.stream().anyMatch(l -> l.startsWith("Installed on "));
            listener.accept(new Event(robot, "canceled", installed
                    ? "Canceled after the app was installed: start the Robot Controller app on the robot."
                    : "Canceled before anything was installed: the robot keeps the app it had.", List.of()));
            return;
        }
        if (exit != 0) {
            listener.accept(new Event(robot, "failed", "The robot build failed: " + whatWentWrong(tail), List.of()));
            return;
        }
        if (serial == null) {
            listener.accept(new Event(robot, "ok", "The Robot Controller app builds. Pick a robot to install it on.", List.of()));
            return;
        }
        // Start the Robot Controller app again, as Android Studio's Run does. The app is installed
        // whatever happens here.
        String started;
        try {
            started = run(List.of(tools.adb().toString(), "-s", serial, "shell", "monkey", "-p", RC_PACKAGE,
                    "-c", "android.intent.category.LAUNCHER", "1"), 30);
        } catch (IOException e) {
            started = "error: " + e.getMessage();
        }
        boolean ok = !started.contains("No activities found") && !started.toLowerCase(Locale.ROOT).contains("error");
        listener.accept(new Event(robot, "ok", ok ? "Installed on " + serial + "; the Robot Controller app is starting with your code."
                : "Installed on " + serial + ". Start the Robot Controller app on the robot (it didn't start by itself).",
                ok ? List.of() : List.of(started.trim())));
    }

    /** Gradle's own explanation of a failed build ("What went wrong"), else the compiler errors, else its last lines. */
    static String whatWentWrong(List<String> lines) {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("* What went wrong:")) {
                StringBuilder sb = new StringBuilder();
                for (int k = i + 1; k < lines.size() && !lines.get(k).startsWith("* ") && sb.length() < 600; k++) {
                    if (!lines.get(k).isBlank()) sb.append(sb.length() > 0 ? " " : "").append(lines.get(k).trim());
                }
                if (!errors.isEmpty()) return String.join("\n", errors.subList(0, Math.min(5, errors.size()))) + "\n" + sb;
                return sb.toString();
            }
            if (l.contains(": error: ")) errors.add(l.trim());
        }
        if (!errors.isEmpty()) return String.join("\n", errors.subList(0, Math.min(5, errors.size())));
        return lines.isEmpty() ? "no output" : lines.get(lines.size() - 1);
    }

    /** JSON-friendly view of the tools and devices, for the page. */
    public static Map<String, Object> describe(Tools tools, List<Device> devices, String deviceProblem, int busyRobot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("project", tools.project() == null ? null : tools.project().toString());
        m.put("sdk", tools.sdk() == null ? null : tools.sdk().toString());
        m.put("jdk", tools.jdk() == null ? null : tools.jdk().toString());
        m.put("problem", tools.problem());
        m.put("devices", devices);
        m.put("deviceProblem", deviceProblem);
        m.put("controlHub", CONTROL_HUB);
        m.put("busyRobot", busyRobot);
        return m;
    }
}
