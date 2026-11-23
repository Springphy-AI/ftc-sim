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
    void afterAnOpModeIsAbandonedTheNextOneGetsFreshStaticFields() throws Exception {
        // On a robot a stuck OpMode restarts the app, and TeamCode's statics start afresh. Here a
        // static executor whose thread the abandoned OpMode started would carry that OpMode on, and
        // every hardware call through it would throw.
        write("Shared", """
                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Executors;

                class Shared {
                    private static ExecutorService pool;

                    static synchronized ExecutorService pool() {
                        if (pool == null) {
                            pool = Executors.newSingleThreadExecutor(r -> {
                                Thread t = new Thread(r, "shared pool");
                                t.setDaemon(true);
                                return t;
                            });
                        }
                        return pool;
                    }
                }
                """);
        write("StuckWithPool", """
                import com.qualcomm.robotcore.hardware.DcMotor;

                @TeleOp(name = "Stuck with pool")
                public class StuckWithPool extends LinearOpMode {
                    @Override
                    public void runOpMode() throws InterruptedException {
                        DcMotor motor = hardwareMap.get(DcMotor.class, "launcher");
                        waitForStart();
                        try {
                            Shared.pool().submit(() -> motor.getCurrentPosition()).get();
                        } catch (java.util.concurrent.ExecutionException e) {
                            throw new RuntimeException(e);
                        }
                        telemetry.addData("used the pool", "yes");
                        telemetry.update();
                        long end = System.nanoTime() + 6_000_000_000L;
                        while (System.nanoTime() < end) Thread.yield();   // ignores STOP, touches no hardware
                    }
                }
                """);
        write("UsesPool", """
                import com.qualcomm.robotcore.hardware.DcMotor;

                @TeleOp(name = "Uses pool")
                public class UsesPool extends LinearOpMode {
                    @Override
                    public void runOpMode() {
                        DcMotor motor = hardwareMap.get(DcMotor.class, "launcher");
                        waitForStart();
                        String result;
                        try {
                            result = "ok " + Shared.pool().submit(() -> motor.getCurrentPosition()).get();
                        } catch (Exception e) {
                            result = String.valueOf(e.getCause());
                        }
                        telemetry.addData("pool", result);
                        telemetry.update();
                        while (opModeIsActive()) idle();
                    }
                }
                """);
        start();
        sim.handle("{\"type\":\"init\",\"opmode\":\"Stuck with pool\"}");
        waitFor(Simulator.DsState.INIT);
        sim.handle("{\"type\":\"start\"}");
        waitFor(Simulator.DsState.RUNNING);
        long used = System.currentTimeMillis() + 3000;
        while (lastTelemetry("used the pool") == null && System.currentTimeMillis() < used) Thread.sleep(20);
        assertEquals("used the pool : yes", lastTelemetry("used the pool"), "error: " + sim.lastError());
        sim.handle("{\"type\":\"stop\"}");
        long stopped = System.currentTimeMillis() + 4000;
        while (sim.dsState() != Simulator.DsState.IDLE && System.currentTimeMillis() < stopped) Thread.sleep(20);
        assertTrue(String.valueOf(sim.lastError()).contains("did not stop"), String.valueOf(sim.lastError()));
        sim.handle("{\"type\":\"init\",\"opmode\":\"Uses pool\"}");
        waitFor(Simulator.DsState.INIT);
        sim.handle("{\"type\":\"start\"}");
        waitFor(Simulator.DsState.RUNNING);
        long deadline = System.currentTimeMillis() + 3000;
        while (lastTelemetry("pool") == null && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals("pool : ok 0", lastTelemetry("pool"), "error: " + sim.lastError());
        sim.handle("{\"type\":\"stop\"}");
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
    void anIterativeLoopTakesItsOwnTimePlusAMillisecond() throws Exception {
        write("ThreeMsLoop", """
                import com.qualcomm.robotcore.eventloop.opmode.OpMode;

                @TeleOp(name = "Three ms loop")
                public class ThreeMsLoop extends OpMode {
                    private final long[] starts = new long[201];
                    private int loops;
                    private String median;
                    private double sleepMs;

                    @Override
                    public void init() {
                        // What Thread.sleep(1) takes on this machine, on this thread.
                        double[] ms = new double[50];
                        for (int i = 0; i < ms.length; i++) {
                            long t = System.nanoTime();
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException e) {
                                return;
                            }
                            ms[i] = (System.nanoTime() - t) / 1e6;
                        }
                        java.util.Arrays.sort(ms);
                        sleepMs = ms[ms.length / 2];
                    }

                    @Override
                    public void loop() {
                        long t = System.nanoTime();
                        if (loops < starts.length) {
                            starts[loops++] = t;
                            if (loops == starts.length) {
                                double[] ms = new double[starts.length - 1];
                                for (int i = 0; i < ms.length; i++) ms[i] = (starts[i + 1] - starts[i]) / 1e6;
                                java.util.Arrays.sort(ms);
                                median = String.valueOf(ms[ms.length / 2]);
                            }
                        }
                        while (System.nanoTime() - t < 3_000_000L) { }   // 3 ms of work
                        if (median != null) {
                            telemetry.addData("median ms per loop", median);
                            telemetry.addData("median ms per sleep", sleepMs);
                        }
                    }
                }
                """);
        start();
        sim.handle("{\"type\":\"init\",\"opmode\":\"Three ms loop\"}");
        waitFor(Simulator.DsState.INIT);
        sim.handle("{\"type\":\"start\"}");
        long deadline = System.currentTimeMillis() + 10000;
        String line;
        while ((line = lastTelemetry("median ms per loop")) == null && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertTrue(line != null, "the OpMode reports its loop time");
        double ms = Double.parseDouble(line.substring(line.indexOf(':') + 1).trim());
        String sleepLine = lastTelemetry("median ms per sleep");
        double sleepMs = Double.parseDouble(sleepLine.substring(sleepLine.indexOf(':') + 1).trim());
        // As the SDK, which runs Thread.sleep(1) after every loop(): the 3 ms of work plus one such
        // sleep on this machine. That is about 4 ms on a PC, but a CI virtual machine's sleep(1) can
        // take 9 ms, hence the measured sleep rather than a fixed limit. Not the 3 ms of the work
        // alone, nor a 15 ms wait for Windows' default timer tick where sleep(1) takes 1-2 ms. Medians,
        // so that a busy machine pausing the OpMode now and then doesn't count.
        assertTrue(ms > 3.8 && ms < 3 + sleepMs + 3,
                "median ms per loop: " + ms + ", median ms per Thread.sleep(1): " + sleepMs);
        sim.handle("{\"type\":\"stop\"}");
    }

    @Test
    void aLinearOpModesTimeFollowsItsRuntime() throws Exception {
        // As LinearOpMode.internalOnEventLoopIteration: the Robot Controller's event loop sets time to
        // getRuntime() about every 5 ms while the OpMode runs.
        write("Timer", """
                @TeleOp(name = "Timer")
                public class Timer extends LinearOpMode {
                    @Override
                    public void runOpMode() {
                        waitForStart();
                        sleep(300);
                        double t = time;
                        telemetry.addData("time", t);
                        telemetry.addData("behind runtime by", getRuntime() - t);
                        telemetry.update();
                        while (opModeIsActive()) idle();
                    }
                }
                """);
        start();
        sim.handle("{\"type\":\"init\",\"opmode\":\"Timer\"}");
        waitFor(Simulator.DsState.INIT);
        sim.handle("{\"type\":\"start\"}");
        waitFor(Simulator.DsState.RUNNING);
        long deadline = System.currentTimeMillis() + 5000;
        String line;
        while ((line = lastTelemetry("behind runtime by")) == null && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertTrue(line != null, "the OpMode reports its time");
        String timeLine = lastTelemetry("time");
        double time = Double.parseDouble(timeLine.substring(timeLine.indexOf(':') + 1).trim());
        double behind = Double.parseDouble(line.substring(line.indexOf(':') + 1).trim());
        assertTrue(time > 0.29, "time after sleep(300), less up to one event loop iteration: " + time);
        assertTrue(behind >= 0 && behind < 0.1, "time is at most an event loop iteration behind getRuntime(): " + behind);
        sim.handle("{\"type\":\"stop\"}");
    }

    @Test
    void telemetryCanBeUsedFromAFieldInitializer() throws Exception {
        // FIRST's OpModeInternal creates the OpMode's TelemetryImpl in its constructor, so a field
        // initializer can keep it (as new MultipleTelemetry(telemetry, ...) does). What is sent
        // through it is shown even after the OpMode puts something else in its telemetry field.
        write("KeepsTelemetry", """
                import org.firstinspires.ftc.robotcore.external.Telemetry;

                @TeleOp(name = "Keeps telemetry")
                public class KeepsTelemetry extends LinearOpMode {
                    private final Telemetry kept = telemetry;

                    {
                        telemetry = null;
                    }

                    @Override
                    public void runOpMode() {
                        while (opModeInInit()) {
                            kept.addData("kept", "yes");
                            kept.update();
                            sleep(20);
                        }
                    }
                }
                """);
        start();
        sim.handle("{\"type\":\"init\",\"opmode\":\"Keeps telemetry\"}");
        waitFor(Simulator.DsState.INIT);
        long deadline = System.currentTimeMillis() + 3000;
        while (lastTelemetry("kept") == null && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals("kept : yes", lastTelemetry("kept"), "error: " + sim.lastError());
        sim.handle("{\"type\":\"stop\"}");
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
