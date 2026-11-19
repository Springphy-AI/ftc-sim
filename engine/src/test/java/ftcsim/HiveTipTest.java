package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.ode4j.math.DVector3C;

import biobuzz.BiobuzzGame;
import biobuzz.Hive;
import biobuzz.HiveBody;
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

/**
 * The HIVE tips like a calibrated field HIVE: Event Field Setup Guide section 12.3. Balls are
 * "gently placed" at rest against the back of the upward-facing CELL, or "tossed in" through its
 * open end.
 */
public class HiveTipTest {
    private static final double DT = 0.001;

    /** A field with the robot parked out of the way. */
    public static final class Field {
        public final RobotRig rig;
        public final GameElements elements;
        public final FieldModel field;
        public final HiveBody red;
        public final HiveLayout cells;
        public final List<Ball> inCell = new ArrayList<>();
        private final List<Ball> spare = new ArrayList<>();

        public Field() throws Exception {
            RobotConfig cfg = RobotConfig.load(Path.of("robots/mecanum.json"));
            field = FieldModel.loadBiobuzz();
            rig = new RobotRig(cfg, new PhysicsWorld(field));
            Latency.commandNanos = 0;
            Latency.i2cNanos = 0;
            elements = new GameElements(field, rig);
            rig.place(1.2, -1.2, 0);
            red = BiobuzzGame.of(elements).hive("red");
            cells = BiobuzzGame.of(elements).layout();
            assertEquals("red", red.start.alliance());
            for (Ball b : elements.balls()) {
                if (BiobuzzGame.of(elements).cellOf(b) != null && b.color.equals("red")) inCell.add(b);
                else if (b.state() != State.HELD) spare.add(b);
            }
        }

        public void run(double seconds) {
            for (int i = 0; i < Math.round(seconds / DT); i++) {
                elements.beforeStep(DT);
                rig.step(DT);
            }
        }

        Ball take(String kind) {
            Ball b = spare.stream().filter(x -> x.kind.equals(kind)).findFirst().orElseThrow();
            spare.remove(b);
            return b;
        }

        /** Takes the starting NECTAR out of the red up CELL. */
        public void emptyCell() {
            int i = 0;
            for (Ball b : inCell) elements.placeBall(b, -1.0 + 0.15 * i++, 1.2);
            inCell.clear();
            run(1.0);
        }

        /** Puts a ball at rest against the back of the red up CELL, or as close as there is room. */
        public void place(String kind) {
            Ball b = take(kind);
            Hive h = red.current();
            double sign = "rear".equals(red.upCell()) ? 1 : -1;
            List<double[]> others = new ArrayList<>();
            for (Ball o : inCell) {
                DVector3C p = o.position();
                double[] q = h.toHive(p.get0(), p.get1(), p.get2());
                others.add(new double[]{q[0], q[1], q[2], o.radius});
            }
            double r = b.radius, z = cells.cellFloor + r + 0.001;
            for (double d = 0; d < 0.25; d += 0.004) {
                double y = sign * (cells.cellInner + r + 0.002 + d);
                for (double ax = 0; ax < cells.cellHalfWidth - r - 0.002; ax += 0.004) {
                    for (double x : new double[]{ax, -ax}) {
                        boolean free = true;
                        for (double[] o : others) {
                            double gap = Math.sqrt((x - o[0]) * (x - o[0]) + (y - o[1]) * (y - o[1]) + (z - o[2]) * (z - o[2]));
                            if (gap < r + o[3] + 0.003) free = false;
                        }
                        if (free) {
                            double[] w = h.toWorld(x, y, z);
                            elements.throwBall(b, w[0], w[1], w[2], 0, 0, 0);
                            inCell.add(b);
                            run(0.8);
                            return;
                        }
                    }
                }
            }
            throw new AssertionError("no room in the CELL");
        }

        /** Tosses a ball by hand into the open end of the red up CELL, arriving at about 2 m/s. */
        public void toss(String kind) {
            Ball b = take(kind);
            Hive h = red.current();
            double sign = "rear".equals(red.upCell()) ? 1 : -1;
            double[] target = h.toWorld(0.03, sign * (cells.cellOuter - 0.08), cells.cellFloor + 0.10);
            double t = 0.3, dy = sign * 0.45, dz = 0.25;
            elements.throwBall(b, target[0], target[1] + dy, target[2] + dz, 0, -dy / t, -dz / t + 9.81 * t / 2);
            inCell.add(b);
            run(0.8);
        }

        /**
         * Waits long enough for a slow tip (the damper makes a marginal one take several seconds),
         * then reports whether the HIVE tipped or at least left its stop for good.
         */
        public boolean tipped() {
            run(8.0);
            return red.tips() > 0 || Math.abs(red.angle() - red.start.phi()) > 0.02;
        }
    }

    /** True if the red HIVE tips with these balls in its up CELL (the last one tossed if asked). */
    public static boolean scenario(int nectar, int pollen, boolean tossLast) throws Exception {
        Field f = new Field();
        f.emptyCell();
        int n = 0, total = nectar + pollen;
        for (int i = 0; i < nectar; i++) {
            if (++n == total && tossLast) f.toss("nectar");
            else f.place("nectar");
        }
        for (int i = 0; i < pollen; i++) {
            if (++n == total && tossLast) f.toss("pollen");
            else f.place("pollen");
        }
        return f.tipped();
    }

