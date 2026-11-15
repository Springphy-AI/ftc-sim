package ftcsim.physics;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The BIOBUZZ field layout (FTC 2026-27), read from field/biobuzz-field.json.
 * Frame: origin at field center on the tiles, +x toward the blue alliance wall, +y away from
 * the audience, +z up. Meters.
 */
public final class FieldModel {

    /** An axis-aligned floor-level obstacle the robot cannot drive through. */
    public record Box(String name, double cx, double cy, double cz, double sx, double sy, double sz) {
    }

    /** A vertical cylinder obstacle. */
    public record Post(String name, double cx, double cy, double radius, double height) {
    }

    /** A colored tape rectangle on the floor; outline=true for taped outlines, false for solid areas. */
    public record Tape(String name, double x0, double x1, double y0, double y1, String color, boolean outline) {
    }

    /** A thin panel: a box of size (sx, sy, sz) at (cx, cy, cz), turned by {@code rot} (3x3, row-major). */
    public record Panel(String name, double cx, double cy, double cz, double[] rot, double sx, double sy, double sz) {
    }

    /** Thickness of the HIVE CELL panels. */
    private static final double CELL_PANEL_M = 0.01;

    public final JsonObject json;
    public final double half;        // half the field's inner width
    public final double wallHeight;
    public final double tapeWidth;
    public final List<Box> boxes = new ArrayList<>();
    public final List<Post> posts = new ArrayList<>();
    public final List<Tape> tapes = new ArrayList<>();
    /** The HIVE CELLS' floors, walls, roofs and closed inner ends, with each HIVE at its starting tilt. */
    public final List<Panel> cellPanels = new ArrayList<>();
    /**
     * CELL inside dimensions in the HIVE frame (the same for every CELL): from {@code cellInner} to
     * {@code cellOuter} along the arm, {@code cellHalfWidth} to each side, floor at {@code cellFloor},
     * walls {@code cellWall} high and the roof peak {@code cellPeak} above the floor.
     */
    public final double cellInner, cellOuter, cellHalfWidth, cellFloor, cellWall, cellPeak;
    /** Each HIVE by alliance, at its starting tilt. */
    private final Map<String, Hive> hiveByAlliance = new LinkedHashMap<>();

    /** A HIVE's pivot, its tilt phi about the pivot axis (x), and which CELL ("rear", "audience") is raised. */
    private record Hive(String alliance, double px, double py, double pz, double phi, String raised) {
    }

    private FieldModel(JsonObject json) {
        this.json = json;
        this.half = json.getAsJsonArray("field_m").get(0).getAsDouble() / 2;
        this.wallHeight = json.get("wall_height_m").getAsDouble();
        JsonObject structure = json.has("structure") ? json.getAsJsonObject("structure") : new JsonObject();
        this.tapeWidth = structure.has("tape_m") ? structure.get("tape_m").getAsDouble() : 0.0254;

        // The HIVE structure stands on two A-frames at x = +-0.628 whose bases span |y| <= 0.5.
        for (double x : new double[]{-0.628, 0.628}) {
            boxes.add(new Box(x < 0 ? "A-frame (red side)" : "A-frame (blue side)", x, 0, 0.175, 0.06, 1.06, 0.35));
        }
        // FLOWERS stand against the walls.
        JsonObject flowers = json.getAsJsonObject("flowers");
        for (String name : flowers.keySet()) {
            JsonObject f = flowers.getAsJsonObject(name);
            JsonArray axis = f.getAsJsonArray("axis_m");
            double h = f.get("opening_height_m").getAsDouble();
            posts.add(new Post(name, axis.get(0).getAsDouble(), axis.get(1).getAsDouble(), 0.075, h));
        }
        // Taped areas: LOADING ZONES are outlined, GARDENS are solid strips along the walls.
        JsonObject zones = json.getAsJsonObject("loading_zones");
        for (String alliance : zones.keySet()) {
            JsonObject z = zones.getAsJsonObject(alliance);
            tapes.add(tape("loading zone " + alliance, z, alliance, true));
        }
        JsonObject gardens = json.getAsJsonObject("gardens");
        for (String alliance : gardens.keySet()) {
            JsonObject g = gardens.getAsJsonObject(alliance);
            tapes.add(tape("garden " + alliance, g, alliance, false));
        }
        JsonObject cells = json.getAsJsonObject("cells");
        cellInner = cells.getAsJsonArray("along_arm_m").get(0).getAsDouble();
        cellOuter = cells.getAsJsonArray("along_arm_m").get(1).getAsDouble();
        cellHalfWidth = cells.get("half_width_m").getAsDouble();
        cellFloor = cells.get("floor_z_m").getAsDouble();
        cellWall = cells.get("wall_height_m").getAsDouble();
        cellPeak = cells.get("peak_height_m").getAsDouble();
        JsonObject hivesJson = json.getAsJsonObject("hives");
        for (String alliance : hivesJson.keySet()) {
            Hive h = hive(alliance, hivesJson.getAsJsonObject(alliance));
            hiveByAlliance.put(alliance, h);
            addCells(h);
        }
    }

