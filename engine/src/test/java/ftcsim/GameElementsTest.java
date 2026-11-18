package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.ode4j.math.DVector3C;

import biobuzz.BiobuzzGame;
import biobuzz.Hive;
import biobuzz.HiveLayout;
import ftcsim.config.RobotConfig;
import ftcsim.hal.Latency;
import ftcsim.hal.ServoPort;
import ftcsim.physics.FieldModel;
import ftcsim.physics.PhysicsWorld;
import ftcsim.sim.GameElements;
import ftcsim.sim.GameElements.Ball;
import ftcsim.sim.GameElements.State;
import ftcsim.sim.RobotRig;

/** POLLEN and NECTAR: staging per Competition Manual 10.3.1, physics, intake and launcher. */
class GameElementsTest {
    private static final double DT = 0.001;

    private record Setup(RobotRig rig, GameElements elements) {
        void run(double seconds) {
            for (int i = 0; i < Math.round(seconds / DT); i++) {
                elements.beforeStep(DT);
                rig.step(DT);
            }
        }
    }

    private static Setup setup(String robot) throws Exception {
        RobotConfig cfg = RobotConfig.load(Path.of("robots/" + robot));
        FieldModel field = FieldModel.loadBiobuzz();
        RobotRig rig = new RobotRig(cfg, new PhysicsWorld(field));
        Latency.commandNanos = 0;
        Latency.i2cNanos = 0;
        return new Setup(rig, new GameElements(field, rig));
    }

    private static long count(List<Ball> balls, String color, State state) {
        return balls.stream().filter(b -> b.color.equals(color) && b.state() == state).count();
    }

    @Test
    void stagedPerTheCompetitionManual() throws Exception {
        Setup s = setup("mecanum.json");
        List<Ball> balls = s.elements.balls();
        // 9.8: 40 POLLEN, 8 red NECTAR, 8 blue NECTAR.
        assertEquals(40, balls.stream().filter(b -> b.kind.equals("pollen")).count());
        assertEquals(8, balls.stream().filter(b -> b.color.equals("red")).count());
        assertEquals(8, balls.stream().filter(b -> b.color.equals("blue")).count());
        assertEquals(0.0355, balls.get(0).radius, 1e-4);
        assertEquals(0.0455, balls.get(balls.size() - 1).radius, 1e-4);
        // 10.3.1 POLLEN: 16 in the 4 FLOWERS (staged), 8 in GARDENS + 12 absent-robot pre-loads (free), 4 in this robot.
        assertEquals(16, count(balls, "yellow", State.STAGED));
        assertEquals(20, count(balls, "yellow", State.FREE));
        assertEquals(4, count(balls, "yellow", State.HELD));
        assertEquals(4, s.elements.heldCount());
        // NECTAR: 3 free in the up CELL (held by its walls) + 5 staged in the ALLIANCE AREA.
        assertEquals(3, count(balls, "red", State.FREE));
        assertEquals(5, count(balls, "red", State.STAGED));
        assertEquals(3, count(balls, "blue", State.FREE));
        assertEquals(5, count(balls, "blue", State.STAGED));
        // Red NECTAR: 3 in the red HIVE's raised (audience) CELL, the others outside the field.
        List<Ball> red = balls.stream().filter(b -> b.color.equals("red")).collect(Collectors.toList());
        assertEquals(3, red.stream().filter(b -> "red audience".equals(BiobuzzGame.of(s.elements).cellOf(b))).count());
        assertEquals(5, red.stream().filter(b -> b.position().get0() < -1.83).count());
        List<Ball> blue = balls.stream().filter(b -> b.color.equals("blue")).collect(Collectors.toList());
        assertEquals(3, blue.stream().filter(b -> "blue rear".equals(BiobuzzGame.of(s.elements).cellOf(b))).count());
    }

    @Test
    void freeBallsRestOnTheTilesInsideTheField() throws Exception {
        Setup s = setup("mecanum.json");
        s.run(2.0);
        for (Ball b : s.elements.balls()) {
            if (b.state() != State.FREE || BiobuzzGame.of(s.elements).cellOf(b) != null) continue;
            DVector3C p = b.position();
            assertEquals(b.radius, p.get2(), 0.004, "ball " + b.id + " should rest on the floor");
            assertTrue(Math.abs(p.get0()) < 1.8288 && Math.abs(p.get1()) < 1.8288, "ball " + b.id + " inside the walls");
            assertTrue(Math.hypot(b.velocity().get0(), b.velocity().get1()) < 0.01, "ball " + b.id + " at rest");
        }
    }

