package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;
import com.qualcomm.robotcore.hardware.DcMotor;

import ftcsim.hal.MotorPort;
import ftcsim.physics.FieldModel;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.sim.Simulator;

/** The physics thread moves every robot and game piece, so nothing that fails on it may stop it. */
class PhysicsLoopTest {

    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> lastState = new AtomicReference<>();
    private final AtomicReference<String> lastDevices = new AtomicReference<>();

    /** Where TeamCode is built: apart from the TeamCode it builds. */
    @TempDir
    Path work;
    private int builds;

    private Simulator start(Path teamCode) throws Exception {
        Path home = Path.of("").toAbsolutePath();
        Path robotFile = home.resolve("robots/mecanum.json");
        TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(new TeamCodeCompiler.SourceRoot(teamCode, false)), work.resolve("build-" + builds++));
        Simulator sim = new Simulator(FieldModel.loadBiobuzz(), robotFile, robotFile.getParent(), compiler, "test");
        sim.setOutput(new Simulator.Output() {
            @Override
            public void broadcast(String json) {
                messages.add(json);
            }

            @Override
            public void broadcastState(String json) {
                if (json.startsWith("{\"type\":\"state\"")) lastState.set(json);
                if (json.startsWith("{\"type\":\"devices\"")) lastDevices.set(json);
            }
        });
        sim.start();
        long deadline = System.currentTimeMillis() + 90000;
        while ((sim.build() == null || sim.isBuilding()) && System.currentTimeMillis() < deadline) Thread.sleep(50);
        return sim;
    }

    /** Simulated time, as the field state the Driver Stations get says. */
    private double simTime() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (lastState.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(10);
        return JsonParser.parseString(lastState.get()).getAsJsonObject().get("t").getAsDouble();
    }

    private void assertFieldMoves(String when) throws InterruptedException {
        double t0 = simTime();
        Thread.sleep(500);
        double t1 = simTime();
        assertTrue(t1 - t0 > 0.25, "simulated time goes on " + when + ": " + t0 + " s, then " + t1 + " s half a second later");
    }

    private List<String> logged(String text) {
        return messages.stream().filter(m -> m.contains("\"type\":\"log\"") && m.contains(text)).toList();
    }

    @Test
    void aFailureInThePhysicsStepIsLoggedOnceAndTheFieldCarriesOnWhenItClears(@TempDir Path dir) throws Exception {
        Simulator sim = start(dir);
        try {
            assertFieldMoves("before anything fails");
            MotorPort port = sim.rig().hubs.get(0).motors[0];
            DcMotor.RunMode mode = port.mode;
            port.mode = null;   // the hub firmware's switch on it throws, in every physics step
            Thread.sleep(1000);
            assertEquals(1, logged("The physics step failed").size(),
                    "the failure is logged once, not on every loop: " + logged("The physics step failed"));
            port.mode = mode;
            assertFieldMoves("once the failure has cleared");
        } finally {
            sim.shutdown();
        }
    }

    @Test
    void teamCodeThatSetsANullBulkCachingModeDoesNotStopTheField(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("org/firstinspires/ftc/teamcode"));
        // LynxModule.setBulkCachingMode(null) is accepted, as on the robot; the device panel has no mode to show.
        Files.writeString(pkg.resolve("NullCaching.java"), """
                package org.firstinspires.ftc.teamcode;

                import com.qualcomm.hardware.lynx.LynxModule;
                import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
                import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

                @TeleOp(name = "Null caching")
                public class NullCaching extends LinearOpMode {
                    @Override
                    public void runOpMode() {
                        for (LynxModule hub : hardwareMap.getAll(LynxModule.class)) hub.setBulkCachingMode(null);
                        waitForStart();
                        while (opModeIsActive()) idle();
                    }
                }
                """);
        Simulator sim = start(dir);
        try {
            assertEquals(1, sim.build().opModes().size(), sim.build().message());
            sim.handle("{\"type\":\"init\",\"opmode\":\"Null caching\"}");
            long deadline = System.currentTimeMillis() + 3000;
            while (sim.dsState() != Simulator.DsState.INIT && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertEquals(Simulator.DsState.INIT, sim.dsState(), sim.lastError());
            Thread.sleep(500);   // several device-panel updates meanwhile
            assertFieldMoves("with no bulk caching mode");
            assertEquals(List.of(), logged("the simulator carries on"), "nothing on the physics thread failed");
        } finally {
            sim.shutdown();
        }
    }

    /** An OpMode whose field initializer takes 2 s, as building a big lookup table might. */
    private static void writeSlowOpMode(Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("org/firstinspires/ftc/teamcode"));
        Files.writeString(pkg.resolve("SlowToCreate.java"), """
                package org.firstinspires.ftc.teamcode;

                import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
                import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

                @TeleOp(name = "Slow to create")
                public class SlowToCreate extends LinearOpMode {
                    private final long table = build();

                    private static long build() {
                        long end = System.nanoTime() + 2_000_000_000L;
                        long n = 0;
                        while (System.nanoTime() < end) n++;
                        return n;
                    }

                    @Override
                    public void runOpMode() {
                        waitForStart();
                        while (opModeIsActive()) idle();
                    }
                }
                """);
    }

    private static Thread initInBackground(Simulator sim) {
        Thread init = new Thread(() -> sim.handle("{\"type\":\"init\",\"opmode\":\"Slow to create\"}"), "test INIT");
        init.start();
        return init;
    }

    @Test
    void aSlowOpModeConstructorDoesNotStopTheField(@TempDir Path dir) throws Exception {
        writeSlowOpMode(dir);
        Simulator sim = start(dir);
        try {
            Thread init = initInBackground(sim);
            Thread.sleep(200);
            assertTrue(init.isAlive(), "the constructor is still running");
            assertFieldMoves("while the OpMode's constructor runs");
            assertEquals(Simulator.DsState.IDLE, sim.dsState(), "the Driver Station answers meanwhile");
            init.join(5000);
            assertEquals(Simulator.DsState.INIT, sim.dsState(), sim.lastError());
            sim.handle("{\"type\":\"stop\"}");
        } finally {
            sim.shutdown();
        }
    }

    @Test
    void stopWhileTheOpModeIsBeingCreatedCancelsTheInit(@TempDir Path dir) throws Exception {
        writeSlowOpMode(dir);
        Simulator sim = start(dir);
        try {
            Thread init = initInBackground(sim);
            Thread.sleep(300);
            sim.handle("{\"type\":\"init\",\"opmode\":\"Slow to create\"}");   // a second INIT meanwhile
            assertTrue(sim.lastError() != null && sim.lastError().contains("still being created"), String.valueOf(sim.lastError()));
            sim.handle("{\"type\":\"stop\"}");
            init.join(5000);
            assertEquals(Simulator.DsState.IDLE, sim.dsState(), "the INIT was cancelled");
        } finally {
            sim.shutdown();
        }
    }

    @Test
    void aResetWhileAnOpModeIsBeingCreatedIsRefused(@TempDir Path dir) throws Exception {
        writeSlowOpMode(dir);
        Simulator sim = start(dir);
        try {
            Thread init = initInBackground(sim);
            Thread.sleep(300);
            sim.handle("{\"type\":\"reset\"}");   // the power cycle would wipe what the OpMode's init() sets
            long deadline = System.currentTimeMillis() + 2000;
            while (sim.lastError() == null && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertTrue(String.valueOf(sim.lastError()).contains("Stop the OpMode before resetting"), String.valueOf(sim.lastError()));
            init.join(5000);
            assertEquals(Simulator.DsState.INIT, sim.dsState());
            sim.handle("{\"type\":\"stop\"}");
        } finally {
            sim.shutdown();
        }
    }

    @Test
    void theDevicePanelIsToldWhatHasNoValue(@TempDir Path dir) throws Exception {
        Simulator sim = start(dir);
        try {
            long deadline = System.currentTimeMillis() + 2000;
            while (lastDevices.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(20);
            String devices = lastDevices.get();
            // No motor is commanding a velocity: the page shows their powers, and needs to see that the
            // velocity is null rather than missing (and an out-of-range sensor as null, not gone).
            assertTrue(devices.contains("\"velocityTarget\":null"), devices);
            assertTrue(devices.contains("\"sensor_distance\":"), "the distance sensor is listed, in range or not: " + devices);
        } finally {
            sim.shutdown();
        }
    }

    /**
     * The loop pauses for about half a millisecond between passes, so motor commands reach the
     * field and sensors see it every millisecond. On Windows the pause used to last a whole 15.6 ms
     * timer tick, and each pass ran about 16 steps at once.
     */
    @Test
    void thePhysicsLoopPausesAboutAMillisecond(@TempDir Path dir) throws Exception {
        Simulator sim = start(dir);
        try {
            Thread.sleep(500);   // (the physics settles in, the JIT warms up)
            long[] before = sim.pauseHistogram();
            Thread.sleep(2000);
            long[] after = sim.pauseHistogram();
            long pauses = 0;
            for (int i = 0; i < after.length; i++) pauses += after[i] - before[i];
            long seen = 0;
            int median = 0;
            while (seen < (pauses + 1) / 2) seen += after[median] - before[median++];
            double medianMs = median / 4.0;   // (the bucket's upper end)
            assertTrue(medianMs <= 4, "median pause of the physics loop: " + medianMs + " ms (" + pauses + " pauses in 2 s)");
        } finally {
            sim.shutdown();
        }
    }

    @Test
    void whatATaskOnThePhysicsThreadThrowsReachesItsCaller(@TempDir Path dir) throws Exception {
        Simulator sim = start(dir);
        try {
            AssertionError e = assertThrows(AssertionError.class,
                    () -> sim.onPhysicsThread(() -> {
                        throw new AssertionError("checked on the physics thread");
                    }));
            assertEquals("checked on the physics thread", e.getMessage());
            assertFieldMoves("after the task failed");
        } finally {
            sim.shutdown();
        }
    }
}
