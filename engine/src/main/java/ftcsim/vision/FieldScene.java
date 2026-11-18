package ftcsim.vision;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ftcsim.physics.FieldModel;

/**
 * What the webcams see of the field itself, built from the same {@link FieldModel} shapes the
 * physics and the browser's 3D view use: the tiles and their tape, the perimeter, the alliance
 * areas, the season's structures and AprilTags, and its moving parts ({@link Mover}), drawn at
 * whatever angle the physics reports (their start angle by default).
 */
final class FieldScene {

    private static final int TILE = 0x50555c, SEAM = 0x3a3e44;
    private static final int VENUE = 0x1c1e22, ALUMINUM = 0xb9c0c8;

    /** Everything that never moves. */
    final Scene scene = new Scene();
    /** The moving parts, in FieldModel order. */
    final List<Mover> movers = new ArrayList<>();
    /** The AprilTags that never move. */
    final List<FieldTag> fixedTags = new ArrayList<>();
    /** Every AprilTag, with the moving parts at their start angle. */
    final List<FieldTag> tags = new ArrayList<>();
    final Rasterizer.FloorShader floor;

    /**
     * One moving part. Its shapes and AprilTags are kept in its own frame and posed at an angle
     * with FieldModel.Mover, the same code the physics poses it with.
     */
    static final class Mover {
        final FieldModel.Mover mover;
        final Scene local = new Scene();
        private volatile Posed posed;

        private record Posed(double angle, List<Scene.Tri> tris) {
        }

        Mover(FieldModel.Mover mover) {
            this.mover = mover;
        }

        /** Its angle at the start of a match. */
        double startAngle() {
            return mover.startAngle();
        }

        /** Its triangles in the world at an angle (the last angle asked for is kept). */
        List<Scene.Tri> at(double angle) {
            Posed p = posed;
            if (p != null && p.angle() == angle) return p.tris();
            double[] r = mover.rotation(angle), o = mover.pivot();
            List<Scene.Tri> out = new ArrayList<>(local.tris.size());
            for (Scene.Tri t : local.tris) {
                double[] v = new double[9];
                for (int k = 0; k < 9; k += 3) {
                    double x = t.v[k], y = t.v[k + 1], z = t.v[k + 2];
                    v[k] = r[0] * x + r[1] * y + r[2] * z + o[0];
                    v[k + 1] = r[3] * x + r[4] * y + r[5] * z + o[1];
                    v[k + 2] = r[6] * x + r[7] * y + r[8] * z + o[2];
                }
                out.add(new Scene.Tri(v, t.uv, t.material));
            }
            posed = new Posed(angle, out);
            return out;
        }

        /** Its tags in the world at an angle. */
        List<FieldTag> tagsAt(double angle) {
            List<FieldTag> out = new ArrayList<>();
            for (FieldModel.Tag t : mover.tagsAt(angle)) out.add(fieldTag(t));
            return out;
        }
    }

    private final double half;

    FieldScene(FieldModel field) {
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
        for (FieldModel.Area a : field.areas) {
            scene.quad(new double[]{a.x0(), a.y0(), -0.002}, new double[]{a.x1(), a.y0(), -0.002},
                    new double[]{a.x1(), a.y1(), -0.002}, new double[]{a.x0(), a.y1(), -0.002}, Material.flat(a.rgb()));
        }

        addWalls(field.wallHeight);
        Map<Long, Material> materials = new HashMap<>();
        for (FieldModel.Solid s : field.solids) add(scene, s, materials);
        for (FieldModel.Tag t : field.tags) {
            FieldTag tag = fieldTag(t);
            fixedTags.add(tag);
            tags.add(tag);
            addTag(scene, tag);
        }
        for (FieldModel.Mover m : field.movers) {
            Mover mv = new Mover(m);
            for (FieldModel.Solid s : m.parts()) add(mv.local, s, materials);
            for (FieldModel.Tag t : m.tags()) addTag(mv.local, fieldTag(t));
            tags.addAll(mv.tagsAt(m.startAngle()));
            movers.add(mv);
        }
    }

    private static FieldTag fieldTag(FieldModel.Tag t) {
        return new FieldTag(t.id(), t.center(), t.right(), t.up(), t.normal(), t.size());
    }

    /** A shape, if it is drawn. */
    private static void add(Scene target, FieldModel.Solid s, Map<Long, Material> materials) {
        if (!s.visible()) return;
        long key = ((long) s.rgb() << 8) | Math.round(Math.max(0, Math.min(1, s.opacity())) * 255);
        Material m = materials.computeIfAbsent(key, k -> s.opacity() < 1 ? Material.translucent(s.rgb(), (float) s.opacity())
                : Material.flat(s.rgb()));
        if (s.shape() == FieldModel.Shape.BOX) {
            target.box(s.center(), s.size()[0], s.size()[1], s.size()[2], s.rot(), m);
        } else {
            double r = s.size()[0];
            double[][] ends = s.axisEnds();
            target.cylinder(ends[0], ends[1], r, r < 0.01 ? 6 : r < 0.05 ? 12 : 20, m);
        }
    }

    // ---- floor ---------------------------------------------------------------------------------

    private static Rasterizer.FloorShader floorShader(FieldModel field) {
        double half = field.half, tile = field.tile;
        List<FieldModel.Tape> tapes = field.tapes;
        int n = tapes.size();
        // Bounding boxes, to skip most tapes quickly at most pixels.
        double[] box = new double[n * 4];
        for (int i = 0; i < n; i++) {
            FieldModel.Tape t = tapes.get(i);
            double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
            double[] p = t.points();
            for (int k = 0; k < p.length; k += 2) {
                x0 = Math.min(x0, p[k]);
                x1 = Math.max(x1, p[k]);
                y0 = Math.min(y0, p[k + 1]);
                y1 = Math.max(y1, p[k + 1]);
            }
            double m = t.width() / 2;
            box[4 * i] = x0 - m;
            box[4 * i + 1] = x1 + m;
            box[4 * i + 2] = y0 - m;
            box[4 * i + 3] = y1 + m;
        }
        double light = Lighting.sphere(0, 0, 1);
        int tileLit = Lighting.scale(TILE, light), seamLit = Lighting.scale(SEAM, light);
        int[] tapeLit = tapes.stream().mapToInt(t -> Lighting.scale(t.rgb(), light)).toArray();
        double seam = 0.0035;
        return (x, y) -> {
            for (int i = n - 1; i >= 0; i--) {   // the last listed is on top
                if (x < box[4 * i] || x > box[4 * i + 1] || y < box[4 * i + 2] || y > box[4 * i + 3]) continue;
                if (tapes.get(i).covers(x, y)) return tapeLit[i];
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

    /** The printed tag: the black-bordered square is {@code size}; its white margin goes around it. */
    private static void addTag(Scene target, FieldTag t) {
        double full = t.size() * Tag36h11.TOTAL_WIDTH / Tag36h11.WIDTH_AT_BORDER;
        double h = full / 2;
        // a hair off the surface toward the viewer, so the structure never covers it
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
}
