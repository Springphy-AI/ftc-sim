package ftcsim.vision;

import java.util.List;
import java.util.concurrent.locks.LockSupport;

import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibration;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibrationHelper;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibrationIdentity;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.PlaceholderCalibratedAspectRatioMismatch;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.VendorProductCalibrationIdentity;

import ftcsim.config.RobotConfig;
import ftcsim.hal.CameraBackend;

/**
 * A webcam mounted on a simulated robot. Its lens is the camera model's entry in the SDK's own
 * calibration table at the requested resolution, so FIRST's AprilTag processor, which looks up
 * the same entry, solves poses with the lens that took the picture, as on a calibrated robot.
 */
public final class SimCamera implements CameraBackend {

    private final CameraWorld world;
    private final int robot;
    private final RobotConfig.Device mount;
    private final WebcamModels.Model model;
    private final CameraCalibrationIdentity identity;

    SimCamera(CameraWorld world, int robot, RobotConfig.Device mount) {
        this.world = world;
        this.robot = robot;
        this.mount = mount;
        this.model = WebcamModels.get(mount.model);
        this.identity = new VendorProductCalibrationIdentity(model.vendorId(), model.productId());
    }

    public WebcamModels.Model model() {
        return model;
    }

    /**
     * Where the camera is now: {position (world), world-to-camera rotation (rows: right, down,
     * forward)}.
     */
    public double[][] currentPose() {
        return pose(world.latest());
    }

    /**
     * Where the camera was at {@code nanos} (System.nanoTime()), as {@link #currentPose()}: for a
     * frame, the moment {@link #capture} returned, which VisionPortal passes on to processors.
     */
    public double[][] poseAt(long nanos) {
        return pose(world.at(nanos));
    }

    /** fx, fy, cx, cy of the lens at a resolution. */
    public double[] lens(int width, int height) {
        return intrinsics(width, height);
    }

    @Override
    public int vendorId() {
        return model.vendorId();
    }

    @Override
    public int productId() {
        return model.productId();
    }

    @Override
    public double fps() {
        return mount.fps > 0 ? mount.fps : 30;
    }

    /** How long ago the frame VisionPortal gets now was exposed (USB webcams: tens of milliseconds). */
    public long latencyNanos() {
        return Math.round(Math.max(0, mount.latencyMs) * 1e6);
    }

    /** Renders the world as it was {@link #latencyNanos()} ago, and returns that moment. */
    @Override
    public long capture(int width, int height, byte[] rgba) {
        if (world.keepRecording()) {
            // The physics thread was not recording while no camera looked: wait for a fresh snapshot.
            long since = System.nanoTime();
            for (int i = 0; i < 50; i++) {
                CameraWorld.Snapshot s = world.latest();
                if (s != null && s.nanos() >= since) break;
                LockSupport.parkNanos(1_000_000L);
            }
        }
        CameraWorld.Snapshot snap = world.at(System.nanoTime() - latencyNanos());
        for (int i = 0; snap == null && i < 50; i++) {   // the physics thread has not recorded the world yet
            LockSupport.parkNanos(2_000_000L);
            snap = world.latest();
        }
        double[][] pose = pose(snap);
        double[] k = intrinsics(width, height);
        world.render(snap, pose[0], pose[1], k[0], k[1], k[2], k[3], width, height, rgba);
        return snap != null ? snap.nanos() : System.nanoTime();
    }

    /** fx, fy, cx, cy of the lens at this resolution. */
    double[] intrinsics(int width, int height) {
        CameraCalibration cal = CameraCalibrationHelper.getInstance().getCalibration(identity, width, height);
        if (cal != null && !cal.isFake() && !(cal instanceof PlaceholderCalibratedAspectRatioMismatch) && !cal.isDegenerate()) {
            return new double[]{cal.focalLengthX, cal.focalLengthY, cal.principalPointX, cal.principalPointY};
        }
        // Nothing in the table for this shape: keep the camera's horizontal field of view.
        double hfov = Math.toRadians(model.horizontalFovDeg());
        List<CameraCalibration> known = CameraCalibrationHelper.getInstance().getCalibrations(identity);
        CameraCalibration largest = null;
        for (CameraCalibration c : known) if (largest == null || c.getDiagonal() > largest.getDiagonal()) largest = c;
        if (largest != null) hfov = 2 * Math.atan(largest.getSize().getWidth() / (2.0 * largest.focalLengthX));
        double f = width / 2.0 / Math.tan(hfov / 2);
        return new double[]{f, f, (width - 1) / 2.0, (height - 1) / 2.0};
    }

    /**
     * Camera position and world-to-camera rotation (rows: camera right, down, forward in world
     * coordinates). Mount angles: yaw left of straight ahead, pitch up, roll clockwise as seen
     * from behind the camera.
     */
    double[][] pose(CameraWorld.Snapshot snap) {
        double yaw = Math.toRadians(mount.yawDeg), pitch = Math.toRadians(mount.pitchDeg), roll = Math.toRadians(mount.rollDeg);
        // camera axes in the robot frame (x forward, y left, z up)
        double[] fwd = {Math.cos(yaw) * Math.cos(pitch), Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch)};
        double[] right0 = {Math.sin(yaw), -Math.cos(yaw), 0};
        double[] down0 = Scene.cross(fwd, right0);
        double cr = Math.cos(roll), sr = Math.sin(roll);
        double[] right = {right0[0] * cr + down0[0] * sr, right0[1] * cr + down0[1] * sr, right0[2] * cr + down0[2] * sr};
        double[] down = {down0[0] * cr - right0[0] * sr, down0[1] * cr - right0[1] * sr, down0[2] * cr - right0[2] * sr};

        double[] origin = {0, 0, 0};
        double[] R = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        double chassisZ = 0;
        if (snap != null && robot < snap.robots().length) {
            CameraWorld.RobotView rv = snap.robots()[robot];
            origin = rv.pos();
            R = rv.rot();
            chassisZ = rv.chassisZ();
        }
        double[] m = {mount.xM, mount.yM, mount.zM - chassisZ};
        double[] wm = Scene.mul(R, m);
        double[] position = {origin[0] + wm[0], origin[1] + wm[1], origin[2] + wm[2]};
        double[] r = Scene.mul(R, right), d = Scene.mul(R, down), f = Scene.mul(R, fwd);
        return new double[][]{position, {r[0], r[1], r[2], d[0], d[1], d[2], f[0], f[1], f[2]}};
    }
}
