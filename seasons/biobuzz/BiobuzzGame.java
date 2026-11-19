package biobuzz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.ode4j.math.DVector3C;

import com.google.gson.JsonObject;

import ftcsim.season.SeasonGame;
import ftcsim.sim.GameElements;
import ftcsim.sim.GameElements.Ball;

/**
 * The BIOBUZZ game on one field: staging the 40 yellow POLLEN (7.1 cm) and 8 red + 8 blue NECTAR
 * (9.1 cm) as Competition Manual section 10.3.1 describes, the HIVES' tips, and the balls in each
 * CELL for the top bar. The human player's NECTAR buttons are plain season.json data.
 */
public final class BiobuzzGame extends SeasonGame {

    private final HiveLayout layout;
    private final List<HiveBody> hives = new ArrayList<>();
    private final List<Flower> flowers;
    /** 10.3.1: every ROBOT starts the MATCH with this many pre-loaded POLLEN. */
    private final int preload;
    /** Per ball (by id): rolling out of a FLOWER's Retrieval Opening, its direction (radians) and time left (s). */
    private final double[] exitDir, exitTime;
    private final boolean[] exitStarted;
    private final Random exitRandom = new Random(7);

    BiobuzzGame(GameElements elements) {
        super(elements);
        layout = HiveLayout.of(field);
        for (Hive h : layout.hives) hives.add(new HiveBody(world.mover(h.alliance() + " HIVE"), h));
        flowers = Flower.of(json);
        preload = json.getAsJsonObject("preload").getAsJsonArray("colors").size();
        int n = elements.balls().size();
        exitDir = new double[n];
        exitTime = new double[n];
        exitStarted = new boolean[n];
    }

    /** The BIOBUZZ game on a field that plays BIOBUZZ. */
    public static BiobuzzGame of(GameElements elements) {
        return (BiobuzzGame) elements.game();
    }

    public HiveLayout layout() {
        return layout;
    }

    /** Both HIVES, red first. */
    public List<HiveBody> hives() {
        return hives;
    }

    public HiveBody hive(String alliance) {
        for (HiveBody h : hives) if (h.start.alliance().equals(alliance)) return h;
        throw new IllegalArgumentException("no " + alliance + " HIVE");
    }

    // ---- staging (Competition Manual 10.3.1) ---------------------------------------------------

