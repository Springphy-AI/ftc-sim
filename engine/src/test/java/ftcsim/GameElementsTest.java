package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.ode4j.math.DVector3C;

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
        assertEquals(GameElements.PRELOAD, s.elements.heldCount());
        // NECTAR: 3 free in the up CELL (a physical container) + 5 staged in the ALLIANCE AREA.
        assertEquals(5, count(balls, "red", State.STAGED));
        assertEquals(5, count(balls, "blue", State.STAGED));
        assertEquals(3, count(balls, "red", State.FREE));
        assertEquals(3, count(balls, "blue", State.FREE));
        // Red NECTAR: 3 in the red HIVE's raised (audience) CELL, the others outside the field.
        List<Ball> red = balls.stream().filter(b -> b.color.equals("red")).collect(Collectors.toList());
        assertEquals(3, red.stream().filter(b -> "red audience".equals(cellOf(s, b))).count());
        assertEquals(5, red.stream().filter(b -> b.position().get0() < -1.83).count());
        List<Ball> blue = balls.stream().filter(b -> b.color.equals("blue")).collect(Collectors.toList());
        assertEquals(3, blue.stream().filter(b -> "blue rear".equals(cellOf(s, b))).count());
    }

    @Test
    void hiveCellsCatchBallsThroughTheOpenEndOnly() throws Exception {
        Setup s = setup("mecanum.json");
        s.rig.place(1.2, -1.2, 0);   // out of the way
        List<Ball> free = s.elements.balls().stream()
                .filter(b -> b.kind.equals("pollen") && b.state() == State.FREE).limit(2).collect(Collectors.toList());
        Ball intoMouth = free.get(0), atSide = free.get(1);
        // Red HIVE: its raised CELL opens toward the audience (-y), 1.36-1.67 m up. Lob one in from
        // the audience side (5.2 m/s at 67.5 degrees); throw the other at the CELL's side wall from
        // the red wall's side (+x).
        s.elements.throwBall(intoMouth, -0.32, -1.35, 0.49, 0, 2.0, 4.82);
        s.elements.throwBall(atSide, -1.3, -0.31, 0.5, 1.7, 0, 4.0);
        s.run(3.0);
        assertEquals("red audience", cellOf(s, intoMouth), "lobbed into the open end, at " + intoMouth.position());
        assertEquals(null, cellOf(s, atSide), "bounced off the CELL's side wall, at " + atSide.position());
        assertTrue(atSide.position().get2() < 0.1, "the ball that hit the side fell to the tiles");
        assertTrue(atSide.position().get0() < -0.55, "...on the red side of the HIVE, x=" + atSide.position().get0());
        // NECTAR already in the CELL are still there.
        assertEquals(3, s.elements.balls().stream().filter(b -> b.color.equals("red") && "red audience".equals(cellOf(s, b))).count());
    }

    @Test
    void freeBallsRestOnTheTilesOrInTheirCell() throws Exception {
        Setup s = setup("mecanum.json");
        s.run(2.0);
        for (Ball b : s.elements.balls()) {
            if (b.state() != State.FREE) continue;
            DVector3C p = b.position();
            DVector3C v = b.velocity();
            assertTrue(Math.sqrt(v.get0() * v.get0() + v.get1() * v.get1() + v.get2() * v.get2()) < 0.01, "ball " + b.id + " at rest");
            if (b.kind.equals("nectar")) {
                assertEquals(b.color.equals("red") ? "red audience" : "blue rear", cellOf(s, b), "NECTAR " + b.id + " stays in its CELL");
                continue;
            }
            assertEquals(b.radius, p.get2(), 0.004, "ball " + b.id + " should rest on the floor");
            assertTrue(Math.abs(p.get0()) < 1.8288 && Math.abs(p.get1()) < 1.8288, "ball " + b.id + " inside the walls");
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
        assertEquals(GameElements.ROBOT_CAPACITY, s.elements.heldCount());
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

    private static void ballAt(Setup s, Ball b, double x, double y) {
        s.elements.placeBall(b, x, y);
    }

    private static String cellOf(Setup s, Ball b) {
        DVector3C p = b.position();
        return s.rig.world.field.cellAt(p.get0(), p.get1(), p.get2());
    }
}
