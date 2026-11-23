package ftcsim.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import ftcsim.config.RobotConfig;
import ftcsim.physics.FieldModel;

/** What the simulated webcams see of the robots and the field. */
class WebcamSceneTest {
    private static final int W = 320, H = 240;

    /** robots/mecanum.json (drawn from its numbers) with its webcam moved to (x, 0, z), looking straight ahead. */
    private static RobotConfig mecanumWithWebcamAt(double x, double z, double pitch) throws Exception {
        RobotConfig cfg = RobotConfig.load(Path.of("robots/mecanum.json"), false);
        RobotConfig.Device cam = webcam(cfg);
        cam.xM = x;
        cam.zM = z;
        cam.pitchDeg = pitch;
        return cfg;
    }

    private static RobotConfig.Device webcam(RobotConfig cfg) {
        return cfg.devices.stream().filter(d -> "webcam".equals(d.type)).findFirst().orElseThrow();
    }

    /** The share of the webcam's image that is its own robot: what changes when the robot isn't drawn. */
    private static double ownRobotShare(RobotConfig cfg) throws Exception {
        CameraWorld world = new CameraWorld(FieldModel.loadBiobuzz());
        double chassisZ = cfg.chassis.groundClearanceM + cfg.chassis.frameHeightM / 2;
        CameraWorld.RobotView rv = new CameraWorld.RobotView(new double[]{-1.2, -1.2, chassisZ},
                new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, chassisZ, cfg, null);
        CameraWorld.Snapshot with = new CameraWorld.Snapshot(0, new CameraWorld.RobotView[]{rv}, new double[0], new double[0], new int[0], null);
        CameraWorld.Snapshot without = new CameraWorld.Snapshot(0, new CameraWorld.RobotView[0], new double[0], new double[0], new int[0], null);
        SimCamera cam = new SimCamera(world, 0, webcam(cfg));
        double[][] pose = cam.pose(with);
        double[] k = cam.intrinsics(W, H);
        byte[] a = new byte[W * H * 4], b = new byte[W * H * 4];
        world.render(with, pose[0], pose[1], k[0], k[1], k[2], k[3], W, H, a);
        world.render(without, pose[0], pose[1], k[0], k[1], k[2], k[3], W, H, b);
        int robot = 0;
        for (int i = 0; i < W * H; i++) {
            int d = 0;
            for (int c = 0; c < 3; c++) d += Math.abs((a[i * 4 + c] & 0xff) - (b[i * 4 + c] & 0xff));
            if (d > 6) robot++;
        }
        return (double) robot / (W * H);
    }

    @Test
    void aWebcamMountedLowInTheFrontRailSeesPastIt() throws Exception {
        // Between the front wheels, its lens inside the frame's front rail (x 0.199 to 0.229 m).
        double share = ownRobotShare(mecanumWithWebcamAt(0.21, 0.08, 0));
        System.out.printf("webcam in the front rail: %.1f%% of the image is its own robot%n", share * 100);
        assertTrue(share < 0.05, "it sees the field, not the inside of the rail: " + share);
    }

    /** A field with one tag, its normal and up as season.json gives them. */
    private static FieldTag tagOnAField(String normal, String up) {
        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString("""
                {"field_m": [3.6576, 3.6576],
                 "field_tags": {"size_m": 0.1, "tags": {"7": {"center_m": [0, 1.8, 0.3], "normal": %s, "up": %s}}}}
                """.formatted(normal, up)).getAsJsonObject();
        return new CameraWorld(new FieldModel(null, json)).tags().get(0);
    }

    private static double length(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    @Test
    void aTagIsItsSizeWhateverLengthItsDirectionsAreGiven() {
        FieldTag unit = tagOnAField("[0, -1, 0]", "[0, 0, 1]");
        // Twice as long a normal, an up three times as long and leaning toward the normal.
        FieldTag given = tagOnAField("[0, -2, 0]", "[0, -0.5, 3]");
        for (FieldTag t : new FieldTag[]{unit, given}) {
            assertEquals(1, length(t.right()), 1e-12, "right: the tag's width");
            assertEquals(1, length(t.up()), 1e-12, "up: its height");
            assertEquals(1, length(t.normal()), 1e-12);
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(unit.right(), given.right(), 1e-12);
        org.junit.jupiter.api.Assertions.assertArrayEquals(unit.up(), given.up(), 1e-12, "up, square to the normal");
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> tagOnAField("[0, -1, 0]", "[0, 2, 0]"));
        assertTrue(e.getMessage().contains("AprilTag 7: its up (or it lies along the normal) has no direction"), e.getMessage());
        e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> tagOnAField("[0, 0, 0]", "[0, 0, 1]"));
        assertTrue(e.getMessage().contains("AprilTag 7: its normal has no direction"), e.getMessage());
    }

    @Test
    void theShippedWebcamMountSeesTheRobotAsBefore() throws Exception {
        RobotConfig shipped = RobotConfig.load(Path.of("robots/mecanum.json"), false);
        RobotConfig noWebcam = RobotConfig.load(Path.of("robots/mecanum.json"), false);
        noWebcam.devices.removeIf(d -> "webcam".equals(d.type));
        assertEquals(CameraWorld.buildRobotMesh(noWebcam).size(), CameraWorld.buildRobotMesh(shipped).size(),
                "30 cm up, nothing of the robot is near its lens");
    }
}
