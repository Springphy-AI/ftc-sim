package ftcsim.physics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ftcsim.season.Season;

/**
 * A season's field, described as plain shapes that the physics, the webcams and the browser's 3D
 * view all build from: the tiles and perimeter (the same every season), tape on the tiles, solid
 * structures (boxes and cylinders), AprilTags, and {@link Mover}s, parts that turn on a hinge.
 * The shapes come from the season's season.json and from its code ({@link Season}).
 *
 * <p>Frame: the season's official FTC field coordinates. Origin at the field center on the tiles,
 * +z up, meters. Which way +x and +y point differs from season to season (season.json "frame").
 */
public final class FieldModel {

    public enum Shape {BOX, CYLINDER, PRISM}

    /**
     * A solid part of the field. {@code size} is the full size along the shape's own axes for a
     * BOX, {radius, length} for a CYLINDER, whose axis is its own z, or {length} for a PRISM: the
     * convex polygon {@code outline} (x, y pairs in the shape's own x-y plane) made {@code length}
     * thick along its z. {@code rot} is row-major, from the shape's axes to the world's.
     * {@code collide}: robots and game pieces hit it; {@code visible}: drawn (the 3D view and the
     * webcams). A shape can be one without the other: a simple collision box under a detailed
     * drawing. A PRISM is only drawn.
     */
    public record Solid(String name, Shape shape, double[] center, double[] size, double[] rot,
                        int rgb, double opacity, boolean collide, boolean visible, double friction, double[] outline) {

        public Solid {
            if (shape == Shape.PRISM && collide) {
                throw new IllegalArgumentException("\"" + name + "\" is a prism, which is only drawn: give it \"collide\": false and a box to collide with");
            }
        }

        /** This shape moved by a rigid transform: rotation {@code r} (row-major) then translation {@code t}. */
        public Solid transformed(double[] r, double[] t) {
            return new Solid(name, shape, add(mul(r, center), t), size, mul3(r, rot), rgb, opacity, collide, visible, friction, outline);
        }

        /** The two ends of a cylinder's axis. */
        public double[][] axisEnds() {
            double h = size[1] / 2;
            double[] z = {rot[2], rot[5], rot[8]};
            return new double[][]{{center[0] - z[0] * h, center[1] - z[1] * h, center[2] - z[2] * h},
                    {center[0] + z[0] * h, center[1] + z[1] * h, center[2] + z[2] * h}};
        }
    }

    /**
     * Tape on the tiles. A LINE is a strip {@code width} wide centered on the segment
     * (x0, y0) - (x1, y1); an AREA is a filled polygon (x, y pairs).
     */
    public record Tape(String name, Kind kind, double[] points, double width, String color, int rgb) {
        public enum Kind {LINE, AREA}

