package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftcsim.deploy.RobotDeploy;

/**
 * Deploying to the real robot, with a stand-in Gradle wrapper and adb that print what they were
 * asked to do (no robot, no Android SDK needed).
 */
class DeployTest {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    @TempDir
    Path tmp;
    private Path project;
    private Path teamCode;
    private Path adb;

    @BeforeEach
    void fakeProject() throws Exception {
        // A space in the path, as in a user folder named "First Last": the wrapper must still run.
        project = Files.createDirectories(tmp.resolve("My Robot"));
        Files.writeString(project.resolve("settings.gradle"), "include ':TeamCode'\n");
        teamCode = Files.createDirectories(project.resolve("TeamCode/src/main/java"));
        Path sdk = Files.createDirectories(project.resolve("fake-sdk/platform-tools"));
        Files.writeString(project.resolve("local.properties"), "sdk.dir=" + sdk.getParent().toString().replace("\\", "\\\\") + "\n");
        if (WINDOWS) {
            Files.writeString(project.resolve("gradlew.bat"), String.join("\r\n",
                    "@echo off",
                    "echo args %*",
                    "echo serial=%ANDROID_SERIAL%",
                    "if exist \"%~dp0fail.flag\" (",
                    "  echo C:\\p\\TeamCode\\src\\main\\java\\Auto.java:3: error: ';' expected",
                    "  echo * What went wrong:",
                    "  echo Execution failed for task ':TeamCode:compileDebugJavaWithJavac'.",
                    "  echo * Try:",
                    "  exit /b 1",
                    ")",
                    "echo BUILD SUCCESSFUL",
                    ""));
            adb = sdk.resolve("adb.bat");
            Files.writeString(adb, String.join("\r\n",
                    "@echo off",
                    "if \"%1\"==\"devices\" (",
                    "  echo List of devices attached",
                    "  echo 192.168.43.1:5555      device product:rev_ch model:REV_Control_Hub_v1 device:x transport_id:1",
                    "  echo ABC123                 unauthorized usb:1-1 transport_id:2",
                    "  exit /b 0",
                    ")",
                    "if \"%1\"==\"connect\" (",
                    "  echo connected to %2",
                    "  exit /b 0",
                    ")",
                    "echo Events injected: 1",
                    ""));
        } else {
            Files.writeString(project.resolve("gradlew"), String.join("\n",
                    "#!/bin/sh",
                    "echo \"args $*\"",
                    "echo \"serial=$ANDROID_SERIAL\"",
                    "if [ -f \"$(dirname \"$0\")/fail.flag\" ]; then",
                    "  echo \"/p/TeamCode/src/main/java/Auto.java:3: error: ';' expected\"",
                    "  echo '* What went wrong:'",
                    "  echo \"Execution failed for task ':TeamCode:compileDebugJavaWithJavac'.\"",
                    "  echo '* Try:'",
                    "  exit 1",
                    "fi",
                    "echo BUILD SUCCESSFUL",
                    ""));
            adb = sdk.resolve("adb");
            Files.writeString(adb, String.join("\n",
                    "#!/bin/sh",
                    "if [ \"$1\" = devices ]; then",
                    "  echo 'List of devices attached'",
                    "  echo '192.168.43.1:5555      device product:rev_ch model:REV_Control_Hub_v1 device:x transport_id:1'",
                    "  echo 'ABC123                 unauthorized usb:1-1 transport_id:2'",
                    "  exit 0",
                    "fi",
                    "if [ \"$1\" = connect ]; then echo \"connected to $2\"; exit 0; fi",
                    "echo 'Events injected: 1'",
                    ""));
            assertTrue(adb.toFile().setExecutable(true));
        }
        System.setProperty("ftcsim.adb", adb.toString());
    }

    @AfterEach
    void forgetFakeAdb() {
        System.clearProperty("ftcsim.adb");
    }

    @Test
    void findsTheProjectAndTheRobots() throws Exception {
        RobotDeploy.Tools tools = RobotDeploy.tools(teamCode);
        assertNull(tools.problem(), tools.problem());
        assertEquals(project.toRealPath(), tools.project().toRealPath());
        assertEquals(project.resolve("fake-sdk").toRealPath(), tools.sdk().toRealPath(), "local.properties' sdk.dir comes first");

        List<RobotDeploy.Device> devices = RobotDeploy.devices(tools.adb());
        assertEquals(List.of(new RobotDeploy.Device("192.168.43.1:5555", "device", "REV Control Hub v1"),
                new RobotDeploy.Device("ABC123", "unauthorized", null)), devices);
        assertTrue(RobotDeploy.connect(tools.adb(), RobotDeploy.CONTROL_HUB).contains("connected to 192.168.43.1:5555"));

        // The bundled examples aren't a robot project.
        assertNotNull(RobotDeploy.tools(Path.of("examples/teamcode")).problem());
    }