    /**
     * Puts every SCORING ELEMENT where it starts a MATCH:
     * <ul>
     *   <li>POLLEN: 4 stacked in each FLOWER (free balls, held by its tube), 4 in each GARDEN, 4
     *       pre-loaded in each simulated robot. The pre-loads of absent ROBOTS (each ALLIANCE has
     *       two) go in the center of their LOADING ZONE against the perimeter wall (10.3.4).</li>
     *   <li>NECTAR: 3 in each upward-facing CELL of its color (free balls, held by the CELL's
     *       walls), 5 in each ALLIANCE AREA.</li>
     * </ul>
     * Both HIVES are already back at their start position.
     */
    @Override
    public void restage() {
        for (HiveBody h : hives) h.reset();
        List<Ball> pollen = new ArrayList<>(), red = new ArrayList<>(), blue = new ArrayList<>();
        for (Ball b : elements.balls()) {
            if ("pollen".equals(b.kind)) pollen.add(b);
            else if ("red".equals(b.color)) red.add(b);
            else blue.add(b);
        }
        int p = 0;
        // FLOWERS: 4 stacked in each. The lowest sits in the 2.79 in hole of the bottom ring, on the
        // tiles (9.7); the others rest on top of it, inside the tube.
        for (Flower f : flowers) {
            for (int i = 0; i < 4; i++) {
                Ball b = pollen.get(p++);
                elements.throwBall(b, f.x(), f.y(), b.radius + i * (2 * b.radius + 0.001), 0, 0, 0);
            }
        }
        java.util.Arrays.fill(exitTime, 0);
        java.util.Arrays.fill(exitStarted, false);
        // GARDENS: 4 in a row along the wall.
        JsonObject gardens = json.getAsJsonObject("gardens");
        for (String alliance : gardens.keySet()) {
            double[] g = Biobuzz.rect(gardens.getAsJsonObject(alliance));
            boolean wallAtLowY = Math.abs(g[2] + field.half) < Math.abs(g[3] - field.half);
            for (int i = 0; i < 4; i++) {
                Ball b = pollen.get(p++);
                double x = g[0] + (g[1] - g[0]) * (i + 0.5) / 4;
                double y = wallAtLowY ? -field.half + b.radius + 0.002 : field.half - b.radius - 0.002;
                elements.throwBall(b, x, y, b.radius, 0, 0, 0);
            }
        }
        // Each simulated robot's pre-load.
        for (int r = 0; r < elements.robotCount(); r++) {
            for (int i = 0; i < preload; i++) elements.hold(r, pollen.get(p++));
        }
        // Absent robots' pre-loads: two ROBOTS per ALLIANCE, minus the ones simulated.
        JsonObject zones = json.getAsJsonObject("loading_zones");
        for (String alliance : zones.keySet()) {
            int here = 0;
            for (int r = 0; r < elements.robotCount(); r++) if (elements.alliance(r).equals(alliance)) here++;
            int absent = 2 - here;
            if (absent <= 0) continue;
            double[] z = Biobuzz.rect(zones.getAsJsonObject(alliance));
            double yc = (z[2] + z[3]) / 2;
            double wallSign = Math.abs(z[0] + field.half) < Math.abs(z[1] - field.half) ? -1 : 1;
            int n = absent * preload, rows = n / 2;
            for (int i = 0; i < n; i++) {
                Ball b = pollen.get(p++);
                double d = 2 * b.radius + 0.002;
                double x = wallSign * (field.half - b.radius - 0.002 - (i % 2) * d);
                double y = yc + (i / 2 - (rows - 1) / 2.0) * d;
                elements.throwBall(b, x, y, b.radius, 0, 0, 0);
            }
        }
        // Any POLLEN left over (a season.json with a different count) waits beside the field.
        for (int k = 0; p < pollen.size(); k++) elements.stage(pollen.get(p++), 0, -field.half - 0.25 - 0.08 * k, 0.04);

        // NECTAR: 3 in each upward-facing CELL of its color, the rest in the ALLIANCE AREA. The CELL
        // is a physical container: its NECTAR lie side by side against the closed inner end.
        for (String alliance : new String[]{"red", "blue"}) {
            List<Ball> nec = "red".equals(alliance) ? red : blue;
            int n = 0;
            Hive hive = layout.hive(alliance);
            double sign = "rear".equals(hive.upCell()) ? 1 : -1;
            for (int i = 0; i < 3 && n < nec.size(); i++) {
                Ball b = nec.get(n++);
                double[] w = hive.toWorld((i - 1) * (2 * b.radius + 0.01), sign * (layout.cellInner + b.radius + 0.002),
                        layout.cellFloor + b.radius + 0.001);
                elements.throwBall(b, w[0], w[1], w[2], 0, 0, 0);
            }
            double wall = "red".equals(alliance) ? -1 : 1;
            for (int i = 0; n < nec.size(); i++) {
                Ball b = nec.get(n++);
                elements.stage(b, wall * (field.half + 0.35), wall * (-0.4 + i * 0.12), b.radius, alliance);
            }
        }
    }

    @Override
    public void beforeStep(double dt) {
        for (HiveBody h : hives) h.beforeStep();
        rollOutOfFlowers(dt);
    }

    // A POLLEN that drops out of a FLOWER's middle ring rolls out of the Retrieval Opening.
    private static final double FLOWER_DROP_SPEED = 0.2;       // m/s downward: it is falling, not resting
    private static final double FLOWER_ROLL_OUT_SPEED = 0.5;   // m/s
    private static final double FLOWER_EXIT_TIME_S = 0.5;

    /**
     * On the field, when the bottom POLLEN of a FLOWER is taken or knocked out, the ones above drop
     * through the tall Retrieval Opening and roll out on their own, so the whole stack comes out;
     * only the weight of the stack keeps the bottom one in place at the start. Simulated POLLEN are
     * perfect spheres and fall straight, so each lands back in the lower ring and the next one,
     * falling right behind it, pins it there. Real ones are not quite round (Competition Manual
     * 9.8), come off the middle ring at an angle and keep rolling: a POLLEN that falls clear of the
     * middle ring rolls toward the open front or sides (never the wall) at a walking pace until it
     * is out of the lower ring, even with the next one landing on it. One resting in place, like
     * the bottom one at the start, does not move.
     */
    private void rollOutOfFlowers(double dt) {
        for (Ball b : elements.balls()) {
            if (!b.awake()) continue;
            DVector3C p = b.position(), v = b.velocity();
            Flower f = flowerNear(p.get0(), p.get1(), 0.075);
            int i = b.id;
            if (f == null) {
                exitTime[i] = 0;
                exitStarted[i] = false;
                continue;
            }
            if (p.get2() > f.retrievalTop() - b.radius) {   // its top has not left the middle ring
                exitStarted[i] = false;
                continue;
            }
            if (!exitStarted[i] && v.get2() < -FLOWER_DROP_SPEED) {
                exitStarted[i] = true;
                exitTime[i] = FLOWER_EXIT_TIME_S;
                exitDir[i] = f.yaw() + (exitRandom.nextDouble() * 2 - 1) * Math.toRadians(100);   // front or sides
            }
            if (exitTime[i] > 0) {
                exitTime[i] -= dt;
                double cx = Math.cos(exitDir[i]), cy = Math.sin(exitDir[i]);
                double along = v.get0() * cx + v.get1() * cy;
                if (along < FLOWER_ROLL_OUT_SPEED) {
                    double dv = FLOWER_ROLL_OUT_SPEED - along;
                    elements.setVelocity(b, v.get0() + dv * cx, v.get1() + dv * cy, v.get2());
                }
            }
        }
    }

