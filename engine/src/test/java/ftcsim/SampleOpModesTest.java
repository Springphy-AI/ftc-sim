package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftcsim.physics.FieldModel;
import ftcsim.runner.OpModeInfo;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.sim.Simulator;

/**
 * Runs the official FTC sample OpModes, unmodified, through the whole simulator: compile,
 * INIT, START, gamepad input, telemetry, STOP.
 */
class SampleOpModesTest {

    private final List<String> messages = new CopyOnWriteArrayList<>();
    /** Every simulator a test started: stopped after it, passed or failed, so none runs on into the next tests. */
    private final List<Simulator> started = new ArrayList<>();

    @TempDir
    Path work;

    @AfterEach
    void stopSimulators() {
        for (Simulator sim : started) sim.shutdown();
    }

    private Simulator start(String robot) throws Exception {
        Path home = Path.of("").toAbsolutePath();
        TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(
                new TeamCodeCompiler.SourceRoot(home.resolve("examples/teamcode"), false),
                new TeamCodeCompiler.SourceRoot(home.resolve("examples/samples"), true)), work.resolve("build-" + started.size()));
        Path robotFile = home.resolve("robots/" + robot);
        Simulator sim = new Simulator(FieldModel.loadBiobuzz(), robotFile, robotFile.getParent(), compiler, "test");
        started.add(sim);
        sim.setOutput(new Simulator.Output() {
            @Override
            public void broadcast(String json) {
                messages.add(json);
            }

            @Override
            public void broadcastState(String json) {
            }
        });
        sim.start();
        long deadline = System.currentTimeMillis() + 60000;
        while ((sim.build() == null || sim.isBuilding()) && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertNotNull(sim.build(), "build finished");
        assertTrue(sim.build().ok(), sim.build().message());
        return sim;
    }

