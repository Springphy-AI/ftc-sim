package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.vision.VisionPortal;
import org.firstinspires.ftc.vision.apriltag.AprilTagClusterDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagGameDatabase;
import org.firstinspires.ftc.vision.apriltag.AprilTagLibrary;
import org.firstinspires.ftc.vision.apriltag.AprilTagPoseRaw;
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor;
import org.firstinspires.ftc.vision.apriltag.AprilTagSingleDetection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qualcomm.robotcore.hardware.HardwareMap;

import ftcsim.config.RobotConfig;
import ftcsim.hal.HalSession;
import ftcsim.hal.SimWebcamName;
import ftcsim.physics.FieldModel;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.sim.Simulator;
import ftcsim.vision.CameraWorld;
import ftcsim.vision.FieldTag;
import ftcsim.vision.SimCamera;

/**
 * FIRST's own VisionPortal and AprilTagProcessor (SDK 12.0, unmodified) running on a simulated
 * webcam: the detector must find the tags printed on the HIVE, and the poses it solves must
 * match where the simulator put the camera and the tags.
 */
class AprilTagVisionTest {

    /**
     * How far a solved tag position may be from the truth, per axis. On rendered frames (no lens
     * distortion or noise) the positions solved at about 0.7 m come within 2 mm; the test prints
     * every error to 0.1 mm, so the log shows whether that still holds. The limit leaves room for
     * the detector as built for another platform (MSVC on Windows, gcc on Linux), while a real error
     * in the camera model or in where the tags are fails it.
     */
    private static final double POSITION_TOLERANCE_M = 0.005;
    /**
     * The RED SCORING cluster's origin is not a tag: it lies 0.28 m from tag 30 through FIRST's
     * inch-rounded offsets and is solved at 0.88 m from 3 of the 4 tags, so a small rotation error
     * moves it more (observed: under 1 mm). The check is that FIRST's layout and the field agree.
     */
    private static final double CLUSTER_ORIGIN_TOLERANCE_M = 0.010;
    /**
     * How far a solved tag orientation may be from the truth: the angle of the rotation between the
     * two (observed: up to 1.7 degrees; a small tag pins down its tilt less well than its place).
     * Unlike the bearing, which follows from the position, this checks what the position doesn't: a
     * tag drawn turned or mirrored, or axes in another convention, is off by tens of degrees.
     */
    private static final double ORIENTATION_TOLERANCE_DEG = 3.0;

    private Simulator sim;
    private VisionPortal portal;

    @AfterEach
    void tearDown() {
        if (portal != null) portal.close();
        if (sim != null) sim.shutdown();
    }

    @TempDir
    Path tmp;
    private int setUps;