    /** The FLOWER within this distance (m, horizontally) of the point, or null. */
    private Flower flowerNear(double x, double y, double within) {
        for (Flower f : flowers) if (Math.hypot(x - f.x(), y - f.y()) < within) return f;
        return null;
    }

    /** The four FLOWERS. */
    public List<Flower> flowers() {
        return flowers;
    }

    public Flower flower(String name) {
        for (Flower f : flowers) if (f.name().equals(name)) return f;
        throw new IllegalArgumentException("no FLOWER " + name);
    }

    /** The FLOWER a ball is in (flower_red, flower_rear...), or null. */
    public String flowerOf(Ball b) {
        if (b.state() != GameElements.State.FREE) return null;
        DVector3C p = b.position();
        for (Flower f : flowers) if (f.holds(p.get0(), p.get1(), p.get2())) return f.name();
        return null;
    }

    /** Free balls inside each FLOWER, keyed by FLOWER name. */
    public Map<String, Integer> flowerCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Flower f : flowers) out.put(f.name(), 0);
        for (Ball b : elements.balls()) {
            String f = flowerOf(b);
            if (f != null) out.merge(f, 1, Integer::sum);
        }
        return out;
    }

    // ---- the human player ------------------------------------------------------------------------

    /** NECTAR of this color still waiting in its ALLIANCE AREA (outside the field). */
    public int waitingNectar(String color) {
        return elements.waitingCount(color, "nectar", color);
    }

    /**
     * The human player puts one NECTAR of this color ("red" or "blue") from its ALLIANCE AREA onto
     * the field, at rest in that alliance's LOADING ZONE: the first free spot along the alliance
     * wall, clear of other balls and the robots; past the zone's inner edge if the zone is full.
     *
     * @return false if none of that color is waiting or there is no free spot
     */
    public boolean introduceNectar(String color) {
        return act(color + "-nectar");
    }

    // ---- where the balls are -----------------------------------------------------------------------

    /** Each HIVE at its current tilt. */
    public List<Hive> currentHives() {
        List<Hive> out = new ArrayList<>();
        for (HiveBody h : hives) out.add(h.current());
        return out;
    }

    /**
     * Free balls inside each CELL, keyed "red rear", "red audience", "blue rear", "blue audience".
     * This is just where the balls are, not a MATCH score.
     */
    public Map<String, Integer> cellCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Hive h : layout.hives) {
            out.put(h.alliance() + " rear", 0);
            out.put(h.alliance() + " audience", 0);
        }
        List<Hive> current = currentHives();
        for (Ball b : elements.balls()) {
            if (b.state() != GameElements.State.FREE) continue;
            DVector3C p = b.position();
            String cell = layout.cellAt(p.get0(), p.get1(), p.get2(), current);
            if (cell != null) out.merge(cell, 1, Integer::sum);
        }
        return out;
    }

    /** The CELL a ball is in ("red audience"...), or null. */
    public String cellOf(Ball b) {
        if (b.state() != GameElements.State.FREE) return null;
        DVector3C p = b.position();
        return layout.cellAt(p.get0(), p.get1(), p.get2(), currentHives());
    }

    @Override
    public List<Stat> stats() {
        Map<String, Integer> cells = cellCounts();
        List<Stat> out = new ArrayList<>();
        for (HiveBody h : hives) {
            String a = h.start.alliance();
            int tips = h.tips();
            out.add(new Stat(Character.toUpperCase(a.charAt(0)) + a.substring(1) + " HIVE",
                    cells.get(a + " audience") + " / " + cells.get(a + " rear") + (tips > 0 ? " · " + tips + " tip" + (tips > 1 ? "s" : "") : ""),
                    "Balls inside the " + a + " HIVE's CELLS: audience CELL / rear CELL, and how many times it has tipped since Reset (not a MATCH score)"));
        }
        return out;
    }
}
