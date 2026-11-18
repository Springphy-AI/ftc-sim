package ftcsim.vision;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.ode4j.math.DMatrix3C;
import org.ode4j.math.DVector3C;

import ftcsim.cad.MeshPart;
import ftcsim.cad.RobotModel;
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

    /**
     * One robot at one moment. {@code pos} is the world position of the point {@code chassisZ}
     * above the robot-frame origin, {@code rot} row-major world-from-robot; mounts are relative to
     * the floor under it. {@code groups}: where each part of a CAD model is (world transforms of
     * its rest-pose geometry, see RobotBody#groupTransforms), or null.
     */
    record RobotView(double[] pos, double[] rot, double chassisZ, RobotConfig config, double[][] groups) {
    }

    /**
     * Where everything was at {@code nanos} ({@link System#nanoTime()}). {@code moverAngles}: the
     * angle of each moving part of the field (rad, FieldModel order), or null for their start angles.
     */
    record Snapshot(long nanos, RobotView[] robots, double[] balls, double[] radii, int[] colors, double[] moverAngles) {
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

    /** The AprilTags printed on the field (world coordinates), the moving parts at their start angle. */
    public List<FieldTag> tags() {
        return field.tags;
    }

    /** The AprilTags with the field's moving parts at these angles (rad, FieldModel order; null: start angles). */
    public List<FieldTag> tags(double[] moverAngles) {
        List<FieldTag> out = new ArrayList<>(field.fixedTags);
        for (int i = 0; i < field.movers.size(); i++) {
            FieldScene.Mover m = field.movers.get(i);
            double a = moverAngles != null && i < moverAngles.length ? moverAngles[i] : m.startAngle();
            out.addAll(m.tagsAt(a));
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
     * Records the robots, balls and the field's moving parts; call on the physics thread after each step.
     *
     * @param moverAngles the angle of each moving part of the field (rad, FieldModel order: a
     *                    HIVE's tilt about its axle...), or null while they stay at their start angle
     */
    public void update(List<RobotRig> rigs, GameElements elements, double[] moverAngles) {
        if (latest != null && System.nanoTime() - recordUntil > 0) {   // no camera is looking
            historyStale = true;
            return;
        }
        RobotView[] robots = new RobotView[rigs.size()];
        for (int i = 0; i < robots.length; i++) {
            RobotRig rig = rigs.get(i);
            DVector3C p = rig.body.centerPosition();
            DMatrix3C r = rig.body.rotation();
            robots[i] = new RobotView(new double[]{p.get0(), p.get1(), p.get2()},
                    new double[]{r.get00(), r.get01(), r.get02(), r.get10(), r.get11(), r.get12(), r.get20(), r.get21(), r.get22()},
                    rig.body.chassisRestZ(), rig.config, rig.groupTransforms());
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
                    colors[i] = list.get(i).rgb;
                }
                ballsSeen = list;
                ballRadii = radii;
                ballColors = colors;
            }
        }
        Snapshot snap = new Snapshot(System.nanoTime(), robots, balls, elements != null ? ballRadii : new double[0],
                elements != null ? ballColors : new int[0], moverAngles == null ? null : moverAngles.clone());
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
        double[] angles = snap != null ? snap.moverAngles() : null;
        for (int i = 0; i < field.movers.size(); i++) {
            FieldScene.Mover m = field.movers.get(i);
            tris.addAll(m.at(angles != null && i < angles.length ? angles[i] : m.startAngle()));
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
        RobotModel model = rv.config().model();
        if (model != null && rv.groups() != null) return cadTris(model, rv.groups(), rv.config());
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

    /** A CAD robot's simplified triangles, each group where it is now. */
    private static List<Scene.Tri> cadTris(RobotModel model, double[][] groups, RobotConfig cfg) {
        List<double[]> cams = new ArrayList<>();
        for (RobotConfig.Device d : cfg.devices) if ("webcam".equals(d.type)) cams.add(new double[]{d.xM, d.yM, d.zM});
        List<List<MeshPart>> mesh = model.visionMesh(cams);
        List<Scene.Tri> out = new ArrayList<>();
        for (int g = 0; g < mesh.size() && g < groups.length; g++) {
            double[] t = groups[g];
            for (MeshPart m : mesh.get(g)) {
                Material mat = Material.flat(m.rgb());
                float[] p = m.positions();
                int[] ix = m.indices();
                for (int k = 0; k < ix.length; k += 3) {
                    double[] v = new double[9];
                    for (int c = 0; c < 3; c++) {
                        int i = ix[k + c] * 3;
                        double x = p[i], y = p[i + 1], z = p[i + 2];
                        v[c * 3] = t[0] * x + t[1] * y + t[2] * z + t[9];
                        v[c * 3 + 1] = t[3] * x + t[4] * y + t[5] * z + t[10];
                        v[c * 3 + 2] = t[6] * x + t[7] * y + t[8] * z + t[11];
                    }
                    out.add(new Scene.Tri(v, null, mat));
                }
            }
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
