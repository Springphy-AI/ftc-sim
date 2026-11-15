package ftcsim.vision;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.ode4j.math.DMatrix3C;
import org.ode4j.math.DVector3C;

import ftcsim.config.RobotConfig;
import ftcsim.physics.FieldModel;
import ftcsim.sim.GameElements;
import ftcsim.sim.RobotRig;

/**
 * Everything the simulated webcams can see. The physics thread records where the robots and
 * balls are ({@link #update}); camera threads render the latest record from their own pose.
 */
public final class CameraWorld {

    /** Rendering at this many samples per pixel side, then averaging, gives smooth edges. */
    static final int SUPERSAMPLE = 2;
    private static final int BACKGROUND = 0x2a2d31;
    private static final Map<String, Integer> BALL_COLORS = Map.of("yellow", 0xf4d23c, "red", 0xd8343a, "blue", 0x2f6fe0);

    /** One robot at one moment. {@code rot} is row-major world-from-robot; mounts are relative to the floor under it. */
    record RobotView(double[] pos, double[] rot, double chassisZ, RobotConfig config) {
    }

    /**
     * Where everything was at {@code nanos} ({@link System#nanoTime()}). {@code hiveTilts}: each
     * HIVE's tilt about its axle (rad, field JSON order), or null for their starting tilts.
     */
    record Snapshot(long nanos, RobotView[] robots, double[] balls, double[] radii, int[] colors, double[] hiveTilts) {
    }

    private final FieldScene field;
    private volatile Snapshot latest;
    /** The last second or so of snapshots, every 2 ms, for cameras that see the world with a delay. */
    private final Snapshot[] history = new Snapshot[512];
    private int historyHead;
    private long lastHistoryNanos;
    /** Cameras that captured lately want snapshots until then (System.nanoTime()). */
    private volatile long recordUntil;
    // physics thread only
    private boolean historyStale;
    private List<GameElements.Ball> ballsSeen;
    private double[] ballRadii = new double[0];
    private int[] ballColors = new int[0];
    private final Map<RobotConfig, List<double[]>> robotMeshes = new IdentityHashMap<>();
    private final ThreadLocal<Rasterizer> rasterizers = new ThreadLocal<>();

    public CameraWorld(FieldModel field) {
        this.field = new FieldScene(field);
    }

    /** The AprilTags printed on the field (world coordinates). */
    public List<FieldTag> tags() {
        return field.tags;
    }

    /** The AprilTags with the HIVES at these tilts (rad, field JSON order: red first). */
    public List<FieldTag> tags(double[] hiveTilts) {
        List<FieldTag> out = new ArrayList<>();
        for (int i = 0; i < field.hives.size(); i++) {
            FieldScene.Hive h = field.hives.get(i);
            double tilt = hiveTilts != null && i < hiveTilts.length ? hiveTilts[i] : h.startTilt;
            for (FieldTag t : h.localTags) out.add(h.tagAt(t, tilt));
        }
        return out;
    }

    /** A webcam on robot {@code robot}, mounted as {@code device} says. */
    public SimCamera camera(int robot, RobotConfig.Device device) {
        return new SimCamera(this, robot, device);
    }

    // ---- physics thread ------------------------------------------------------------------------

    /** Records the robots and balls; call on the physics thread after each step. */
    public void update(List<RobotRig> rigs, GameElements elements) {
        update(rigs, elements, null);
    }