    private static void waitFor(Simulator sim, Simulator.DsState state, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (sim.dsState() != state && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(state, sim.dsState(), "Driver Station state (error: " + sim.lastError() + ")");
    }

    private static String gamepad(float ly, float rx) {
        return "{\"type\":\"gamepad\",\"gamepad\":1,\"kind\":\"XBOX_360\",\"axes\":[0," + ly + "," + rx + ",0,0,0],"
                + "\"buttons\":[false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false]}";
    }

    @Test
    void compilesTheOfficialSamples() throws Exception {
        Simulator sim = start("mecanum.json");
        TeamCodeCompiler.Result b = sim.build();
        System.out.println(b.message());
        b.skipped().forEach(s -> System.out.println("  skipped " + s.file() + ": " + s.reason()));
        assertTrue(b.skipped().isEmpty(), "every bundled sample should compile");
        long samples = b.opModes().stream().filter(OpModeInfo::sample).count();
        System.out.println("  " + b.opModes().size() + " OpModes, " + samples + " samples");
        // Every sample file with an OpMode in it gives one: none is lost on the way to the list.
        long sampleOpModeFiles;
        try (var files = Files.walk(Path.of("examples/samples"))) {
            sampleOpModeFiles = files.filter(f -> f.toString().endsWith(".java")).filter(f -> {
                try {
                    return java.util.regex.Pattern.compile("(?m)^@(TeleOp|Autonomous)\\b").matcher(Files.readString(f)).find();
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).count();
        }
        assertTrue(sampleOpModeFiles >= 25, "the samples are there: " + sampleOpModeFiles);
        assertEquals(sampleOpModeFiles, samples, "one OpMode per sample file with @TeleOp or @Autonomous");
        sim.shutdown();
    }

    @Test
    void basicOmniTeleOpDrivesWithTheGamepad() throws Exception {
        Simulator sim = start("mecanum.json");
        double x0 = sim.rig().body.position().get0();
        double h0 = sim.rig().body.heading();
        sim.handle("{\"type\":\"init\",\"opmode\":\"Basic: Omni Linear OpMode\"}");
        waitFor(sim, Simulator.DsState.INIT, 2000);
        sim.handle("{\"type\":\"start\"}");
        waitFor(sim, Simulator.DsState.RUNNING, 2000);
        sim.handle(gamepad(-1.0f, 0f));   // left stick pushed forward
        Thread.sleep(800);
        sim.handle(gamepad(0f, 0f));
        Thread.sleep(500);
        double dx = sim.rig().body.position().get0() - x0;
        System.out.printf("BasicOmni: moved %.3f m forward%n", dx);
        assertTrue(dx > 0.5, "robot should have driven forward (facing +x), moved " + dx);
        assertEquals(Math.toDegrees(h0), Math.toDegrees(sim.rig().body.heading()), 3.0, "heading unchanged");
        assertTrue(messages.stream().anyMatch(m -> m.contains("\"type\":\"telemetry\"") && m.contains("Front left/Right")),
                "sample telemetry reached the Driver Station");
        sim.handle("{\"type\":\"stop\"}");
        waitFor(sim, Simulator.DsState.IDLE, 3000);
        assertNull(sim.lastError());
        sim.shutdown();
    }

    @Test
    void autoDriveByGyroReturnsToStart() throws Exception {
        Simulator sim = start("tank.json");
        double x0 = sim.rig().body.position().get0(), y0 = sim.rig().body.position().get1();
        sim.handle("{\"type\":\"init\",\"opmode\":\"Robot: Auto Drive By Gyro\"}");
        waitFor(sim, Simulator.DsState.INIT, 2000);
        Thread.sleep(300);
        sim.handle("{\"type\":\"start\"}");
        waitFor(sim, Simulator.DsState.RUNNING, 2000);
        // The routine drives 24", turns to -45, drives 17", turns to 45, drives 17", turns to 0 and backs up 48".
        waitFor(sim, Simulator.DsState.IDLE, 29000);
        double x = sim.rig().body.position().get0(), y = sim.rig().body.position().get1();
        double heading = Math.toDegrees(sim.rig().body.heading());
        System.out.printf("AutoDriveByGyro: ended %.3f m from start, heading %.1f deg%n", Math.hypot(x - x0, y - y0), heading);
        assertNull(sim.lastError(), "no user-code errors");
        assertEquals(0, heading, 3.0, "ends facing the start heading");
        // The sample says it "should return to approx. starting position".
        assertTrue(Math.hypot(x - x0, y - y0) < 0.15, "ends near the start");
        sim.shutdown();
    }

    @Test
    void missingDeviceIsReportedLikeTheDriverStation() throws Exception {
        Simulator sim = start("mecanum.json");
        // The tank teleop asks for left_drive, which the mecanum robot does not have.
        sim.handle("{\"type\":\"init\",\"opmode\":\"Robot: Teleop Tank\"}");
        long deadline = System.currentTimeMillis() + 3000;
        while (sim.lastError() == null && System.currentTimeMillis() < deadline) Thread.sleep(20);
        String err = sim.lastError();
        System.out.println("error: " + err);
        assertNotNull(err);
        assertTrue(err.contains("Unable to find a hardware device with name \"left_drive\" and type DcMotor"), err);
        assertTrue(err.startsWith("User code threw an uncaught exception: IllegalArgumentException"), err);
        assertTrue(err.contains("but tank.json "), "points at the robot file that has left_drive: " + err);
        waitFor(sim, Simulator.DsState.IDLE, 3000);
        assertTrue(sim.dsJson().contains("\"fixRobot\":\"tank.json\""), "offers the one-click switch: " + sim.dsJson());

        // What the "Use tank.json" button sends; afterwards the same OpMode initializes.
        sim.handle("{\"type\":\"robot\",\"file\":\"tank.json\"}");
        deadline = System.currentTimeMillis() + 3000;
        while (!sim.dsJson().contains("\"robotFile\":\"tank.json\"") && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(sim.dsJson().contains("\"robotFile\":\"tank.json\""), sim.dsJson());
        assertNull(sim.lastError(), "switching to the suggested robot clears the error");
        sim.handle("{\"type\":\"init\",\"opmode\":\"Robot: Teleop Tank\"}");
        waitFor(sim, Simulator.DsState.INIT, 3000);
        Thread.sleep(300);
        assertNull(sim.lastError(), "Teleop Tank finds its devices on tank.json");
        sim.handle("{\"type\":\"stop\"}");
        waitFor(sim, Simulator.DsState.IDLE, 3000);
        sim.shutdown();
    }

    @Test
    void conceptAprilTagSeesTheHiveThroughTheWebcam() throws Exception {
        VisionAssumptions.assumeDetectorAvailable();
        Simulator sim = start("mecanum.json");
        // Facing the red HIVE from the audience side: the tags under its lowered (rear) CELL hang
        // about 0.9 m up, 1.4 m from the webcam, which is tilted up 20 degrees.
        sim.handle("{\"type\":\"place\",\"x\":-0.25,\"y\":-1.15,\"h\":90}");
        Thread.sleep(500);
        sim.handle("{\"type\":\"init\",\"opmode\":\"Concept: AprilTag\"}");
        waitFor(sim, Simulator.DsState.INIT, 10000);
        sim.handle("{\"type\":\"start\"}");
        waitFor(sim, Simulator.DsState.RUNNING, 2000);
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline && messages.stream().noneMatch(m -> m.contains("Tag Cluster (RED SCORING)"))) {
            Thread.sleep(100);
        }
        assertTrue(messages.stream().anyMatch(m -> m.contains("Tag Cluster (RED SCORING)")),
                "FIRST's ConceptAprilTag sample reports the red HIVE's tag cluster");
        // The camera thread draws a frame's live view after it has processed it, and the first draw
        // takes a while: the frame's detections can reach the Driver Station before the view is up.
        deadline = System.currentTimeMillis() + 5000;
        while (ftcsim.hal.CameraPreviews.of(0).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(!ftcsim.hal.CameraPreviews.of(0).isEmpty(), "the portal shows a live view");
        sim.handle("{\"type\":\"stop\"}");
        waitFor(sim, Simulator.DsState.IDLE, 5000);
        assertNull(sim.lastError(), "no errors from the sample");
        deadline = System.currentTimeMillis() + 3000;
        while (!ftcsim.hal.CameraPreviews.of(0).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(ftcsim.hal.CameraPreviews.of(0).isEmpty(), "the camera closes when the OpMode stops, as on the robot");
        sim.shutdown();
    }
}