    @Test
    void robotPushesABall() throws Exception {
        Setup s = setup("tank.json");
        s.rig.place(0, -1.0, 0);
        Ball ball = s.elements.balls().stream().filter(b -> b.state() == State.FREE).findFirst().orElseThrow();
        // Put a free ball just ahead of the robot and drive into it.
        ballAt(s, ball, 0.35, -1.0);
        s.rig.motors.get("left_drive").port.power = -0.5;
        s.rig.motors.get("right_drive").port.power = 0.5;
        s.run(1.0);
        assertTrue(ball.position().get0() > 0.5, "ball pushed forward, at x=" + ball.position().get0());
    }

    @Test
    void intakePicksUpToFourThenLauncherShoots() throws Exception {
        Setup s = setup("mecanum.json");
        s.rig.place(0, -1.0, 0);
        // Empty the robot: reverse the intake until the pre-load is out.
        s.rig.motors.get("intake").port.power = -1;
        s.run(1.5);
        assertEquals(0, s.elements.heldCount(), "reversed intake ejects the pre-load");
        s.rig.motors.get("intake").port.power = 0;
        s.rig.place(0, -0.3, 0);   // away from the ejected balls
        s.run(0.3);

        // Line up 5 free balls in front of the roller; it should keep only 4 (G407).
        List<Ball> free = s.elements.balls().stream().filter(b -> b.state() == State.FREE).limit(5).collect(Collectors.toList());
        s.rig.motors.get("intake").port.power = 1;
        s.run(0.3);
        for (Ball b : free) {
            ballAt(s, b, 0.27, -0.3);
            s.run(0.3);
        }
        assertEquals(s.elements.capacity(), s.elements.heldCount());
        s.rig.motors.get("intake").port.power = 0;

        // Spin the flywheel, then feed.
        s.rig.motors.get("launcher").port.power = 1;
        s.run(1.5);
        ServoPort feeder = s.rig.servoPorts().stream().filter(p -> p.name.equals("feeder")).findFirst().orElseThrow();
        feeder.position = 1.0;
        feeder.pwmEnabled = true;
        double maxZ = 0;
        for (int i = 0; i < 300; i++) {
            s.run(0.005);
            for (Ball b : s.elements.balls()) if (b.state() == State.FREE) maxZ = Math.max(maxZ, b.position().get2());
        }
        assertEquals(0, s.elements.heldCount(), "all four launched");
        assertEquals(4, s.elements.launchedCount());
        assertTrue(maxZ > 1.0, "launched balls fly high, max z " + maxZ);
    }

    @Test
    void nectarStaysInTheUpwardFacingCells() throws Exception {
        Setup s = setup("mecanum.json");
        s.run(3.0);
        Map<String, Integer> cells = BiobuzzGame.of(s.elements).cellCounts();
        // Red starts audience-side up, blue rear-side up (season.json "start_up").
        assertEquals(3, cells.get("red audience"), "cells " + cells);
        assertEquals(3, cells.get("blue rear"), "cells " + cells);
        assertEquals(0, cells.get("red rear"));
        assertEquals(0, cells.get("blue audience"));
        for (Ball b : s.elements.balls()) {
            if (BiobuzzGame.of(s.elements).cellOf(b) == null) continue;
            assertTrue(Math.hypot(b.velocity().get0(), Math.hypot(b.velocity().get1(), b.velocity().get2())) < 0.01,
                    "NECTAR " + b.id + " settled in its CELL");
        }
    }

    @Test
    void ballShotIntoTheOpenEndStaysInTheCell() throws Exception {
        Setup s = setup("mecanum.json");
        HiveLayout field = BiobuzzGame.of(s.elements).layout();
        Hive red = field.hive("red");
        Ball ball = freePollen(s);
        // Aim just inside the open outer end of the red audience CELL, arriving from the audience
        // side on a falling arc, as a launched ball would.
        double[] target = red.toWorld(0, -(field.cellOuter - 0.04), field.cellFloor + 0.12);
        lob(s, ball, target, 0, -1.2, 0.2, 0.45);
        s.run(3.0);
        assertEquals("red audience", BiobuzzGame.of(s.elements).cellOf(ball), "ball at " + ball.position());
        assertEquals(1 + 3, BiobuzzGame.of(s.elements).cellCounts().get("red audience"));
    }

    @Test
    void ballThrownAtTheSideOfTheCellBouncesOff() throws Exception {
        Setup s = setup("mecanum.json");
        s.rig.place(1.2, -1.2, 0);   // out of the way
        Ball ball = freePollen(s);
        // At the red raised CELL's side wall, 1.3 m up, from the red wall's side (+x): the CELL is
        // open only at its outer end, so this one must not get in.
        s.elements.throwBall(ball, -1.3, -0.31, 0.5, 1.7, 0, 4.0);
        s.run(3.0);
        assertEquals(null, BiobuzzGame.of(s.elements).cellOf(ball), "bounced off the CELL's side wall, at " + ball.position());
        assertTrue(ball.position().get2() < 0.1, "fell to the tiles, z " + ball.position().get2());
        assertTrue(ball.position().get0() < -0.55, "on the red side of the HIVE, x " + ball.position().get0());
        assertEquals(3, BiobuzzGame.of(s.elements).cellCounts().get("red audience"), "the NECTAR in the CELL are still there");
    }

