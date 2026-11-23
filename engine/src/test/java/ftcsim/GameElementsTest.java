package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ode4j.math.DVector3C;

import biobuzz.BiobuzzGame;
import biobuzz.Flower;
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
        return setup(Path.of("robots/" + robot));
    }

    private static Setup setup(Path robot) throws Exception {
        RobotConfig cfg = RobotConfig.load(robot);
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
        // 10.3.1 POLLEN: 16 in the 4 FLOWERS, 8 in GARDENS, 12 absent-robot pre-loads (all free), 4 in this robot.
        assertEquals(0, count(balls, "yellow", State.STAGED));
        assertEquals(36, count(balls, "yellow", State.FREE));
        for (int n : BiobuzzGame.of(s.elements).flowerCounts().values()) assertEquals(4, n);
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
            BiobuzzGame game = BiobuzzGame.of(s.elements);
            if (b.state() != State.FREE || game.cellOf(b) != null || game.flowerOf(b) != null) continue;
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

    // ---- FLOWERS (Competition Manual 9.7) ------------------------------------------------------

    @Test
    void pollenStayStackedInTheFlowers() throws Exception {
        Setup s = setup("mecanum.json");
        BiobuzzGame game = BiobuzzGame.of(s.elements);
        s.run(3.0);
        for (int n : game.flowerCounts().values()) assertEquals(4, n, "flowers " + game.flowerCounts());
        for (Ball b : s.elements.balls()) {
            if (game.flowerOf(b) == null) continue;
            assertTrue(b.velocity().length() < 0.01, "POLLEN " + b.id + " at rest in its FLOWER");
        }
        // The bottom one sits in the lower ring's hole, on the tiles.
        double lowest = s.elements.balls().stream().filter(b -> "flower_red".equals(game.flowerOf(b)))
                .mapToDouble(b -> b.position().get2()).min().orElseThrow();
        assertEquals(0.0355, lowest, 0.003);
    }

    @Test
    void intakeTakesPollenFromTheBottomOfAFlowerAndTheStackDrops() throws Exception {
        Setup s = setup("mecanum.json");
        BiobuzzGame game = BiobuzzGame.of(s.elements);
        emptyRobot(s);
        // Drive the running intake up to the red FLOWER's Retrieval Opening: the roller reaches in,
        // takes the bottom POLLEN, and each one above drops down and is taken in turn.
        s.rig.motors.get("intake").port.power = 1;
        driveIntoFlower(s, game.flower("flower_red"), 0.3, 3.0);
        assertEquals(4, s.elements.heldCount(), "all 4 POLLEN came out of the bottom");
        assertEquals(0, game.flowerCounts().get("flower_red"));
    }

    @Test
    void aFullRobotsRollerKnocksTheBottomPollenOut() throws Exception {
        Setup s = setup("mecanum.json");   // full: 4 pre-loaded, so the intake can't take any
        BiobuzzGame game = BiobuzzGame.of(s.elements);
        Flower f = game.flower("flower_red");
        Ball bottom = s.elements.balls().stream().filter(b -> "flower_red".equals(game.flowerOf(b)))
                .min((a, b) -> Double.compare(a.position().get2(), b.position().get2())).orElseThrow();
        for (int bump = 0; bump < 3 && game.flowerOf(bottom) != null; bump++) {   // a firm bump, slightly off center
            driveIntoFlower(s, f, 0.6, 2.5, 0.02);
        }
        assertEquals(null, game.flowerOf(bottom), "a bump pushed the bottom POLLEN out");
        // The robot's body stopped at the FLOWER's frame; only the low roller reached in.
        double front = Math.hypot(s.rig.body.position().get0() - f.x(), s.rig.body.position().get1() - f.y()) - 0.2286;
        assertTrue(front > f.innerRadius(), "chassis front " + front + " m from the FLOWER's axis");
        // Once the robot stops pushing, the POLLEN lies on the tiles. Right after the bump the roller
        // can still be holding it on the lower ring's rim, 1 cm up and just outside the tube. (How the
        // stack falls differs from run to run: ODE shuffles its constraints with one random number
        // generator for the whole JVM, so it depends on what was simulated before.)
        for (var m : s.rig.motors.values()) m.port.power = 0;
        s.run(1.5);
        assertEquals(bottom.radius, bottom.position().get2(), 0.005, "knocked out onto the tiles");
    }

    @Test
    void onceTheBottomPollenIsGoneTheOthersRollOut() throws Exception {
        Setup s = setup("mecanum.json");
        BiobuzzGame game = BiobuzzGame.of(s.elements);
        s.run(1.0);
        List<Ball> stack = s.elements.balls().stream().filter(b -> "flower_red".equals(game.flowerOf(b)))
                .sorted((a, b) -> Double.compare(a.position().get2(), b.position().get2())).toList();
        assertEquals(4, stack.size(), "the stack holds the bottom one in place");
        s.elements.placeBall(stack.get(0), 0.5, 1.2);   // the bottom POLLEN is knocked away
        s.run(3.0);
        long out = stack.stream().skip(1).filter(b -> game.flowerOf(b) == null).count();
        assertTrue(out >= 2, "the POLLEN above dropped and rolled out of the Retrieval Opening: " + out + " of 3");
        for (Ball b : stack.subList(1, 4)) {
            if (game.flowerOf(b) == null) assertEquals(b.radius, b.position().get2(), 0.005, "rolled out onto the tiles");
        }
    }

    @Test
    void pollenInAnEmptyFlowerFallsThroughButANectarLocksItIn() throws Exception {
        Setup s = setup("mecanum.json");
        BiobuzzGame game = BiobuzzGame.of(s.elements);
        Flower f = game.flower("flower_red");
        List<Ball> stack = s.elements.balls().stream().filter(b -> "flower_red".equals(game.flowerOf(b))).toList();
        int k = 0;
        for (Ball b : stack) s.elements.placeBall(b, 0.3 + 0.12 * k++, 1.3);   // empty the FLOWER
        s.run(0.5);
        // A POLLEN dropped into the empty FLOWER falls through and rolls out of the bottom.
        Ball p1 = stack.get(0);
        s.elements.throwBall(p1, f.x() + 0.005, f.y(), f.height() + 0.15, 0, 0, -1.0);
        s.run(2.0);
        assertEquals(null, game.flowerOf(p1), "nothing holds a lone POLLEN in");
        // A NECTAR stops on the middle ring and locks in the POLLEN put in on top of it.
        assertTrue(game.introduceNectar("red"));
        Ball nectar = s.elements.balls().stream().filter(b -> b.color.equals("red") && b.state() == State.FREE
                && game.cellOf(b) == null).findFirst().orElseThrow();
        s.elements.throwBall(nectar, f.x() + 0.005, f.y(), f.height() + 0.15, 0, 0, -1.0);
        s.run(2.0);
        assertEquals("flower_red", game.flowerOf(nectar), "the NECTAR stays on the middle ring");
        for (Ball b : stack.subList(1, 4)) {
            s.elements.throwBall(b, f.x() + 0.005, f.y(), f.height() + 0.15, 0, 0, -1.0);
            s.run(1.0);
        }
        s.run(1.0);
        for (Ball b : stack.subList(1, 4)) assertEquals("flower_red", game.flowerOf(b), "POLLEN locked in above the NECTAR");
        assertEquals(4, game.flowerCounts().get("flower_red"));
    }

    @Test
    void ballDroppedInTheTopLandsOnTheStack() throws Exception {
        Setup s = setup("mecanum.json");
        BiobuzzGame game = BiobuzzGame.of(s.elements);
        Flower f = game.flower("flower_blue");
        Ball ball = s.elements.balls().stream().filter(b -> b.kind.equals("pollen") && b.state() == State.FREE
                && game.flowerOf(b) == null).findFirst().orElseThrow();
        s.elements.throwBall(ball, f.x() + 0.01, f.y(), f.height() + 0.2, 0, 0, 0);
        s.run(2.0);
        assertEquals("flower_blue", game.flowerOf(ball));
        assertEquals(5, game.flowerCounts().get("flower_blue"));
    }

    @Test
    void nectarInAFlowerStopsAtTheMiddleRing() throws Exception {
        Setup s = setup("mecanum.json");
        BiobuzzGame game = BiobuzzGame.of(s.elements);
        emptyRobot(s);
        Flower f = game.flower("flower_red");
        assertTrue(game.introduceNectar("red"));
        Ball nectar = s.elements.balls().stream().filter(b -> b.color.equals("red") && b.state() == State.FREE
                && game.cellOf(b) == null).findFirst().orElseThrow();
        s.elements.throwBall(nectar, f.x() + 0.005, f.y(), f.height() + 0.2, 0, 0, 0);
        s.run(2.0);
        assertEquals("flower_red", game.flowerOf(nectar), "a NECTAR goes in the top");
        // Take all the POLLEN out of the bottom: the NECTAR comes down to the middle ring and stays.
        s.rig.motors.get("intake").port.power = 1;
        driveIntoFlower(s, f, 0.3, 4.0);
        assertEquals(4, s.elements.heldCount(), "the 4 POLLEN came out");
        assertEquals("flower_red", game.flowerOf(nectar), "the NECTAR did not");
        assertTrue(nectar.position().get2() > 0.11, "resting on the middle ring, z " + nectar.position().get2());
        assertEquals(1, game.flowerCounts().get("flower_red"));
    }

    /** Drives the robot straight at a FLOWER's Retrieval Opening from 0.9 m out, for this long. */
    private static void driveIntoFlower(Setup s, Flower f, double power, double seconds) {
        driveIntoFlower(s, f, power, seconds, 0);
    }

    /** As above, with the robot shifted sideways by offset (m) from the FLOWER's center line. */
    private static void driveIntoFlower(Setup s, Flower f, double power, double seconds, double offset) {
        double c = Math.cos(f.yaw()), sn = Math.sin(f.yaw());
        s.rig.place(f.x() + c * 0.9 - sn * offset, f.y() + sn * 0.9 + c * offset, f.yaw() + Math.PI);
        for (String m : new String[]{"front_left_drive", "back_left_drive"}) s.rig.motors.get(m).port.power = -power;
        for (String m : new String[]{"front_right_drive", "back_right_drive"}) s.rig.motors.get(m).port.power = power;
        s.run(seconds);
    }

    private static void emptyRobot(Setup s) {
        int k = 0;
        for (Ball b : s.elements.balls()) {
            if (b.state() == State.HELD) s.elements.placeBall(b, 0.5 + 0.15 * k++, 1.2);
        }
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

    /** robots/mecanum.json with {@code intake} (a device) for its intake motor. */
    private static Setup withIntake(Path dir, String intake) throws Exception {
        // (as one line ending: a Windows checkout has the file's lines end in CRLF, the text blocks in LF)
        String json = Files.readString(Path.of("robots/mecanum.json")).replace("\r\n", "\n");
        String motor = """
                { "type": "motor", "name": "intake", "motor": "goBILDA 5203 1150rpm", "role": "intake",
                      "load": { "kind": "roller", "inertia": 0.0002 } },""";
        assertTrue(json.contains(motor), "robots/mecanum.json has its intake motor");
        Path file = dir.resolve("robot.json");
        Files.writeString(file, json.replace(motor, intake));
        return setup(file);
    }

    /**
     * Empties the robot, puts a ball against its roller, and runs {@code intake} for a second:
     * whether the robot took the ball in.
     */
    private static boolean takesABall(Setup s, Runnable intake) {
        s.rig.place(0, -0.3, 0);
        for (Ball b : s.elements.balls()) if (b.state() == State.HELD) s.elements.stage(b, 3, 3, 0.1);
        s.run(0.3);
        Ball ball = s.elements.balls().stream().filter(b -> b.state() == State.FREE).findFirst().orElseThrow();
        ballAt(s, ball, 0.27, -0.3);
        intake.run();
        s.run(1.0);
        return ball.state() == State.HELD;
    }

    private static void power(Setup s, String motor, double power) {
        s.rig.motors.get(motor).port.power = power;
    }

    @Test
    void aSlowCoreHexIntakeTakesBallsIn(@TempDir Path dir) throws Exception {
        // 125 rpm at its output (13 rad/s): a Core Hex turns a roller directly, or through gears.
        Setup s = withIntake(dir, """
                { "type": "motor", "name": "intake", "motor": "REV Core Hex", "role": "intake", "load": { "kind": "roller" } },""");
        assertTrue(takesABall(s, () -> power(s, "intake", 1)), "the Core Hex intake took the ball");
        power(s, "intake", -1);
        s.run(0.5);
        assertEquals(0, s.elements.heldCount(), "and spits it out reversed");

        Setup geared = withIntake(dir, """
                { "type": "motor", "name": "intake", "motor": "goBILDA 5203 117rpm", "external_gear_ratio": 2,
                  "role": "intake", "load": { "kind": "roller" } },""");
        assertTrue(takesABall(geared, () -> power(geared, "intake", 1)), "a 117 rpm motor geared down 2:1 too");
    }

    @Test
    void aCrServoIntakeTakesBallsIn(@TempDir Path dir) throws Exception {
        Setup s = withIntake(dir, """
                { "type": "crservo", "name": "intake", "rpm": 50, "role": "intake" },""");
        ServoPort intake = s.rig.servoPorts().stream().filter(p -> p.name.equals("intake")).findFirst().orElseThrow();
        assertTrue(takesABall(s, () -> {
            intake.position = 1.0;   // full speed forward
            intake.pwmEnabled = true;
        }), "the CR servo intake took the ball");
        intake.position = 0.0;
        s.run(0.5);
        assertEquals(0, s.elements.heldCount(), "and spits it out reversed");
    }

    @Test
    void anIntakeThatTurnsBackwardToTakeBallsIn(@TempDir Path dir) throws Exception {
        // Its code sets the motor to REVERSE and runs it at +1: the port sees -1.
        Setup s = withIntake(dir, """
                { "type": "motor", "name": "intake", "motor": "goBILDA 5203 1150rpm", "role": "intake",
                  "intake_direction": "reverse", "load": { "kind": "roller", "inertia": 0.0002 } },""");
        assertTrue(takesABall(s, () -> power(s, "intake", -1)), "negative power takes the ball in");
        power(s, "intake", 1);
        s.run(0.5);
        assertEquals(0, s.elements.heldCount(), "positive power spits it out");
    }

    private static void ballAt(Setup s, Ball b, double x, double y) {
        s.elements.placeBall(b, x, y);
    }
}