    @Test
    void buildsInstallsAndRestartsTheRobotController() throws Exception {
        List<RobotDeploy.Event> events = deploy("192.168.43.1:5555");
        String log = events.stream().flatMap(e -> e.lines().stream()).collect(Collectors.joining("\n"));
        assertTrue(log.contains("args --console=plain :TeamCode:installDebug"), log);
        assertTrue(log.contains("serial=192.168.43.1:5555"), log);
        RobotDeploy.Event last = events.get(events.size() - 1);
        assertEquals("ok", last.state(), last.message());
        assertTrue(last.message().contains("Robot Controller app is starting"), last.message());

        // Without a robot: only builds the app.
        List<RobotDeploy.Event> buildOnly = deploy(null);
        String log2 = buildOnly.stream().flatMap(e -> e.lines().stream()).collect(Collectors.joining("\n"));
        assertTrue(log2.contains(":TeamCode:assembleDebug"), log2);
        assertEquals("ok", buildOnly.get(buildOnly.size() - 1).state());
    }

    @Test
    void aFailedBuildSaysWhatWentWrong() throws Exception {
        Files.writeString(project.resolve("fail.flag"), "");
        List<RobotDeploy.Event> events = deploy("192.168.43.1:5555");
        RobotDeploy.Event last = events.get(events.size() - 1);
        assertEquals("failed", last.state());
        assertTrue(last.message().contains("Auto.java:3: error: ';' expected"), last.message());
        assertTrue(last.message().contains("compileDebugJavaWithJavac"), last.message());
        assertFalse(last.message().contains("Events injected"), "nothing is started after a failed build");
    }

    /** A JDK folder that says it is {@code version}. */
    private static Path jdk(Path dir, String version) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Files.writeString(dir.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        Files.writeString(bin.resolve(WINDOWS ? "java.exe" : "java"), "");
        return dir;
    }

