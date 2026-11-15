package ftcsim.physics;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * A thin static box at any orientation: center (world), full size along its own axes, and
     * rotation as a row-major 3x3 matrix from the box's axes to world axes.
     */
    public record Panel(String name, double[] center, double[] size, double[] rot) {
    }

    /**
     * One alliance's HIVE: two CELLS on an arm that pivots about the x axis through (px, py, pz).
     * Hive frame (biobuzz-field.json "hive_frame"): origin on the pivot, x along the pivot axis,
     * at phi = 0 the arm is level and +y points to the rear CELL. {@code phi} is the tilt: the
     * start position in {@link FieldModel#hives}, the live one from {@link #at(double)}.
     */
    public record Hive(String alliance, double px, double py, double pz, double phi, String upCell) {
        /** The same HIVE tilted to phi. */
        public Hive at(double newPhi) {
            return new Hive(alliance, px, py, pz, newPhi, newPhi < 0 ? "audience" : "rear");
        }

        /** Hive-frame point to world. */
        public double[] toWorld(double x, double y, double z) {
            double c = Math.cos(phi), s = Math.sin(phi);
            return new double[]{px + x, py + y * c - z * s, pz + y * s + z * c};
        }

        /** World point to hive frame. */
        public double[] toHive(double wx, double wy, double wz) {
            double c = Math.cos(phi), s = Math.sin(phi), dy = wy - py, dz = wz - pz;
            return new double[]{wx - px, dy * c + dz * s, -dy * s + dz * c};
        }
    }

    /** A colored tape rectangle on the floor; outline=true for taped outlines, false for solid areas. */
    public record Tape(String name, double x0, double x1, double y0, double y1, String color, boolean outline) {
    }

    public final JsonObject json;
    public final double half;        // half the field's inner width
    public final double wallHeight;
    public final double tapeWidth;
    public final List<Box> boxes = new ArrayList<>();
    public final List<Post> posts = new ArrayList<>();
    public final List<Tape> tapes = new ArrayList<>();
    /** Static structure balls can hit up high: axle, sign board and the A-frame legs. World frame. */
    public final List<Panel> panels = new ArrayList<>();
    /** One HIVE's CELLS and arm, in the hive frame (the same for both HIVES; they turn on the pivot). */
    public final List<Panel> hivePanels = new ArrayList<>();
    public final List<Hive> hives = new ArrayList<>();
    /** CELL dimensions in the hive frame ("cells" in biobuzz-field.json). */
    public final double cellInner, cellOuter, cellHalfWidth, cellFloor, cellWall, cellPeak;
    /** Thickness of the CELL panels as modeled (polycarbonate; they grow outward from the inside faces). */
    public static final double SHELL_M = 0.012;

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
        // The HIVES: CELLS on a pivoting arm, the axle they turn on, the sign board, A-frame legs.
        JsonObject cells = json.getAsJsonObject("cells");
        cellInner = cells.getAsJsonArray("along_arm_m").get(0).getAsDouble();
        cellOuter = cells.getAsJsonArray("along_arm_m").get(1).getAsDouble();
        cellHalfWidth = cells.get("half_width_m").getAsDouble();
        cellFloor = cells.get("floor_z_m").getAsDouble();
        cellWall = cells.get("wall_height_m").getAsDouble();
        cellPeak = cells.get("peak_height_m").getAsDouble();
        JsonObject hiveJson = json.getAsJsonObject("hives");
        double pivotZ = 0;
        for (String alliance : hiveJson.keySet()) {
            JsonObject h = hiveJson.getAsJsonObject(alliance);
            if (h.has("axis")) {
                JsonArray a = h.getAsJsonArray("axis");
                if (a.get(0).getAsDouble() != 1 || a.get(1).getAsDouble() != 0 || a.get(2).getAsDouble() != 0) {
                    throw new IllegalArgumentException("the " + alliance + " HIVE turns about " + a + "; only [1, 0, 0] (x) is supported");
                }
            }
            JsonArray pv = h.getAsJsonArray("pivot_m");
            String up = h.get("start_up").getAsString();
            double phi = h.getAsJsonObject("stops_rad").get(up + "_up").getAsDouble();
            Hive hive = new Hive(alliance, pv.get(0).getAsDouble(), pv.get(1).getAsDouble(), pv.get(2).getAsDouble(), phi, up);
            hives.add(hive);
            pivotZ = hive.pz();
        }
        addHivePanels();
        // The axle (square here, round on the field) and the sign board above it.
        panels.add(new Panel("axle", new double[]{0, 0, pivotZ}, new double[]{1.32, 0.036, 0.036}, IDENTITY));
        if (structure.has("sign_board")) {
            JsonObject sb = structure.getAsJsonObject("sign_board");
            JsonArray c = sb.getAsJsonArray("centre_m"), sz = sb.getAsJsonArray("size_m");
            panels.add(new Panel("sign board", new double[]{c.get(0).getAsDouble(), c.get(1).getAsDouble(), c.get(2).getAsDouble()},
                    new double[]{sz.get(0).getAsDouble(), sz.get(1).getAsDouble(), sz.get(2).getAsDouble()}, IDENTITY));
        }
        // A-frame legs, from the base (y = +-0.5) up to just above the axle, as drawn in the 3D view.
        for (double x : new double[]{-0.628, 0.628}) {
            for (double y0 : new double[]{-0.5, 0.5}) {
                double z0 = 0.02, z1 = pivotZ + 0.04, dy = -y0, dz = z1 - z0, len = Math.hypot(dy, dz);
                double t = Math.atan2(dz, dy);   // rotate the leg's local +y onto (0, dy, dz)
                panels.add(new Panel("A-frame leg", new double[]{x, y0 / 2, (z0 + z1) / 2},
                        new double[]{0.045, len, 0.045}, rotX(t)));
            }
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
    }

    private static final double[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    private static double[] rotX(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{1, 0, 0, 0, c, -s, 0, s, c};
    }

    private static double[] rotY(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, 0, s, 0, 1, 0, -s, 0, c};
    }

    /**
     * The two CELLS of one HIVE and the arm under them. Each CELL is a five-sided tube (floor, two
     * side walls, a peaked roof) running along the arm, closed at the inner end next to the pivot
     * and open at the outer end ("open_end": "outer"), so a ball that gets into the upward-facing
     * CELL rolls down against the inner end and stays.
     */
    private void addHivePanels() {
        double t = SHELL_M, hw = cellHalfWidth, f = cellFloor, wall = cellWall, peak = cellPeak;
        double depth = cellOuter - cellInner, mid = (cellInner + cellOuter) / 2;
        double rise = peak - wall, slope = Math.hypot(hw, rise);
        for (int side : new int[]{1, -1}) {          // +1 rear CELL (+y), -1 audience CELL (-y)
            String cell = (side > 0 ? "rear" : "audience") + " CELL ";
            double y = side * mid;
            hivePanel(cell + "floor", 0, y, f - t / 2, 2 * hw + 2 * t, depth, t, 0);
            for (int sx : new int[]{1, -1}) {
                hivePanel(cell + "wall", sx * (hw + t / 2), y, f + wall / 2, t, depth, wall, 0);
                // Roof half from (sx*hw, wall) up to the ridge (0, peak), pushed out by half its thickness.
                double nx = sx * rise / slope, nz = hw / slope;
                hivePanel(cell + "roof", sx * hw / 2 + nx * t / 2, y, f + (wall + peak) / 2 + nz * t / 2,
                        slope + t, depth, t, sx > 0 ? Math.atan2(rise, hw) : -Math.atan2(rise, hw));
            }
            hivePanel(cell + "inner end", 0, side * (cellInner - t / 2), f + peak / 2, 2 * hw + 2 * t, t, peak + t, 0);
        }
        hivePanel("HIVE arm", 0, 0, f - 0.02, 0.05, 2 * cellOuter, 0.03, 0);
    }

    /** A box in the hive frame, optionally tilted about its own y axis (roof halves). */
    private void hivePanel(String name, double x, double y, double z, double sx, double sy, double sz, double tiltY) {
        hivePanels.add(new Panel(name, new double[]{x, y, z}, new double[]{sx, sy, sz}, rotY(tiltY)));
    }

    /**
     * Which CELL a point is inside, as "red rear", "blue audience"..., or null: between the floor,
     * the side walls, the sloped roof, and the inner and outer ends, with each HIVE at the tilt
     * given in {@code current}.
     */
    public String cellAt(double wx, double wy, double wz, List<Hive> current) {
        for (Hive h : current) {
            double[] p = h.toHive(wx, wy, wz);
            double ay = Math.abs(p[1]);
            double ax = Math.abs(p[0]);
            double roof = cellFloor + cellWall + (cellPeak - cellWall) * (1 - ax / cellHalfWidth);
            if (ax < cellHalfWidth && ay > cellInner && ay < cellOuter && p[2] > cellFloor && p[2] < roof) {
                return h.alliance() + (p[1] > 0 ? " rear" : " audience");
            }
        }
        return null;
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