        /** Whether this tape covers the point. */
        public boolean covers(double x, double y) {
            if (kind == Kind.LINE) {
                // A strip with square ends at the segment's ends, as the 3D view draws it.
                double ax = points[0], ay = points[1], bx = points[2], by = points[3];
                double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
                if (len2 == 0) return false;
                double t = ((x - ax) * dx + (y - ay) * dy) / len2;
                return t >= 0 && t <= 1 && Math.abs((x - ax) * dy - (y - ay) * dx) / Math.sqrt(len2) <= width / 2;
            }
            boolean in = false;
            int n = points.length / 2;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = points[2 * i], yi = points[2 * i + 1], xj = points[2 * j], yj = points[2 * j + 1];
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
            }
            return in;
        }
    }

    /** An AprilTag: center, the tag's right and up directions and the normal out of its face, black-square size. */
    public record Tag(int id, double[] center, double[] right, double[] up, double[] normal, double size) {
        public Tag transformed(double[] r, double[] t) {
            return new Tag(id, add(mul(r, center), t), mul(r, right), mul(r, up), mul(r, normal), size);
        }
    }

    /** An area outside the field (where drivers and human players stand), drawn on the venue floor. */
    public record Area(String name, double x0, double x1, double y0, double y1, int rgb) {
    }

    /**
     * A part of the field that turns on a hinge: a HIVE, a GATE, an OBELISK. Its {@code parts} and
     * {@code tags} are in its own frame, whose origin is the pivot and whose axes are the world's
     * at angle 0; at angle a the frame is turned by a about {@code axis} (a unit vector, world).
     *
     * <p>A dynamic mover is a rigid body on a hinge joint between the angle limits: it has mass
     * ({@code mass}, center of mass {@code com} in its frame, principal moments {@code inertia}
     * about the center of mass along its frame's axes), a damper, and is pushed by gravity, robots
     * and game pieces. A kinematic one stays where the season's code puts it.
     */
    public record Mover(String name, double[] pivot, double[] axis, double startAngle, double minAngle, double maxAngle,
                        boolean dynamic, double mass, double[] com, double[] inertia, double damping,
                        List<Solid> parts, List<Tag> tags) {

        /** Rotation (row-major) of the mover's frame at angle {@code a}. */
        public double[] rotation(double a) {
            return axisAngle(axis, a);
        }

        /** A point in the mover's frame, in the world, at angle {@code a}. */
        public double[] toWorld(double a, double[] local) {
            return add(mul(rotation(a), local), pivot);
        }

        public List<Tag> tagsAt(double a) {
            double[] r = rotation(a);
            List<Tag> out = new ArrayList<>(tags.size());
            for (Tag t : tags) out.add(t.transformed(r, pivot));
            return out;
        }
    }

    /** The season this field belongs to. */
    public final Season season;
    /** The season's season.json. */
    public final JsonObject json;
    /** Half the field's inner width (inside face of the perimeter). */
    public final double half;
    public final double wallHeight;
    public final double tile;
    public final double tapeWidth;
    public final List<Solid> solids = new ArrayList<>();
    /**
     * Shapes only the robots' bodies run into, where the field's frame stops a robot but leaves room
     * for game pieces and a low intake (around a FLOWER's thin rings, for instance). Game pieces and
     * wheels pass through them. They are never drawn.
     */
    public final List<Solid> robotStops = new ArrayList<>();
    public final List<Tape> tapes = new ArrayList<>();
    public final List<Tag> tags = new ArrayList<>();
    public final List<Mover> movers = new ArrayList<>();
    public final List<Area> areas = new ArrayList<>();
    /** Colors by name ("red", "purple"...), for tape, structures and game pieces. */
    public final Map<String, Integer> palette = new LinkedHashMap<>();

    private static final Map<String, Integer> DEFAULT_PALETTE = Map.ofEntries(
            Map.entry("red", 0xd23a3f), Map.entry("blue", 0x2f6fe0), Map.entry("white", 0xe8e8e8),
            Map.entry("yellow", 0xf4d23c), Map.entry("black", 0x1a1a1a), Map.entry("aluminum", 0xb9c0c8),
            Map.entry("steel", 0x8d949c), Map.entry("gray", 0x5b6169));

    /** Reads the shapes listed in season.json; the season's code adds its own afterwards. */
    public FieldModel(Season season, JsonObject json) {
        this.season = season;
        this.json = json;
        JsonObject structure = json.has("structure") ? json.getAsJsonObject("structure") : new JsonObject();
        this.half = json.getAsJsonArray("field_m").get(0).getAsDouble() / 2;
        this.wallHeight = json.has("wall_height_m") ? json.get("wall_height_m").getAsDouble() : 0.305;
        this.tile = json.has("tile_m") ? json.get("tile_m").getAsDouble() : 0.6096;
        this.tapeWidth = json.has("tape_m") ? json.get("tape_m").getAsDouble()
                : structure.has("tape_m") ? structure.get("tape_m").getAsDouble() : 0.0254;
        palette.putAll(DEFAULT_PALETTE);
        if (json.has("palette")) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("palette").entrySet()) {
                palette.put(e.getKey(), parseColor(e.getValue().getAsString()));
            }
        }
        if (json.has("tape")) for (JsonElement e : json.getAsJsonArray("tape")) addTapeJson(e.getAsJsonObject());
        if (json.has("structures")) {
            for (JsonElement e : json.getAsJsonArray("structures")) addSolidJson(solids, e.getAsJsonObject());
        }
        if (json.has("field_tags")) {
            JsonObject t = json.getAsJsonObject("field_tags");
            double size = t.get("size_m").getAsDouble();
            for (Map.Entry<String, JsonElement> e : t.getAsJsonObject("tags").entrySet()) {
                tags.add(tag(Integer.parseInt(e.getKey()), e.getValue().getAsJsonObject(), size));
            }
        }
        if (json.has("movers")) {
            for (JsonElement e : json.getAsJsonArray("movers")) {
                JsonObject o = e.getAsJsonObject();
                Mover m = moverJson(o);
                movers.add(m);
                if (o.has("mirror")) movers.add(mirror(m, mirrorAxis(o), o));
            }
        }
        if (json.has("alliance_areas")) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("alliance_areas").entrySet()) {
                JsonObject a = e.getValue().getAsJsonObject();
                int rgb = a.has("color") ? color(a.get("color").getAsString())
                        : "red".equals(e.getKey()) ? 0x4a1c1f : "blue".equals(e.getKey()) ? 0x1b2a52 : 0x2a2d31;
                areas.add(new Area(e.getKey(), num(a, "x_m", 0), num(a, "x_m", 1), num(a, "y_m", 0), num(a, "y_m", 1), rgb));
            }
        }
    }

    /** The BIOBUZZ field (FTC 2026-27). */
    public static FieldModel loadBiobuzz() throws IOException {
        return Season.load("biobuzz").field;
    }

    // ---- building (season.json and season code) ---------------------------------------------------

    /** A color by palette name or "#rrggbb". */
    public int color(String name) {
        if (name.startsWith("#")) return parseColor(name);
        Integer c = palette.get(name);
        if (c == null) throw new IllegalArgumentException("unknown color \"" + name + "\" (add it to season.json \"palette\")");
        return c;
    }

    private static int parseColor(String s) {
        return Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16);
    }

    /**
     * A shape from season.json:
     * <pre>
     *   {"box_m": [sx, sy, sz], "at_m": [x, y, z], "yaw_deg": 30}           (or "rot": row-major 3x3)
     *   {"beam_m": {"from": [x, y, z], "to": [x, y, z], "width": 0.04}}     (a square bar)
     *   {"cylinder_m": {"from": [...], "to": [...], "radius": 0.07}}
     *   {"cylinder_m": {"radius": 0.07, "height": 0.5}, "at_m": [x, y, 0]}   (upright, standing at z)
     *   {"prism_m": {"outline": [[x, y], ...], "length": 0.01}, "at_m": [...], "roll_deg": 90}
     *                                         (a convex polygon made thick; drawn only)
     * </pre>
     * plus "name", "color", "opacity", "collide" (default true), "visible" (default true) and
     * "friction" (default 0.3).
     */
    public Solid solid(JsonObject o) {
        String name = o.has("name") ? o.get("name").getAsString() : "structure";
        int rgb = o.has("color") ? color(o.get("color").getAsString()) : 0x8d949c;
        double opacity = o.has("opacity") ? o.get("opacity").getAsDouble() : 1;
        boolean collide = !o.has("collide") || o.get("collide").getAsBoolean();
        boolean visible = !o.has("visible") || o.get("visible").getAsBoolean();
        double friction = o.has("friction") ? o.get("friction").getAsDouble() : 0.3;
        if (o.has("box_m") || o.has("prism_m")) {
            double[] at = vec(o.getAsJsonArray("at_m"));
            double[] rot = o.has("rot") ? vec9(o.getAsJsonArray("rot"))
                    : o.has("yaw_deg") ? rotZ(Math.toRadians(o.get("yaw_deg").getAsDouble())) : IDENTITY;
            if (o.has("pitch_deg")) rot = mul3(rot, rotY(Math.toRadians(o.get("pitch_deg").getAsDouble())));
            if (o.has("roll_deg")) rot = mul3(rot, rotX(Math.toRadians(o.get("roll_deg").getAsDouble())));
            if (o.has("box_m")) return new Solid(name, Shape.BOX, at, vec(o.getAsJsonArray("box_m")), rot, rgb, opacity, collide, visible, friction, null);
            JsonObject p = o.getAsJsonObject("prism_m");
            JsonArray pts = p.getAsJsonArray("outline");
            double[] outline = new double[pts.size() * 2];
            for (int i = 0; i < pts.size(); i++) {
                outline[2 * i] = pts.get(i).getAsJsonArray().get(0).getAsDouble();
                outline[2 * i + 1] = pts.get(i).getAsJsonArray().get(1).getAsDouble();
            }
            return new Solid(name, Shape.PRISM, at, new double[]{p.get("length").getAsDouble()}, rot, rgb, opacity,
                    o.has("collide") && o.get("collide").getAsBoolean(), visible, friction, outline);
        }
        if (o.has("beam_m")) {
            JsonObject b = o.getAsJsonObject("beam_m");
            double w = b.get("width").getAsDouble();
            return beam(name, vec(b.getAsJsonArray("from")), vec(b.getAsJsonArray("to")), w,
                    b.has("height") ? b.get("height").getAsDouble() : w, rgb, opacity, collide, visible, friction);
        }
        if (o.has("cylinder_m")) {
            JsonObject c = o.getAsJsonObject("cylinder_m");
            double r = c.get("radius").getAsDouble();
            double[] a, b;
            if (c.has("from")) {
                a = vec(c.getAsJsonArray("from"));
                b = vec(c.getAsJsonArray("to"));
            } else {
                double[] at = vec(o.getAsJsonArray("at_m"));
                a = at;
                b = new double[]{at[0], at[1], at[2] + c.get("height").getAsDouble()};
            }
            return cylinder(name, a, b, r, rgb, opacity, collide, visible, friction);
        }
        throw new IllegalArgumentException("structure \"" + name + "\" needs box_m, beam_m, cylinder_m or prism_m");
    }

    private void addSolidJson(List<Solid> into, JsonObject o) {
        Solid s = solid(o);
        into.add(s);
        if (o.has("mirror")) into.add(mirror(s, mirrorAxis(o)));
    }

    /** A box between two points, {@code w} wide and {@code h} high across its length. */
    public static Solid beam(String name, double[] a, double[] b, double w, double h, int rgb, double opacity,
                             boolean collide, boolean visible, double friction) {
        double[] d = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        return new Solid(name, Shape.BOX, mid(a, b), new double[]{w, h, len}, frameAlong(d), rgb, opacity, collide, visible, friction, null);
    }

    /** A cylinder from {@code a} to {@code b}. */
    public static Solid cylinder(String name, double[] a, double[] b, double r, int rgb, double opacity,
                                 boolean collide, boolean visible, double friction) {
        double[] d = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        return new Solid(name, Shape.CYLINDER, mid(a, b), new double[]{r, len}, frameAlong(d), rgb, opacity, collide, visible, friction, null);
    }

    /** An upright or tilted box, axis-aligned when {@code rot} is null. */
    public static Solid box(String name, double[] center, double[] size, double[] rot, int rgb, double opacity,
                            boolean collide, boolean visible, double friction) {
        return new Solid(name, Shape.BOX, center, size, rot == null ? IDENTITY : rot, rgb, opacity, collide, visible, friction, null);
    }

    /**
     * A convex polygon ({@code outline}: x, y pairs in its own x-y plane) made {@code length}
     * thick along its own z, centered on {@code center}; drawn only.
     */
    public static Solid prism(String name, double[] center, double[] outline, double length, double[] rot, int rgb, double opacity) {
        return new Solid(name, Shape.PRISM, center, new double[]{length}, rot == null ? IDENTITY : rot, rgb, opacity, false, true, 0, outline);
    }

    /**
     * Tape from season.json:
     * <pre>
     *   {"rect_m": [x0, x1, y0, y1], "fill": false}   tape along the inside of the rectangle's edges
     *   {"rect_m": [x0, x1, y0, y1], "fill": true}    a solid area
     *   {"line_m": [[x, y], [x, y], ...]}             tape centered on the line (a polyline)
     *   {"polygon_m": [[x, y], ...], "fill": true}    a solid polygon (or its outline, centered on the edges)
     * </pre>
     * plus "name", "color" and "width_m" (default: the season's tape width).
     */
    private void addTapeJson(JsonObject o) {
        String name = o.has("name") ? o.get("name").getAsString() : "tape";
        String color = o.has("color") ? o.get("color").getAsString() : "white";
        double w = o.has("width_m") ? o.get("width_m").getAsDouble() : tapeWidth;
        boolean fill = o.has("fill") && o.get("fill").getAsBoolean();
        List<Tape> added = new ArrayList<>();
        if (o.has("rect_m")) {
            JsonArray r = o.getAsJsonArray("rect_m");
            double x0 = r.get(0).getAsDouble(), x1 = r.get(1).getAsDouble(), y0 = r.get(2).getAsDouble(), y1 = r.get(3).getAsDouble();
            if (fill) added.add(area(name, new double[]{x0, y0, x1, y0, x1, y1, x0, y1}, color));
            else added.addAll(rectOutline(name, x0, x1, y0, y1, w, color));
        } else if (o.has("line_m") || o.has("polygon_m")) {
            boolean polygon = o.has("polygon_m");
            JsonArray pts = o.getAsJsonArray(polygon ? "polygon_m" : "line_m");
            double[] p = new double[pts.size() * 2];
            for (int i = 0; i < pts.size(); i++) {
                p[2 * i] = pts.get(i).getAsJsonArray().get(0).getAsDouble();
                p[2 * i + 1] = pts.get(i).getAsJsonArray().get(1).getAsDouble();
            }
            if (polygon && fill) {
                added.add(area(name, p, color));
            } else {
                int n = pts.size(), segs = polygon ? n : n - 1;
                for (int i = 0; i < segs; i++) {
                    int j = (i + 1) % n;
                    added.add(line(name, p[2 * i], p[2 * i + 1], p[2 * j], p[2 * j + 1], w, color));
                }
            }
        } else {
            throw new IllegalArgumentException("tape \"" + name + "\" needs rect_m, line_m or polygon_m");
        }
        tapes.addAll(added);
        if (o.has("mirror")) {
            String axis = mirrorAxis(o);
            for (Tape t : added) tapes.add(mirror(t, axis));
        }
    }

    public Tape line(String name, double x0, double y0, double x1, double y1, double width, String color) {
        return new Tape(name, Tape.Kind.LINE, new double[]{x0, y0, x1, y1}, width, color, color(color));
    }

    public Tape area(String name, double[] xy, String color) {
        return new Tape(name, Tape.Kind.AREA, xy, 0, color, color(color));
    }

    /** Tape along the inside of a rectangle's four edges. */
    public List<Tape> rectOutline(String name, double x0, double x1, double y0, double y1, double w, String color) {
        double h = w / 2;
        return List.of(line(name, x0, y0 + h, x1, y0 + h, w, color), line(name, x0, y1 - h, x1, y1 - h, w, color),
                line(name, x0 + h, y0, x0 + h, y1, w, color), line(name, x1 - h, y0, x1 - h, y1, w, color));
    }

    private Tag tag(int id, JsonObject t, double size) {
        double[] normal = vec(t.getAsJsonArray("normal"));
        double[] up = t.has("up") ? vec(t.getAsJsonArray("up")) : new double[]{0, 0, 1};
        double[] right = t.has("right") ? vec(t.getAsJsonArray("right")) : cross(up, normal);
        return new Tag(id, vec(t.getAsJsonArray("center_m")), right, up, normal, t.has("size_m") ? t.get("size_m").getAsDouble() : size);
    }

    /**
     * A mover from season.json: "pivot_m", "axis", "start_rad", "limits_rad" [min, max], "parts"
     * (shapes in its frame), "tags" (id: center_m, normal, up in its frame), and for a dynamic one
     * "mass_kg", "com_m", "inertia_kgm2" [ixx, iyy, izz] and "damping_nms". Without "mass_kg"
     * the mover is kinematic.
     */
    private Mover moverJson(JsonObject o) {
        String name = o.get("name").getAsString();
        List<Solid> parts = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("parts")) parts.add(solid(e.getAsJsonObject()));
        List<Tag> tagList = new ArrayList<>();
        if (o.has("tags")) {
            double size = o.has("tag_size_m") ? o.get("tag_size_m").getAsDouble() : 0.1651;
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("tags").entrySet()) {
                tagList.add(tag(Integer.parseInt(e.getKey()), e.getValue().getAsJsonObject(), size));
            }
        }
        double start = o.has("start_rad") ? o.get("start_rad").getAsDouble() : 0;
        double lo = o.has("limits_rad") ? o.getAsJsonArray("limits_rad").get(0).getAsDouble() : -Math.PI;
        double hi = o.has("limits_rad") ? o.getAsJsonArray("limits_rad").get(1).getAsDouble() : Math.PI;
        boolean dynamic = o.has("mass_kg");
        double mass = dynamic ? o.get("mass_kg").getAsDouble() : 0;
        double[] com = o.has("com_m") ? vec(o.getAsJsonArray("com_m")) : new double[3];
        double[] inertia = o.has("inertia_kgm2") ? vec(o.getAsJsonArray("inertia_kgm2")) : new double[]{1e-3, 1e-3, 1e-3};
        double damping = o.has("damping_nms") ? o.get("damping_nms").getAsDouble() : 0;
        return new Mover(name, vec(o.getAsJsonArray("pivot_m")), normalize(vec(o.getAsJsonArray("axis"))), start, lo, hi,
                dynamic, mass, com, inertia, damping, parts, tagList);
    }

    // ---- mirroring: "mirror": "y" adds a copy reflected across y = 0 (x = 0 for "x"), red <-> blue ----

    private static String mirrorAxis(JsonObject o) {
        String a = o.get("mirror").getAsString();
        if (!"x".equals(a) && !"y".equals(a)) {
            throw new IllegalArgumentException("\"mirror\" is \"x\" or \"y\" (the line the copy is reflected across), not \"" + a + "\"");
        }
        return a;
    }

    private static double[] flip(String axis) {
        return "x".equals(axis) ? new double[]{-1, 1, 1} : new double[]{1, -1, 1};
    }

    /** A shape reflected across the line x = 0 or y = 0, its alliance color swapped. */
    private Solid mirror(Solid s, String axis) {
        double[] f = flip(axis);
        // R' = F R F keeps a proper rotation; the shape is symmetric about its own planes.
        double[] r = s.rot().clone();
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[3 * i + j] = f[i] * s.rot()[3 * i + j] * f[j];
        int rgb = s.rgb() == palette.get("red") ? palette.get("blue") : s.rgb() == palette.get("blue") ? palette.get("red") : s.rgb();
        double[] outline = s.outline() == null ? null : s.outline().clone();
        if (outline != null) for (int i = 0; i < outline.length; i++) outline[i] *= f[i % 2];   // its own x, y by the same reflection
        return new Solid(swapAlliance(s.name()), s.shape(), mulDiag(f, s.center()), s.size(), r, rgb, s.opacity(),
                s.collide(), s.visible(), s.friction(), outline);
    }

    private Tape mirror(Tape t, String axis) {
        double[] pts = t.points().clone();
        for (int i = "x".equals(axis) ? 0 : 1; i < pts.length; i += 2) pts[i] = -pts[i];
        String c = "red".equals(t.color()) ? "blue" : "blue".equals(t.color()) ? "red" : t.color();
        return new Tape(swapAlliance(t.name()), t.kind(), pts, t.width(), c, color(c));
    }

    private Mover mirror(Mover m, String axis, JsonObject o) {
        String name = swapAlliance(m.name());
        if (name.equals(m.name())) {
            throw new IllegalArgumentException("mover \"" + m.name() + "\" is mirrored: put red or blue in its name, so the copy gets a name of its own");
        }
        double[] f = flip(axis);
        List<Solid> parts = new ArrayList<>();
        for (Solid s : m.parts()) parts.add(mirror(s, axis));
        // "mirror_tag_ids": {"24": 20} gives the copy's tags their own ids.
        Map<String, Integer> ids = new LinkedHashMap<>();
        if (o.has("mirror_tag_ids")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("mirror_tag_ids").entrySet()) ids.put(e.getKey(), e.getValue().getAsInt());
        }
        List<Tag> tagList = new ArrayList<>();
        for (Tag t : m.tags()) {
            // A mirror image of a tag reads backwards: keep its up and normal, recompute right.
            double[] up = mulDiag(f, t.up()), normal = mulDiag(f, t.normal());
            tagList.add(new Tag(ids.getOrDefault(String.valueOf(t.id()), t.id()), mulDiag(f, t.center()), cross(up, normal), up, normal, t.size()));
        }
        // Reflecting reverses turning: the copy turns about the reflected axis, reversed.
        double[] a = mulDiag(f, m.axis());
        return new Mover(name, mulDiag(f, m.pivot()), new double[]{-a[0], -a[1], -a[2]}, m.startAngle(), m.minAngle(), m.maxAngle(),
                m.dynamic(), m.mass(), mulDiag(f, m.com()), m.inertia(), m.damping(), parts, tagList);
    }

    private static final java.util.regex.Pattern ALLIANCE_WORD = java.util.regex.Pattern.compile("\\b(red|blue|Red|Blue|RED|BLUE)\\b");

    /** "red GATE" to "blue GATE" and back, whole words only ("shared" stays). */
    private static String swapAlliance(String s) {
        return ALLIANCE_WORD.matcher(s).replaceAll(r -> switch (r.group()) {
            case "red" -> "blue";
            case "blue" -> "red";
            case "Red" -> "Blue";
            case "Blue" -> "Red";
            case "RED" -> "BLUE";
            default -> "RED";
        });
    }

    // ---- queries ---------------------------------------------------------------------------------

    /** The tape at a point, or null (the last one listed wins where tapes overlap). */
    public Tape tapeAt(double x, double y) {
        for (int i = tapes.size() - 1; i >= 0; i--) if (tapes.get(i).covers(x, y)) return tapes.get(i);
        return null;
    }

    /**
     * Floor color under a point, as normalized RGBA reflectance (what a downward color sensor with
     * its LED on reads at gain 1). Gray foam tiles and colored tape.
     */
    public float[] floorColor(double x, double y) {
        if (Math.abs(x) > half || Math.abs(y) > half) return new float[]{0.02f, 0.02f, 0.02f, 0.02f};
        Tape t = tapeAt(x, y);
        if (t != null) return reflectance(t.color(), t.rgb());
        // Tile seams are slightly darker.
        double fx = Math.abs(((x + half) % tile)), fy = Math.abs(((y + half) % tile));
        boolean seam = fx < 0.004 || tile - fx < 0.004 || fy < 0.004 || tile - fy < 0.004;
        float v = seam ? 0.025f : 0.045f;
        return new float[]{v, v * 1.05f, v * 1.1f, v * 1.05f};
    }

    private static float[] reflectance(String name, int rgb) {
        return switch (name) {
            case "red" -> new float[]{0.24f, 0.045f, 0.05f, 0.11f};
            case "blue" -> new float[]{0.035f, 0.07f, 0.26f, 0.12f};
            case "white" -> new float[]{0.30f, 0.31f, 0.32f, 0.31f};
            default -> {
                float r = ((rgb >> 16) & 255) / 255f * 0.3f, g = ((rgb >> 8) & 255) / 255f * 0.3f, b = (rgb & 255) / 255f * 0.3f;
                yield new float[]{r, g, b, (r + g + b) / 3};
            }
        };
    }

    /**
     * The field for the browser's 3D view: everything drawn, as plain numbers. Movers are drawn
     * in their own frame and turned by the angles in the state stream.
     */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("half", half);
        m.put("tile", tile);
        m.put("wallHeight", wallHeight);
        List<Object> tapeList = new ArrayList<>();
        for (Tape t : tapes) tapeList.add(Map.of("kind", t.kind().name().toLowerCase(), "points", t.points(), "width", t.width(), "rgb", t.rgb()));
        m.put("tape", tapeList);
        m.put("solids", describe(solids));
        List<Object> tagList = new ArrayList<>();
        for (Tag t : tags) tagList.add(describe(t));
        m.put("tags", tagList);
        List<Object> moverList = new ArrayList<>();
        for (Mover mv : movers) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", mv.name());
            o.put("pivot", mv.pivot());
            o.put("axis", mv.axis());
            o.put("start", mv.startAngle());
            o.put("solids", describe(mv.parts()));
            List<Object> mt = new ArrayList<>();
            for (Tag t : mv.tags()) mt.add(describe(t));
            o.put("tags", mt);
            moverList.add(o);
        }
        m.put("movers", moverList);
        List<Object> areaList = new ArrayList<>();
        for (Area a : areas) areaList.add(Map.of("name", a.name(), "x", new double[]{a.x0(), a.x1()}, "y", new double[]{a.y0(), a.y1()}, "rgb", a.rgb()));
        m.put("areas", areaList);
        return m;
    }

    private static List<Object> describe(List<Solid> list) {
        List<Object> out = new ArrayList<>();
        for (Solid s : list) {
            if (!s.visible()) continue;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("shape", s.shape().name().toLowerCase());
            o.put("c", s.center());
            o.put("size", s.size());
            o.put("rot", s.rot());
            o.put("rgb", s.rgb());
            if (s.opacity() < 1) o.put("opacity", s.opacity());
            if (s.outline() != null) o.put("outline", s.outline());
            out.add(o);
        }
        return out;
    }

    private static Map<String, Object> describe(Tag t) {
        return Map.of("id", t.id(), "c", t.center(), "right", t.right(), "up", t.up(), "normal", t.normal(), "size", t.size());
    }

    // ---- small math ----------------------------------------------------------------------------

    public static final double[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    public static double[] rotX(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{1, 0, 0, 0, c, -s, 0, s, c};
    }

    public static double[] rotY(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, 0, s, 0, 1, 0, -s, 0, c};
    }

    public static double[] rotZ(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, -s, 0, s, c, 0, 0, 0, 1};
    }

    /** Rotation by {@code a} about a unit axis (exact for the x, y and z axes). */
    public static double[] axisAngle(double[] u, double a) {
        if (u[1] == 0 && u[2] == 0) return rotX(u[0] > 0 ? a : -a);
        if (u[0] == 0 && u[2] == 0) return rotY(u[1] > 0 ? a : -a);
        if (u[0] == 0 && u[1] == 0) return rotZ(u[2] > 0 ? a : -a);
        double c = Math.cos(a), s = Math.sin(a), t = 1 - c, x = u[0], y = u[1], z = u[2];
        return new double[]{t * x * x + c, t * x * y - s * z, t * x * z + s * y,
                t * x * y + s * z, t * y * y + c, t * y * z - s * x,
                t * x * z - s * y, t * y * z + s * x, t * z * z + c};
    }

    /** A rotation whose z axis points along {@code d} (the same frame the webcam renderer uses for bars). */
    static double[] frameAlong(double[] d) {
        double[] z = normalize(d);
        double[] helper = Math.abs(z[2]) < 0.9 ? new double[]{0, 0, 1} : new double[]{1, 0, 0};
        double[] x = normalize(cross(helper, z));
        double[] y = cross(z, x);
        return new double[]{x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2]};
    }

    public static double[] mul(double[] m, double[] v) {
        return new double[]{m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    public static double[] mul3(double[] a, double[] b) {
        double[] out = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[3 * i + j] = a[3 * i] * b[j] + a[3 * i + 1] * b[3 + j] + a[3 * i + 2] * b[6 + j];
            }
        }
        return out;
    }

    static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] mid(double[] a, double[] b) {
        return new double[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2};
    }

    private static double[] mulDiag(double[] f, double[] v) {
        return new double[]{f[0] * v[0], f[1] * v[1], f[2] * v[2]};
    }

    public static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    public static double[] normalize(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }

    public static double[] vec(JsonArray a) {
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    private static double[] vec9(JsonArray a) {
        double[] out = new double[9];
        for (int i = 0; i < 9; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }

    private static double num(JsonObject o, String key, int i) {
        return o.getAsJsonArray(key).get(i).getAsDouble();
    }
}