    /** The project's Gradle wrapper downloads Gradle {@code version}. */
    private void gradle(String version) throws Exception {
        Path wrapper = Files.createDirectories(project.resolve("gradle/wrapper"));
        Files.writeString(wrapper.resolve("gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-" + version + "-bin.zip\n");
    }

    /** Android Studio's setting of the project's Gradle JDK, saved as Android Studio saves it. */
    private void projectGradleJdk(Path jdk) throws Exception {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("java.home", jdk.toString());
        try (var w = Files.newBufferedWriter(Files.createDirectories(project.resolve(".gradle")).resolve("config.properties"))) {
            p.store(w, null);
        }
    }

    @Test
    void theProjectsOwnGradleJdkComesFirst() throws Exception {
        gradle("8.9");
        Path jdk17 = jdk(tmp.resolve("Android Studio/jbr"), "17.0.11");
        projectGradleJdk(jdk17);
        RobotDeploy.Tools tools = RobotDeploy.tools(teamCode);
        assertEquals(jdk17, tools.jdk());
        assertTrue(tools.jdkReason().startsWith("the project's Gradle JDK"), tools.jdkReason());
        // The deploy's log says which JDK and why.
        String log = deploy(null).stream().flatMap(e -> e.lines().stream()).collect(Collectors.joining("\n"));
        assertTrue(log.contains("> JAVA_HOME=" + jdk17 + "   (the project's Gradle JDK"), log);

        // Set to a JDK too new for the project's Gradle 8.9: passed over, saying why.
        Path jdk25 = jdk(tmp.resolve("jdk-25"), "25");
        projectGradleJdk(jdk25);
        tools = RobotDeploy.tools(teamCode);
        assertTrue(!jdk25.equals(tools.jdk()), "Gradle 8.9 doesn't run on Java 25");
        assertTrue(tools.jdkReason().contains("passed over the project's Gradle JDK (.gradle/config.properties) (Java 25, newer than Gradle 8.9 runs on)"),
                tools.jdkReason());
    }

    @Test
    void aJdkTheProjectsGradleCanRunOnIsPreferred() throws Exception {
        Path jdk25 = jdk(tmp.resolve("jbr-25"), "25.0.1"), jdk21 = jdk(tmp.resolve("jdk-21"), "21.0.5"), jdk11 = jdk(tmp.resolve("jdk-11"), "11.0.2");
        Path own = jdk(tmp.resolve("own-26"), "26");
        List<RobotDeploy.JdkCandidate> candidates = List.of(new RobotDeploy.JdkCandidate(jdk11, "the project's Gradle JDK"),
                new RobotDeploy.JdkCandidate(tmp.resolve("not there"), "Android Studio's JDK"),
                new RobotDeploy.JdkCandidate(jdk25, "Android Studio's JDK"), new RobotDeploy.JdkCandidate(jdk21, "JAVA_HOME"));
        RobotDeploy.GradleJdk chosen = RobotDeploy.chooseJdk(candidates, own, "8.9");
        assertEquals(jdk21, chosen.home());
        assertEquals("JAVA_HOME, Java 21; passed over the project's Gradle JDK (Java 11, older than 17), "
                + "Android Studio's JDK (Java 25, newer than Gradle 8.9 runs on)", chosen.reason());
        assertEquals(jdk25, RobotDeploy.chooseJdk(candidates, own, "9.1.0").home(), "Gradle 9.1 runs on Java 25");
        assertEquals(jdk25, RobotDeploy.chooseJdk(candidates, own, null).home(), "Gradle's version unknown: in order");
        // Nothing the project's Gradle runs on: the first JDK 17 or newer, saying it may be too new.
        RobotDeploy.GradleJdk tooNew = RobotDeploy.chooseJdk(List.of(new RobotDeploy.JdkCandidate(jdk25, "Android Studio's JDK")), own, "8.9");
        assertEquals(jdk25, tooNew.home());
        assertTrue(tooNew.reason().contains("though Gradle 8.9 runs on Java 22 at most"), tooNew.reason());

        assertEquals(22, RobotDeploy.maxJava("8.9"));
        assertEquals(23, RobotDeploy.maxJava("8.10.2"));
        assertEquals(24, RobotDeploy.maxJava("8.14.3"));
        assertEquals(25, RobotDeploy.maxJava("9.1.0"));
        assertEquals(21, RobotDeploy.maxJava("8.5"));
        assertEquals(17, RobotDeploy.maxJava("7.4.2"));
        gradle("9.1.0");
        assertEquals("9.1.0", RobotDeploy.gradleVersion(project));
        gradle("8.10-rc-1");
        assertEquals("8.10", RobotDeploy.gradleVersion(project));
    }

    @Test
    void androidStudioIsFoundWhereJetBrainsToolboxInstallsIt() throws Exception {
        Path home = tmp.resolve("home"), local = tmp.resolve("AppData/Local");
        Map<String, String> env = Map.of("LOCALAPPDATA", local.toString(), "JAVA_HOME", "\"" + tmp.resolve("jdk") + "\"");
        Path toolbox2 = Files.createDirectories(local.resolve("Programs/Android Studio Ladybug"));
        Path toolbox1 = Files.createDirectories(local.resolve("JetBrains/Toolbox/apps/AndroidStudio/ch-0/242.23339.11.2421.12550806"));
        List<Path> windows = RobotDeploy.jdkCandidates(project, env, home.toString(), "Windows 11").stream().map(RobotDeploy.JdkCandidate::home).toList();
        assertTrue(windows.contains(toolbox2.resolve("jbr")), windows.toString());
        assertTrue(windows.contains(toolbox1.resolve("jbr")), windows.toString());
        assertEquals(tmp.resolve("jdk"), windows.get(windows.size() - 1), "JAVA_HOME last, its quotes gone");

        Path macApp = Files.createDirectories(home.resolve("Applications/Android Studio Preview.app"));
        List<Path> mac = RobotDeploy.jdkCandidates(project, env, home.toString(), "Mac OS X").stream().map(RobotDeploy.JdkCandidate::home).toList();
        assertTrue(mac.contains(macApp.resolve("Contents/jbr/Contents/Home")), mac.toString());

        Path linuxToolbox1 = Files.createDirectories(home.resolve(".local/share/JetBrains/Toolbox/apps/AndroidStudio/ch-0/241.18034.62"));
        List<Path> linux = RobotDeploy.jdkCandidates(project, env, home.toString(), "Linux").stream().map(RobotDeploy.JdkCandidate::home).toList();
        assertTrue(linux.contains(home.resolve(".local/share/JetBrains/Toolbox/apps/android-studio/jbr")), linux.toString());
        assertTrue(linux.contains(linuxToolbox1.resolve("jbr")), linux.toString());
    }

    /** Runs a deploy and returns its events, the last one final. */
    private List<RobotDeploy.Event> deploy(String serial) throws Exception {
        RobotDeploy d = new RobotDeploy();
        List<RobotDeploy.Event> events = new CopyOnWriteArrayList<>();
        assertTrue(d.start(0, RobotDeploy.tools(teamCode), serial, events::add));
        assertFalse(d.start(1, RobotDeploy.tools(teamCode), serial, e -> { }), "one deploy at a time");
        long deadline = System.currentTimeMillis() + 60000;
        while (d.busyRobot() >= 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(-1, d.busyRobot());
        assertFalse(events.isEmpty());
        return events;
    }
}
