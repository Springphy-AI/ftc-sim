package ftcsim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.firstinspires.ftc.robotcore.external.matrices.VectorF;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Position;
import org.firstinspires.ftc.vision.VisionPortal;
import org.firstinspires.ftc.vision.apriltag.AprilTagDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagGameDatabase;
import org.firstinspires.ftc.vision.apriltag.AprilTagLibrary;
import org.firstinspires.ftc.vision.apriltag.AprilTagMetadata;
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor;
import org.firstinspires.ftc.vision.apriltag.AprilTagSingleDetection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ode4j.math.DVector3C;

import com.qualcomm.robotcore.hardware.HardwareMap;

import decode.DecodeGame;
import ftcsim.config.RobotConfig;
import ftcsim.hal.HalSession;
import ftcsim.hal.Latency;
import ftcsim.hal.MotorPort;
import ftcsim.hal.SimWebcamName;
import ftcsim.physics.FieldModel;
import ftcsim.physics.HingeBody;
import ftcsim.physics.PhysicsWorld;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.season.Season;
import ftcsim.sim.GameElements;
import ftcsim.sim.GameElements.Ball;
import ftcsim.sim.GameElements.State;
import ftcsim.sim.RobotRig;
import ftcsim.sim.Simulator;
import ftcsim.vision.CameraWorld;
import ftcsim.vision.FieldTag;
import ftcsim.vision.SimCamera;

/**
 * Season packs: every season in seasons/ loads and runs, and DECODE (2025-26) is played on its
 * own field with FIRST's own numbers, from the AprilTags in the SDK to how the ARTIFACTS start.
 */
class SeasonsTest {
    private static final double DT = 0.001;
    private static final double IN = 0.0254;

    private record Setup(FieldModel field, RobotRig rig, GameElements elements) {
        void run(double seconds) {
            for (int i = 0; i < Math.round(seconds / DT); i++) {
                elements.beforeStep(DT);
                rig.step(DT);
            }
        }

        DecodeGame decode() {
            return DecodeGame.of(elements);
        }
    }

    private static Setup setup(String season, String robot) throws Exception {
        FieldModel field = Season.load(season).field;
        RobotRig rig = new RobotRig(RobotConfig.load(Path.of("robots/" + robot)), new PhysicsWorld(field));
        Latency.commandNanos = 0;
        Latency.i2cNanos = 0;
        return new Setup(field, rig, new GameElements(field, rig));
    }

    @Test
    void everySeasonLoadsAndPlays() throws Exception {
        List<String> seasons = Season.available(Path.of("seasons"));
        assertTrue(seasons.containsAll(List.of("biobuzz", "decode")), "seasons " + seasons);
        for (String id : seasons) {
            Setup s = setup(id, "mecanum.json");
            assertEquals(id, s.field.season.id);
            assertFalse(s.field.solids.isEmpty(), id + " has structures");
            double[] start = s.rig.start.clone();
            s.run(2.0);
            DVector3C p = s.rig.body.position();
            assertEquals(start[0], p.get0(), 0.03, id + ": the robot starts clear of the field's structures and stays put");
            assertEquals(start[1], p.get1(), 0.03, id + ": the robot starts clear of the field's structures and stays put");
            for (Ball b : s.elements.balls()) {
                if (b.state() != State.FREE) continue;
                DVector3C q = b.position();
                assertTrue(Math.abs(q.get0()) < s.field.half && Math.abs(q.get1()) < s.field.half, id + ": ball " + b.id + " inside the walls, at " + q);
                assertTrue(q.get2() > b.radius - 0.01, id + ": ball " + b.id + " above the tiles, at " + q);
            }
        }
    }

