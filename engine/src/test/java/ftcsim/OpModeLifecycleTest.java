package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    private final List<String> messages = new CopyOnWriteArrayList<>();

    private void start() throws Exception {
        Path robot = Path.of("").toAbsolutePath().resolve("robots/mecanum.json");
        sim = new Simulator(FieldModel.loadBiobuzz(), robot, robot.getParent(),
                new TeamCodeCompiler(List.of(new TeamCodeCompiler.SourceRoot(dir, false)), Files.createTempDirectory("ftcsim-test")), "test");
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

    private static boolean threadAlive(String name) {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals(name) && t.isAlive());
    }

    /** Runs an OpMode, presses STOP and waits until the robot gives up on it. */
    private void runAndAbandon(String opMode) throws Exception {
        sim.handle("{\"type\":\"init\",\"opmode\":\"" + opMode + "\"}");
        waitFor(Simulator.DsState.INIT);
        sim.handle("{\"type\":\"start\"}");
        waitFor(Simulator.DsState.RUNNING);
        sim.handle("{\"type\":\"stop\"}");
        long deadline = System.currentTimeMillis() + 4000;
        while (sim.dsState() != Simulator.DsState.IDLE && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(Simulator.DsState.IDLE, sim.dsState());
        assertTrue(String.valueOf(sim.lastError()).contains("did not stop"), String.valueOf(sim.lastError()));
    }

    @Test
    void anOpModeThatIgnoresStopIsUnwoundAtItsNextHardwareCall() throws Exception {
        write("Stuck", """
                import com.qualcomm.robotcore.hardware.DcMotor;

                @TeleOp(name = "Stuck")
                public class Stuck extends LinearOpMode {
                    @Override
                    public void runOpMode() {
                        DcMotor motor = hardwareMap.get(DcMotor.class, "launcher");
                        waitForStart();
                        long end = System.nanoTime() + 8_000_000_000L;   // (bounded, so a failing test doesn't spin on)
                        while (System.nanoTime() < end) motor.getCurrentPosition();   // ignores STOP
                    }
                }
                """);
        start();
        runAndAbandon("Stuck");
        long deadline = System.currentTimeMillis() + 2000;
        while (threadAlive("OpMode Stuck") && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertFalse(threadAlive("OpMode Stuck"), "its next hardware call ended the abandoned OpMode's thread");
    }

    @Test
    void anAbandonedOpModeNoLongerTakesTheNextOnesButtonPresses() throws Exception {
        write("PollsA", """
                @TeleOp(name = "Polls A")
                public class PollsA extends LinearOpMode {
                    @Override
                    public void runOpMode() {
                        waitForStart();
                        long end = System.nanoTime() + 6_000_000_000L;
                        while (System.nanoTime() < end) {   // ignores STOP, touches no hardware
                            gamepad1.aWasPressed();
                            Thread.yield();
                        }
                    }
                }
                """);
        write("CountsA", """
                @TeleOp(name = "Counts A")
                public class CountsA extends LinearOpMode {
                    @Override
                    public void runOpMode() {
                        waitForStart();
                        int presses = 0;
                        while (opModeIsActive()) {
                            if (gamepad1.aWasPressed()) presses++;
                            telemetry.addData("A presses", presses);
                            telemetry.update();
                            sleep(5);
                        }
                    }
                }
                """);
        start();
        runAndAbandon("Polls A");
        sim.handle("{\"type\":\"init\",\"opmode\":\"Counts A\"}");
        waitFor(Simulator.DsState.INIT);
        sim.handle("{\"type\":\"start\"}");
        waitFor(Simulator.DsState.RUNNING);
        Thread.sleep(200);
        for (int i = 0; i < 3; i++) {
            sim.handle(gamepadA(true));
            Thread.sleep(60);
            sim.handle(gamepadA(false));
            Thread.sleep(60);
        }
        Thread.sleep(400);
        assertEquals("A presses : 3", lastTelemetry("A presses"), "every press reaches the running OpMode");
        sim.handle("{\"type\":\"stop\"}");
    }

    private static String gamepadA(boolean a) {
        return "{\"type\":\"gamepad\",\"gamepad\":1,\"kind\":\"XBOX_360\",\"axes\":[0,0,0,0,0,0],\"buttons\":[" + a
                + ",false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false]}";
    }

    /** The newest telemetry line that starts with {@code caption}. */
    private String lastTelemetry(String caption) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonObject m = JsonParser.parseString(messages.get(i)).getAsJsonObject();
            if (!"telemetry".equals(m.get("type").getAsString())) continue;
            for (var line : m.getAsJsonArray("lines")) if (line.getAsString().startsWith(caption)) return line.getAsString();
        }
        return null;
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
