package ftcsim.season;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ftcsim.physics.FieldModel;
import ftcsim.physics.PhysicsWorld;
import ftcsim.sim.GameElements;
import ftcsim.sim.GameElements.Ball;

/**
 * The game on one simulated field: where the game pieces start a MATCH, what the human player
 * can do, and the numbers the top bar shows. This base class does it all from season.json; a
 * season's code subclasses it for anything more ({@link SeasonCode#newGame}). Every method runs on
 * the physics thread.
 *
 * <p>season.json:
 * <pre>
 *   "staging": [
 *     {"piece": "artifact", "colors": ["green", "purple", "purple"], "line_m": {"from": [x, y, z], "to": [x, y, z]}},
 *     {"piece": "artifact", "color": "green", "at_m": [x, y, z]},
 *     {"piece": "artifact", "colors": ["purple", "purple", "green"], "area": "red"}     waiting in the ALLIANCE AREA
 *   ],
 *   "preload": {"piece": "artifact", "colors": ["purple", "purple", "green"], "from_area": true},
 *   "human_player": [
 *     {"id": "red-green", "label": "+ Red green", "key": "KeyN", "alliance": "red", "piece": "artifact",
 *      "color": "green", "zone_m": [x0, x1, y0, y1], "wall": "+x"}
 *   ]
 * </pre>
 * Pieces staging doesn't place wait beside the field.
 */
public class SeasonGame {

    /** A human player button: id, label, keyboard key (a KeyboardEvent.code) or null, tooltip. */
    public record Action(String id, String label, String key, String title) {
    }

    /** A number for the top bar: label, value, tooltip. */
    public record Stat(String label, String value, String title) {
    }

    protected final GameElements elements;
    protected final FieldModel field;
    protected final Season season;
    protected final JsonObject json;
    protected final PhysicsWorld world;
    /** season.json "human_player", by id, and the buttons they make. */
    private final Map<String, JsonObject> humanPlayer = new LinkedHashMap<>();
    private final List<Action> actions = new ArrayList<>();

    public SeasonGame(GameElements elements) {
        this.elements = elements;
        this.field = elements.field();
        this.season = field.season;
        this.json = season.json;
        this.world = elements.world();
        if (json.has("human_player")) {
            for (JsonElement e : json.getAsJsonArray("human_player")) {
                JsonObject a = e.getAsJsonObject();
                String id = a.get("id").getAsString();
                humanPlayer.put(id, a);
                actions.add(new Action(id, a.get("label").getAsString(), str(a, "key"), str(a, "title")));
            }
        }
    }

    // ---- staging -------------------------------------------------------------------------------

    /**
     * Every game piece where it starts a MATCH. The field's moving parts are already back at
     * their start and the robots are empty.
     */
    public void restage() {
        List<Ball> pool = new ArrayList<>(elements.balls());
        if (json.has("staging")) {
            for (JsonElement e : json.getAsJsonArray("staging")) stageEntry(e.getAsJsonObject(), pool);
        }
        if (json.has("preload")) {
            JsonObject p = json.getAsJsonObject("preload");
            String piece = p.get("piece").getAsString();
            boolean fromArea = p.has("from_area") && p.get("from_area").getAsBoolean();
            for (int r = 0; r < elements.robotCount(); r++) {
                for (String color : colors(p)) {
                    Ball b = fromArea ? elements.waiting(elements.alliance(r), piece, color) : null;
                    if (b == null) b = take(pool, piece, color);
                    if (b == null) break;
                    pool.remove(b);
                    elements.hold(r, b);
                }
            }
        }
        stageBesideField(pool);
    }