    private static Hive hive(String alliance, JsonObject o) {
        if (o.has("axis")) {
            JsonArray a = o.getAsJsonArray("axis");
            if (a.get(0).getAsDouble() != 1 || a.get(1).getAsDouble() != 0 || a.get(2).getAsDouble() != 0) {
                throw new IllegalArgumentException("the " + alliance + " HIVE turns about " + a + "; only [1, 0, 0] (x) is supported");
            }
        }
        JsonArray pv = o.getAsJsonArray("pivot_m");
        String raised = o.get("start_up").getAsString();
        double phi = o.getAsJsonObject("stops_rad").get(raised + "_up").getAsDouble();
        return new Hive(alliance, pv.get(0).getAsDouble(), pv.get(1).getAsDouble(), pv.get(2).getAsDouble(), phi, raised);
    }

    /**
     * The two CELLS of one HIVE. Each is a tube with a five-sided cross-section (floor, two walls, a
     * peaked roof) running along the arm, closed at the pivot end and open at the outer end. The
     * HIVE stays at its starting tilt ({@code start_up}); it does not tip.
     */
    private void addCells(Hive h) {
        double a0 = cellInner, a1 = cellOuter, hw = cellHalfWidth, f = cellFloor, wall = cellWall, peak = cellPeak;
        double t = CELL_PANEL_M, depth = a1 - a0;
        double roofLen = Math.hypot(hw, peak - wall), roofTilt = Math.atan2(peak - wall, hw);
        // In the HIVE frame (x along the pivot axis, y along the arm, z up from the arm), for the rear
        // CELL (+y); the audience CELL is the same mirrored to -y. Panels sit outside the inside faces.
        for (String cell : new String[]{"rear", "audience"}) {
            double side = "rear".equals(cell) ? 1 : -1, mid = side * (a0 + a1) / 2;
            String name = h.alliance() + " HIVE " + cell + " CELL ";
            panel(name + "floor", h, 0, mid, f - t / 2, null, 2 * hw + 2 * t, depth, t);
            for (double sx : new double[]{-1, 1}) {
                panel(name + "wall", h, sx * (hw + t / 2), mid, f + wall / 2, null, t, depth, wall);
                // Roof panel from the wall top (sx * hw, f + wall) to the peak (0, f + peak), turned about y.
                double nx = sx * Math.sin(roofTilt), nz = Math.cos(roofTilt);
                panel(name + "roof", h, sx * hw / 2 + nx * t / 2, mid, f + (wall + peak) / 2 + nz * t / 2,
                        rotY(sx * roofTilt), roofLen + t, depth, t);
            }
            panel(name + "inner end", h, 0, side * (a0 - t / 2), f + peak / 2, null, 2 * hw, t, peak);
        }
    }

    /** Which CELL of this alliance's HIVE is raised at the start: "rear" or "audience". */
    public String raisedCell(String alliance) {
        return hiveByAlliance.get(alliance).raised();
    }

    /** A point in this alliance's HIVE frame (HIVE at its starting tilt) in world coordinates. */
    public double[] hiveToWorld(String alliance, double hx, double hy, double hz) {
        return hiveToWorld(hiveByAlliance.get(alliance), hx, hy, hz);
    }

