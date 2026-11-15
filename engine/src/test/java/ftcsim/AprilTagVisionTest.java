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

import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.vision.VisionPortal;
import org.firstinspires.ftc.vision.apriltag.AprilTagClusterDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagGameDatabase;
import org.firstinspires.ftc.vision.apriltag.AprilTagLibrary;
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor;
import org.firstinspires.ftc.vision.apriltag.AprilTagSingleDetection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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

    private Simulator sim;
    private VisionPortal portal;

    @AfterEach
    void tearDown() {
        if (portal != null) portal.close();
        if (sim != null) sim.shutdown();
    }

    /** A mecanum robot whose webcam is tilted up toward the HIVE, parked under the red HIVE. */
    private SimWebcamName setUp(double x, double y, double headingDeg, double pitchDeg) throws Exception {
        Path home = Path.of("").toAbsolutePath();
        Path dir = Files.createTempDirectory("ftcsim-vision");
        String json = Files.readString(home.resolve("robots/mecanum.json"))
                .replace("\"pitch_deg\": 20 }", "\"pitch_deg\": " + pitchDeg + " }");
        Path robot = dir.resolve("vision.json");
        Files.writeString(robot, json);
        Files.createDirectories(dir.resolve("teamcode"));
        TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(new TeamCodeCompiler.SourceRoot(dir.resolve("teamcode"), false)),
                Files.createTempDirectory("ftcsim-vision-build"));
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

    /** The tag's center as FIRST's ftcPose reports it: x right, y forward, z up from the camera (m). */
    private static double[] expectedFtcXyz(SimCamera camera, FieldTag tag) {
        double[][] pose = camera.currentPose();
        double[] p = pose[0], r = pose[1], c = tag.center();
        double dx = c[0] - p[0], dy = c[1] - p[1], dz = c[2] - p[2];
        double X = r[0] * dx + r[1] * dy + r[2] * dz;     // camera right
        double Y = r[3] * dx + r[4] * dy + r[5] * dz;     // camera down
        double Z = r[6] * dx + r[7] * dy + r[8] * dz;     // camera forward
        return new double[]{X, Z, -Y};
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
        int checked = 0;
        for (AprilTagDetection d : detections) {
            assertTrue(d instanceof AprilTagSingleDetection, "tags from a plain library are single detections");
            AprilTagSingleDetection s = (AprilTagSingleDetection) d;
            if (s.id < 30 || s.id > 33) {
                // Other HIVE tags in view: found, but not in this library, so no pose (as on the robot).
                assertNull(s.metadata, "tag " + s.id + " is not in the library");
                continue;
            }
            assertNotNull(s.metadata, "tag " + s.id + " is in the library");
            double[] want = expectedFtcXyz(camera, tag(s.id));
            System.out.printf(Locale.US, "tag %d: ftcPose x=%.3f y=%.3f z=%.3f (expected %.3f %.3f %.3f) yaw=%.1f range=%.3f bearing=%.1f margin=%.0f%n",
                    s.id, s.ftcPose.x, s.ftcPose.y, s.ftcPose.z, want[0], want[1], want[2], s.ftcPose.yaw, s.ftcPose.range,
                    s.ftcPose.bearing, s.decisionMargin);
            assertEquals(want[0], s.ftcPose.x, 0.02, "tag " + s.id + " x");
            assertEquals(want[1], s.ftcPose.y, 0.03, "tag " + s.id + " y");
            assertEquals(want[2], s.ftcPose.z, 0.02, "tag " + s.id + " z");
            assertEquals(Math.toDegrees(Math.atan2(-want[0], want[1])), s.ftcPose.bearing, 1.5, "tag " + s.id + " bearing");
            checked++;
        }
        assertTrue(checked >= 2, "sees at least two of the red rear CELL's tags, saw " + checked);
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
        double[] want = expectedFtcXyz(camera, originAsTag);
        double in = 0.0254;
        System.out.printf(Locale.US, "RED SCORING %d%%: ftcPose x=%.3f y=%.3f z=%.3f m (expected %.3f %.3f %.3f)%n", red.percentClusterFound,
                red.ftcPose.x * in, red.ftcPose.y * in, red.ftcPose.z * in, want[0], want[1], want[2]);
        assertEquals(want[0], red.ftcPose.x * in, 0.03, "cluster origin x");
        assertEquals(want[1], red.ftcPose.y * in, 0.04, "cluster origin y");
        assertEquals(want[2], red.ftcPose.z * in, 0.03, "cluster origin z");
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
        double[] want = expectedFtcXyz(camera, t35);
        System.out.printf(Locale.US, "tipped tag 35: ftcPose x=%.3f y=%.3f z=%.3f (expected %.3f %.3f %.3f)%n",
                seen.ftcPose.x, seen.ftcPose.y, seen.ftcPose.z, want[0], want[1], want[2]);
        assertEquals(want[0], seen.ftcPose.x, 0.02);
        assertEquals(want[1], seen.ftcPose.y, 0.03);
        assertEquals(want[2], seen.ftcPose.z, 0.02);
    }
}