    @Test
    void holdsItsStartPositionWithTheStartingNectar() throws Exception {
        Field f = new Field();
        f.run(3.0);
        assertEquals(0, f.red.tips());
        assertEquals("audience", f.red.upCell());
        assertEquals(f.red.start.phi(), f.red.angle(), 0.01, "resting on its stop");
        assertEquals(3, BiobuzzGame.of(f.elements).cellCounts().get("red audience"));
    }

    // Section 12.3, "Necessary" rows.
    @Test
    void threeNectarOnePollenThenATossedPollenDoesNotTip() throws Exception {
        assertTrue(!scenario(3, 2, true));
    }

    @Test
    void threeNectarTwoPollenThenATossedPollenTips() throws Exception {
        assertTrue(scenario(3, 3, true));
    }

    @Test
    void sixPollenThenATossedPollenDoesNotTip() throws Exception {
        assertTrue(!scenario(0, 7, true));
    }

    @Test
    void sevenPollenThenATossedPollenTips() throws Exception {
        assertTrue(scenario(0, 8, true));
    }

    // The gently placed holds from the calibration process (12.4). The "Preferred" placed tips (a 3rd
    // POLLEN with 3 NECTAR, an 8th POLLEN) need a toss here: the HIVE is set a little stiffer so that
    // 3 NECTAR + 2 LAUNCHED POLLEN never tips it, as teams see on the field.
    @Test
    void threeNectarTwoPollenPlacedHolds() throws Exception {
        assertTrue(!scenario(3, 2, false));
    }

    @Test
    void sevenPollenPlacedHolds() throws Exception {
        assertTrue(!scenario(0, 7, false));
    }

    // Section 12.3, "Preferred" rows: on the field a gently placed 3rd POLLEN on 3 NECTAR, or 8th POLLEN,
    // tips the HIVE. This HIVE is deliberately stiffer (above), so it holds; these pin that choice down.
    @Test
    void threeNectarTwoPollenThenAPlacedPollenHolds() throws Exception {
        assertTrue(!scenario(3, 3, false));
    }

    @Test
    void sevenPollenThenAPlacedPollenHolds() throws Exception {
        assertTrue(!scenario(0, 8, false));
    }

    /** Launches this many of the robot's pre-loaded POLLEN at the red up CELL; true if the HIVE tips. */
    private static boolean launchFromRobot(int count, double power, double hoodPos, double y) throws Exception {
        Field f = new Field();
        int removed = 0;
        for (Ball b : f.elements.balls()) {
            if (b.state() == State.HELD && removed < 4 - count) f.elements.placeBall(b, 1.0 + 0.2 * removed++, 1.5);
        }
        f.rig.place(-0.32, y, Math.PI / 2);   // facing the red HIVE's audience CELL
        ServoPort hood = f.rig.servoPorts().stream().filter(p -> p.name.equals("hood")).findFirst().orElseThrow();
        ServoPort feeder = f.rig.servoPorts().stream().filter(p -> p.name.equals("feeder")).findFirst().orElseThrow();
        hood.position = hoodPos;
        hood.pwmEnabled = true;
        f.rig.motors.get("launcher").port.power = power;
        f.run(2.0);
        feeder.position = 1.0;
        feeder.pwmEnabled = true;
        f.run(2.0);
        boolean tipped = f.tipped();
        assertTrue(tipped || BiobuzzGame.of(f.elements).cellCounts().get("red audience") == 3 + count, "all launched POLLEN landed in the CELL");
        return tipped;
    }

    @Test
    void threeNectarAndTwoLaunchedPollenHold() throws Exception {
        assertTrue(!launchFromRobot(2, 0.40, 0.9, -1.0));
        assertTrue(!launchFromRobot(2, 0.45, 0.7, -1.2));
    }

    @Test
    void aThirdLaunchedPollenTips() throws Exception {
        assertTrue(launchFromRobot(3, 0.40, 0.9, -1.0));
        assertTrue(launchFromRobot(3, 0.45, 0.7, -1.2));
    }

    @Test
    void tippingDumpsTheBallsAndTheOtherCellFacesUp() throws Exception {
        Field f = new Field();
        f.place("pollen");
        f.place("pollen");
        f.toss("pollen");
        assertTrue(f.tipped());
        assertEquals("rear", f.red.upCell());
        assertEquals(-f.red.start.phi(), f.red.angle(), 0.01, "resting on the other stop");
        // The CELL that was up now points down, open end low: what was in it has rolled out.
        f.run(2.0);
        assertEquals(0, BiobuzzGame.of(f.elements).cellCounts().get("red audience"), "balls fell out");
        assertEquals(0, BiobuzzGame.of(f.elements).cellCounts().get("red rear"));
        // Reset puts it back.
        f.elements.restage();
        assertEquals("audience", f.red.upCell());
        assertEquals(0, f.red.tips());
    }
}
