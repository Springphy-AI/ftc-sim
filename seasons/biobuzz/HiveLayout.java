package biobuzz;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ftcsim.physics.FieldModel;

/**
 * The HIVES and their CELLS, from season.json "hives" and "cells": where each HIVE pivots and
 * starts, and the CELL shape in the hive frame (the same for both HIVES; they turn on the pivot).
 */
public final class HiveLayout {

    /** Thickness of the CELL panels as modeled (polycarbonate; they grow outward from the inside faces). */
    public static final double SHELL_M = 0.012;

    /** Each HIVE at its start position, in season.json order (red first). */
    public final List<Hive> hives = new ArrayList<>();
    /** CELL dimensions in the hive frame ("cells" in season.json). */
    public final double cellInner, cellOuter, cellHalfWidth, cellFloor, cellWall, cellPeak;

    private HiveLayout(JsonObject json) {
        JsonObject cells = json.getAsJsonObject("cells");
        cellInner = cells.getAsJsonArray("along_arm_m").get(0).getAsDouble();
        cellOuter = cells.getAsJsonArray("along_arm_m").get(1).getAsDouble();
        cellHalfWidth = cells.get("half_width_m").getAsDouble();
        cellFloor = cells.get("floor_z_m").getAsDouble();
        cellWall = cells.get("wall_height_m").getAsDouble();
        cellPeak = cells.get("peak_height_m").getAsDouble();
        JsonObject hiveJson = json.getAsJsonObject("hives");
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
            hives.add(new Hive(alliance, pv.get(0).getAsDouble(), pv.get(1).getAsDouble(), pv.get(2).getAsDouble(), phi, up));
        }
    }

    public static HiveLayout of(FieldModel field) {
        return new HiveLayout(field.json);
    }

    /** One alliance's HIVE at its start position. */
    public Hive hive(String alliance) {
        for (Hive h : hives) if (h.alliance().equals(alliance)) return h;
        throw new IllegalArgumentException("no " + alliance + " HIVE");
    }

    /**
     * The two CELLS of one HIVE and the arm under them, in the hive frame. Each CELL is a
     * five-sided tube (floor, two side walls, a peaked roof) running along the arm, closed at the
     * inner end next to the pivot and open at the outer end ("open_end": "outer"), so a ball that
     * gets into the upward-facing CELL rolls down against the inner end and stays.
     */
    public List<FieldModel.Solid> panels(int shell, double opacity, int armColor) {
        List<FieldModel.Solid> out = new ArrayList<>();
        double t = SHELL_M, hw = cellHalfWidth, f = cellFloor, wall = cellWall, peak = cellPeak;
        double depth = cellOuter - cellInner, mid = (cellInner + cellOuter) / 2;
        double rise = peak - wall, slope = Math.hypot(hw, rise);
        for (int side : new int[]{1, -1}) {          // +1 rear CELL (+y), -1 audience CELL (-y)
            String cell = (side > 0 ? "rear" : "audience") + " CELL ";
            double y = side * mid;
            out.add(panel(cell + "floor", 0, y, f - t / 2, 2 * hw + 2 * t, depth, t, 0, shell, opacity));
            for (int sx : new int[]{1, -1}) {
                out.add(panel(cell + "wall", sx * (hw + t / 2), y, f + wall / 2, t, depth, wall, 0, shell, opacity));
                // Roof half from (sx*hw, wall) up to the ridge (0, peak), pushed out by half its thickness.
                double nx = sx * rise / slope, nz = hw / slope;
                out.add(panel(cell + "roof", sx * hw / 2 + nx * t / 2, y, f + (wall + peak) / 2 + nz * t / 2,
                        slope + t, depth, t, sx > 0 ? Math.atan2(rise, hw) : -Math.atan2(rise, hw), shell, opacity));
            }
            // The closed inner end: a box to collide with (its top corners stick out past the
            // roof, hidden), drawn as the CELL's own five-sided shape.
            out.add(FieldModel.box(cell + "inner end", new double[]{0, side * (cellInner - t / 2), f + peak / 2},
                    new double[]{2 * hw + 2 * t, t, peak + t}, null, shell, opacity, true, false, 0.25));
            double[] pentagon = {-hw - t, f - t, hw + t, f - t, hw + t, f + wall, 0, f + peak + t, -hw - t, f + wall};
            out.add(FieldModel.prism(cell + "inner end", new double[]{0, side * (cellInner - t / 2), 0}, pentagon, t,
                    FieldModel.rotX(Math.PI / 2), shell, opacity));
        }
        out.add(panel("HIVE arm", 0, 0, f - 0.02, 0.05, 2 * cellOuter, 0.03, 0, armColor, 1));
        return out;
    }

    /** A box in the hive frame, optionally tilted about its own y axis (roof halves). */
    private static FieldModel.Solid panel(String name, double x, double y, double z, double sx, double sy, double sz,
                                          double tiltY, int rgb, double opacity) {
        return FieldModel.box(name, new double[]{x, y, z}, new double[]{sx, sy, sz}, FieldModel.rotY(tiltY), rgb, opacity,
                true, true, 0.25);
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
}
