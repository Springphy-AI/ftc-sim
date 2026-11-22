package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qualcomm.robotcore.hardware.Gamepad;

import ftcsim.hal.GamepadAccess;
import ftcsim.physics.FieldModel;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.sim.Simulator;

/** Gamepads as FIRST's SDK has them: trigger threshold and edge detection across INIT and START. */
class GamepadTest {

    private static void triggers(Gamepad gp, float left) {
        GamepadAccess.get().update(gp, Gamepad.Type.XBOX_360, 0, 0, 0, 0, left, 0, new boolean[18]);
    }

    @Test
    void aTriggerCountsAsPressedFromHalfWay() {
        Gamepad gp = new Gamepad();
        assertEquals(0.5f, gp.getTriggerThreshold());
        triggers(gp, 0.3f);
        assertFalse(gp.left_trigger_pressed, "0.3 is under FIRST's default threshold of 0.5");
        assertFalse(gp.leftTriggerWasPressed());
        triggers(gp, 0.5f);
        assertTrue(gp.left_trigger_pressed, "the threshold itself counts as pressed");
        assertTrue(gp.leftTriggerWasPressed());
        gp.setTriggerThreshold(1.5f);   // taken as is, as FIRST's is
        assertEquals(1.5f, gp.getTriggerThreshold());
    }

    private final List<String> messages = new CopyOnWriteArrayList<>();

    private static final String HEADER = """
            package org.firstinspires.ftc.teamcode;

            import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
            import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

            """;

    @Test
    void initResetsTheThresholdAndStartForgetsPressesMadeDuringInit(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("org/firstinspires/ftc/teamcode"));
        Files.writeString(pkg.resolve("LowThreshold.java"), HEADER + """
                @TeleOp(name = "Low threshold")
                public class LowThreshold extends LinearOpMode {
                    @Override
                    public void runOpMode() {
                        gamepad1.setTriggerThreshold(0.1f);
                        waitForStart();
                    }
                }
                """);
        Files.writeString(pkg.resolve("Edges.java"), HEADER + """
                @TeleOp(name = "Edges")
                public class Edges extends LinearOpMode {
                    @Override
                    public void runOpMode() {
                        waitForStart();
                        telemetry.addData("threshold", gamepad1.getTriggerThreshold());
                        telemetry.addData("a pressed", gamepad1.aWasPressed());
                        telemetry.update();
                        while (opModeIsActive()) idle();
                    }
                }
                """);
        Path robot = Path.of("").toAbsolutePath().resolve("robots/mecanum.json");
        Simulator sim = new Simulator(FieldModel.loadBiobuzz(), robot, robot.getParent(),
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
        try {
            long deadline = System.currentTimeMillis() + 60000;
            while ((sim.build() == null || sim.isBuilding()) && System.currentTimeMillis() < deadline) Thread.sleep(50);
            assertEquals(2, sim.build().opModes().size(), sim.build().message());

            sim.handle("{\"type\":\"init\",\"opmode\":\"Low threshold\"}");
            waitFor(sim, Simulator.DsState.INIT);
            sim.handle("{\"type\":\"stop\"}");
            waitFor(sim, Simulator.DsState.IDLE);

            sim.handle("{\"type\":\"init\",\"opmode\":\"Edges\"}");
            waitFor(sim, Simulator.DsState.INIT);
            sim.handle(gamepad(true));    // A pressed and let go during INIT
            sim.handle(gamepad(false));
            sim.handle("{\"type\":\"start\"}");
            assertEquals("threshold : 0.5", telemetryLine("threshold"), "INIT sets the trigger threshold back to 0.5");
            assertEquals("a pressed : false", telemetryLine("a pressed"), "START forgets what was pressed during INIT");
            sim.handle("{\"type\":\"stop\"}");
        } finally {
            sim.shutdown();
        }
    }

    private static String gamepad(boolean a) {
        return "{\"type\":\"gamepad\",\"gamepad\":1,\"kind\":\"XBOX_360\",\"axes\":[0,0,0,0,0,0],\"buttons\":[" + a
                + ",false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false]}";
    }

    private static void waitFor(Simulator sim, Simulator.DsState state) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (sim.dsState() != state && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(state, sim.dsState(), "Driver Station state (error: " + sim.lastError() + ")");
    }

    /** The newest telemetry line that starts with {@code caption}, waiting up to 3 s for one. */
    private String telemetryLine(String caption) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonObject m = JsonParser.parseString(messages.get(i)).getAsJsonObject();
                if (!"telemetry".equals(m.get("type").getAsString())) continue;
                for (var line : m.getAsJsonArray("lines")) if (line.getAsString().startsWith(caption)) return line.getAsString();
            }
            Thread.sleep(20);
        }
        return null;
    }
}
