package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.util.RobotLog;

import ftcsim.hal.RobotContext;
import ftcsim.physics.FieldModel;
import ftcsim.runner.OpModeInfo;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.sim.GameElements.Ball;
import ftcsim.sim.GameElements.State;
import ftcsim.sim.SimRobot;
import ftcsim.sim.Simulator;

/** Two robots on one field: separate Robot Controllers and Driver Stations, one shared physics world. */
class MultiRobotTest {

    private final List<String> messages = new CopyOnWriteArrayList<>();

    private Simulator start(int robots) throws Exception {
        Path home = Path.of("").toAbsolutePath();
        Path robotFile = home.resolve("robots/mecanum.json");
        List<Simulator.RobotSpec> specs = new ArrayList<>();
        for (int i = 0; i < robots; i++) {
            TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(
                    new TeamCodeCompiler.SourceRoot(home.resolve("examples/teamcode"), false),
                    new TeamCodeCompiler.SourceRoot(home.resolve("examples/samples"), true)), Files.createTempDirectory("ftcsim-test"));
            specs.add(new Simulator.RobotSpec(robotFile, compiler, "test", null));
        }
        Simulator sim = new Simulator(FieldModel.loadBiobuzz(), robotFile.getParent(), specs);
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
        long deadline = System.currentTimeMillis() + 90000;
        for (SimRobot r : sim.robots()) {
            while ((r.build() == null || r.isBuilding()) && System.currentTimeMillis() < deadline) Thread.sleep(50);
            assertNotNull(r.build(), "build finished");
            assertTrue(r.build().ok(), r.build().message());
        }
        return sim;
    }

    private static void waitFor(SimRobot r, Simulator.DsState state, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (r.dsState() != state && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(state, r.dsState(), "Robot " + (r.index + 1) + " Driver Station state (error: " + r.lastError() + ")");
    }

    private static String gamepad(int robot, float ly) {
        return "{\"type\":\"gamepad\",\"robot\":" + robot + ",\"gamepad\":1,\"kind\":\"XBOX_360\",\"axes\":[0," + ly + ",0,0,0,0],"
                + "\"buttons\":[false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false]}";
    }

    private static String cmd(String type, int robot, String extra) {
        return "{\"type\":\"" + type + "\",\"robot\":" + robot + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    /** The newest telemetry line of {@code robot} that starts with {@code caption}, waiting up to 2 s for one. */
    private String telemetryLine(int robot, String caption, int after) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            for (int i = messages.size() - 1; i >= after; i--) {
                JsonObject m = JsonParser.parseString(messages.get(i)).getAsJsonObject();
                if (!"telemetry".equals(m.get("type").getAsString()) || m.get("robot").getAsInt() != robot) continue;
                for (var line : m.getAsJsonArray("lines")) if (line.getAsString().startsWith(caption)) return line.getAsString();
            }
            Thread.sleep(20);
        }
        return null;
    }

    @Test
    void twoRobotsHaveTheirOwnCodeStartSpotsAndPreloads() throws Exception {
        Simulator sim = start(2);
        SimRobot red = sim.robot(0), blue = sim.robot(1);
        assertEquals("red", red.alliance);
        assertEquals("blue", blue.alliance);

        // Each robot has its own TeamCode class loader, so TeamCode static fields aren't shared.
        assertNotSame(red.build().loader(), blue.build().loader());

        // Robot 2 starts on the blue side: robot 1's spot turned half a turn about the field center.
        double[] s1 = red.rig().start, s2 = blue.rig().start;
        assertEquals(-s1[0], s2[0], 1e-9);
        assertEquals(-s1[1], s2[1], 1e-9);
        assertEquals(180, Math.abs(s2[2] - s1[2]), 1e-9);
        assertEquals(s2[0], blue.rig().body.position().get0(), 0.01);

        // 10.3.1: both robots hold a pre-load; only the two absent robots' pre-loads wait in LOADING ZONES.
        sim.onPhysicsThread(() -> {
            assertEquals(4, sim.elements().heldCount(0));
            assertEquals(4, sim.elements().heldCount(1));
            List<Ball> balls = sim.elements().balls();
            assertEquals(8, balls.stream().filter(b -> b.color.equals("yellow") && b.state() == State.HELD).count());
            assertEquals(8 + 8, balls.stream().filter(b -> b.color.equals("yellow") && b.state() == State.FREE).count());
        });

        // OpMode.blackboard belongs to each robot: the sample counts how often it was started.
        String blackboard = red.build().opModes().stream().filter(o -> o.className().endsWith("ConceptBlackboard"))
                .map(OpModeInfo::name).findFirst().orElseThrow();
        for (int run = 1; run <= 2; run++) {
            int mark = messages.size();
            sim.handle(cmd("init", 0, "\"opmode\":\"" + blackboard + "\""));
            waitFor(red, Simulator.DsState.INIT, 2000);
            String line = telemetryLine(0, "OpMode started times", mark);
            assertNotNull(line, "robot 1 telemetry");
            assertTrue(line.endsWith(" " + run), line);
            sim.handle(cmd("stop", 0, ""));
            waitFor(red, Simulator.DsState.IDLE, 3000);
        }
        int mark = messages.size();
        sim.handle(cmd("init", 1, "\"opmode\":\"" + blackboard + "\""));
        waitFor(blue, Simulator.DsState.INIT, 2000);
        assertEquals(Simulator.DsState.IDLE, red.dsState(), "robot 1 is not affected");
        String line = telemetryLine(1, "OpMode started times", mark);
        assertNotNull(line, "robot 2 telemetry");
        assertTrue(line.endsWith(" 1"), "robot 2 has its own blackboard: " + line);
        sim.handle(cmd("stop", 1, ""));
        waitFor(blue, Simulator.DsState.IDLE, 3000);
        assertNull(red.lastError());
        assertNull(blue.lastError());
        sim.shutdown();
    }

    @Test
    void eachRobotDrivesWithItsOwnGamepadAndTheyPushEachOther() throws Exception {
        Simulator sim = start(2);
        SimRobot one = sim.robot(0), two = sim.robot(1);
        // Open floor on the audience side: robot 1 facing +x, robot 2 a little ahead of it, facing it.
        sim.handle(cmd("place", 0, "\"x\":-1.1,\"y\":-1.0,\"h\":0"));
        sim.handle(cmd("place", 1, "\"x\":-0.45,\"y\":-1.0,\"h\":180"));
        Thread.sleep(500);
        double x2 = two.rig().body.position().get0();

        String omni = "Basic: Omni Linear OpMode";
        sim.handle(cmd("init", 0, "\"opmode\":\"" + omni + "\""));
        waitFor(one, Simulator.DsState.INIT, 2000);
        sim.handle(cmd("start", 0, ""));
        waitFor(one, Simulator.DsState.RUNNING, 2000);

        // Robot 2's gamepad does nothing to robot 1, whose OpMode reads only its own gamepad1.
        sim.handle(gamepad(1, -1f));
        Thread.sleep(600);
        assertEquals(-1.1, one.rig().body.position().get0(), 0.03, "robot 1 ignores robot 2's gamepad");
        sim.handle(gamepad(1, 0f));

        // Robot 1 drives forward into robot 2 and shoves it.
        sim.handle(gamepad(0, -1f));
        Thread.sleep(2000);
        sim.handle(gamepad(0, 0f));
        double pushed = two.rig().body.position().get0() - x2;
        double gap = two.rig().body.position().get0() - one.rig().body.position().get0();
        System.out.printf("robot 2 pushed %.2f m; centers %.2f m apart%n", pushed, gap);
        assertTrue(pushed > 0.15, "robot 1 pushes robot 2 (moved " + pushed + " m)");
        assertTrue(gap > 0.4, "the robots collide instead of passing through each other (" + gap + " m)");
        assertEquals(Simulator.DsState.IDLE, two.dsState());

        sim.handle(cmd("stop", 0, ""));
        waitFor(one, Simulator.DsState.IDLE, 3000);
        assertNull(one.lastError());
        sim.shutdown();
    }

    @Test
    void bothRobotsRunTheirAutonomousAtTheSameTime() throws Exception {
        Simulator sim = start(2);
        String auto = "Pinpoint Square";
        double[][] start = new double[2][];
        for (SimRobot r : sim.robots()) {
            start[r.index] = new double[]{r.rig().body.position().get0(), r.rig().body.position().get1()};
            sim.handle(cmd("select", r.index, "\"opmode\":\"" + auto + "\""));
        }
        // The INIT all / START all buttons: every robot at once.
        sim.handle("{\"type\":\"initAll\"}");
        for (SimRobot r : sim.robots()) waitFor(r, Simulator.DsState.INIT, 2000);
        sim.handle("{\"type\":\"startAll\"}");
        for (SimRobot r : sim.robots()) waitFor(r, Simulator.DsState.RUNNING, 2000);
        Thread.sleep(2500);
        for (SimRobot r : sim.robots()) {
            double moved = Math.hypot(r.rig().body.position().get0() - start[r.index][0], r.rig().body.position().get1() - start[r.index][1]);
            System.out.printf("robot %d moved %.2f m while both ran %s%n", r.index + 1, moved, auto);
            assertEquals(Simulator.DsState.RUNNING, r.dsState(), "robot " + (r.index + 1) + " still running (error: " + r.lastError() + ")");
            assertTrue(moved > 0.2, "robot " + (r.index + 1) + " drives its square (moved " + moved + " m)");
        }
        sim.handle("{\"type\":\"stopAll\"}");
        for (SimRobot r : sim.robots()) {
            waitFor(r, Simulator.DsState.IDLE, 3000);
            assertNull(r.lastError());
        }
        sim.shutdown();
    }

    /** A new robot file (a CAD import, a finished conversion) never swaps the robot under a running OpMode. */
    @Test
    void aRobotChangeWaitsForTheOpModeToStop() throws Exception {
        Simulator sim = start(1);
        SimRobot one = sim.robot(0);
        var rig = one.rig();
        sim.handle(cmd("init", 0, "\"opmode\":\"Basic: Omni Linear OpMode\""));
        waitFor(one, Simulator.DsState.INIT, 2000);
        one.requestLoad(Path.of("").toAbsolutePath().resolve("robots/tank.json"));
        Thread.sleep(800);
        assertSame(rig, one.rig(), "the OpMode keeps the robot it started with");
        assertEquals("mecanum.json", one.robotFile().getFileName().toString());
        sim.handle(cmd("stop", 0, ""));
        waitFor(one, Simulator.DsState.IDLE, 3000);
        long deadline = System.currentTimeMillis() + 5000;
        while (!one.robotFile().getFileName().toString().equals("tank.json") && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals("tank.json", one.robotFile().getFileName().toString(), "changed once the OpMode stopped");
        assertNotSame(rig, one.rig());
        assertNull(one.lastError());
        sim.shutdown();
    }

    @Test
    void switchingOneRobotsDescriptionLeavesTheOtherAlone() throws Exception {
        Simulator sim = start(2);
        SimRobot one = sim.robot(0), two = sim.robot(1);
        sim.handle(cmd("place", 0, "\"x\":-1.0,\"y\":-1.0,\"h\":30"));
        Thread.sleep(300);
        var rigOne = one.rig();
        double x1 = rigOne.body.position().get0();

        sim.handle(cmd("robot", 1, "\"file\":\"tank.json\""));
        long deadline = System.currentTimeMillis() + 3000;
        while (!two.robotFile().getFileName().toString().equals("tank.json") && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals("tank.json", two.robotFile().getFileName().toString());
        assertTrue(two.rig().config.drive.type.startsWith("tank"));
        assertTrue(sim.helloJson().contains("\"robotFile\":\"tank.json\""));
        Thread.sleep(1000);   // the world keeps stepping with the new robot in it

        assertSame(rigOne, one.rig(), "robot 1 keeps its hardware");
        assertEquals("mecanum.json", one.robotFile().getFileName().toString());
        assertEquals(x1, one.rig().body.position().get0(), 0.01, "robot 1 stays where it was placed");
        double[] s = two.rig().start;
        assertEquals(s[0], two.rig().body.position().get0(), 0.02, "robot 2 rests at its start spot");
        assertEquals(s[1], two.rig().body.position().get1(), 0.02);
        sim.onPhysicsThread(() -> assertEquals(4, sim.elements().heldCount(1), "the balls inside stay inside"));
        assertNull(two.lastError());
        sim.shutdown();
    }

    @Test
    void alliancePartnersStartSideBySide() throws Exception {
        Path home = Path.of("").toAbsolutePath();
        Path robotFile = home.resolve("robots/mecanum.json");
        List<Simulator.RobotSpec> specs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(
                    new TeamCodeCompiler.SourceRoot(home.resolve("examples/teamcode"), false)), Files.createTempDirectory("ftcsim-test"));
            specs.add(new Simulator.RobotSpec(robotFile, compiler, "test", "red"));
        }
        Simulator sim = new Simulator(FieldModel.loadBiobuzz(), robotFile.getParent(), specs);   // not started: no physics thread
        SimRobot a = sim.robot(0), b = sim.robot(1);
        assertEquals("red", b.alliance);
        assertEquals(2, b.station);
        double[] s1 = a.rig().start, s2 = b.rig().start;
        assertEquals(s1[0], s2[0], 1e-9, "same distance from the red wall");
        assertEquals(-s1[1], s2[1], 1e-9, "mirrored across the center line");
        assertEquals(s1[2], s2[2], 1e-9, "same heading");
        double width = a.rig().config.chassis.widthM;
        assertTrue(Math.abs(s2[1] - s1[1]) > width + 0.05, "they don't overlap: " + s1[1] + " vs " + s2[1]);

        // Both red robots hold a pre-load; the red LOADING ZONE is empty, the blue one has the two absent blue robots'.
        List<Ball> balls = sim.elements().balls();
        assertEquals(4, sim.elements().heldCount(0));
        assertEquals(4, sim.elements().heldCount(1));
        long nearRedWall = balls.stream().filter(x -> x.state() == State.FREE && x.color.equals("yellow")
                && x.position().get0() < -1.5 && x.position().get1() > 0.5).count();
        long nearBlueWall = balls.stream().filter(x -> x.state() == State.FREE && x.color.equals("yellow")
                && x.position().get0() > 1.5 && x.position().get1() < -0.5).count();
        assertEquals(0, nearRedWall, "no absent red robots");
        assertEquals(8, nearBlueWall, "two absent blue robots");

        // A third red robot is one too many.
        specs.add(new Simulator.RobotSpec(robotFile, specs.get(0).compiler(), "test", "red"));
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Simulator(FieldModel.loadBiobuzz(), robotFile.getParent(), specs));
        assertTrue(e.getMessage().contains("already has two robots"), e.getMessage());
    }

    @Test
    void sdkStaticStateIsPerRobot() {
        RobotContext a = new RobotContext(0), b = new RobotContext(1);
        a.run(() -> {
            OpMode.blackboard.put("pose", "A");
            RobotLog.setGlobalErrorMsg("robot A failed");
        });
        b.run(() -> {
            assertNull(OpMode.blackboard.get("pose"));
            assertTrue(OpMode.blackboard.isEmpty());
            assertEquals("", RobotLog.getGlobalErrorMsg());
            OpMode.blackboard.put("pose", "B");
        });
        a.run(() -> {
            assertEquals("A", OpMode.blackboard.get("pose"));
            assertEquals("robot A failed", RobotLog.getGlobalErrorMsg());
        });
        // Threads an OpMode starts belong to its robot too.
        AtomicReference<Object> seen = new AtomicReference<>();
        a.run(() -> {
            Thread t = new Thread(() -> seen.set(OpMode.blackboard.get("pose")));
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        assertEquals("A", seen.get());
    }
}
