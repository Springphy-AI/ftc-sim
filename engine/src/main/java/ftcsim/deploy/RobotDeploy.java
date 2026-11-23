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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * when nothing); jdk is the JDK Gradle runs on and jdkReason why that one.
     */
    public record Tools(Path project, Path gradlew, Path sdk, Path adb, Path jdk, String jdkReason, String problem) {
    }

    /** A JDK to run a project's Gradle on, and why it is that one (for the deploy's log). */
    public record GradleJdk(Path home, String reason) {
    }

    /** A JDK that may run the project's Gradle, and where it was found ("Android Studio's JDK"). */
    public record JdkCandidate(Path home, String source) {
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
            return new Tools(null, null, null, findAdb(findSdk(null)), null, null,
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
        GradleJdk jdk = chooseJdk(jdkCandidates(project, System.getenv(), System.getProperty("user.home"), System.getProperty("os.name", "")),
                Paths.get(System.getProperty("java.home")), gradleVersion(project));
        return new Tools(project, gradlew, sdk, adb, jdk.home(), jdk.reason(), problem);
    }

    /**
     * The JDKs that may run the project's Gradle, in the order Android Studio would pick them: the
     * project's own Gradle JDK setting (Android Studio keeps it in .gradle/config.properties, its
     * default GRADLE_LOCAL_JAVA_HOME), Android Studio's bundled JDK (jbr, also as JetBrains Toolbox
     * installs it), then JAVA_HOME.
     */
    public static List<JdkCandidate> jdkCandidates(Path project, Map<String, String> env, String home, String osName) {
        List<JdkCandidate> out = new ArrayList<>();
        Path config = project.resolve(".gradle").resolve("config.properties");
        if (Files.isRegularFile(config)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
                p.load(r);
                String javaHome = p.getProperty("java.home");
                if (javaHome != null && !javaHome.isBlank()) {
                    out.add(new JdkCandidate(Paths.get(javaHome.trim()), "the project's Gradle JDK (.gradle/config.properties)"));
                }
            } catch (IOException | RuntimeException ignored) {
                // unreadable: Android Studio's own settings below
            }
        }
        String os = osName.toLowerCase(Locale.ROOT);
        List<Path> jbr = new ArrayList<>();
        if (os.contains("win")) {
            String localAppData = env.get("LOCALAPPDATA");
            for (String dir : new String[]{env.get("ProgramFiles"), localAppData}) {
                if (dir == null) continue;
                jbr.add(Paths.get(dir, "Android", "Android Studio", "jbr"));
                for (Path app : children(Paths.get(dir, "Programs"), "Android Studio")) jbr.add(app.resolve("jbr"));   // Toolbox 2
            }
            if (localAppData != null) {   // Toolbox 1: apps\AndroidStudio\ch-0\<version>\jbr
                for (Path channel : children(Paths.get(localAppData, "JetBrains", "Toolbox", "apps", "AndroidStudio"), "ch-")) {
                    for (Path version : children(channel, "")) jbr.add(version.resolve("jbr"));
                }
            }
        } else if (os.contains("mac")) {
            for (Path apps : new Path[]{Paths.get("/Applications"), Paths.get(home, "Applications")}) {
                for (Path app : children(apps, "Android Studio")) jbr.add(app.resolve("Contents/jbr/Contents/Home"));
            }
            Path toolbox = Paths.get(home, "Library", "Application Support", "JetBrains", "Toolbox", "apps", "AndroidStudio");
            for (Path channel : children(toolbox, "ch-")) {
                for (Path version : children(channel, "")) {
                    for (Path app : children(version, "Android Studio")) jbr.add(app.resolve("Contents/jbr/Contents/Home"));
                }
            }
        } else {
            jbr.add(Paths.get(home, "android-studio", "jbr"));
            jbr.add(Paths.get("/opt/android-studio/jbr"));
            jbr.add(Paths.get("/usr/local/android-studio/jbr"));
            jbr.add(Paths.get("/snap/android-studio/current/jbr"));
            Path toolbox = Paths.get(home, ".local", "share", "JetBrains", "Toolbox", "apps");
            jbr.add(toolbox.resolve("android-studio").resolve("jbr"));   // Toolbox 2
            for (Path channel : children(toolbox.resolve("AndroidStudio"), "ch-")) {
                for (Path version : children(channel, "")) jbr.add(version.resolve("jbr"));
            }
        }
        for (Path j : jbr) out.add(new JdkCandidate(j, "Android Studio's JDK"));
        String javaHome = env.get("JAVA_HOME");
        try {
            if (javaHome != null && !javaHome.isBlank()) out.add(new JdkCandidate(Paths.get(javaHome.replace("\"", "").trim()), "JAVA_HOME"));
        } catch (java.nio.file.InvalidPathException ignored) {
            // not a folder
        }
        return out;
    }

    /** The folders in {@code dir} whose names start with {@code prefix}, newest name first. */
    private static List<Path> children(Path dir, String prefix) {
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).filter(p -> p.getFileName().toString().startsWith(prefix))
                    .sorted(java.util.Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * The first of {@code candidates}, then the simulator's own JDK ({@code own}), that is a JDK 17
     * or newer (as the Android build of FTC SDK 10 and later needs) and that the project's Gradle
     * ({@code gradleVersion}, null if unknown) can run on: Gradle refuses a Java newer than it knows
     * (FtcRobotController 10.3 and 11.0 use Gradle 8.9, which runs on Java 22 at most). If none it
     * can run on, the first JDK 17 or newer anyway. The reason says where it was found and what was
     * passed over.
     */
    public static GradleJdk chooseJdk(List<JdkCandidate> candidates, Path own, String gradleVersion) {
        int max = maxJava(gradleVersion);
        List<JdkCandidate> all = new ArrayList<>(candidates);
        all.add(new JdkCandidate(own, "the simulator's JDK"));
        List<String> passed = new ArrayList<>();
        JdkCandidate tooNew = null;
        int tooNewVersion = 0;
        for (JdkCandidate c : all) {
            if (!Files.isRegularFile(c.home().resolve("bin").resolve(windows() ? "java.exe" : "java"))) continue;
            int v = jdkMajor(c.home());
            if (v < 17) {
                passed.add(c.source() + " (Java " + (v == 0 ? "?" : v) + ", older than 17)");
                continue;
            }
            if (v > max) {
                passed.add(c.source() + " (Java " + v + ", newer than Gradle " + gradleVersion + " runs on)");
                if (tooNew == null) {
                    tooNew = c;
                    tooNewVersion = v;
                }
                continue;
            }
            String why = c.source() + ", Java " + v;
            return new GradleJdk(c.home(), passed.isEmpty() ? why : why + "; passed over " + String.join(", ", passed));
        }
        if (tooNew != null) {
            return new GradleJdk(tooNew.home(), tooNew.source() + ", Java " + tooNewVersion + ", though Gradle " + gradleVersion
                    + " runs on Java " + max + " at most: install Android Studio or a JDK " + max + " if the build fails");
        }
        return new GradleJdk(own, "the simulator's JDK");
    }

    /** {Gradle major, minor, newest Java it runs on}, newest Gradle first (Gradle's compatibility matrix). */
    private static final int[][] GRADLE_JAVA = {{9, 1, 25}, {8, 14, 24}, {8, 10, 23}, {8, 8, 22}, {8, 5, 21}, {8, 3, 20},
            {7, 6, 19}, {7, 5, 18}, {7, 3, 17}};

    /** The newest Java a Gradle version runs on (any when the version is unknown). */
    public static int maxJava(String gradleVersion) {
        if (gradleVersion == null) return Integer.MAX_VALUE;
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)").matcher(gradleVersion);
        if (!m.lookingAt()) return Integer.MAX_VALUE;
        int major = Integer.parseInt(m.group(1)), minor = Integer.parseInt(m.group(2));
        for (int[] row : GRADLE_JAVA) {
            if (major > row[0] || (major == row[0] && minor >= row[1])) return row[2];
        }
        return 16;
    }

    /** The Gradle version the project's wrapper downloads (gradle-8.9-bin.zip: "8.9"), or null. */
    public static String gradleVersion(Path project) {
        Path wrapper = project.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.properties");
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(wrapper, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        Matcher m = Pattern.compile("gradle-(\\d+\\.\\d+(?:\\.\\d+)?)(?:-[\\w.-]+?)?-(?:bin|all)\\.zip").matcher(p.getProperty("distributionUrl", ""));
        return m.find() ? m.group(1) : null;
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
                List.of("> JAVA_HOME=" + javaHome + (tools.jdkReason() == null ? "" : "   (" + tools.jdkReason() + ")"),
                        "> " + String.join(" ", command))));

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
