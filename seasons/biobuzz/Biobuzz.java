package biobuzz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ftcsim.physics.FieldModel;
import ftcsim.season.SeasonCode;
import ftcsim.season.SeasonGame;
import ftcsim.sim.GameElements;

/**
 * BIOBUZZ presented by RTX (FTC 2026-27). season.json lists the plain structures (the A-frames,
 * the axle); this adds what is worked out from the game's own data: the LOADING ZONE and GARDEN
 * tape, the sign board, the FLOWERS, and the two HIVES that tip on their pivots.
 */
public final class Biobuzz implements SeasonCode {

    private static final int FLOWER = 0x2fa84f, GOLD = 0xd9a52b, FLOWER_BASE = 0x24262a;
    private static final int SHELL = 0xe6ebf0, ARM = 0x5b6169, SIGN = 0xf2c230;

    @Override
    public void describe(FieldModel field) {
        JsonObject json = field.json;
        // Tape: LOADING ZONES are outlined, GARDENS are solid strips along the walls.
        JsonObject zones = json.getAsJsonObject("loading_zones");
        for (String alliance : zones.keySet()) {
            double[] r = rect(zones.getAsJsonObject(alliance));
            field.tapes.addAll(field.rectOutline("loading zone " + alliance, r[0], r[1], r[2], r[3], field.tapeWidth, alliance));
        }
        JsonObject gardens = json.getAsJsonObject("gardens");
        for (String alliance : gardens.keySet()) {
            double[] r = rect(gardens.getAsJsonObject(alliance));
            field.tapes.add(field.area("garden " + alliance, new double[]{r[0], r[2], r[1], r[2], r[1], r[3], r[0], r[3]}, alliance));
        }

        // The sign board above the axle.
        JsonObject structure = json.has("structure") ? json.getAsJsonObject("structure") : new JsonObject();
        if (structure.has("sign_board")) {
            JsonObject sb = structure.getAsJsonObject("sign_board");
            field.solids.add(FieldModel.box("sign board", FieldModel.vec(sb.getAsJsonArray("centre_m")),
                    FieldModel.vec(sb.getAsJsonArray("size_m")), null, SIGN, 1, true, true, 0.25));
        }

        // FLOWERS stand against the walls: a post to collide with, drawn as six bars, two hoops,
        // the gold ring at the top and a base.
        JsonObject flowers = json.getAsJsonObject("flowers");
        JsonObject top = structure.has("flower_top") ? structure.getAsJsonObject("flower_top") : null;
        double ringR = top != null && top.has("radius_m") ? top.get("radius_m").getAsDouble() : 0.07;
        for (String name : flowers.keySet()) {
            JsonObject f = flowers.getAsJsonObject(name);
            JsonArray axis = f.getAsJsonArray("axis_m");
            double x = axis.get(0).getAsDouble(), y = axis.get(1).getAsDouble(), h = f.get("opening_height_m").getAsDouble();
            field.solids.add(FieldModel.cylinder(name, new double[]{x, y, 0}, new double[]{x, y, h}, 0.075, FLOWER, 1, true, false, 0.3));
            for (int i = 0; i < 6; i++) {
                double a = i / 6.0 * Math.PI * 2, bx = x + Math.cos(a) * 0.055, by = y + Math.sin(a) * 0.055;
                field.solids.add(FieldModel.cylinder(name + " bar", new double[]{bx, by, 0}, new double[]{bx, by, h}, 0.006,
                        FLOWER, 1, false, true, 0.3));
            }
            for (double z : new double[]{0.12, 0.3}) ring(field.solids, name + " hoop", x, y, z, 0.055, 0.01, 24, FLOWER);
            ring(field.solids, name + " ring", x, y, h, ringR, 0.024, 20, GOLD);
            field.solids.add(FieldModel.cylinder(name + " base", new double[]{x, y, 0}, new double[]{x, y, 0.012}, 0.08,
                    FLOWER_BASE, 1, false, true, 0.3));
        }

        // The HIVES: CELLS on a pivoting arm, with AprilTags under the CELLS.
        HiveLayout layout = HiveLayout.of(field);
        JsonObject tagJson = json.getAsJsonObject("apriltags");
        double tagSize = tagJson.get("size_m").getAsDouble();
        for (Hive h : layout.hives) {
            List<FieldModel.Solid> parts = new ArrayList<>(layout.panels(SHELL, 0.25, ARM));
            // Alliance-colored edges along each CELL.
            int edge = field.color(h.alliance());
            double hw = layout.cellHalfWidth, f = layout.cellFloor, wall = layout.cellWall, peak = layout.cellPeak;
            double a0 = layout.cellInner, a1 = layout.cellOuter;
            double[][] outline = {{-hw, f}, {hw, f}, {hw, f + wall}, {0, f + peak}, {-hw, f + wall}};
            for (int side = -1; side <= 1; side += 2) {
                for (int i = 0; i < outline.length; i++) {
                    double[] p = outline[i], q = outline[(i + 1) % outline.length];
                    double[] pa = {p[0], side * a0, p[1]}, pb = {p[0], side * a1, p[1]}, qb = {q[0], side * a1, q[1]};
                    parts.add(FieldModel.beam("CELL edge", pa, pb, 0.008, 0.008, edge, 1, false, true, 0.25));
                    parts.add(FieldModel.beam("CELL edge", pb, qb, 0.008, 0.008, edge, 1, false, true, 0.25));
                }
            }
            List<FieldModel.Tag> tags = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : tagJson.getAsJsonObject("tags").entrySet()) {
                JsonObject t = e.getValue().getAsJsonObject();
                if (!h.alliance().equals(t.get("hive").getAsString())) continue;
                tags.add(new FieldModel.Tag(Integer.parseInt(e.getKey()), FieldModel.vec(t.getAsJsonArray("center_m")),
                        FieldModel.vec(t.getAsJsonArray("right")), FieldModel.vec(t.getAsJsonArray("up")),
                        FieldModel.vec(t.getAsJsonArray("normal")), tagSize));
            }
            double stop = Math.abs(h.phi());
            double ixx = HiveBody.MASS_KG * HiveBody.GYRATION_M * HiveBody.GYRATION_M;
            field.movers.add(new FieldModel.Mover(h.alliance() + " HIVE", new double[]{h.px(), h.py(), h.pz()},
                    new double[]{1, 0, 0}, h.phi(), -stop, stop, true, HiveBody.MASS_KG,
                    new double[]{0, 0, HiveBody.COM_ABOVE_PIVOT_M}, new double[]{ixx, 0.3 * ixx, ixx}, HiveBody.DAMPING_NMS,
                    parts, tags));
        }
    }

    /** A ring of short bars around a vertical axis. */
    private static void ring(List<FieldModel.Solid> into, String name, double x, double y, double z, double r, double w,
                             int segments, int rgb) {
        for (int i = 0; i < segments; i++) {
            double t0 = 2 * Math.PI * i / segments, t1 = 2 * Math.PI * (i + 1) / segments;
            into.add(FieldModel.beam(name, new double[]{x + Math.cos(t0) * r, y + Math.sin(t0) * r, z},
                    new double[]{x + Math.cos(t1) * r, y + Math.sin(t1) * r, z}, w, w, rgb, 1, false, true, 0.3));
        }
    }

    static double[] rect(JsonObject o) {
        JsonArray xs = o.getAsJsonArray("x_m"), ys = o.getAsJsonArray("y_m");
        return new double[]{xs.get(0).getAsDouble(), xs.get(1).getAsDouble(), ys.get(0).getAsDouble(), ys.get(1).getAsDouble()};
    }

    @Override
    public SeasonGame newGame(GameElements elements) {
        return new BiobuzzGame(elements);
    }
}
