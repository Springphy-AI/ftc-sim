package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftcsim.physics.FieldModel;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.sim.Simulator;

/** OpModes one after another on one robot, as the Robot Controller app runs them. */
class OpModeLifecycleTest {

    private static final String HEADER = """
            package org.firstinspires.ftc.teamcode;

            import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
            import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

            """;

    @TempDir
    Path dir;
    private Simulator sim;

    @AfterEach
    void stop() {
        if (sim != null) sim.shutdown();
    }

    private void write(String className, String body) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("org/firstinspires/ftc/teamcode"));
        Files.writeString(pkg.resolve(className + ".java"), HEADER + body);
    }

    private void start() throws Exception {
        Path robot = Path.of("").toAbsolutePath().resolve("robots/mecanum.json");
        sim = new Simulator(FieldModel.loadBiobuzz(), robot, robot.getParent(),
                new TeamCodeCompiler(List.of(new TeamCodeCompiler.SourceRoot(dir, false)), Files.createTempDirectory("ftcsim-test")), "test");
        sim.start();
        long deadline = System.currentTimeMillis() + 60000;
        while ((sim.build() == null || sim.isBuilding()) && System.currentTimeMillis() < deadline) Thread.sleep(50);
    }

    private void waitFor(Simulator.DsState state) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (sim.dsState() != state && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(state, sim.dsState(), "Driver Station state (error: " + sim.lastError() + ")");
    }

    private static final String WAITS = """
            @TeleOp(name = "Waits")
            public class Waits extends LinearOpMode {
                @Override
                public void runOpMode() {
                    waitForStart();
                    while (opModeIsActive()) idle();
                }
            }
            """;

    @Test
    void aStopRequestFromAThreadAnEarlierOpModeLeftDoesNotStopTheNextOne() throws Exception {
        write("LeavesATimer", """
                @TeleOp(name = "Leaves a timer")
                public class LeavesATimer extends LinearOpMode {
                    @Override
                    public void runOpMode() {
                        new Thread(() -> {
                            try {
                                Thread.sleep(1500);
                            } catch (InterruptedException e) {
                                return;
                            }
                            requestOpModeStop();   // long after this OpMode was stopped
                        }).start();
                        waitForStart();
                    }
                }
                """);
        write("Waits", WAITS);
        start();
        sim.handle("{\"type\":\"init\",\"opmode\":\"Leaves a timer\"}");
        waitFor(Simulator.DsState.INIT);
        sim.handle("{\"type\":\"stop\"}");
        waitFor(Simulator.DsState.IDLE);
        sim.handle("{\"type\":\"init\",\"opmode\":\"Waits\"}");
        waitFor(Simulator.DsState.INIT);
        sim.handle("{\"type\":\"start\"}");
        waitFor(Simulator.DsState.RUNNING);
        Thread.sleep(2000);   // the old timer fires meanwhile
        assertEquals(Simulator.DsState.RUNNING, sim.dsState(), "the running OpMode is not stopped by an old one's timer");
    }

    @Test
    void theRunningOpModeCanStillStopItself() throws Exception {
        write("StopsItself", """
                @TeleOp(name = "Stops itself")
                public class StopsItself extends LinearOpMode {
                    @Override
                    public void runOpMode() {
                        waitForStart();
                        requestOpModeStop();
                        while (opModeIsActive()) idle();
                    }
                }
                """);
        start();
        sim.handle("{\"type\":\"init\",\"opmode\":\"Stops itself\"}");
        waitFor(Simulator.DsState.INIT);
        sim.handle("{\"type\":\"start\"}");
        waitFor(Simulator.DsState.IDLE);
    }
}