    @Test
    void ballLandingOnTheRoofBouncesOff() throws Exception {
        Setup s = setup("mecanum.json");
        HiveLayout field = BiobuzzGame.of(s.elements).layout();
        Hive red = field.hive("red");
        Ball ball = freePollen(s);
        double mid = (field.cellInner + field.cellOuter) / 2;
        double[] ridge = red.toWorld(0.05, -mid, field.cellFloor + field.cellPeak);
        s.elements.throwBall(ball, ridge[0], ridge[1], ridge[2] + 0.4, 0, 0, 0);
        boolean gotIn = false;
        for (int i = 0; i < 60; i++) {
            s.run(0.05);
            if (BiobuzzGame.of(s.elements).cellOf(ball) != null) gotIn = true;
        }
        assertTrue(!gotIn, "a ball dropped on the roof must not fall through into the CELL");
        assertTrue(ball.position().get2() < 0.1, "it slides off and ends on the tiles, z " + ball.position().get2());
    }

    @Test
    void ballInTheDownwardFacingCellRollsOut() throws Exception {
        Setup s = setup("mecanum.json");
        HiveLayout field = BiobuzzGame.of(s.elements).layout();
        Hive red = field.hive("red");
        Ball ball = freePollen(s);
        double mid = (field.cellInner + field.cellOuter) / 2;
        double[] p = red.toWorld(0, mid, field.cellFloor + ball.radius + 0.002);
        s.elements.throwBall(ball, p[0], p[1], p[2], 0, 0, 0);
        s.run(0.05);
        assertEquals("red rear", BiobuzzGame.of(s.elements).cellOf(ball), "starts inside the down CELL");
        s.run(3.0);
        assertEquals(null, BiobuzzGame.of(s.elements).cellOf(ball), "rolls out of the open, lower end");
    }

    @Test
    void humanPlayerPutsNectarIntoTheLoadingZone() throws Exception {
        Setup s = setup("mecanum.json");
        FieldModel field = FieldModel.loadBiobuzz();
        for (String color : new String[]{"red", "blue"}) {
            assertEquals(5, BiobuzzGame.of(s.elements).waitingNectar(color));
            for (int i = 0; i < 5; i++) assertTrue(BiobuzzGame.of(s.elements).introduceNectar(color), color + " NECTAR " + (i + 1));
            assertEquals(0, BiobuzzGame.of(s.elements).waitingNectar(color));
            assertTrue(!BiobuzzGame.of(s.elements).introduceNectar(color), "no 6th " + color + " NECTAR");
        }
        s.run(2.0);
        var zones = field.json.getAsJsonObject("loading_zones");
        for (Ball b : s.elements.balls()) {
            if (!b.kind.equals("nectar") || BiobuzzGame.of(s.elements).cellOf(b) != null) continue;
            assertEquals(State.FREE, b.state(), "NECTAR " + b.id + " is on the field");
            var z = zones.getAsJsonObject(b.color);
            double x = b.position().get0(), y = b.position().get1();
            assertTrue(x >= z.getAsJsonArray("x_m").get(0).getAsDouble() - 0.01 && x <= z.getAsJsonArray("x_m").get(1).getAsDouble() + 0.01
                    && y >= z.getAsJsonArray("y_m").get(0).getAsDouble() && y <= z.getAsJsonArray("y_m").get(1).getAsDouble(),
                    b.color + " NECTAR " + b.id + " in its LOADING ZONE, at " + x + ", " + y);
            assertEquals(b.radius, b.position().get2(), 0.004, "resting on the tiles");
            assertTrue(Math.hypot(b.velocity().get0(), b.velocity().get1()) < 0.01, "at rest (placed clear of other balls)");
        }
        s.elements.restage();
        assertEquals(5, BiobuzzGame.of(s.elements).waitingNectar("red"));
        assertEquals(5, BiobuzzGame.of(s.elements).waitingNectar("blue"));
    }

    private static Ball freePollen(Setup s) {
        return s.elements.balls().stream().filter(b -> b.kind.equals("pollen") && b.state() == State.FREE)
                .findFirst().orElseThrow();
    }

    /** Throws a ball from (target + offset) so that, ignoring drag, it reaches target after t seconds. */
    private static void lob(Setup s, Ball b, double[] target, double dx, double dy, double dz, double t) {
        double x = target[0] + dx, y = target[1] + dy, z = target[2] + dz;
        s.elements.throwBall(b, x, y, z, -dx / t, -dy / t, -dz / t + 9.81 * t / 2);
    }

    private static void ballAt(Setup s, Ball b, double x, double y) {
        s.elements.placeBall(b, x, y);
    }
}
