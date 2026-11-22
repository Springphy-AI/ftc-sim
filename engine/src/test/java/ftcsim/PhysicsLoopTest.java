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

    private Simulator start(Path teamCode) throws Exception {
        Path home = Path.of("").toAbsolutePath();
        Path robotFile = home.resolve("robots/mecanum.json");
        TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(new TeamCodeCompiler.SourceRoot(teamCode, false)),
                Files.createTempDirectory("ftcsim-test"));
        Simulator sim = new Simulator(FieldModel.loadBiobuzz(), robotFile, robotFile.getParent(), compiler, "test");
        sim.setOutput(new Simulator.Output() {
            @Override
            public void broadcast(String json) {
                messages.add(json);
            }

            @Override
            public void broadcastState(String json) {
                if (json.startsWith("{\"type\":\"state\"")) lastState.set(json);
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
