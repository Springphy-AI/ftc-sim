package ftcsim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftcsim.cad.CadImport;
import ftcsim.cad.CadMeshFile;
import ftcsim.cad.CadState;
import ftcsim.cad.Geom3;
import ftcsim.cad.MeshFiles;
import ftcsim.cad.MeshMath;
import ftcsim.cad.MeshPart;
import ftcsim.cad.ModelBuilder;
import ftcsim.cad.RobotModel;
import ftcsim.config.RobotConfig;
import ftcsim.hal.Latency;
import ftcsim.hal.ServoPort;
import ftcsim.physics.FieldModel;
import ftcsim.physics.Mechanism;
import ftcsim.physics.PhysicsWorld;
import ftcsim.physics.RobotBody;
import ftcsim.sim.GameElements;
import ftcsim.sim.GameElements.Ball;
import ftcsim.sim.GameElements.State;
import ftcsim.sim.RobotRig;

/**
 * Robots from CAD: a STEP assembly (converted to triangles by the Driver Station page; the
 * conversion is committed next to it) and a URDF, read into links, joints and mass properties,
 * and simulated with their moving parts as rigid bodies.
 */
class CadImportTest {
    private static final double DT = 0.001;

    private static RobotConfig load(String file) throws Exception {
        RobotConfig cfg = RobotConfig.load(Path.of("robots", file));
        assertNotNull(cfg.cadState, file + " has a cad section");
        System.out.println(file + ": " + cfg.cadState.status + " " + cfg.cadState.message);
        if (cfg.model() != null) for (String n : cfg.model().notes) System.out.println("  note: " + n);
        return cfg;
    }

    private static RobotRig rig(RobotConfig cfg) throws Exception {
        Latency.commandNanos = 0;
        Latency.i2cNanos = 0;
        RobotRig rig = new RobotRig(cfg, new PhysicsWorld(FieldModel.loadBiobuzz()));
        rig.place(0, -1.0, 0);   // open floor between the audience wall and the HIVE structure
        return rig;
    }

    private static void run(RobotRig rig, double seconds) {
        for (int i = 0; i < Math.round(seconds / DT); i++) rig.step(DT);
    }

    private static RobotModel.Group group(RobotModel m, String name) {
        RobotModel.Group g = m.group(name);
        assertNotNull(g, "group " + name + " in " + m.groups.stream().map(x -> x.name).collect(Collectors.toList()));
        return g;
    }

    // ---- reading ----------------------------------------------------------------------------------

    @Test
    void stepRobotHasItsLinksWheelsAndMass() throws Exception {
        RobotConfig cfg = load("mecanum-cad.json");
        assertEquals(CadState.Status.READY, cfg.cadState.status, cfg.cadState.message);
        RobotModel m = cfg.model();
        assertEquals(RobotModel.Kind.SPINNER, group(m, "intake roller").kind);
        assertEquals(RobotModel.Kind.SPINNER, group(m, "flywheel").kind);
        assertEquals(RobotModel.Kind.SPINNER, group(m, "feeder").kind);
        RobotModel.Group hood = group(m, "hood");
        assertEquals(RobotModel.Kind.LINK, hood.kind);
        assertEquals("hood", hood.device);
        assertEquals(0.15, hood.mass, 1e-9);
        // The hood turns on its pivot shaft: across the robot, at the shaft's center.
        assertEquals(1.0, Math.abs(hood.axis[1]), 1e-6, "hood axis along y: " + java.util.Arrays.toString(hood.axis));
        double pivotX = -0.110 + 0.090 * Math.cos(Math.toRadians(110)), pivotZ = 0.330 + 0.090 * Math.sin(Math.toRadians(110));
        assertEquals(pivotX, hood.anchor[0], 0.002);
        assertEquals(pivotZ, hood.anchor[2], 0.002);
        // The CAD's origin is a corner of the robot; the robot frame's is under its center, on the floor.
        for (String k : ModelBuilder.wheelKeys("mecanum")) {
            RobotModel.Group w = m.wheel(k);
            assertNotNull(w, "wheel " + k);
            assertEquals(k.startsWith("front") ? 0.168 : -0.168, w.anchor[0], 0.002, k + " x");
            assertEquals(k.endsWith("left") ? 0.178 : -0.178, w.anchor[1], 0.002, k + " y");
            assertEquals(0.052, w.anchor[2], 0.002, k + " z");
            assertEquals(0.052, w.wheelRadius, 0.001);
        }
        // The robot's numbers follow the CAD.
        assertEquals(104, cfg.drive.wheelDiameterMm, 1);
        assertEquals(0.336, cfg.drive.wheelbaseM, 0.002);
        assertEquals(0.356, cfg.drive.trackWidthM, 0.002);
        assertEquals(13.0, m.totalMass, 1e-6, "chassis.mass_kg spread over the parts");
        assertTrue(m.base().boxes.size() > 10, "collision boxes for the chassis parts");
        System.out.println(m.summary);
    }

    @Test
    void stepRobotNeedsOnlyItsFileName(@TempDir Path dir) throws Exception {
        RobotModel listed = load("mecanum-cad.json").model();
        RobotConfig cfg = stepRobot(dir, "auto", exampleParts(), "\"FILE\"");
        RobotModel m = cfg.model();
        assertTrue(m.notes.stream().anyMatch(n -> n.startsWith("the front is CAD +y (the end with the intake); up is CAD +z (from the wheels)")),
                "faces the way mecanum-cad.json says: " + m.notes);
        sameWheels(listed, m);
        // The flywheel and the feeder wheel are named like wheels but aren't on the floor.
        assertEquals(4, m.groups.stream().filter(g -> g.kind == RobotModel.Kind.WHEEL).count());
        // Rollers and flywheels, found by their motors' names: the same parts and axes as listed by hand.
        String[][] found = {{"intake", "intake roller"}, {"launcher", "flywheel"}, {"feeder", "feeder"}};
        for (String[] f : found) {
            RobotModel.Group g = m.drivenBy(f[0]), want = group(listed, f[1]);
            assertNotNull(g, f[0] + " found");
            assertTrue(g.found);
            assertEquals(RobotModel.Kind.SPINNER, g.kind);
            assertEquals(want.parts.size(), g.parts.size(), f[0] + ": " + g.parts.stream().map(MeshPart::path).collect(Collectors.toList()));
            sameAxis(want, g);
            assertTrue(Double.isNaN(g.axisInertia), "keeps the robot file's inertia");
        }
        assertNull(m.drivenBy("hood"), "a servo's link is listed: how far it may turn isn't in the CAD");
        assertEquals(13.0, m.totalMass, 1e-6);
        rig(cfg);   // builds
    }

