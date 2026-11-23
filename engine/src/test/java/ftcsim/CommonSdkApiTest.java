package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ftcsim.physics.FieldModel;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.sim.Simulator;

/**
 * SDK API that robot code commonly uses besides motors, servos and sensors (analog inputs, LEDs, a
 * Blinkin, files on the robot, thread pools, Blocks' JavaUtil...): TeamCode using it builds and runs,
 * and the values flow between it and the simulated robot.
 */
class CommonSdkApiTest {

    private static final String OPMODE = """
            package org.firstinspires.ftc.teamcode;

            import java.io.File;
            import java.util.concurrent.ExecutorService;
            import java.util.concurrent.Future;

            import org.firstinspires.ftc.robotcore.external.JavaUtil;
            import org.firstinspires.ftc.robotcore.internal.system.AppUtil;

            import android.graphics.Color;

            import com.qualcomm.hardware.rev.RevBlinkinLedDriver;
            import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
            import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
            import com.qualcomm.robotcore.hardware.AnalogInput;
            import com.qualcomm.robotcore.hardware.LED;
            import com.qualcomm.robotcore.hardware.Servo;
            import com.qualcomm.robotcore.util.ReadWriteFile;
            import com.qualcomm.robotcore.util.RobotLog;
            import com.qualcomm.robotcore.util.ThreadPool;

            @TeleOp(name = "Common API")
            public class CommonApi extends LinearOpMode {
                @Override
                public void runOpMode() throws InterruptedException {
                    AnalogInput pot = hardwareMap.get(AnalogInput.class, "pot");
                    AnalogInput hoodAngle = hardwareMap.analogInput.get("hood_angle");
                    LED led = hardwareMap.led.get("status_led");
                    RevBlinkinLedDriver lights = hardwareMap.get(RevBlinkinLedDriver.class, "lights");
                    Servo hood = hardwareMap.get(Servo.class, "hood");

                    File file = new File(AppUtil.FIRST_FOLDER, "sim-api-test.txt");
                    ReadWriteFile.writeFile(file, "hello " + JavaUtil.makeTextFromList(JavaUtil.createListWith("a", "b"), "-"));
                    File settings = AppUtil.getInstance().getSettingsFile("sim-api-settings.txt");
                    ReadWriteFile.writeFile(settings, "42");
                    RobotLog.logStackTrace(new Exception("logged on purpose"));

                    double pooled;
                    ExecutorService pool = ThreadPool.newSingleThreadExecutor("api-test");
                    try {
                        Future<Double> fromPool = pool.submit(() -> pot.getVoltage());
                        pooled = fromPool.get();
                        ThreadPool.getDefault().submit(() -> led.enable(true)).get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    pool.shutdown();
                    lights.setPattern(RevBlinkinLedDriver.BlinkinPattern.RED);

                    waitForStart();
                    hood.setPosition(1.0);
                    sleep(1000);
                    telemetry.addData("pot", pot.getVoltage());
                    telemetry.addData("max", pot.getMaxVoltage());
                    telemetry.addData("pooled", pooled);
                    telemetry.addData("hood angle", hoodAngle.getVoltage());
                    telemetry.addData("file", ReadWriteFile.readFile(file));
                    telemetry.addData("settings", ReadWriteFile.readFile(settings));
                    telemetry.addData("in settings", settings.getParentFile().equals(new File(AppUtil.FIRST_FOLDER, "settings")));
                    telemetry.addData("navy", Integer.toHexString(Color.parseColor("Navy")));
                    telemetry.addData("hue of red", JavaUtil.colorToHue(Color.RED));
                    telemetry.addData("package", hardwareMap.appContext.getPackageName());
                    telemetry.addData("mappings", hardwareMap.analogInput.size() + " analog, " + hardwareMap.led.size() + " led, "
                            + hardwareMap.gyroSensor.size() + " gyro");
                    telemetry.update();
                    while (opModeIsActive()) idle();
                }
            }
            """;

    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final List<String> states = new CopyOnWriteArrayList<>();