    /**
     * Records the robots, balls and HIVE tilts; call on the physics thread after each step.
     *
     * @param hiveTilts each HIVE's tilt about its axle (rad, field JSON order: red first), or
     *                  null while the HIVES stay at their starting tilt
     */
    public void update(List<RobotRig> rigs, GameElements elements, double[] hiveTilts) {
        if (latest != null && System.nanoTime() - recordUntil > 0) {   // no camera is looking
            historyStale = true;
            return;
        }
        RobotView[] robots = new RobotView[rigs.size()];
        for (int i = 0; i < robots.length; i++) {
            RobotRig rig = rigs.get(i);
            DVector3C p = rig.body.position();
            DMatrix3C r = rig.body.rotation();
            robots[i] = new RobotView(new double[]{p.get0(), p.get1(), p.get2()},
                    new double[]{r.get00(), r.get01(), r.get02(), r.get10(), r.get11(), r.get12(), r.get20(), r.get21(), r.get22()},
                    rig.body.chassisRestZ(), rig.config);
        }
        double[] balls = new double[0];
        if (elements != null) {
            balls = elements.positions();
            List<GameElements.Ball> list = elements.balls();
            if (list != ballsSeen || ballRadii.length != list.size()) {   // sizes and colors never change
                double[] radii = new double[list.size()];
                int[] colors = new int[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    radii[i] = list.get(i).radius;
                    colors[i] = BALL_COLORS.getOrDefault(list.get(i).color, 0xffffff);
                }
                ballsSeen = list;
                ballRadii = radii;
                ballColors = colors;
            }
        }
        Snapshot snap = new Snapshot(System.nanoTime(), robots, balls, elements != null ? ballRadii : new double[0],
                elements != null ? ballColors : new int[0], hiveTilts == null ? null : hiveTilts.clone());
        synchronized (history) {
            if (historyStale) {   // recording paused: what is kept is from before, not a second ago
                java.util.Arrays.fill(history, null);
                historyHead = 0;
                lastHistoryNanos = 0;
                historyStale = false;
            }
            if (snap.nanos() - lastHistoryNanos >= 2_000_000L) {
                history[historyHead] = snap;
                historyHead = (historyHead + 1) % history.length;
                lastHistoryNanos = snap.nanos();
            }
        }
        latest = snap;   // after the history, so a fresh latest never comes with a stale history
    }

    /**
     * A camera is capturing: keep recording the world for the next two seconds.
     *
     * @return true if recording had stopped (the latest snapshot may be old)
     */
    boolean keepRecording() {
        long now = System.nanoTime();
        boolean idle = now - recordUntil > 0;
        recordUntil = now + 2_000_000_000L;
        return idle;
    }

    Snapshot latest() {
        return latest;
    }

    /** The newest snapshot taken at or before {@code nanos} (the oldest kept if none is that old). */
    Snapshot at(long nanos) {
        Snapshot newest = latest;
        if (newest == null || newest.nanos() <= nanos) return newest;
        synchronized (history) {
            Snapshot oldest = newest;
            for (int i = 1; i <= history.length; i++) {
                Snapshot s = history[(historyHead - i + history.length) % history.length];
                if (s == null) break;
                if (s.nanos() <= nanos) return s;
                oldest = s;
            }
            return oldest;
        }
    }

    // ---- camera threads ------------------------------------------------------------------------

    /**
     * Renders the world from a camera into {@code rgba} (w x h, 4 bytes per pixel).
     *
     * @param worldToCamera rows are the camera's right, down and forward axes in world coordinates
     */
    void render(Snapshot snap, double[] position, double[] worldToCamera, double fx, double fy, double cx, double cy,
                int w, int h, byte[] rgba) {
        int s = SUPERSAMPLE;
        Rasterizer r = rasterizers.get();
        if (r == null || r.w != w * s || r.h != h * s) {
            r = new Rasterizer(w * s, h * s, field.floor);
            rasterizers.set(r);
        }
        // Pixel centers: low-res u maps to high-res s * (u + 0.5) - 0.5.
        r.setCamera(worldToCamera, position, fx * s, fy * s, s * (cx + 0.5) - 0.5, s * (cy + 0.5) - 0.5);
        List<Scene.Tri> tris = new ArrayList<>(field.scene.tris);
        double[] tilts = snap != null ? snap.hiveTilts() : null;
        for (int i = 0; i < field.hives.size(); i++) {
            FieldScene.Hive hive = field.hives.get(i);
            tris.addAll(hive.at(tilts != null && i < tilts.length ? tilts[i] : hive.startTilt));
        }
        double[] spheres = new double[0];
        if (snap != null) {
            for (RobotView rv : snap.robots()) tris.addAll(robotTris(rv));
            double[] b = snap.balls();
            int n = snap.radii().length;
            spheres = new double[n * 5];
            for (int i = 0; i < n; i++) {
                spheres[i * 5] = b[i * 3];
                spheres[i * 5 + 1] = b[i * 3 + 1];
                spheres[i * 5 + 2] = b[i * 3 + 2];
                spheres[i * 5 + 3] = snap.radii()[i];
                spheres[i * 5 + 4] = snap.colors()[i];
            }
        }
        // Horizontal bands on several cores; each band owns its rows.
        final Rasterizer target = r;
        final double[] balls = spheres;
        int rows = target.h, bands = Math.max(1, Math.min(BANDS, rows / 16));
        target.prepare(tris);   // project and clip once
        java.util.stream.IntStream.range(0, bands).parallel().forEach(i ->
                target.renderBand(balls, BACKGROUND, rows * i / bands, rows * (i + 1) / bands));
        r.resolve(s, rgba);
    }

