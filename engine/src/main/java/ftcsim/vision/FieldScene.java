package ftcsim.vision;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ftcsim.physics.FieldModel;

/**
 * What the webcams see of the field itself, built from the FieldModel the physics uses (and the
 * field JSON for the AprilTags):
 * tiles and tape, the perimeter, the HIVE structure with its CELLS and AprilTags, and the FLOWERS.
 * The HIVES are drawn at whatever tilt the physics reports (their starting tilt by default).
 */
final class FieldScene {

    private static final int TILE = 0x50555c, SEAM = 0x3a3e44, RED = 0xd23a3f, BLUE = 0x2f6fe0;
    private static final int VENUE = 0x1c1e22, ALUMINUM = 0xb9c0c8, STEEL = 0x8d949c, FLOWER = 0x2fa84f, GOLD = 0xd9a52b;

    /** Everything that never moves. */
    final Scene scene = new Scene();
    /** The HIVES, which turn about their axle; red first, in field JSON order. */
    final List<Hive> hives = new ArrayList<>();
    /** The AprilTags with each HIVE at its starting tilt. */
    final List<FieldTag> tags = new ArrayList<>();
    final Rasterizer.FloorShader floor;

    /**
     * One HIVE. Its CELLS, arm and AprilTags are kept in the HIVE frame (origin on the pivot, x
     * along the axle, y along the arm, z up from the arm when level) and posed at a tilt with
     * FieldModel.Hive, the same code the physics poses it with.
     */
    static final class Hive {
        final FieldModel.Hive start;
        /** Tilt about the axle (rad) at the start of a match. */
        final double startTilt;
        final Scene local = new Scene();
        final List<FieldTag> localTags = new ArrayList<>();
        private volatile Posed posed;

        private record Posed(double tilt, List<Scene.Tri> tris) {
        }

        Hive(FieldModel.Hive start) {
            this.start = start;
            this.startTilt = start.phi();
        }

        /** The HIVE's triangles in the world at a tilt (the last tilt asked for is kept). */
        List<Scene.Tri> at(double tilt) {
            Posed p = posed;
            if (p != null && p.tilt() == tilt) return p.tris();
            FieldModel.Hive h = start.at(tilt);
            List<Scene.Tri> out = new ArrayList<>(local.tris.size());
            for (Scene.Tri t : local.tris) {
                double[] v = new double[9];
                for (int k = 0; k < 9; k += 3) {
                    double[] w = h.toWorld(t.v[k], t.v[k + 1], t.v[k + 2]);
                    v[k] = w[0];
                    v[k + 1] = w[1];
                    v[k + 2] = w[2];
                }
                out.add(new Scene.Tri(v, t.uv, t.material));
            }
            posed = new Posed(tilt, out);
            return out;
        }

        /** A tag of this HIVE in the world at a tilt. */
        FieldTag tagAt(FieldTag t, double tilt) {
            FieldModel.Hive h = start.at(tilt);
            double[] c = t.center();
            return new FieldTag(t.id(), h.toWorld(c[0], c[1], c[2]), direction(h, t.right()), direction(h, t.up()),
                    direction(h, t.normal()), t.size());
        }

        private static double[] direction(FieldModel.Hive h, double[] d) {
            double[] o = h.toWorld(0, 0, 0), e = h.toWorld(d[0], d[1], d[2]);
            return new double[]{e[0] - o[0], e[1] - o[1], e[2] - o[2]};
        }
    }

    private final double half;

    FieldScene(FieldModel field) {
        JsonObject json = field.json;
        half = field.half;
        floor = floorShader(field);

        // Floor: the tiles (colored per pixel) and the venue floor around the field.
        double big = 7;
        scene.quad(new double[]{-half, -half, 0}, new double[]{half, -half, 0}, new double[]{half, half, 0},
                new double[]{-half, half, 0}, Material.floor());
        Material venue = Material.flat(VENUE);
        scene.quad(new double[]{-big, -big, -0.003}, new double[]{big, -big, -0.003}, new double[]{big, -half, -0.003},
                new double[]{-big, -half, -0.003}, venue);
        scene.quad(new double[]{-big, half, -0.003}, new double[]{big, half, -0.003}, new double[]{big, big, -0.003},
                new double[]{-big, big, -0.003}, venue);
        scene.quad(new double[]{-big, -half, -0.003}, new double[]{-half, -half, -0.003}, new double[]{-half, half, -0.003},
                new double[]{-big, half, -0.003}, venue);
        scene.quad(new double[]{half, -half, -0.003}, new double[]{big, -half, -0.003}, new double[]{big, half, -0.003},
                new double[]{half, half, -0.003}, venue);

        addWalls(field.wallHeight);
        for (FieldModel.Box b : field.boxes) {
            if (b.name().startsWith("A-frame")) addAFrameBase(b.cx());
        }
        // The axle, the sign board and the A-frame legs: the panels the physics collides with.
        for (FieldModel.Panel panel : field.panels) {
            int rgb = panel.name().startsWith("sign") ? 0xf2c230 : panel.name().startsWith("axle") ? ALUMINUM : STEEL;
            scene.box(panel.center(), panel.size()[0], panel.size()[1], panel.size()[2], panel.rot(), Material.flat(rgb));
        }
        for (FieldModel.Hive h : field.hives) addHive(field, h);
        JsonObject structure = json.has("structure") ? json.getAsJsonObject("structure") : new JsonObject();
        JsonObject flowers = json.getAsJsonObject("flowers");
        JsonObject top = structure.has("flower_top") ? structure.getAsJsonObject("flower_top") : null;
        for (Map.Entry<String, com.google.gson.JsonElement> e : flowers.entrySet()) addFlower(e.getValue().getAsJsonObject(), top);
    }