    @Test
    void teamCodeUsingTheCommonSdkApiRunsAndItsValuesFlow(@TempDir Path dir) throws Exception {
        // The mecanum robot with an analog input at a constant voltage, an absolute encoder on the
        // hood servo, an LED and a Blinkin.
        JsonObject robot = JsonParser.parseString(Files.readString(Path.of("robots/mecanum.json"))).getAsJsonObject();
        JsonArray devices = robot.getAsJsonArray("devices");
        devices.add(JsonParser.parseString("{\"type\": \"analog\", \"name\": \"pot\", \"volts\": 1.65}"));
        devices.add(JsonParser.parseString("{\"type\": \"analog\", \"name\": \"hood_angle\", \"encoder_of\": \"hood\"}"));
        devices.add(JsonParser.parseString("{\"type\": \"led\", \"name\": \"status_led\"}"));
        devices.add(JsonParser.parseString("{\"type\": \"blinkin\", \"name\": \"lights\"}"));
        Path robots = Files.createDirectories(dir.resolve("robots"));
        Path robotFile = robots.resolve("signals.json");
        Files.writeString(robotFile, robot.toString());
        Path pkg = Files.createDirectories(dir.resolve("src/org/firstinspires/ftc/teamcode"));
        Files.writeString(pkg.resolve("CommonApi.java"), OPMODE);

        Simulator sim = new Simulator(FieldModel.loadBiobuzz(), robotFile, robots,
                new TeamCodeCompiler(List.of(new TeamCodeCompiler.SourceRoot(dir.resolve("src"), false)),
                        dir.resolve("build")), "test");
        sim.setOutput(new Simulator.Output() {
            @Override
            public void broadcast(String json) {
                messages.add(json);
            }

            @Override
            public void broadcastState(String json) {
                if (json.startsWith("{\"type\":\"devices\"")) states.add(json);
            }
        });
        sim.start();
        File file = new File(AppUtil.FIRST_FOLDER, "sim-api-test.txt");
        File settings = new File(AppUtil.ROBOT_SETTINGS, "sim-api-settings.txt");
        try {
            long deadline = System.currentTimeMillis() + 60000;
            while ((sim.build() == null || sim.isBuilding()) && System.currentTimeMillis() < deadline) Thread.sleep(50);
            assertEquals(1, sim.build().opModes().size(), sim.build().message());
            sim.handle("{\"type\":\"init\",\"opmode\":\"Common API\"}");
            waitFor(sim, Simulator.DsState.INIT);
            sim.handle("{\"type\":\"start\"}");
            waitFor(sim, Simulator.DsState.RUNNING);

            assertEquals("pot : 1.65", telemetryLine("pot", 5000), "error: " + sim.lastError());
            assertEquals("max : 3.3", telemetryLine("max", 0));
            assertEquals("pooled : 1.65", telemetryLine("pooled", 0), "a hardware call from a thread pool");
            String hood = telemetryLine("hood angle", 0);
            double volts = Double.parseDouble(hood.substring(hood.indexOf(':') + 1).trim());
            assertEquals(3.3 * 150 / 360, volts, 0.01, "the hood's horn at +150 degrees, read as an absolute encoder");
            assertEquals("file : hello a-b", telemetryLine("file", 0));
            assertEquals("settings : 42", telemetryLine("settings", 0));
            assertEquals("in settings : true", telemetryLine("in settings", 0));
            assertEquals("navy : ff000080", telemetryLine("navy", 0));
            assertEquals("hue of red : 0", telemetryLine("hue of red", 0));
            assertEquals("package : com.qualcomm.ftcrobotcontroller", telemetryLine("package", 0));
            assertEquals("mappings : 2 analog, 1 led, 0 gyro", telemetryLine("mappings", 0));
            assertEquals("hello a-b", Files.readString(file.toPath()), "the file is in the simulator's stand-in for /sdcard");

            String readings = lastDevices();
            assertTrue(readings.contains("\"pot\":\"1.650 V\""), readings);
            assertTrue(readings.contains("\"status_led\":\"LED on\""), readings);
            assertTrue(readings.contains("\"lights\":\"Blinkin RED\""), readings);
            sim.handle("{\"type\":\"stop\"}");
        } finally {
            sim.shutdown();
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(settings.toPath());
        }
    }

    private static void waitFor(Simulator sim, Simulator.DsState state) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (sim.dsState() != state && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(state, sim.dsState(), "Driver Station state (error: " + sim.lastError() + ")");
    }

    /** The newest telemetry line that starts with {@code caption + " :"}, waiting up to {@code waitMs}. */
    private String telemetryLine(String caption, long waitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitMs;
        do {
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonObject m = JsonParser.parseString(messages.get(i)).getAsJsonObject();
                if (!"telemetry".equals(m.get("type").getAsString())) continue;
                for (var line : m.getAsJsonArray("lines")) if (line.getAsString().startsWith(caption + " :")) return line.getAsString();
            }
            Thread.sleep(20);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    /** The newest device readings, waiting for one taken after this call. */
    private String lastDevices() throws InterruptedException {
        int n = states.size();
        long deadline = System.currentTimeMillis() + 3000;
        while (states.size() == n && System.currentTimeMillis() < deadline) Thread.sleep(20);
        return states.get(states.size() - 1);
    }
}