    private static final int BANDS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));

    /** A robot's triangles at its pose: chassis frame, side plates in its color, and wheels. */
    private List<Scene.Tri> robotTris(RobotView rv) {
        List<double[]> mesh;
        synchronized (robotMeshes) {
            mesh = robotMeshes.computeIfAbsent(rv.config(), CameraWorld::buildRobotMesh);
        }
        List<Scene.Tri> out = new ArrayList<>(mesh.size());
        double[] R = rv.rot(), p = rv.pos();
        for (double[] t : mesh) {
            double[] v = new double[9];
            for (int k = 0; k < 9; k += 3) {
                double x = t[k], y = t[k + 1], z = t[k + 2] - rv.chassisZ();
                v[k] = p[0] + R[0] * x + R[1] * y + R[2] * z;
                v[k + 1] = p[1] + R[3] * x + R[4] * y + R[5] * z;
                v[k + 2] = p[2] + R[6] * x + R[7] * y + R[8] * z;
            }
            out.add(new Scene.Tri(v, null, Material.flat((int) t[9])));
        }
        return out;
    }

    /** Robot geometry in the robot frame (origin on the floor under its center): 9 coordinates + color per triangle. */
    private static List<double[]> buildRobotMesh(RobotConfig cfg) {
        Scene s = new Scene();
        RobotConfig.Chassis c = cfg.chassis;
        double L = c.lengthM, W = c.widthM, H = c.frameHeightM, z0 = c.groundClearanceM;
        int plate = parseColor(c.color);
        Material frame = Material.flat(0x2b2f35), side = Material.flat(plate), dark = Material.flat(0x1b1d20);
        double zc = z0 + H / 2;
        s.box(new double[]{0, W / 2 - 0.015, zc}, L, 0.03, H, null, frame);
        s.box(new double[]{0, -W / 2 + 0.015, zc}, L, 0.03, H, null, frame);
        s.box(new double[]{L / 2 - 0.015, 0, zc}, 0.03, W - 0.06, H * 0.8, null, frame);
        s.box(new double[]{-L / 2 + 0.015, 0, zc}, 0.03, W - 0.06, H * 0.8, null, frame);
        s.box(new double[]{0, W / 2 + 0.003, zc}, L * 0.98, 0.006, H * 0.9, null, side);
        s.box(new double[]{0, -W / 2 - 0.003, zc}, L * 0.98, 0.006, H * 0.9, null, side);
        s.box(new double[]{0, 0, z0 + 0.01}, L - 0.06, W - 0.06, 0.006, null, dark);
        double wr = cfg.drive.wheelDiameterMm / 2000.0, a = cfg.drive.wheelbaseM / 2, b = cfg.drive.trackWidthM / 2;
        for (double x : new double[]{-a, a}) {
            for (double y : new double[]{-b, b}) {
                double yo = Math.signum(y) * 0.022;
                s.cylinder(new double[]{x, y - yo, wr}, new double[]{x, y + yo, wr}, wr, 14, Material.flat(0x26282c));
            }
        }
        List<double[]> out = new ArrayList<>();
        for (Scene.Tri t : s.tris) {
            double[] e = java.util.Arrays.copyOf(t.v, 10);
            e[9] = t.material.rgb;
            out.add(e);
        }
        return out;
    }

    private static int parseColor(String css) {
        try {
            return Integer.parseInt(css.replace("#", ""), 16) & 0xffffff;
        } catch (RuntimeException e) {
            return 0xb3261e;
        }
    }
}