    @Test
    void unknownSeasonListsTheSeasons() {
        Exception e = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> Season.load("no-such-game"));
        assertTrue(e.getMessage().contains("biobuzz") && e.getMessage().contains("decode"), e.getMessage());
    }

    @Test
    void robotFileStartSpotIsPerSeason(@TempDir Path tmp) throws Exception {
        RobotConfig tank = RobotConfig.load(Path.of("robots/tank.json"));
        assertArrayEquals(new double[]{-1.2, -1.2, 0}, tank.startFor("biobuzz"), 1e-9);
        assertEquals(null, tank.startFor("decode"));
        Setup s = setup("decode", "tank.json");
        assertArrayEquals(s.field.season.startSpot("red", 1), s.rig.start, 1e-9, "DECODE puts it on its own red start spot");
        // A robot file from before there were seasons: its plain start is a BIOBUZZ spot.
        Path old = tmp.resolve("old.json");
        Files.writeString(old, Files.readString(Path.of("robots/mecanum.json"))
                .replace("\n  \"io\":", "\n  \"start\": [-1.0, 0.5, 90],\n  \"io\":"));
        RobotConfig legacy = RobotConfig.load(old);
        assertArrayEquals(new double[]{-1.0, 0.5, 90}, legacy.startFor("biobuzz"), 1e-9);
        assertEquals(null, legacy.startFor("decode"), "not a spot on the DECODE field");
    }

    @Test
    void theAlliancePartnerOfARobotFilesStartFacesTheMirroredWay() throws Exception {
        Season biobuzz = Season.load("biobuzz");   // partner: mirror_y
        // robots/my-robot.json: back to the audience wall, facing the red HIVE (+y). Its partner
        // starts against the rear wall, so it must face -y: into the field, not into the wall.
        double[] own = RobotConfig.load(Path.of("robots/my-robot.json")).startFor("biobuzz");
        assertArrayEquals(new double[]{-0.3239, 1.5964, -90}, biobuzz.partnerOf(own), 1e-9);
        assertArrayEquals(new double[]{-1.2, 1.2, -30}, biobuzz.partnerOf(new double[]{-1.2, -1.2, 30}), 1e-9);
        assertArrayEquals(new double[]{-1.2, 0.75, 30}, biobuzz.partnerOf(new double[]{-1.2, 0.1, 30}), 1e-9,
                "on the line: beside it, facing the same way");
        // And the partner of the other alliance's robot is where the field's symmetry puts it.
        double[] blue2 = biobuzz.otherAlliance(biobuzz.partnerOf(own));
        assertEquals(-blue2[2], biobuzz.otherAlliance(own)[2], 1e-9, "the blue robots face mirrored ways too");
    }

    // ---- DECODE ----------------------------------------------------------------------------------

    @Test
    void decodeIsStagedPerTheCompetitionManual() throws Exception {
        Setup s = setup("decode", "mecanum.json");
        List<Ball> balls = s.elements.balls();
        // 9.9: 36 ARTIFACTS, 24 purple and 12 green, 5 in.
        assertEquals(36, balls.size());
        assertEquals(24, balls.stream().filter(b -> b.color.equals("purple")).count());
        assertEquals(12, balls.stream().filter(b -> b.color.equals("green")).count());
        assertEquals(0.0635, balls.get(0).radius, 1e-4);
        // 10.3.1: 3 on each of the 6 SPIKE MARKS and 3 in each LOADING ZONE, on the field; the
        // robot holds 3 (G408: no more), taken from its ALLIANCE AREA; the rest wait there.
        assertEquals(24, balls.stream().filter(b -> b.state() == State.FREE).count());
        assertEquals(3, s.elements.heldCount());
        assertEquals(3, s.elements.capacity());
        assertEquals(3, s.elements.waitingCount("red", "artifact", null));
        assertEquals(6, s.elements.waitingCount("blue", "artifact", null));
        // The near SPIKE MARKS hold green, purple, purple from the field center outward.
        Ball inner = nearest(s, 35.3 * IN, 42.3 * IN), outer = nearest(s, 35.3 * IN, 52.3 * IN);
        assertEquals("green", inner.color);
        assertEquals("purple", outer.color);
        s.run(2.0);
        for (Ball b : balls) {
            if (b.state() != State.FREE) continue;
            assertTrue(Math.hypot(b.velocity().get0(), b.velocity().get1()) < 0.01, "ARTIFACT " + b.id + " at rest where it was staged");
        }
    }

    private static Ball nearest(Setup s, double x, double y) {
        Ball best = null;
        for (Ball b : s.elements.balls()) {
            if (b.state() != State.FREE) continue;
            if (best == null || Math.hypot(b.position().get0() - x, b.position().get1() - y)
                    < Math.hypot(best.position().get0() - x, best.position().get1() - y)) best = b;
        }
        return best;
    }

    /** The GOAL AprilTags are where FIRST's SDK says they are, facing the way it says. */
    @Test
    void decodeGoalTagsMatchFirstsTagLibrary() throws Exception {
        FieldModel field = Season.load("decode").field;
        AprilTagLibrary lib = AprilTagGameDatabase.getDecodeTagLibrary();
        for (int id : new int[]{20, 24}) {
            AprilTagMetadata m = lib.lookupTag(id);
            FieldModel.Tag t = field.tags.stream().filter(x -> x.id() == id).findFirst().orElseThrow();
            VectorF p = m.fieldPosition;
            double k = m.distanceUnit.toMeters(1);
            assertEquals(p.get(0) * k, t.center()[0], 0.001, "tag " + id + " x");
            assertEquals(p.get(1) * k, t.center()[1], 0.001, "tag " + id + " y");
            assertEquals(p.get(2) * k, t.center()[2], 0.001, "tag " + id + " z");
            assertEquals(m.tagsize * k, t.size(), 0.0005, "tag " + id + " size");
            // FIRST's orientation: the tag's z axis points into the tag, so its face points along -z.
            org.firstinspires.ftc.robotcore.external.navigation.Quaternion q = m.fieldOrientation;
            double[] z = {2 * (q.x * q.z + q.w * q.y), 2 * (q.y * q.z - q.w * q.x), 1 - 2 * (q.x * q.x + q.y * q.y)};
            for (int i = 0; i < 3; i++) assertEquals(-z[i], t.normal()[i], 0.001, "tag " + id + " faces the field");
        }
    }

    @Test
    void artifactsShotIntoTheGoalRollDownTheRampToTheGate() throws Exception {
        Setup s = setup("decode", "mecanum.json");
        DecodeGame game = s.decode();
        assertEquals(0, game.ramp("red").size());
        // Drop three ARTIFACTS through the top of the red GOAL, one after another.
        List<Ball> spare = s.elements.balls().stream().filter(b -> b.area() != null).toList();
        for (int i = 0; i < 3; i++) {
            s.elements.throwBall(spare.get(i), -62 * IN, 60 * IN, 46 * IN, 0, 0, 0);
            s.run(2.5);
        }
        s.run(3.0);
        List<Ball> ramp = game.ramp("red");
        assertEquals(3, ramp.size(), "on the RAMP: " + ramp.stream().map(b -> b.position().toString()).toList());
        assertEquals(3, game.through("red"), "each went through the SQUARE");
        assertTrue(ramp.get(0).position().get0() > -0.08, "the first waits at the GATE, x " + ramp.get(0).position().get0());
        for (Ball b : ramp) assertTrue(b.velocity().length() < 0.02, "held still by the GATE");

        // Hold the GATE open: they roll off the end and along the wall.
        HingeBody gate = s.rig.world.mover("red GATE");
        for (int i = 0; i < 3000; i++) {
            gate.setAngle(1.2);
            s.elements.beforeStep(DT);
            s.rig.step(DT);
        }
        assertEquals(0, game.ramp("red").size(), "the RAMP emptied");
        for (Ball b : ramp) {
            // Off the low end of the RAMP (x = 0.016 m) and down on the tiles, toward the audience.
            assertTrue(b.position().get0() > 0.016 && b.position().get2() < b.radius + 0.01, "released onto the tiles, at " + b.position());
        }
        // Let go: gravity closes it.
        s.run(2.0);
        assertEquals(0, gate.angle(), 0.03, "closed again");
    }

    @Test
    void robotPushesTheGateOpen() throws Exception {
        Setup s = setup("decode", "mecanum.json");
        HingeBody gate = s.rig.world.mover("red GATE");
        // In the red GATE ZONE, between two rows of SPIKE MARKS, facing the GATE's paddle.
        s.rig.place(-0.004, 49.2 * IN, Math.PI / 2);
        s.run(0.5);
        assertEquals(0, gate.angle(), 0.01, "closed");
        drive(s.rig, 0.35);
        s.run(1.5);
        System.out.printf(Locale.US, "GATE at %.0f deg, robot at y %.3f%n", Math.toDegrees(gate.angle()), s.rig.body.position().get1());
        assertTrue(gate.angle() > Math.toRadians(35), "pushed open, at " + Math.toDegrees(gate.angle()) + " deg");
        drive(s.rig, -0.35);
        s.run(1.0);
        drive(s.rig, 0);
        s.run(1.5);
        assertEquals(0, gate.angle(), 0.03, "closes when the robot backs away");
    }

    private static void drive(RobotRig rig, double power) {
        for (String m : new String[]{"front_left_drive", "back_left_drive"}) port(rig, m).power = -power;
        for (String m : new String[]{"front_right_drive", "back_right_drive"}) port(rig, m).power = power;
    }

    private static MotorPort port(RobotRig rig, String name) {
        return rig.motors.get(name).port;
    }

    @Test
    void humanPlayerPutsArtifactsInTheLoadingZone() throws Exception {
        Setup s = setup("decode", "mecanum.json");
        DecodeGame game = s.decode();
        // The red ALLIANCE AREA had 4 purple and 2 green; the robot took purple, purple, green.
        assertEquals(2, game.left("red-purple"));
        assertEquals(1, game.left("red-green"));
        assertEquals(4, game.left("blue-purple"));
        for (int i = 0; i < 2; i++) assertTrue(game.act("red-purple"));
        assertTrue(game.act("red-green"));
        assertFalse(game.act("red-green"), "none left");
        s.run(1.5);
        int inZone = 0;
        for (Ball b : s.elements.balls()) {
            DVector3C p = b.position();
            if (b.state() == State.FREE && p.get0() > 47.53 * IN && p.get1() < -47.53 * IN) inZone++;
        }
        assertEquals(3 + 3, inZone, "3 staged in the red LOADING ZONE plus 3 from the human player");
    }

    @Test
    void theObeliskShowsTheMotif() throws Exception {
        Setup s = setup("decode", "mecanum.json");
        DecodeGame game = s.decode();
        CameraWorld cameras = new CameraWorld(s.field);
        String[] motifs = {"GPP", "PGP", "PPG"};
        for (int tag = 21; tag <= 23; tag++) {
            game.setMotif(tag);
            assertEquals(motifs[tag - 21], game.motif());
            for (FieldTag t : cameras.tags(s.rig.world.moverAngles())) {
                if (t.id() < 21 || t.id() > 23) continue;
                boolean towardField = t.normal()[0] > 0.99;
                assertEquals(t.id() == tag, towardField, "tag " + t.id() + " faces the field only when it is the MOTIF");
            }
        }
        assertTrue(game.stats().stream().anyMatch(st -> st.label().equals("MOTIF") && st.value().equals("PPG")));
    }

    /**
     * FIRST's own AprilTagProcessor, given the SDK's DECODE tag library, works out where the
     * camera is from the red GOAL's tag: it must be where the simulator has it, in DECODE's field
     * coordinates. So the season's field and FIRST's agree, and TeamCode localizes correctly.
     */
    @Test
    void firstsAprilTagLocalizationWorksOnTheDecodeField(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("teamcode"));
        Path robot = Path.of("robots/mecanum.json").toAbsolutePath();
        TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(new TeamCodeCompiler.SourceRoot(dir.resolve("teamcode"), false)),
                dir.resolve("build"));
        Simulator sim = new Simulator(Season.load("decode").field, robot, robot.getParent(), compiler, "test");
        VisionPortal portal = null;
        try {
            sim.start();
            VisionAssumptions.assumeDetectorAvailable();
            // 1.3 m in front of the red GOAL's tag, facing it.
            double heading = Math.toDegrees(Math.atan2(0.8096, -0.587));
            double x = -58.3727 * IN + 0.587 * 1.3, y = 55.6425 * IN - 0.8096 * 1.3;
            sim.handle(String.format(Locale.US, "{\"type\":\"place\",\"x\":%f,\"y\":%f,\"h\":%f}", x, y, heading));
            Thread.sleep(600);
            HardwareMap hw = sim.rig().createHardwareMap(new HalSession());
            SimWebcamName webcam = (SimWebcamName) hw.get(WebcamName.class, "Webcam 1");
            SimCamera camera = (SimCamera) webcam.camera();
            AprilTagProcessor processor = new AprilTagProcessor.Builder()
                    .setTagLibrary(AprilTagGameDatabase.getDecodeTagLibrary())
                    .setOutputUnits(DistanceUnit.METER, AngleUnit.DEGREES)
                    .build();
            portal = new VisionPortal.Builder().setCamera(webcam).addProcessor(processor).build();
            AprilTagSingleDetection found = null;
            long deadline = System.currentTimeMillis() + 20000;
            while (found == null && System.currentTimeMillis() < deadline) {
                for (AprilTagDetection d : processor.getDetections()) {
                    if (d instanceof AprilTagSingleDetection t && t.metadata != null && t.id == 24) found = t;
                }
                if (found == null) Thread.sleep(50);
            }
            assertNotNull(found, "FIRST's processor finds tag 24 in the simulated image");
            assertEquals("RedTarget", found.metadata.name);
            assertNotNull(found.robotPose, "with the tag's field position, FIRST's processor solves the camera's");
            Position p = found.robotPose.getPosition().toUnit(DistanceUnit.METER);
            double[] truth = camera.poseAt(found.frameAcquisitionNanoTime)[0];
            System.out.printf(Locale.US, "camera by FIRST's localization (%.3f, %.3f, %.3f) m, truth (%.3f, %.3f, %.3f) m%n",
                    p.x, p.y, p.z, truth[0], truth[1], truth[2]);
            assertEquals(truth[0], p.x, 0.03, "x");
            assertEquals(truth[1], p.y, 0.03, "y");
            assertEquals(truth[2], p.z, 0.03, "z");
        } finally {
            if (portal != null) portal.close();
            sim.shutdown();
        }
    }
}