    // ---- floor ---------------------------------------------------------------------------------

    private record Zone(double x0, double x1, double y0, double y1, int rgb, boolean filled) {
    }

    private static Rasterizer.FloorShader floorShader(FieldModel field) {
        JsonObject json = field.json;
        double half = field.half, tile = json.get("tile_m").getAsDouble(), tape = field.tapeWidth;
        List<Zone> zones = new ArrayList<>();
        for (String key : new String[]{"gardens", "loading_zones"}) {
            JsonObject group = json.getAsJsonObject(key);
            for (String alliance : group.keySet()) {
                JsonObject z = group.getAsJsonObject(alliance);
                zones.add(new Zone(z.getAsJsonArray("x_m").get(0).getAsDouble(), z.getAsJsonArray("x_m").get(1).getAsDouble(),
                        z.getAsJsonArray("y_m").get(0).getAsDouble(), z.getAsJsonArray("y_m").get(1).getAsDouble(),
                        "red".equals(alliance) ? RED : BLUE, "gardens".equals(key)));
            }
        }
        double light = Lighting.sphere(0, 0, 1);
        int tileLit = Lighting.scale(TILE, light), seamLit = Lighting.scale(SEAM, light);
        int[] zoneLit = zones.stream().mapToInt(z -> Lighting.scale(z.rgb, light)).toArray();
        double seam = 0.0035;
        return (x, y) -> {
            for (int i = 0; i < zones.size(); i++) {
                Zone z = zones.get(i);
                if (x < z.x0 || x > z.x1 || y < z.y0 || y > z.y1) continue;
                if (z.filled || x - z.x0 < tape || z.x1 - x < tape || y - z.y0 < tape || z.y1 - y < tape) return zoneLit[i];
            }
            double fx = (x + half) % tile, fy = (y + half) % tile;
            if (fx < seam || tile - fx < seam || fy < seam || tile - fy < seam) return seamLit;
            return tileLit;
        };
    }

    // ---- structure -----------------------------------------------------------------------------