    private static double[] hiveToWorld(Hive h, double hx, double hy, double hz) {
        double[] p = mul(rotX(h.phi()), new double[]{hx, hy, hz});
        return new double[]{h.px() + p[0], h.py() + p[1], h.pz() + p[2]};
    }

    /**
     * The HIVE CELL a point (world frame) is inside, such as "red audience" or "blue rear", or null.
     * HIVES are at their starting tilt.
     */
    public String cellAt(double x, double y, double z) {
        for (Hive h : hiveByAlliance.values()) {
            double dx = x - h.px(), dy = y - h.py(), dz = z - h.pz();
            double c = Math.cos(h.phi()), s = Math.sin(h.phi());
            double hy = dy * c + dz * s, hz = -dy * s + dz * c;   // into the HIVE frame (inverse turn)
            double ax = Math.abs(dx), ay = Math.abs(hy);
            if (ay < cellInner || ay > cellOuter || ax > cellHalfWidth || hz < cellFloor) continue;
            if (hz > cellFloor + cellWall + (cellPeak - cellWall) * (1 - ax / cellHalfWidth)) continue;
            return h.alliance() + (hy > 0 ? " rear" : " audience");
        }
        return null;
    }

    /** Adds a panel given in the HIVE frame: local turn {@code local} (null = none), then the HIVE's tilt and pivot. */
    private void panel(String name, Hive h, double x, double y, double z, double[] local, double sx, double sy, double sz) {
        double[] c = hiveToWorld(h, x, y, z);
        double[] tilt = rotX(h.phi());
        double[] rot = local == null ? tilt : mulMat(tilt, local);
        cellPanels.add(new Panel(name, c[0], c[1], c[2], rot, sx, sy, sz));
    }

    private static double[] rotX(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{1, 0, 0, 0, c, -s, 0, s, c};
    }

    private static double[] rotY(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, 0, s, 0, 1, 0, -s, 0, c};
    }

    private static double[] mul(double[] m, double[] v) {
        return new double[]{
                m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
                m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    private static double[] mulMat(double[] a, double[] b) {
        double[] out = new double[9];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
            }
        }
        return out;
    }

    private static Tape tape(String name, JsonObject o, String color, boolean outline) {
        JsonArray xs = o.getAsJsonArray("x_m");
        JsonArray ys = o.getAsJsonArray("y_m");
        return new Tape(name, xs.get(0).getAsDouble(), xs.get(1).getAsDouble(),
                ys.get(0).getAsDouble(), ys.get(1).getAsDouble(), color, outline);
    }

    public static FieldModel loadBiobuzz() throws IOException {
        try (InputStream in = FieldModel.class.getResourceAsStream("/field/biobuzz-field.json")) {
            if (in == null) throw new IOException("field/biobuzz-field.json is missing from the classpath");
            JsonElement e = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return new FieldModel(e.getAsJsonObject());
        }
    }

    /**
     * Floor color under a point, as normalized RGBA reflectance (what a downward color sensor with
     * its LED on reads at gain 1). Gray foam tiles, red and blue tape.
     */
    public float[] floorColor(double x, double y) {
        if (Math.abs(x) > half || Math.abs(y) > half) return new float[]{0.02f, 0.02f, 0.02f, 0.02f};
        for (Tape t : tapes) {
            boolean inside = x >= t.x0() && x <= t.x1() && y >= t.y0() && y <= t.y1();
            if (!inside) continue;
            boolean onTape = !t.outline()
                    || x - t.x0() < tapeWidth || t.x1() - x < tapeWidth
                    || y - t.y0() < tapeWidth || t.y1() - y < tapeWidth;
            if (onTape) {
                return "red".equals(t.color())
                        ? new float[]{0.24f, 0.045f, 0.05f, 0.11f}
                        : new float[]{0.035f, 0.07f, 0.26f, 0.12f};
            }
        }
        // Tile seams every 24 inches are slightly darker.
        double tile = json.get("tile_m").getAsDouble();
        double fx = Math.abs(((x + half) % tile)), fy = Math.abs(((y + half) % tile));
        boolean seam = fx < 0.004 || tile - fx < 0.004 || fy < 0.004 || tile - fy < 0.004;
        float v = seam ? 0.025f : 0.045f;
        return new float[]{v, v * 1.05f, v * 1.1f, v * 1.05f};
    }
}
