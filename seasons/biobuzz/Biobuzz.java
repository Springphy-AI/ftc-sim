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
 * tape, the sign board, the FLOWERS (open tubes the POLLEN stack in), and the two HIVES that tip
 * on their pivots.
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

        // FLOWERS stand against the walls: open tubes the POLLEN stack in.
        JsonObject top = structure.has("flower_top") ? structure.getAsJsonObject("flower_top") : null;
        double petals = top != null && top.has("radius_m") ? top.get("radius_m").getAsDouble() : 0.095;
        JsonObject flowerJson = json.getAsJsonObject("flowers");
        for (Flower f : Flower.of(json)) addFlower(field, f, flowerJson.getAsJsonObject(f.name()), petals);

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

    /** Thickness of the FLOWER's tube walls as modeled. */
    private static final double FLOWER_WALL_M = 0.008;
    /** Lower ring (Competition Manual 9.7): 1.0 cm tall, 7.1 cm hole for the bottom POLLEN. */
    private static final double LOWER_RING_TOP_M = 0.010, LOWER_RING_HOLE_M = 0.0355, LOWER_RING_OUTER_M = 0.07;
    /**
     * Middle ring at the top of the Retrieval Opening: 1.5 cm tall, with an opening between a POLLEN
     * (7.1 cm) and a NECTAR (9.1 cm). POLLEN drop through to the lower ring; a NECTAR put in the top
     * comes to rest on the middle ring and cannot come out of the bottom, as on the field. The exact
     * size is not in the manual's text. 8.8 cm: at 8.2 cm a POLLEN pressed off-center by the one
     * above it could wedge on the ring's edge and stop the stack.
     */
    private static final double MIDDLE_RING_HOLE_M = 0.044, MIDDLE_RING_HEIGHT_M = 0.015, FUNNEL_HEIGHT_M = 0.02;
    /** The square extrusion joining the middle and lower rings on the wall side. */
    private static final double EXTRUSION_M = 0.0254;
    private static final int FLOWER_SEGMENTS = 16;
    private static final double FLOWER_FRICTION = 0.3;

    /**
     * One FLOWER (Competition Manual 9.7), in a frame on its axis with +x pointing into the field
     * and -x toward the wall:
     * <ul>
     *   <li>the lower ring on the tiles, with a 7.1 cm hole the bottom POLLEN sits in;</li>
     *   <li>the Retrieval Opening above it ("retrieval_opening_m"), open except for the square
     *       extrusion on the wall side;</li>
     *   <li>the middle ring, which lets POLLEN through but stops NECTAR, with a funnel on top so a
     *       POLLEN coming down off-center slides to the opening;</li>
     *   <li>the tube from the middle ring up to the top, as wide as the 10.15 cm top opening. On the
     *       field the middle and upper rings are joined by four pipes; here the sides are closed so
     *       balls stack inside as they do on the field. It is drawn faint, with the four pipes;</li>
     *   <li>the 3.15 cm backstop on the wall side of the top opening, and the gold petal ring.</li>
     * </ul>
     * A FLOWER's rings are thin, and a chassis taller than the Retrieval Opening could slide under
     * them. On the field the FLOWER's frame stops the robot; an invisible square post around each
     * one (a robot stop) does that here. It stops only the chassis: balls and the intake roller, low
     * enough to reach into the Retrieval Opening, pass it.
     */
    private static void addFlower(FieldModel field, Flower f, JsonObject json, double petals) {
        String n = f.name();
        double inner = f.innerRadius(), h = f.height(), rTop = f.retrievalTop(), t = FLOWER_WALL_M, full = 2 * Math.PI;
        double back = json.get("backstop_height_m").getAsDouble();
        List<FieldModel.Solid> s = field.solids;
        flowerRing(s, f, n + " lower ring", LOWER_RING_HOLE_M, LOWER_RING_OUTER_M - LOWER_RING_HOLE_M, 0, LOWER_RING_TOP_M,
                0, full, FLOWER_SEGMENTS, FLOWER_BASE, 1, true, true);
        s.add(flowerBox(f, n + " extrusion", -(inner + EXTRUSION_M / 2), 0, (LOWER_RING_TOP_M + rTop) / 2,
                EXTRUSION_M, EXTRUSION_M, rTop - LOWER_RING_TOP_M, 0, FLOWER, 1, true, true));
        double middleTop = rTop + MIDDLE_RING_HEIGHT_M;
        flowerRing(s, f, n + " middle ring", MIDDLE_RING_HOLE_M, inner + t - MIDDLE_RING_HOLE_M, rTop, middleTop,
                0, full, FLOWER_SEGMENTS, FLOWER, 1, true, true);
        flowerFunnel(s, f, n + " funnel", MIDDLE_RING_HOLE_M, inner, middleTop, middleTop + FUNNEL_HEIGHT_M);
        flowerRing(s, f, n + " tube", inner, t, middleTop, h, 0, full, FLOWER_SEGMENTS, FLOWER, 0.18, true, true);
        flowerRing(s, f, n + " backstop", inner, 0.006, h, h + back, Math.PI / 2, 3 * Math.PI / 2, FLOWER_SEGMENTS / 2,
                FLOWER, 1, true, true);
        // Drawn only: the four pipes, the top ring and the gold petals.
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2, r = inner + t;
            double[] p = f.toWorld(Math.cos(a) * r, Math.sin(a) * r, 0);
            s.add(FieldModel.cylinder(n + " pipe", new double[]{p[0], p[1], rTop}, new double[]{p[0], p[1], h}, 0.008,
                    FLOWER, 1, false, true, FLOWER_FRICTION));
        }
        flowerRing(s, f, n + " top ring", inner, t, h - 0.01, h, 0, full, FLOWER_SEGMENTS, FLOWER, 1, false, true);
        ring(s, n + " petals", f.x(), f.y(), h, petals, 0.024, 20, GOLD);
        // The robot stop: square, facing the field (box-box contacts are reliable).
        double z0 = 0.03, side = 2 * (inner + t);
        double[] center = {f.x(), f.y(), z0 + (h - z0) / 2};
        field.robotStops.add(FieldModel.box(n + " frame", center, new double[]{side, side, h - z0}, rotZ(f.yaw()),
                FLOWER, 1, true, false, 0.25));
    }

    /** A ring (or an arc from a0 to a1) of boxes around a FLOWER's axis: inner radius r, radial thickness, from z0 to z1. */
    private static void flowerRing(List<FieldModel.Solid> into, Flower f, String name, double r, double thick, double z0, double z1,
                                   double a0, double a1, int n, int rgb, double opacity, boolean collide, boolean visible) {
        double span = (a1 - a0) / n, rc = r + thick / 2, w = 2 * (r + thick) * Math.tan(span / 2) + 0.002;
        for (int k = 0; k < n; k++) {
            double a = a0 + (k + 0.5) * span;
            into.add(flowerBox(f, name, rc * Math.cos(a), rc * Math.sin(a), (z0 + z1) / 2, thick, w, z1 - z0, a,
                    rgb, opacity, collide, visible));
        }
    }

    /**
     * A funnel on top of the middle ring, from the tube wall down to the ring's opening, so a POLLEN
     * coming down off-center slides to the opening instead of catching on the ring's flat top.
     * Collides only: it sits inside the faint tube.
     */
    private static void flowerFunnel(List<FieldModel.Solid> into, Flower f, String name, double rIn, double rOut, double z0, double z1) {
        double run = rOut - rIn, rise = z1 - z0, len = Math.hypot(run, rise), t = 0.006;
        double nx = -rise / len, nz = run / len;                 // surface normal: up and inward
        double rc = (rIn + rOut) / 2 - nx * t / 2, zc = (z0 + z1) / 2 - nz * t / 2;
        double tilt = -Math.atan2(rise, run), span = 2 * Math.PI / FLOWER_SEGMENTS;
        double w = 2 * rOut * Math.tan(span / 2) + 0.002;
        for (int k = 0; k < FLOWER_SEGMENTS; k++) {
            double a = (k + 0.5) * span;
            double[] p = f.toWorld(rc * Math.cos(a), rc * Math.sin(a), zc);
            into.add(FieldModel.box(name, p, new double[]{len, w, t}, mul(rotZ(f.yaw() + a), rotY(tilt)),
                    FLOWER, 1, true, false, FLOWER_FRICTION));
        }
    }

    /** A box in the FLOWER's frame, turned by {@code localYaw} about its own vertical axis. */
    private static FieldModel.Solid flowerBox(Flower f, String name, double lx, double ly, double z, double sx, double sy, double sz,
                                              double localYaw, int rgb, double opacity, boolean collide, boolean visible) {
        return FieldModel.box(name, f.toWorld(lx, ly, z), new double[]{sx, sy, sz}, rotZ(f.yaw() + localYaw),
                rgb, opacity, collide, visible, FLOWER_FRICTION);
    }

    private static double[] rotZ(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, -s, 0, s, c, 0, 0, 0, 1};
    }

    private static double[] rotY(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, 0, s, 0, 1, 0, -s, 0, c};
    }

    private static double[] mul(double[] a, double[] b) {
        double[] o = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) o[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        }
        return o;
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