    @Test
    void stepRobotFromAnyCadToolFacesTheSameWay(@TempDir Path dir) throws Exception {
        List<MeshPart> cad = exampleParts();
        RobotModel upright = stepRobot(dir, "z-up", cad, "\"FILE\"").model();
        // Fusion 360, SolidWorks, Inventor: y is up. The same robot with its up along +y, its front along -z.
        double[] yUp = Geom3.transform(Geom3.axisAngle(new double[]{1, 0, 0}, -Math.PI / 2), new double[]{0.1, 0.2, -0.3});
        RobotModel m = stepRobot(dir, "y-up", transformed(cad, yUp), "{ \"file\": \"FILE\" }").model();
        assertTrue(m.notes.stream().anyMatch(n -> n.startsWith("the front is CAD -z (the end with the intake); up is CAD +y (from the wheels)")), m.notes.toString());
        sameWheels(upright, m);
        // Modeled facing the other way: the intake still says which end is the front.
        double[] back = Geom3.transform(Geom3.axisAngle(new double[]{0, 0, 1}, Math.PI), new double[3]);
        m = stepRobot(dir, "turned", transformed(cad, back), "\"FILE\"").model();
        assertTrue(m.notes.stream().anyMatch(n -> n.startsWith("the front is CAD -y (the end with the intake)")), m.notes.toString());
        sameWheels(upright, m);
        // What the robot file says wins.
        m = stepRobot(dir, "given", cad, "{ \"file\": \"FILE\", \"forward\": \"+y\", \"up\": \"+z\" }").model();
        assertTrue(m.notes.stream().noneMatch(n -> n.contains("the front is")), m.notes.toString());
        sameWheels(upright, m);
    }

    /** The example's wheels, each named for where it is (FL Wheel...), as a CAD whose wheels aren't found by name would be listed. */
    private static List<MeshPart> namedWheels(List<MeshPart> cad) {
        double[] all = MeshPart.emptyBounds();   // the wheels' middle: the CAD's origin is elsewhere
        for (MeshPart p : cad) if (p.path().contains("Mecanum Wheel")) p.addBounds(all);
        double mx = (all[0] + all[3]) / 2, my = (all[1] + all[4]) / 2;
        return cad.stream().map(p -> {
            if (!p.path().contains("Mecanum Wheel")) return p;
            double[] b = p.bounds();   // forward +y, up +z: left is -x
            String where = ((b[1] + b[4]) / 2 > my ? "F" : "B") + ((b[0] + b[3]) / 2 < mx ? "L" : "R");
            return new MeshPart("Robot/" + where + " Pod/" + p.name(), p.rgb(), p.positions(), p.indices());
        }).collect(Collectors.toList());
    }

    /** A round part of radius r and width w at c (meters, z up), its axle along y (across the robot) or x. */
    private static MeshPart wheel(String path, double r, double w, double[] c, boolean acrossTheRobot) {
        MeshFiles.Mesh m = MeshFiles.cylinder(r, w, 24);
        double[] rot = acrossTheRobot ? new double[]{1, 0, 0, 0, 0, -1, 0, 1, 0} : new double[]{0, 0, 1, 0, 1, 0, -1, 0, 0};
        return new MeshPart(path, 0x333333, Geom3.apply(Geom3.transform(rot, c), m.positions()), m.indices());
    }