    private void addWalls(double h) {
        Material panel = Material.translucent(0xa9bfd6, 0.16f);
        Material rail = Material.flat(ALUMINUM);
        double len = 2 * half + 0.1, o = half + 0.025;
        double[][] walls = {{o, 0, 0.012, len}, {-o, 0, 0.012, len}, {0, o, len, 0.012}, {0, -o, len, 0.012}};
        for (double[] wdef : walls) {
            scene.box(new double[]{wdef[0], wdef[1], h / 2}, wdef[2], wdef[3], h, null, panel);
            for (double z : new double[]{0.02, h}) {
                scene.box(new double[]{wdef[0], wdef[1], z}, Math.max(wdef[2], 0.04), Math.max(wdef[3], 0.04), 0.035, null, rail);
            }
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                scene.box(new double[]{sx * o, sy * o, (h + 0.04) / 2}, 0.05, 0.05, h + 0.04, null, rail);
            }
        }
    }

    /** The A-frame's base and crossbar (the legs are FieldModel panels). */
    private void addAFrameBase(double x) {
        Material m = Material.flat(STEEL);
        scene.beam(new double[]{x, -0.53, 0.025}, new double[]{x, 0.53, 0.025}, 0.05, m);
        scene.beam(new double[]{x, -0.39, 0.33}, new double[]{x, 0.39, 0.33}, 0.035, m);
    }

    private void addHive(FieldModel field, FieldModel.Hive start) {
        Hive hive = new Hive(start);
        Scene local = hive.local;

        // The CELLS and the arm, exactly the boxes the physics uses (see-through CELL panels).
        Material shell = Material.translucent(0xe6ebf0, 0.2f);
        Material arm = Material.flat(0x5b6169);
        for (FieldModel.Panel panel : field.hivePanels) {
            local.box(panel.center(), panel.size()[0], panel.size()[1], panel.size()[2], panel.rot(),
                    panel.name().contains("arm") ? arm : shell);
        }
        // Alliance-colored edges along each CELL, as in the 3D view.
        double hw = field.cellHalfWidth, f = field.cellFloor, wall = field.cellWall, peak = field.cellPeak;
        double a0 = field.cellInner, a1 = field.cellOuter;
        double[][] outline = {{-hw, f}, {hw, f}, {hw, f + wall}, {0, f + peak}, {-hw, f + wall}};
        Material edge = Material.flat("red".equals(start.alliance()) ? RED : BLUE);
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < outline.length; i++) {
                double[] p = outline[i], q = outline[(i + 1) % outline.length];
                double[] pa = {p[0], side * a0, p[1]}, pb = {p[0], side * a1, p[1]}, qb = {q[0], side * a1, q[1]};
                local.beam(pa, pb, 0.008, edge);
                local.beam(pb, qb, 0.008, edge);
            }
        }

        // AprilTags under the CELLS (the field JSON gives them in the HIVE frame).
        JsonObject tagJson = field.json.getAsJsonObject("apriltags");
        double size = tagJson.get("size_m").getAsDouble();
        JsonObject list = tagJson.getAsJsonObject("tags");
        for (String id : list.keySet()) {
            JsonObject t = list.getAsJsonObject(id);
            if (!start.alliance().equals(t.get("hive").getAsString())) continue;
            FieldTag tag = new FieldTag(Integer.parseInt(id), vec(t.getAsJsonArray("center_m")), vec(t.getAsJsonArray("right")),
                    vec(t.getAsJsonArray("up")), vec(t.getAsJsonArray("normal")), size);
            hive.localTags.add(tag);
            addTag(local, tag);
            tags.add(hive.tagAt(tag, start.phi()));
        }
        hives.add(hive);
    }

    /** The printed tag: the black-bordered square is {@code size}; its white margin goes around it. */
    private static void addTag(Scene target, FieldTag t) {
        double full = t.size() * Tag36h11.TOTAL_WIDTH / Tag36h11.WIDTH_AT_BORDER;
        double h = full / 2;
        // a hair off the surface toward the viewer, so the HIVE never covers it
        double[] c = {t.center()[0] + t.normal()[0] * 0.0005, t.center()[1] + t.normal()[1] * 0.0005, t.center()[2] + t.normal()[2] * 0.0005};
        double[] r = t.right(), u = t.up();
        double[] tl = {c[0] - r[0] * h + u[0] * h, c[1] - r[1] * h + u[1] * h, c[2] - r[2] * h + u[2] * h};
        double[] tr = {c[0] + r[0] * h + u[0] * h, c[1] + r[1] * h + u[1] * h, c[2] + r[2] * h + u[2] * h};
        double[] br = {c[0] + r[0] * h - u[0] * h, c[1] + r[1] * h - u[1] * h, c[2] + r[2] * h - u[2] * h};
        double[] bl = {c[0] - r[0] * h - u[0] * h, c[1] - r[1] * h - u[1] * h, c[2] - r[2] * h - u[2] * h};
        target.texturedQuad(tl, tr, br, bl, tagMaterial(t.id()));
    }

    /** A tag image, a few texels per cell; white is paper under the lights, not pure white. */
    static Material tagMaterial(int id) {
        boolean[] cells = Tag36h11.cells(id);
        int n = Tag36h11.TOTAL_WIDTH;
        int[] px = new int[n * n];
        for (int i = 0; i < px.length; i++) px[i] = cells[i] ? 0xffe6e6e6 : 0xff141414;
        return Material.texture(px, n);
    }

    private void addFlower(JsonObject flower, JsonObject top) {
        double x = flower.getAsJsonArray("axis_m").get(0).getAsDouble(), y = flower.getAsJsonArray("axis_m").get(1).getAsDouble();
        double h = flower.get("opening_height_m").getAsDouble();
        Material bars = Material.flat(FLOWER);
        for (int i = 0; i < 6; i++) {
            double a = i / 6.0 * Math.PI * 2;
            double bx = x + Math.cos(a) * 0.055, by = y + Math.sin(a) * 0.055;
            scene.cylinder(new double[]{bx, by, 0}, new double[]{bx, by, h}, 0.006, 6, bars);
        }
        double ring = top != null && top.has("radius_m") ? top.get("radius_m").getAsDouble() : 0.07;
        int segments = 20;
        for (int i = 0; i < segments; i++) {
            double t0 = 2 * Math.PI * i / segments, t1 = 2 * Math.PI * (i + 1) / segments;
            scene.beam(new double[]{x + Math.cos(t0) * ring, y + Math.sin(t0) * ring, h},
                    new double[]{x + Math.cos(t1) * ring, y + Math.sin(t1) * ring, h}, 0.024, Material.flat(GOLD));
        }
        scene.cylinder(new double[]{x, y, 0}, new double[]{x, y, 0.012}, 0.08, 16, Material.flat(0x24262a));
    }

    // ---- json helpers --------------------------------------------------------------------------

    private static double[] vec(JsonArray a) {
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

}