    private void stageEntry(JsonObject s, List<Ball> pool) {
        String piece = s.get("piece").getAsString();
        List<String> colors = colors(s);
        String area = s.has("area") ? s.get("area").getAsString() : null;
        List<double[]> at = new ArrayList<>();
        if (s.has("at_m")) {
            if (colors.size() > 1) {
                throw new IllegalArgumentException("staging: \"at_m\" places one " + piece + "; use \"line_m\" for " + colors.size());
            }
            at.add(point(s.getAsJsonArray("at_m")));
        } else if (s.has("line_m")) {
            JsonObject l = s.getAsJsonObject("line_m");
            double[] a = point(l.getAsJsonArray("from")), b = point(l.getAsJsonArray("to"));
            int n = colors.size();
            for (int i = 0; i < n; i++) {
                double t = n == 1 ? 0.5 : i / (double) (n - 1);
                at.add(new double[]{a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]), a[2] + t * (b[2] - a[2])});
            }
        } else if (area == null) {
            throw new IllegalArgumentException("staging for " + piece + " needs at_m, line_m or area");
        }
        for (int i = 0; i < colors.size(); i++) {
            Ball b = take(pool, piece, colors.get(i));
            if (b == null) {
                throw new IllegalArgumentException("season.json stages more " + colors.get(i) + " " + piece + " than \"game_pieces\" has");
            }
            pool.remove(b);
            if (area != null) {
                double[] p = areaSpot(area, elements.waitingCount(area, null, null), b.radius);
                elements.stage(b, p[0], p[1], p[2], area);
            } else {
                double[] p = at.get(Math.min(i, at.size() - 1));
                elements.throwBall(b, p[0], p[1], Double.isNaN(p[2]) ? b.radius : p[2], 0, 0, 0);
            }
        }
    }

    /** The k-th spot for a ball waiting in an alliance's ALLIANCE AREA: rows of 6 from its middle. */
    protected double[] areaSpot(String alliance, int k, double r) {
        for (FieldModel.Area a : field.areas) {
            if (!a.name().equals(alliance)) continue;
            boolean longX = a.x1() - a.x0() > a.y1() - a.y0();
            double cx = (a.x0() + a.x1()) / 2, cy = (a.y0() + a.y1()) / 2, d = 2 * r + 0.02;
            double along = (k % 6 - 2.5) * d, across = (k / 6) * d;
            return longX ? new double[]{cx + along, cy + across, r} : new double[]{cx + across, cy + along, r};
        }
        double wall = "red".equals(alliance) ? -1 : 1;
        return new double[]{wall * (field.half + 0.35), wall * (-0.4 + k * 0.12), r};
    }

    /** Balls nothing stages wait beside the field. */
    protected void stageBesideField(List<Ball> pool) {
        int k = 0;
        for (Ball b : pool) elements.stage(b, 0, -field.half - 0.25 - 0.08 * k++, 0.04);
    }

    /** A ball of this kind and color (null for any) from the pool, or null. */
    protected static Ball take(List<Ball> pool, String kind, String color) {
        for (Ball b : pool) if (b.kind.equals(kind) && (color == null || color.equals(b.color))) return b;
        return null;
    }

    private static List<String> colors(JsonObject o) {
        List<String> out = new ArrayList<>();
        if (o.has("colors")) {
            for (JsonElement c : o.getAsJsonArray("colors")) out.add(c.getAsString());
        } else {
            int n = o.has("count") ? o.get("count").getAsInt() : 1;
            for (int i = 0; i < n; i++) out.add(o.has("color") ? o.get("color").getAsString() : null);
        }
        return out;
    }

    /** x, y and z (NaN when only x, y are given: resting on the tiles). */
    private static double[] point(JsonArray a) {
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.size() > 2 ? a.get(2).getAsDouble() : Double.NaN};
    }

    // ---- every physics step ----------------------------------------------------------------------

    /** Before each physics step (after the robots' intakes and launchers). */
    public void beforeStep(double dt) {
    }

    // ---- the human player ------------------------------------------------------------------------

    /** The human player's buttons, from season.json "human_player". */
    public List<Action> actions() {
        return actions;
    }

    /**
     * Does what the human player button {@code id} does: by default, puts a ball waiting in the
     * button's ALLIANCE AREA into its zone.
     *
     * @return false if nothing happened (none waiting, no room)
     */
    public boolean act(String id) {
        JsonObject a = humanPlayer.get(id);
        if (a == null) return false;
        Ball b = elements.waiting(a.get("alliance").getAsString(), str(a, "piece"), str(a, "color"));
        if (b == null) return false;
        double[] z = zone(a);
        return elements.introduce(b, z[0], z[1], z[2], z[3], a.get("wall").getAsString());
    }

    /**
     * A human player button's zone: "zone_m": [x0, x1, y0, y1], or "zone": a path in season.json
     * ("loading_zones/red") to an object with "x_m" and "y_m".
     */
    private double[] zone(JsonObject a) {
        if (a.has("zone_m")) {
            JsonArray z = a.getAsJsonArray("zone_m");
            return new double[]{z.get(0).getAsDouble(), z.get(1).getAsDouble(), z.get(2).getAsDouble(), z.get(3).getAsDouble()};
        }
        JsonObject o = json;
        for (String key : a.get("zone").getAsString().split("/")) o = o.getAsJsonObject(key);
        JsonArray x = o.getAsJsonArray("x_m"), y = o.getAsJsonArray("y_m");
        return new double[]{x.get(0).getAsDouble(), x.get(1).getAsDouble(), y.get(0).getAsDouble(), y.get(1).getAsDouble()};
    }

    /** How many times button {@code id} can still be pressed (balls waiting), or -1 if it isn't counted. */
    public int left(String id) {
        JsonObject a = humanPlayer.get(id);
        if (a == null || !a.has("alliance")) return -1;
        return elements.waitingCount(a.get("alliance").getAsString(), str(a, "piece"), str(a, "color"));
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsString() : null;
    }

    // ---- the top bar -------------------------------------------------------------------------------

    /** Numbers for the top bar (where the game pieces are, not a MATCH score). */
    public List<Stat> stats() {
        return List.of();
    }

    /** Everything the browser shows about the game, for the state stream. */
    public final Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, String>> stats = new ArrayList<>();
        for (Stat s : stats()) {
            Map<String, String> o = new LinkedHashMap<>();
            o.put("label", s.label());
            o.put("value", s.value());
            if (s.title() != null) o.put("title", s.title());
            stats.add(o);
        }
        m.put("stats", stats);
        Map<String, Integer> left = new LinkedHashMap<>();
        for (Action a : actions()) left.put(a.id(), left(a.id()));
        m.put("left", left);
        return m;
    }
}