    /**
     * mecanum.json built from a CAD of a plate and four 104 mm mecanum wheels on a 0.336 x 0.350 m
     * footprint (z up, +x forward), plus {@code more}: the robot file's numbers become the CAD's.
     */
    private static RobotModel mecanumWith(RobotConfig cfg, List<MeshPart> more) {
        cfg.cad = new RobotConfig.Cad();
        cfg.cad.file = "test.step";
        cfg.cad.forward = "+x";
        cfg.cad.up = "+z";
        List<MeshPart> parts = new ArrayList<>();
        parts.add(part("Robot/Drivetrain/Plate", 0, 0, 0.06, 0.40, 0.40, 0.01));
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                parts.add(wheel("Robot/Drivetrain/104mm Mecanum Wheel", 0.052, 0.038, new double[]{sx * 0.168, sy * 0.175, 0.052}, true));
            }
        }
        parts.addAll(more);
        RobotModel m = ModelBuilder.fromStep(cfg, parts, "test.step");
        ModelBuilder.applyTo(cfg, m);
        for (String n : m.notes) System.out.println("  note: " + n);
        return m;
    }

    @Test
    void odometryPodsAreNotTakenForDriveWheels() throws Exception {
        // Two 35 mm pods, where mecanum.json's Pinpoint has them, touching the floor like the drive wheels.
        for (String pod : new String[]{"Odometry Pod #/Omni Wheel", "Pod #/35mm Omni Wheel", "Tracking #/Wheel"}) {
            RobotConfig cfg = RobotConfig.load(Path.of("robots/mecanum.json"), false);
            mecanumWith(cfg, List.of(
                    wheel("Robot/" + pod.replace("#", "X"), 0.0175, 0.012, new double[]{0.0, -0.084, 0.0175}, true),
                    wheel("Robot/" + pod.replace("#", "Y"), 0.0175, 0.012, new double[]{-0.168, 0.0, 0.0175}, false)));
            System.out.printf("%s: wheel %.1f mm, wheelbase %.3f m, track %.3f m%n", pod,
                    cfg.drive.wheelDiameterMm, cfg.drive.wheelbaseM, cfg.drive.trackWidthM);
            assertEquals(104, cfg.drive.wheelDiameterMm, 1, pod);
            assertEquals(0.336, cfg.drive.wheelbaseM, 0.002, pod);
            assertEquals(0.350, cfg.drive.trackWidthM, 0.002, pod);
        }
    }

    @Test
    void extraWheelsAreNeverMergedIntoACornerWheel() throws Exception {
        // A full-size omni wheel in the middle of each side, as on a 6-wheel base: a mecanum
        // drive has four wheels, so the corners' are used and the middle two left out.
        RobotConfig cfg = RobotConfig.load(Path.of("robots/mecanum.json"), false);
        RobotModel m = mecanumWith(cfg, List.of(
                wheel("Robot/Drivetrain/Center Omni Wheel L", 0.052, 0.038, new double[]{0, 0.175, 0.052}, true),
                wheel("Robot/Drivetrain/Center Omni Wheel R", 0.052, 0.038, new double[]{0, -0.175, 0.052}, true)));
        assertEquals(104, cfg.drive.wheelDiameterMm, 1);
        assertEquals(0.336, cfg.drive.wheelbaseM, 0.002);
        assertEquals(0.350, cfg.drive.trackWidthM, 0.002);
        assertTrue(m.notes.stream().anyMatch(n -> n.contains("found 6 drive wheels") && n.contains("the 4 nearest its corners")),
                m.notes.toString());
    }

    @Test
    void listedWheelsShowWhichWayTheRobotFacesToo(@TempDir Path dir) throws Exception {
        List<MeshPart> cad = namedWheels(exampleParts());
        String listed = "{ \"file\": \"FILE\", \"wheels\": { \"front_left\": \"FL Pod*\", \"front_right\": \"FR Pod*\","
                + " \"back_left\": \"BL Pod*\", \"back_right\": \"BR Pod*\" } }";
        RobotModel upright = stepRobot(dir, "listed", cad, listed).model();
        // Exported y-up: the listed wheels say up is +y (it was taken as +z, a guess, and the robot lay on its side).
        double[] yUp = Geom3.transform(Geom3.axisAngle(new double[]{1, 0, 0}, -Math.PI / 2), new double[]{0.1, 0.2, -0.3});
        RobotModel m = stepRobot(dir, "listed-y-up", transformed(cad, yUp), listed).model();
        assertTrue(m.notes.stream().anyMatch(n -> n.contains("up is CAD +y (from the wheels)")), m.notes.toString());
        sameWheels(upright, m);
    }

    @Test
    void aLinkNeedsLittleMoreThanItsNameAndDevice(@TempDir Path dir) throws Exception {
        RobotModel listed = load("mecanum-cad.json").model();
        RobotConfig cfg = stepRobot(dir, "hood", exampleParts(), """
                { "file": "FILE", "links": [
                    { "name": "Hood", "device": "hood", "ratio": 0.2, "limits": [-35, 35], "reversed": true },
                    { "name": "Intake Roller", "device": "intake" } ] }""");
        RobotModel m = cfg.model();
        RobotModel.Group hood = group(m, "Hood"), want = group(listed, "hood");
        assertEquals(RobotModel.Kind.LINK, hood.kind, "revolute: a servo drives it");
        assertEquals(want.parts.size(), hood.parts.size(), "the parts called Hood");
        assertArrayEquals(want.axis, hood.axis, 1e-6);
        sameAxis(want, hood);
        assertTrue(m.notes.stream().anyMatch(n -> n.startsWith("Hood turns on Hood Pivot")), m.notes.toString());
        RobotModel.Group roller = group(m, "Intake Roller");
        assertEquals(RobotModel.Kind.SPINNER, roller.kind, "continuous: a roller motor drives it");
        sameAxis(group(listed, "intake roller"), roller);
        assertNotNull(m.drivenBy("launcher"), "the rest are still found by name");
    }

    @Test
    void aStepFileDroppedOnThePageBecomesARobot(@TempDir Path dir) throws Exception {
        Path robots = dir.resolve("robots"), template = robots.resolve("mecanum.json");
        Files.createDirectories(robots);
        Files.copy(Path.of("robots/mecanum.json"), template);
        byte[] step = Files.readAllBytes(Path.of("robots/cad/biobuzz-bot.step"));
        CadImport.Result r = CadImport.add(robots, "Our Robot v3.STEP", new ByteArrayInputStream(step), template);
        assertEquals(robots.resolve("our-robot-v3.json"), r.robotFile());
        assertEquals(robots.resolve("cad/our-robot-v3.step"), r.step());
        assertFalse(r.updated());
        RobotConfig cfg = RobotConfig.load(r.robotFile());
        assertEquals("cad/our-robot-v3.step", cfg.cad.file);
        assertEquals("Our Robot v3", cfg.name);
        assertEquals(RobotConfig.load(template).deviceNames(), cfg.deviceNames(), "the same hardware for TeamCode");
        assertEquals(CadState.Status.CONVERT, cfg.cadState.status, "the page converts it next");
        Files.copy(Path.of("robots/cad/biobuzz-bot.step.cadmesh"), CadMeshFile.cacheFor(r.step()));   // what it sends back
        cfg = RobotConfig.load(r.robotFile());
        assertEquals(CadState.Status.READY, cfg.cadState.status, cfg.cadState.message);
        assertNotNull(cfg.model().drivenBy("intake"));

        // A newer export of the same robot: the STEP file is replaced, the robot file and what was added to it kept.
        Files.writeString(r.robotFile(), Files.readString(r.robotFile()).replace("\"links\": []",
                "\"links\": [{ \"name\": \"Hood\", \"device\": \"hood\", \"limits\": [-35, 35] }]"));
        byte[] newer = (new String(step, StandardCharsets.ISO_8859_1) + "\n").getBytes(StandardCharsets.ISO_8859_1);
        CadImport.Result again = CadImport.add(robots, "Our Robot v3.step", new ByteArrayInputStream(newer), template);
        assertTrue(again.updated());
        assertEquals(r.robotFile(), again.robotFile());
        cfg = RobotConfig.load(r.robotFile());
        assertEquals("Hood", cfg.cad.links.get(0).name);
        assertEquals(CadState.Status.CONVERT, cfg.cadState.status, "changed, so converted again");

        // A robot file of the same name that isn't from this STEP file is left alone.
        Files.copy(template, robots.resolve("arm.json"));
        assertEquals(robots.resolve("arm-2.json"), CadImport.add(robots, "Arm.stp", new ByteArrayInputStream(step), template).robotFile());

        assertThrows(IllegalArgumentException.class, () -> CadImport.add(robots, "robot.stl", new ByteArrayInputStream(step), template));
        assertThrows(IllegalArgumentException.class,
                () -> CadImport.add(robots, "robot.step", new ByteArrayInputStream("solid robot".getBytes(StandardCharsets.US_ASCII)), template));
        try (var left = Files.list(robots.resolve("cad"))) {
            assertEquals(List.of("arm-2.step", "our-robot-v3.step", "our-robot-v3.step.cadmesh"),
                    left.map(f -> f.getFileName().toString()).sorted().collect(Collectors.toList()), "no temporary files left");
        }
        assertEquals("evil", CadImport.baseName("../../evil.step"));
        assertEquals("\u673a\u5668\u4eba-v2", CadImport.baseName("\u673a\u5668\u4eba v2.step"));
        assertEquals("con-robot", CadImport.baseName("CON.step"));
    }

    @Test
    void urdfRobotHasItsJointsAndCadMasses() throws Exception {
        RobotConfig cfg = load("tank-urdf.json");
        assertEquals(CadState.Status.READY, cfg.cadState.status, cfg.cadState.message);
        RobotModel m = cfg.model();
        RobotModel.Group arm = group(m, "left_arm");
        assertEquals(RobotModel.Kind.LINK, arm.kind);
        assertEquals("left_arm", arm.device, "bound by the joint's name");
        assertArrayEquals(new double[]{0, -1, 0}, arm.axis, 1e-9);
        assertEquals(0.11, arm.anchor[0], 1e-6);
        assertEquals(0.27, arm.anchor[2], 1e-6, "pivot height above the floor");
        assertEquals(-0.62, arm.lower, 1e-9);
        assertEquals(2.1, arm.upper, 1e-9);
        assertEquals(Math.toRadians(-35), arm.start, 1e-9);
        assertEquals(0.5, arm.mass, 1e-9);
        RobotModel.Group left = group(m, "left_hand"), right = group(m, "right_hand");
        assertEquals(m.index(arm), left.parentIndex, "fingers ride on the arm");
        assertEquals(m.index(arm), right.parentIndex);
        for (String k : ModelBuilder.wheelKeys("tank")) assertNotNull(m.wheel(k), "wheel " + k);
        assertEquals(8.3 + 6 * 0.2 + 0.5 + 0.08, m.totalMass, 1e-6, "the URDF's masses");
        assertEquals(101.6, cfg.drive.wheelDiameterMm, 0.1);
        assertEquals(0.30, cfg.drive.wheelbaseM, 1e-6);
        assertEquals(0.36, cfg.drive.trackWidthM, 1e-6);
    }

    /** Exporters often name links by part number: the drive wheels are the free continuous joints on the floor. */
    @Test
    void urdfWheelsNeedNotBeNamedWheel(@TempDir Path dir) throws Exception {
        Path src = Path.of("robots/cad/arm-bot");
        try (var files = Files.walk(src)) {
            for (Path f : files.collect(Collectors.toList())) {
                Path to = dir.resolve("arm-bot").resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) Files.createDirectories(to);
                else Files.copy(f, to);
            }
        }
        Path urdf = dir.resolve("arm-bot/arm-bot.urdf");
        String renamed = Files.readString(urdf).replace("_wheel\"", "_pod\"");
        assertTrue(!renamed.contains("_wheel\""), "no link is named like a wheel");
        Files.writeString(urdf, renamed);
        Files.writeString(dir.resolve("robot.json"), Files.readString(Path.of("robots/tank-urdf.json")).replace("cad/arm-bot/", "arm-bot/"));
        RobotConfig cfg = RobotConfig.load(dir.resolve("robot.json"));
        assertEquals(CadState.Status.READY, cfg.cadState.status, cfg.cadState.message);
        RobotModel want = load("tank-urdf.json").model(), got = cfg.model();
        for (String k : ModelBuilder.wheelKeys("tank")) {
            assertNotNull(got.wheel(k), "wheel " + k + " in " + got.notes);
            assertArrayEquals(want.wheel(k).anchor, got.wheel(k).anchor, 1e-6, k);
        }
    }

    /** A wheel link whose mesh file is missing (moved, or a path's case wrong on Linux). */
    @Test
    void aUrdfWheelWithoutItsMeshSaysWhichMeshIsMissing(@TempDir Path dir) throws Exception {
        Path src = Path.of("robots/cad/arm-bot");
        try (var files = Files.walk(src)) {
            for (Path f : files.collect(Collectors.toList())) {
                Path to = dir.resolve("arm-bot").resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) Files.createDirectories(to);
                else Files.copy(f, to);
            }
        }
        Files.delete(dir.resolve("arm-bot/meshes/wheel.stl"));
        Files.writeString(dir.resolve("robot.json"), Files.readString(Path.of("robots/tank-urdf.json")).replace("cad/arm-bot/", "arm-bot/"));
        RobotConfig cfg = RobotConfig.load(dir.resolve("robot.json"));
        assertEquals(CadState.Status.ERROR, cfg.cadState.status, cfg.cadState.message);
        assertTrue(cfg.cadState.message.contains("wheel") && cfg.cadState.message.contains("mesh not found: package://arm-bot/meshes/wheel.stl"),
                cfg.cadState.message);
        // The robot file's own numbers instead of an infinite wheel, so the robot is still built.
        assertTrue(cfg.drive.wheelDiameterMm > 0 && Double.isFinite(cfg.drive.wheelDiameterMm), "wheel diameter " + cfg.drive.wheelDiameterMm);
        assertNotNull(new RobotRig(cfg, new PhysicsWorld(FieldModel.loadBiobuzz())).body);
    }

    @Test
    void meshFileRoundTripsAndRejectsDamage() throws Exception {
        MeshPart p = new MeshPart("Robot/Arm/Bar", 0x336699, new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0}, new int[]{0, 1, 2});
        byte[] data = CadMeshFile.encode("x.step", "abc", "test", List.of(p));
        List<MeshPart> back = CadMeshFile.read(data);
        assertEquals(1, back.size());
        assertEquals("Robot/Arm/Bar", back.get(0).path());
        assertEquals(0x336699, back.get(0).rgb());
        assertArrayEquals(p.positions(), back.get(0).positions());
        assertArrayEquals(p.indices(), back.get(0).indices());
        assertEquals("abc", CadMeshFile.header(data).sha256());
        byte[] bad = data.clone();
        bad[bad.length - 4] = 9;   // an index past the part's vertices
        assertThrows(java.io.IOException.class, () -> CadMeshFile.read(bad));
        assertThrows(java.io.IOException.class, () -> CadMeshFile.read(java.util.Arrays.copyOf(data, data.length - 6)));
        // A header that is JSON but not the right shape is a bad file too, not a crash.
        for (String header : new String[]{"{\"parts\":{}}", "{\"parts\":[{\"vertices\":3}]}"}) {
            byte[] json = header.getBytes(StandardCharsets.UTF_8);
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(12 + json.length + 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            bb.put(CadMeshFile.MAGIC.getBytes(StandardCharsets.US_ASCII)).putInt(json.length).put(json);
            assertThrows(java.io.IOException.class, () -> CadMeshFile.header(bb.array()), header);
        }
    }

    /** Two pages sending the same conversion at once: each write goes through a temporary file of its own. */
    @Test
    void meshFileWritesDontCollide(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("robot.step.cadmesh");
        byte[] a = new byte[300_000], b = new byte[300_000];
        java.util.Arrays.fill(a, (byte) 1);
        java.util.Arrays.fill(b, (byte) 2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<java.util.concurrent.Future<?>> writes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            writes.add(pool.submit(() -> { CadMeshFile.writeAtomically(file, a); return null; }));
            writes.add(pool.submit(() -> { CadMeshFile.writeAtomically(file, b); return null; }));
        }
        for (var w : writes) w.get();
        pool.shutdown();
        byte[] got = Files.readAllBytes(file);
        assertTrue(java.util.Arrays.equals(got, a) || java.util.Arrays.equals(got, b), "one whole write, not a mix");
        try (var left = Files.list(dir)) {
            assertEquals(List.of("robot.step.cadmesh"), left.map(f -> f.getFileName().toString()).collect(Collectors.toList()), "no temporary files left");
        }
    }

    @Test
    void stepFileIsConvertedOnceAndAgainWhenItChanges(@TempDir Path dir) throws Exception {
        Path step = dir.resolve("cad/robot.step");
        Files.createDirectories(step.getParent());
        Files.copy(Path.of("robots/cad/biobuzz-bot.step"), step);
        Files.writeString(dir.resolve("robot.json"),
                Files.readString(Path.of("robots/mecanum-cad.json")).replace("cad/biobuzz-bot.step", "cad/robot.step"));
        RobotConfig cfg = RobotConfig.load(dir.resolve("robot.json"));
        assertEquals(CadState.Status.CONVERT, cfg.cadState.status, "no converted mesh yet");
        assertNull(cfg.model());
        assertEquals(0.4572, cfg.chassis.lengthM, 1e-9, "until then, the robot file's numbers");

        // What the page sends back (the committed conversion of the same STEP file).
        Files.copy(Path.of("robots/cad/biobuzz-bot.step.cadmesh"), CadMeshFile.cacheFor(step));
        cfg = RobotConfig.load(dir.resolve("robot.json"));
        assertEquals(CadState.Status.READY, cfg.cadState.status, cfg.cadState.message);

        // Checked out with Windows line endings: the same file, no new conversion.
        String text = Files.readString(step, StandardCharsets.ISO_8859_1);
        Files.writeString(step, text.replace("\n", "\r\n"), StandardCharsets.ISO_8859_1);
        cfg = RobotConfig.load(dir.resolve("robot.json"));
        assertEquals(CadState.Status.READY, cfg.cadState.status, "line endings don't count");

        Files.writeString(step, text + "\n", StandardCharsets.ISO_8859_1);   // edited in CAD
        cfg = RobotConfig.load(dir.resolve("robot.json"));
        assertEquals(CadState.Status.CONVERT, cfg.cadState.status, "the STEP file changed");
        assertNotNull(cfg.model(), "the previous conversion is shown meanwhile");

        // A part added in CAD and named in the robot file at once: the old conversion doesn't have
        // it, but the new STEP file is still converted (only nothing is shown meanwhile).
        Path json = dir.resolve("robot.json");
        String withClaw = Files.readString(json).replace("\"links\": [", "\"links\": [\n      { \"name\": \"Claw\", \"device\": \"hood\" },");
        Files.writeString(json, withClaw);
        cfg = RobotConfig.load(json);
        assertEquals(CadState.Status.CONVERT, cfg.cadState.status, cfg.cadState.message);
        assertNull(cfg.model());

        // A damaged conversion is converted again too.
        Files.write(CadMeshFile.cacheFor(step), java.util.Arrays.copyOf(Files.readAllBytes(Path.of("robots/cad/biobuzz-bot.step.cadmesh")), 5000));
        cfg = RobotConfig.load(json);
        assertEquals(CadState.Status.CONVERT, cfg.cadState.status, cfg.cadState.message);
        assertTrue(cfg.cadState.message.contains("damaged"), cfg.cadState.message);
    }

    @Test
    void anEmptyConversionSaysHowToConvertInstead(@TempDir Path dir) throws Exception {
        // A big assembly fills the page's 2 GB: OpenCascade names every part but makes no triangles.
        Path step = dir.resolve("cad/robot.step");
        Files.createDirectories(step.getParent());
        Files.copy(Path.of("robots/cad/biobuzz-bot.step"), step);
        Files.writeString(dir.resolve("robot.json"),
                Files.readString(Path.of("robots/mecanum-cad.json")).replace("cad/biobuzz-bot.step", "cad/robot.step"));
        byte[] empty = CadMeshFile.encode("robot.step", CadMeshFile.sha256(step), "occt-import-js 0.0.23",
                List.of(new MeshPart("Robot/Side Rail", 0x9aa0a6, new float[0], new int[0])));
        assertEquals(0, CadMeshFile.header(empty).triangles());

        // Saved by a simulator that didn't refuse it yet: an error that says what to do, not a silent plain robot.
        Files.write(CadMeshFile.cacheFor(step), empty);
        RobotConfig cfg = RobotConfig.load(dir.resolve("robot.json"));
        assertEquals(CadState.Status.ERROR, cfg.cadState.status, cfg.cadState.message);
        assertTrue(cfg.cadState.message.contains("tools/step2cadmesh.py"), cfg.cadState.message);
        assertNull(cfg.model());
        assertNotNull(rig(cfg), "still a working robot, from the robot file's numbers");
    }

    @Test
    void badCadFallsBackToTheRobotFileNumbers(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("robot.json"), Files.readString(Path.of("robots/mecanum-cad.json"))
                .replace("\"axis_part\": \"Hood Pivot\"", "\"axis_part\": \"No Such Part\""));
        Files.createDirectories(dir.resolve("cad"));
        Files.copy(Path.of("robots/cad/biobuzz-bot.step"), dir.resolve("cad/biobuzz-bot.step"));
        Files.copy(Path.of("robots/cad/biobuzz-bot.step.cadmesh"), dir.resolve("cad/biobuzz-bot.step.cadmesh"));
        RobotConfig cfg = RobotConfig.load(dir.resolve("robot.json"));
        assertEquals(CadState.Status.ERROR, cfg.cadState.status);
        assertTrue(cfg.cadState.message.contains("No Such Part"), cfg.cadState.message);
        RobotRig rig = rig(cfg);   // still a working robot
        assertNull(rig.body.model);
    }

    @Test
    void namePatternsMatchAnyAssemblyLevel() {
        assertTrue(ModelBuilder.matches("Robot/Arm Assembly/Arm Bar", "Arm Assembly"));
        assertTrue(ModelBuilder.matches("Robot/Arm Assembly/Arm Bar", "arm*"));
        assertTrue(ModelBuilder.matches("Robot/Arm Assembly/Arm Bar", "Robot/Arm*/*"));
        assertTrue(!ModelBuilder.matches("Robot/Arm Assembly/Arm Bar", "Arm"));
        assertTrue(!ModelBuilder.matches("Robot/Hood Pivot", "Hood"));
    }

    @Test
    void massPropertiesOfABoxMesh() {
        MeshFiles.Mesh b = MeshFiles.box(0.2, 0.1, 0.05);
        MeshMath.MassProps mp = MeshMath.massProps(new MeshPart("box", 0, Geom3.apply(Geom3.transform(Geom3.IDENTITY, new double[]{1, 2, 3}), b.positions()), b.indices()));
        // float32 vertices: about 7 significant digits
        assertEquals(0.2 * 0.1 * 0.05, mp.volume(), 1e-8);
        assertArrayEquals(new double[]{1, 2, 3}, mp.com(), 1e-6);
        double[] in = mp.inertiaForMass(2.0);
        double[] expected = MeshMath.boxInertia(2.0, 0.2, 0.1, 0.05);
        assertArrayEquals(expected, in, 1e-6);
        // Turned (30 degrees about z, then 20 about x): the inertia turns with it, R I R^T, products of
        // inertia and their signs included.
        double[] r = Geom3.matMul(Geom3.axisAngle(new double[]{1, 0, 0}, Math.toRadians(20)), Geom3.axisAngle(new double[]{0, 0, 1}, Math.toRadians(30)));
        MeshMath.MassProps turned = MeshMath.massProps(new MeshPart("box", 0, Geom3.apply(Geom3.transform(r, new double[]{1, 2, 3}), b.positions()), b.indices()));
        double[] want = Geom3.matMul(Geom3.matMul(r, expected), Geom3.transpose(r));
        assertTrue(Math.abs(want[1]) > 1e-4, "the turned box has products of inertia to check");
        assertArrayEquals(want, turned.inertiaForMass(2.0), 1e-6);
        assertArrayEquals(new double[]{1, 2, 3}, turned.com(), 1e-6);
    }

    @Test
    void anOpenSheetWeighsNothingWhereverItIs() {
        // A 0.2 m square sheet (a decal, a surface body), 0.3 m up: open, it encloses nothing.
        float[] sheet = {-0.1f, -0.1f, 0.3f, 0.1f, -0.1f, 0.3f, 0.1f, 0.1f, 0.3f, -0.1f, 0.1f, 0.3f};
        MeshMath.MassProps mp = MeshMath.massProps(new MeshPart("decal", 0, sheet, new int[]{0, 1, 2, 0, 2, 3}));
        assertEquals(0, mp.volume(), 1e-9, "summed to the origin it came out as 0.004 m^3, half the example robot");
        // An open box (its top left out by the tessellation) still weighs about what it encloses, wherever it is.
        MeshFiles.Mesh box = MeshFiles.box(0.2, 0.2, 0.2);
        int[] open = java.util.Arrays.copyOf(box.indices(), box.indices().length - 6);
        double near = MeshMath.massProps(new MeshPart("open box", 0, box.positions(), open)).volume();
        float[] far = Geom3.apply(Geom3.transform(Geom3.IDENTITY, new double[]{1.5, -2, 0.8}), box.positions());
        assertEquals(near, MeshMath.massProps(new MeshPart("open box", 0, far, open)).volume(), 1e-7);
    }

    // ---- simulating -------------------------------------------------------------------------------

    @Test
    void cadRobotRestsAndDrives() throws Exception {
        RobotRig rig = rig(load("mecanum-cad.json"));
        run(rig, 1.0);
        double[] v = rig.body.bodyVelocity();
        assertEquals(0, Math.hypot(v[0], v[1]), 0.01, "sits still with no power");
        double[] origin = rig.body.toRobotFrame(rig.body.pointToWorld(0, 0, 0));
        assertArrayEquals(new double[]{0, 0, 0}, origin, 1e-9);
        assertEquals(0, rig.body.pointToWorld(0, 0, 0).get2(), 0.004, "robot-frame origin on the floor");
        // Turning in place about the robot's center: its center (what odometry measures from)
        // doesn't move, though the CAD center of mass, where the body's origin is, does.
        double[] com = rig.body.bodyOrigin();
        assertTrue(Math.hypot(com[0], com[1]) > 0.003, "the example's center of mass is off center: " + java.util.Arrays.toString(com));
        double w = 3.0, hd = rig.body.heading();
        double cx = Math.cos(hd) * com[0] - Math.sin(hd) * com[1], cy = Math.sin(hd) * com[0] + Math.cos(hd) * com[1];
        rig.body.chassis.setAngularVel(0, 0, w);
        rig.body.chassis.setLinearVel(-w * cy, w * cx, 0);   // v = w x r, r from the center to the center of mass
        double[] turning = rig.body.bodyVelocity();
        assertEquals(0, Math.hypot(turning[0], turning[1]), 1e-9, "no translation while turning in place");
        rig.body.chassis.setAngularVel(0, 0, 0);
        rig.body.chassis.setLinearVel(0, 0, 0);
        rig.motors.get("front_left_drive").port.power = -1;
        rig.motors.get("back_left_drive").port.power = -1;
        rig.motors.get("front_right_drive").port.power = 1;
        rig.motors.get("back_right_drive").port.power = 1;
        run(rig, 1.0);
        v = rig.body.bodyVelocity();
        System.out.printf("CAD robot forward: v=(%.3f, %.3f) m/s, heading %.2f deg%n", v[0], v[1], Math.toDegrees(rig.body.heading()));
        assertTrue(v[0] > 1.2 && v[0] < 1.95, "forward speed " + v[0]);
        assertEquals(0, v[1], 0.05, "no sideways drift");
        assertEquals(0, Math.toDegrees(rig.body.heading()), 1.5, "no turning");
    }

    @Test
    void armRisesOnItsMotorAndFallsBackOnItsStop() throws Exception {
        RobotRig rig = rig(load("tank-urdf.json"));
        Mechanism arm = rig.mechanisms.stream().filter(x -> x.device.name.equals("left_arm")).findFirst().orElseThrow();
        RobotBody.LinkBody link = arm.joint();
        assertNotNull(link, "the arm motor drives the CAD joint");
        run(rig, 0.5);
        assertEquals(-0.62, link.position(), 0.03, "resting on its lower stop");
        int ticks0 = rig.motors.get("left_arm").port.encoderPosition();
        rig.motors.get("left_arm").port.power = 0.6;
        run(rig, 0.35);
        double up = link.position();
        int ticks = rig.motors.get("left_arm").port.encoderPosition() - ticks0;
        System.out.printf("arm: %.1f deg after 0.35 s, %d ticks%n", Math.toDegrees(up), ticks);
        assertTrue(up > 0.3 && up < 1.4, "the arm swung up, short of vertical: " + Math.toDegrees(up) + " deg");
        double ticksPerRad = rig.motors.get("left_arm").spec.ticksPerRadian();
        assertEquals((up + 0.62) * ticksPerRad, ticks, 0.05 * ticksPerRad, "the encoder follows the joint");
        rig.motors.get("left_arm").port.power = 0;
        run(rig, 0.3);   // BRAKE stops it
        rig.motors.get("left_arm").port.zeroPowerBehavior = com.qualcomm.robotcore.hardware.DcMotor.ZeroPowerBehavior.FLOAT;
        run(rig, 3.0);
        assertEquals(-0.62, link.position(), 0.05, "unpowered, gravity takes it back down");

        rig.motors.get("left_arm").port.power = 0.6;
        run(rig, 2.0);
        assertEquals(2.1, link.position(), 0.03, "past vertical, up against its top stop");
        rig.motors.get("left_arm").port.power = 0;
        run(rig, 1.0);
        assertEquals(2.1, link.position(), 0.03, "leaning back, it stays on that stop");
    }

    @Test
    void hoodFollowsItsServo() throws Exception {
        RobotRig rig = rig(load("mecanum-cad.json"));
        ServoPort hood = rig.servoPorts().stream().filter(p -> p.name.equals("hood")).findFirst().orElseThrow();
        hood.pwmEnabled = true;
        hood.position = 0.8;
        run(rig, 1.0);
        RobotBody.LinkBody link = rig.servoJoints.get(0).link;
        double target = rig.servoJoints.get(0).target();
        System.out.printf("hood: target %.1f deg, at %.1f deg%n", Math.toDegrees(target), Math.toDegrees(link.position()));
        assertEquals(Math.toRadians((0.8 - 0.5) * 1800 / 900 * 150 * 0.2), target, 1e-6);
        assertEquals(target, link.position(), Math.toRadians(1.0));
        hood.position = 0.2;
        run(rig, 1.0);
        assertEquals(-target, link.position(), Math.toRadians(1.0));
    }

    @Test
    void afterAResetTheHoodMovesFromItsStart() throws Exception {
        RobotRig rig = rig(load("mecanum-cad.json"));
        ServoPort hood = rig.servoPorts().stream().filter(p -> p.name.equals("hood")).findFirst().orElseThrow();
        RobotBody.LinkBody link = rig.servoJoints.get(0).link;
        double start = link.position();
        hood.pwmEnabled = true;
        hood.position = 0.9;   // raised 24 degrees
        run(rig, 1.0);
        rig.place(0, -1.0, 0);   // what Reset does: the robot and its links back at the start...
        rig.powerCycle();        // ...and the power cycled, which leaves the servo limp
        hood.pwmEnabled = true;
        hood.position = 0.5;     // the next OpMode centers it: where it starts
        double max = -1e9;
        for (int i = 0; i < 400; i++) {
            rig.step(DT);
            max = Math.max(max, link.position());
        }
        System.out.printf("hood after a reset: start %.1f deg, highest %.1f deg%n", Math.toDegrees(start), Math.toDegrees(max));
        assertEquals(start, max, Math.toRadians(1), "the hood stays at its start, not swinging up toward where it was");
    }

    @Test
    void cadIntakeAndLauncherWork() throws Exception {
        RobotConfig cfg = load("mecanum-cad.json");
        FieldModel field = FieldModel.loadBiobuzz();
        Latency.commandNanos = 0;
        Latency.i2cNanos = 0;
        RobotRig rig = new RobotRig(cfg, new PhysicsWorld(field));
        GameElements elements = new GameElements(field, rig);
        Runnable step = () -> {
            elements.beforeStep(DT);
            rig.step(DT);
        };
        rig.place(0, -1.0, 0);
        rig.motors.get("intake").port.power = -1;
        for (int i = 0; i < 1500; i++) step.run();
        assertEquals(0, elements.heldCount(), "reversed intake ejects the pre-load");
        for (Ball b : elements.balls()) assertTrue(b.position().get2() > 0, "every ball above the floor");
        rig.motors.get("intake").port.power = 0;
        rig.place(0, -0.3, 0);
        for (int i = 0; i < 300; i++) step.run();
        List<Ball> free = elements.balls().stream().filter(b -> b.state() == State.FREE).limit(2).collect(Collectors.toList());
        rig.motors.get("intake").port.power = 1;
        for (Ball b : free) {
            elements.placeBall(b, 0.30, -0.3);   // in front of the CAD roller (x = 0.205 m)
            for (int i = 0; i < 300; i++) step.run();
        }
        assertEquals(2, elements.heldCount(), "the CAD roller takes balls in");
        rig.motors.get("intake").port.power = 0;
        rig.motors.get("launcher").port.power = 1;
        for (int i = 0; i < 1500; i++) step.run();
        ServoPort feeder = rig.servoPorts().stream().filter(p -> p.name.equals("feeder")).findFirst().orElseThrow();
        feeder.position = 1.0;
        feeder.pwmEnabled = true;
        // Each ball leaves from the CAD flywheel's rim, at the hood's angle (50° with the hood servo
        // limp at its center), at the flywheel's launch speed.
        double[] flywheel = Geom3.apply(rig.body.robotTransform(), new double[]{-0.110, 0, 0.330});
        List<double[]> launches = new ArrayList<>();
        java.util.Set<Ball> seen = new java.util.HashSet<>();
        for (int i = 0; i < 1500; i++) {
            step.run();
            for (Ball b : free) {
                if (b.state() != State.FREE || !seen.add(b)) continue;
                double[] p = {b.position().get0(), b.position().get1(), b.position().get2()};
                double[] v = {b.velocity().get0(), b.velocity().get1(), b.velocity().get2()};
                double[] fromAxis = Geom3.mul(Geom3.transpose(Geom3.rot(rig.body.robotTransform())), Geom3.sub(p, flywheel));
                launches.add(new double[]{Geom3.norm(Geom3.sub(p, flywheel)), Geom3.norm(v), Math.toDegrees(Math.atan2(v[2], Math.hypot(v[0], v[1]))),
                        Math.toDegrees(Math.atan2(fromAxis[2], fromAxis[0])), fromAxis[1]});
            }
        }
        assertEquals(0, elements.heldCount(), "both launched");
        assertEquals(2, launches.size());
        for (double[] l : launches) {
            System.out.printf("CAD launch: %.0f mm from the flywheel's axis, %.1f m/s at %.1f deg%n", l[0] * 1000, l[1], l[2]);
            assertEquals(0.048 + 0.0355 + 0.005, l[0], 0.02, "leaves at the flywheel's rim (measured a step later)");
            assertTrue(l[1] > 5, "launch speed " + l[1]);
            assertEquals(50, l[2], 3, "launch angle");
            // Not wherever the flywheel's spin has carried the rest-pose rim to.
            assertEquals(50, l[3], 6, "leaves the rim in the launch direction, in front of and above the axis");
            assertEquals(0, l[4], 0.01, "across the robot: in the middle of the flywheel");
        }
    }

    /** A two-stage cascade slide from STEP parts: the motor drives stage 1, stage 2 follows at twice. */
    @Test
    void cascadeSlideFollowsItsMotor(@TempDir Path dir) throws Exception {
        List<MeshPart> parts = new ArrayList<>();
        parts.add(part("Robot/Frame", 0, 0, 0.06, 0.40, 0.36, 0.04));
        parts.add(part("Robot/Slide Rail", 0, 0, 0.25, 0.02, 0.02, 0.40));
        parts.add(part("Robot/Stage 1", 0.03, 0, 0.25, 0.02, 0.10, 0.36));
        parts.add(part("Robot/Stage 2", 0.06, 0, 0.25, 0.02, 0.08, 0.32));
        for (double x : new double[]{0.15, -0.15}) {
            for (double y : new double[]{0.17, -0.17}) parts.add(part("Robot/Wheel", x, y, 0.05, 0.10, 0.03, 0.10));
        }
        Path step = dir.resolve("slide.step");
        Files.writeString(step, "ISO-10303-21; not really, the triangles below stand in for its conversion");
        Files.write(CadMeshFile.cacheFor(step), CadMeshFile.encode("slide.step", CadMeshFile.sha256(step), "test", parts));
        String json = Files.readString(Path.of("robots/mecanum.json"))
                .replace("{ \"type\": \"motor\", \"name\": \"intake\"", "{ \"type\": \"motor\", \"name\": \"lift\", \"motor\": \"goBILDA 5203 435rpm\" },\n    { \"type\": \"motor\", \"name\": \"intake\"")
                .replace("\n  \"io\":", """
                          "cad": {
                            "file": "slide.step",
                            "links": [
                              { "name": "stage1", "parts": ["Stage 1"], "joint": "prismatic", "axis_part": "Slide Rail",
                                "device": "lift", "limits": [0, 300], "spool_diameter_mm": 38, "mass_kg": 0.3 },
                              { "name": "stage2", "parts": ["Stage 2"], "joint": "prismatic", "parent": "stage1",
                                "axis_dir": [0, 0, 1], "mimic": "stage1", "limits": [0, 300], "mass_kg": 0.2 }
                            ]
                          },
                          "io":""");
        Files.writeString(dir.resolve("robot.json"), json);
        RobotConfig cfg = RobotConfig.load(dir.resolve("robot.json"));
        assertEquals(CadState.Status.READY, cfg.cadState.status, cfg.cadState.message);
        RobotRig rig = rig(cfg);
        RobotBody.LinkBody s1 = rig.body.link(cfg.model().index(cfg.model().group("stage1")));
        RobotBody.LinkBody s2 = rig.body.link(cfg.model().index(cfg.model().group("stage2")));
        run(rig, 0.3);
        assertEquals(0, s1.position(), 0.005, "the slide rests down");
        rig.motors.get("lift").port.power = 1;
        // The motor's rotor, geared up through the spool, is most of what the motor accelerates.
        run(rig, 0.010);
        ftcsim.config.MotorModels.Spec spec = ftcsim.config.MotorModels.get("goBILDA 5203 435rpm");
        double k = 1 / 0.019;   // motor output radians per meter of slide
        double rotor = spec.reflectedInertia() * k * k, stages = 0.3 + 0.2, weight = stages * 9.81;
        double withRotor = (spec.stallTorqueNm() * k - weight) / (stages + rotor) * 0.010;
        double without = (spec.stallTorqueNm() * k - weight) / stages * 0.010;
        System.out.printf("slide after 10 ms: %.3f m/s (motor and rotor: %.3f, without the rotor's %.1f kg: %.3f)%n",
                s1.rate(), withRotor, rotor, without);
        assertTrue(s1.rate() > 0.3 * withRotor && s1.rate() < 1.3 * withRotor, "accelerates with the rotor: " + s1.rate());
        run(rig, 0.390);
        System.out.printf("slide: stage 1 at %.0f mm, stage 2 at %.0f mm (relative)%n", s1.position() * 1000, s2.position() * 1000);
        assertTrue(s1.position() > 0.05, "the motor lifts stage 1: " + s1.position());
        assertEquals(s1.position(), s2.position(), 0.01, "stage 2 follows stage 1 (cascade)");
        run(rig, 2.0);
        assertEquals(0.30, s1.position(), 0.01, "up against its top stop");
        double heightOfStage2 = rig.groupTransforms()[cfg.model().index(cfg.model().group("stage2"))][11] - rig.body.robotTransform()[11];
        assertEquals(0.60, heightOfStage2, 0.02, "stage 2 rides 300 mm on stage 1's 300 mm");
    }

    private static List<MeshPart> exampleParts() throws Exception {
        return CadMeshFile.read(Path.of("robots/cad/biobuzz-bot.step.cadmesh"));
    }

    private static List<MeshPart> transformed(List<MeshPart> parts, double[] tf) {
        return parts.stream().map(p -> p.transformed(tf)).collect(Collectors.toList());
    }

    /**
     * mecanum.json with a STEP file that converts to {@code parts} and this cad section (FILE
     * stands for the STEP file's name).
     */
    private static RobotConfig stepRobot(Path dir, String name, List<MeshPart> parts, String cad) throws Exception {
        Path step = dir.resolve(name + ".step");
        Files.writeString(step, "ISO-10303-21; the triangles below stand in for the conversion of " + name);
        Files.write(CadMeshFile.cacheFor(step), CadMeshFile.encode(step.getFileName().toString(), CadMeshFile.sha256(step), "test", parts));
        String json = Files.readString(Path.of("robots/mecanum.json"))
                .replace("\n  \"io\":", "\n  \"cad\": " + cad.replace("FILE", name + ".step") + ",\n  \"io\":");
        Files.writeString(dir.resolve(name + ".json"), json);
        RobotConfig cfg = RobotConfig.load(dir.resolve(name + ".json"));
        assertEquals(CadState.Status.READY, cfg.cadState.status, cfg.cadState.message);
        System.out.println(name + ": " + cfg.cadState.message);
        for (String n : cfg.model().notes) System.out.println("  note: " + n);
        return cfg;
    }

    private static void sameWheels(RobotModel want, RobotModel got) {
        for (String k : ModelBuilder.wheelKeys("mecanum")) {
            assertNotNull(got.wheel(k), "wheel " + k);
            assertArrayEquals(want.wheel(k).anchor, got.wheel(k).anchor, 0.002, "wheel " + k);
        }
    }

    /** The same joint axis: parallel, through the same points. */
    private static void sameAxis(RobotModel.Group want, RobotModel.Group got) {
        assertEquals(0, Geom3.norm(Geom3.cross(want.axis, got.axis)), 1e-3, got.name + " axis " + java.util.Arrays.toString(got.axis));
        double[] d = Geom3.sub(got.anchor, want.anchor);
        double off = Geom3.norm(Geom3.sub(d, Geom3.scale(want.axis, Geom3.dot(d, want.axis))));
        assertEquals(0, off, 0.003, got.name + " axis position");
    }

    private static MeshPart part(String path, double x, double y, double z, double sx, double sy, double sz) {
        MeshFiles.Mesh b = MeshFiles.box(sx, sy, sz);
        return new MeshPart(path, 0x808080, Geom3.apply(Geom3.transform(Geom3.IDENTITY, new double[]{x, y, z}), b.positions()), b.indices());
    }
}
