package biobuzz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.ode4j.math.DVector3C;

import com.google.gson.JsonArray;
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
    /** 10.3.1: every ROBOT starts the MATCH with this many pre-loaded POLLEN. */
    private final int preload;

    BiobuzzGame(GameElements elements) {
        super(elements);
        layout = HiveLayout.of(field);
        for (Hive h : layout.hives) hives.add(new HiveBody(world.mover(h.alliance() + " HIVE"), h));
        preload = json.getAsJsonObject("preload").getAsJsonArray("colors").size();
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
     *   <li>POLLEN: 4 in each FLOWER, 4 in each GARDEN, 4 pre-loaded in each simulated robot. The
     *       pre-loads of absent ROBOTS (each ALLIANCE has two) go in the center of their LOADING
     *       ZONE against the perimeter wall (10.3.4).</li>
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
        // tiles (9.7); the others rest on top of it. (The 1.25 in backstop is the guide at the top.)
        JsonObject flowers = json.getAsJsonObject("flowers");
        for (String name : flowers.keySet()) {
            JsonArray axis = flowers.getAsJsonObject(name).getAsJsonArray("axis_m");
            for (int i = 0; i < 4; i++) {
                Ball b = pollen.get(p++);
                elements.stage(b, axis.get(0).getAsDouble(), axis.get(1).getAsDouble(), b.radius + i * 2 * b.radius);
            }
        }
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
