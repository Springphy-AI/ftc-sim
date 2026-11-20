package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