    /** A mecanum robot whose webcam is tilted up toward the HIVE, parked under the red HIVE. */
    private SimWebcamName setUp(double x, double y, double headingDeg, double pitchDeg) throws Exception {
        Path home = Path.of("").toAbsolutePath();
        Path dir = Files.createDirectories(tmp.resolve("robot-" + setUps++));
        String json = Files.readString(home.resolve("robots/mecanum.json"))
                .replace("\"pitch_deg\": 20 }", "\"pitch_deg\": " + pitchDeg + " }");
        Path robot = dir.resolve("vision.json");
        Files.writeString(robot, json);
        Files.createDirectories(dir.resolve("teamcode"));
        TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(new TeamCodeCompiler.SourceRoot(dir.resolve("teamcode"), false)),
                dir.resolve("build"));
        sim = new Simulator(FieldModel.loadBiobuzz(), robot, dir, compiler, "test");
        sim.setOutput(new Simulator.Output() {
            @Override
            public void broadcast(String json) {
            }

            @Override
            public void broadcastState(String json) {
            }
        });
        sim.start();
        VisionAssumptions.assumeDetectorAvailable();
        sim.handle(String.format(Locale.US, "{\"type\":\"place\",\"x\":%f,\"y\":%f,\"h\":%f}", x, y, headingDeg));
        Thread.sleep(600);   // the robot settles on its wheels
        HardwareMap hw = sim.rig().createHardwareMap(new HalSession());
        return (SimWebcamName) hw.get(WebcamName.class, "Webcam 1");
    }

    private static List<AprilTagDetection> waitForDetections(AprilTagProcessor processor, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<AprilTagDetection> d = processor.getDetections();
            if (!d.isEmpty()) return d;
            Thread.sleep(50);
        }
        return processor.getDetections();
    }

    private FieldTag tag(int id) {
        for (FieldTag t : sim.cameras().tags()) if (t.id() == id) return t;
        throw new AssertionError("no tag " + id + " on the field");
    }

    /**
     * The tag's center as FIRST's ftcPose reports it (x right, y forward, z up from the camera, in
     * meters), from where the camera was when the detection's frame was taken: the frame shows the
     * world {@code latency_ms} ago, and the robot may still be settling.
     */
    private static double[] expectedFtcXyz(SimCamera camera, FieldTag tag, AprilTagDetection d) {
        double[][] pose = camera.poseAt(d.frameAcquisitionNanoTime);
        double[] p = pose[0], r = pose[1], c = tag.center();
        double dx = c[0] - p[0], dy = c[1] - p[1], dz = c[2] - p[2];
        double X = r[0] * dx + r[1] * dy + r[2] * dz;     // camera right
        double Y = r[3] * dx + r[4] * dy + r[5] * dz;     // camera down
        double Z = r[6] * dx + r[7] * dy + r[8] * dz;     // camera forward
        return new double[]{X, Z, -Y};
    }

    /** Checks a solved position against the truth per axis, and prints the errors to 0.1 mm. */
    private static void assertFtcXyz(String what, double[] want, double x, double y, double z, double tolerance) {
        double[] got = {x, y, z};
        System.out.printf(Locale.US, "%s: x=%.4f y=%.4f z=%.4f m, error x %+.1f y %+.1f z %+.1f mm (limit %.0f mm)%n", what,
                x, y, z, (x - want[0]) * 1000, (y - want[1]) * 1000, (z - want[2]) * 1000, tolerance * 1000);
        String[] axis = {"x", "y", "z"};
        for (int k = 0; k < 3; k++) assertEquals(want[k], got[k], tolerance, what + " " + axis[k]);
    }

    /**
     * Checks the solved orientation (the rotation from the tag's axes to the camera's, OpenCV's
     * convention: tag x right, y down, z into the tag) against the tag's true orientation as seen
     * from where the camera was for that frame, and prints the angle between them.
     */
    private static void assertOrientation(String what, SimCamera camera, FieldTag tag, AprilTagDetection d) {
        double[] r = camera.poseAt(d.frameAcquisitionNanoTime)[1];
        double[][] tagAxes = {tag.right(), {-tag.up()[0], -tag.up()[1], -tag.up()[2]}, {-tag.normal()[0], -tag.normal()[1], -tag.normal()[2]}};
        AprilTagPoseRaw raw = d.rawPose;
        double trace = 0;   // trace of expected^T * solved
        for (int col = 0; col < 3; col++) {
            for (int row = 0; row < 3; row++) {
                double want = r[row * 3] * tagAxes[col][0] + r[row * 3 + 1] * tagAxes[col][1] + r[row * 3 + 2] * tagAxes[col][2];
                trace += want * raw.R.get(row, col);
            }
        }
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (trace - 1) / 2))));
        System.out.printf(Locale.US, "%s: orientation off by %.2f deg (limit %.1f)%n", what, angle, ORIENTATION_TOLERANCE_DEG);
        assertTrue(angle <= ORIENTATION_TOLERANCE_DEG, what + " orientation off by " + angle + " deg");
    }

    @Test
    void firstsAprilTagProcessorFindsTheHiveTagsAndSolvesTheirPose() throws Exception {
        // Red rear CELL tags (30-33) hang under the lowered end of the red HIVE, facing down and
        // toward the audience. Park below and audience-side of them, webcam tilted up 55 degrees.
        SimWebcamName webcam = setUp(-0.254, -0.33, 90, 55);
        SimCamera camera = (SimCamera) webcam.camera();
        assertNotNull(camera, "the webcam has a simulated camera behind it");

        AprilTagLibrary.Builder library = new AprilTagLibrary.Builder();
        for (int id = 30; id <= 33; id++) library.addTag(id, "red rear " + id, 0.0825, DistanceUnit.METER);
        AprilTagProcessor processor = new AprilTagProcessor.Builder()
                .setTagLibrary(library.build())
                .setOutputUnits(DistanceUnit.METER, AngleUnit.DEGREES)
                .build();
        portal = new VisionPortal.Builder().setCamera(webcam).addProcessor(processor).build();

        List<AprilTagDetection> detections = waitForDetections(processor, 20000);
        assertEquals(VisionPortal.CameraState.STREAMING, portal.getCameraState());
        assertFalse(detections.isEmpty(), "FIRST's AprilTag processor detects tags in the simulated image");
        Set<Integer> checked = new TreeSet<>();
        for (AprilTagDetection d : detections) {
            assertTrue(d instanceof AprilTagSingleDetection, "tags from a plain library are single detections");
            AprilTagSingleDetection s = (AprilTagSingleDetection) d;
            if (s.id < 30 || s.id > 33) {
                // Other HIVE tags in view: found, but not in this library, so no pose (as on the robot).
                assertNull(s.metadata, "tag " + s.id + " is not in the library");
                continue;
            }
            assertNotNull(s.metadata, "tag " + s.id + " is in the library");
            System.out.printf(Locale.US, "tag %d: range %.4f m, decision margin %.0f%n", s.id, s.ftcPose.range, s.decisionMargin);
            assertFtcXyz("tag " + s.id, expectedFtcXyz(camera, tag(s.id), s), s.ftcPose.x, s.ftcPose.y, s.ftcPose.z, POSITION_TOLERANCE_M);
            assertOrientation("tag " + s.id, camera, tag(s.id), s);
            checked.add(s.id);
        }
        // From here, tags 30, 31 and 33 are in full view (32 is partly hidden by the CELL's frame).
        assertTrue(checked.containsAll(Set.of(30, 31, 33)), "sees tags 30, 31 and 33, saw " + checked);
    }

    /**
     * With the SDK's default library (BIOBUZZ), the red rear CELL's tags come back as FIRST's
     * "RED SCORING" cluster, whose origin is the center of the CELL opening. Where FIRST's
     * cluster layout (AprilTagGameDatabase: tag 30 sits at (-6.5, 7.1874, -5.622) in from the
     * origin, in OpenCV tag axes x right, y down, z into the tag) puts that origin must agree with
     * the simulator's field: the same tags, the same spacing, the opening where the CELL ends.
     */
    @Test
    void biobuzzTagsAreReportedAsFirstsClusterAtTheCellOpening() throws Exception {
        SimWebcamName webcam = setUp(-0.254, -0.33, 90, 55);
        SimCamera camera = (SimCamera) webcam.camera();
        AprilTagLibrary bioBuzz = AprilTagGameDatabase.getBioBuzzTagLibrary();
        for (int id = 30; id <= 33; id++) assertEquals("RED SCORING", bioBuzz.lookupCluster(id).name, "tag " + id + "'s cluster");

        AprilTagProcessor processor = AprilTagProcessor.easyCreateWithDefaults();   // current game library, inches
        portal = VisionPortal.easyCreateWithDefaults(webcam, processor);
        List<AprilTagDetection> detections = waitForDetections(processor, 20000);
        AprilTagClusterDetection red = null;
        for (AprilTagDetection d : detections) {
            if (d instanceof AprilTagClusterDetection && "RED SCORING".equals(((AprilTagClusterDetection) d).metadata.name)) {
                red = (AprilTagClusterDetection) d;
            }
        }
        assertNotNull(red, "the red rear CELL's tags are reported as FIRST's RED SCORING cluster, got " + detections);
        assertTrue(red.percentClusterFound >= 50, "most of the cluster is visible: " + red.percentClusterFound + "%");

        // FIRST's origin, from tag 30 and FIRST's offset of tag 30 in the cluster (inches -> m).
        FieldTag t30 = tag(30);
        double ox = -6.5 * 0.0254, oy = 7.1874 * 0.0254, oz = -5.622 * 0.0254;
        double[] down = {-t30.up()[0], -t30.up()[1], -t30.up()[2]}, into = {-t30.normal()[0], -t30.normal()[1], -t30.normal()[2]};
        double[] origin = new double[3];
        for (int k = 0; k < 3; k++) origin[k] = t30.center()[k] - (ox * t30.right()[k] + oy * down[k] + oz * into[k]);
        FieldTag originAsTag = new FieldTag(-1, origin, t30.right(), t30.up(), t30.normal(), 0);
        double in = 0.0254;
        System.out.printf(Locale.US, "RED SCORING: %d%% of the cluster found%n", red.percentClusterFound);
        assertFtcXyz("cluster origin", expectedFtcXyz(camera, originAsTag, red), red.ftcPose.x * in, red.ftcPose.y * in, red.ftcPose.z * in,
                CLUSTER_ORIGIN_TOLERANCE_M);
        assertOrientation("cluster", camera, originAsTag, red);
    }

    /**
     * The camera draws each HIVE at the tilt the physics reports, so the tags move with it. Red
     * starts with its audience CELL up; tipped to the other stop, that CELL comes down and its tags
     * (34-37) face the rear. A camera aimed at tag 35's new place must see it there.
     */
    @Test
    void tagsMoveWithTheHivesTilt() throws Exception {
        VisionAssumptions.assumeDetectorAvailable();
        CameraWorld world = new CameraWorld(FieldModel.loadBiobuzz());
        double[] tilts = {0.523599, 0.523599};   // red tipped to rear-up; blue at its start (rear-up)
        world.update(List.of(), null, tilts);
        FieldTag t35 = null;
        for (FieldTag t : world.tags(tilts)) if (t.id() == 35) t35 = t;
        assertNotNull(t35);
        FieldTag start35 = null;
        for (FieldTag t : world.tags()) if (t.id() == 35) start35 = t;
        assertTrue(Math.abs(t35.center()[2] - start35.center()[2]) > 0.2, "tipping moves the tag: z " + start35.center()[2] + " -> " + t35.center()[2]);

        // No robot: the mount is in field coordinates. 0.7 m out along the tag's normal, facing it.
        double[] n = t35.normal(), c = t35.center();
        RobotConfig.Device mount = new RobotConfig.Device();
        mount.type = "webcam";
        mount.name = "Webcam 1";
        mount.xM = c[0] + 0.7 * n[0];
        mount.yM = c[1] + 0.7 * n[1];
        mount.zM = c[2] + 0.7 * n[2];
        mount.yawDeg = Math.toDegrees(Math.atan2(-n[1], -n[0]));
        mount.pitchDeg = Math.toDegrees(Math.asin(-n[2]));
        SimCamera camera = world.camera(0, mount);
        AprilTagLibrary.Builder library = new AprilTagLibrary.Builder();
        for (int id = 34; id <= 37; id++) library.addTag(id, "red audience " + id, 0.0825, DistanceUnit.METER);
        AprilTagProcessor processor = new AprilTagProcessor.Builder().setTagLibrary(library.build())
                .setOutputUnits(DistanceUnit.METER, AngleUnit.DEGREES).build();
        portal = new VisionPortal.Builder().setCamera(new SimWebcamName("Webcam 1", camera)).addProcessor(processor).build();

        AprilTagSingleDetection seen = null;
        long deadline = System.currentTimeMillis() + 20000;
        while (seen == null && System.currentTimeMillis() < deadline) {
            for (AprilTagDetection d : processor.getDetections()) {
                if (d instanceof AprilTagSingleDetection && ((AprilTagSingleDetection) d).id == 35) seen = (AprilTagSingleDetection) d;
            }
            Thread.sleep(50);
        }
        assertNotNull(seen, "tag 35 is where the tipped HIVE put it");
        assertFtcXyz("tipped tag 35", expectedFtcXyz(camera, t35, seen), seen.ftcPose.x, seen.ftcPose.y, seen.ftcPose.z, POSITION_TOLERANCE_M);
        assertOrientation("tipped tag 35", camera, t35, seen);
    }
}
