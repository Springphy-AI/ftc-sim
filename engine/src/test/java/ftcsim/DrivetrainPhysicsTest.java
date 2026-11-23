package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.HardwareMap;

import ftcsim.cad.ModelBuilder;
import ftcsim.config.MotorModels;
import ftcsim.config.RobotConfig;
import ftcsim.hal.HalSession;
import ftcsim.hal.Latency;
import ftcsim.hal.MotorPort;
import ftcsim.physics.FieldModel;
import ftcsim.physics.Mechanism;
import ftcsim.physics.MotorUnit;
import ftcsim.physics.PhysicsWorld;
import ftcsim.physics.RobotBody;
import ftcsim.sim.RobotRig;

/** Sanity checks of the drivetrain physics, driving the motor ports directly (no OpMode). */
class DrivetrainPhysicsTest {
    private static final double DT = 0.001;

    private static RobotRig mecanum() throws Exception {
        return rig(RobotConfig.load(Path.of("robots/mecanum.json")));
    }

    /** robots/mecanum.json with its drive's {@code "mirrored_side": "left"} line replaced. */
    private static Path mecanumWith(Path dir, String mirroredLine) throws Exception {
        String json = Files.readString(Path.of("robots/mecanum.json"));
        String left = "\"mirrored_side\": \"left\",";
        assertTrue(json.contains(left), "robots/mecanum.json should say which side is mirrored");
        Path file = dir.resolve("robot.json");
        Files.writeString(file, json.replace(left, mirroredLine));
        return file;
    }

    private static RobotRig rig(RobotConfig cfg) throws Exception {
        RobotRig rig = new RobotRig(cfg, new PhysicsWorld(FieldModel.loadBiobuzz()));
        Latency.commandNanos = 0;
        Latency.i2cNanos = 0;
        rig.place(0, -1.0, 0);   // open floor between the audience wall and the HIVE structure
        return rig;
    }

    private static void run(RobotRig rig, double seconds) {
        for (int i = 0; i < Math.round(seconds / DT); i++) rig.step(DT);
    }

    /** Sets wheel powers the way the FTC samples do: left side reversed. */
    private static void drive(RobotRig rig, double fl, double fr, double bl, double br) {
        port(rig, "front_left_drive").power = -fl;
        port(rig, "back_left_drive").power = -bl;
        port(rig, "front_right_drive").power = fr;
        port(rig, "back_right_drive").power = br;
    }

    private static MotorPort port(RobotRig rig, String name) {
        return rig.motors.get(name).port;
    }

    @Test
    void restsOnTheFloor() throws Exception {
        RobotRig rig = mecanum();
        run(rig, 1.0);
        double[] v = rig.body.bodyVelocity();
        assertEquals(0, Math.hypot(v[0], v[1]), 0.01, "robot should sit still with no power");
        assertEquals(-1.0, rig.body.position().get1(), 0.01);
    }

    @Test
    void drivesForwardAtAboutFreeSpeed() throws Exception {
        RobotRig rig = mecanum();
        drive(rig, 1, 1, 1, 1);
        run(rig, 1.0);
        double[] v = rig.body.bodyVelocity();
        System.out.printf("forward: v=(%.3f, %.3f) m/s, heading %.2f deg, battery %.2f V%n",
                v[0], v[1], Math.toDegrees(rig.body.heading()), rig.batteryVolts());
        // 312 RPM on 104 mm wheels is 1.70 m/s free at 12 V; a fresh 13 V battery spins faster.
        assertTrue(v[0] > 1.2 && v[0] < 1.95, "forward speed " + v[0]);
        assertEquals(0, v[1], 0.05, "no sideways drift");
        assertEquals(0, Math.toDegrees(rig.body.heading()), 1.0, "no turning");
    }

    @Test
    void strafesRight() throws Exception {
        RobotRig rig = mecanum();
        rig.place(0, -0.75, 0);   // 0.85 m of open floor to the right
        // lateral = +1 (right): FL +, FR -, BL -, BR +
        drive(rig, 1, -1, -1, 1);
        run(rig, 0.4);
        double[] v = rig.body.bodyVelocity();
        System.out.printf("strafe: v=(%.3f, %.3f) m/s%n", v[0], v[1]);
        assertTrue(v[1] < -0.8, "should move right (negative y), got " + v[1]);
        assertEquals(0, v[0], 0.1, "no forward drift while strafing");
    }

    @Test
    void turnsCounterClockwise() throws Exception {
        RobotRig rig = mecanum();
        // yaw = -1 in the sample convention turns left (CCW): FL -, FR +, BL -, BR +
        drive(rig, -1, 1, -1, 1);
        run(rig, 0.5);
        double[] w = rig.body.bodyAngularVelocity();
        System.out.printf("turn: omega=%.3f rad/s%n", w[2]);
        assertTrue(w[2] > 2.0, "should spin CCW quickly, got " + w[2]);
    }

    @Test
    void runToPositionReachesTarget() throws Exception {
        RobotRig rig = mecanum();
        rig.place(-0.9, -1.0, 0);
        String[] names = {"front_left_drive", "front_right_drive", "back_left_drive", "back_right_drive"};
        int target = 1000;
        for (String n : names) {
            MotorPort p = port(rig, n);
            p.targetPosition = n.contains("left") ? -target : target;
            p.targetPositionSet = true;
            p.mode = DcMotor.RunMode.RUN_TO_POSITION;
            p.modeGeneration++;
            p.power = 0.5;
        }
        run(rig, 3.0);
        int notBusy = 0;
        for (String n : names) {
            MotorPort p = port(rig, n);
            int expected = n.contains("left") ? -target : target;
            System.out.printf("  %s: %d (busy=%s)%n", n, p.encoderPosition(), p.isBusyNow());
            assertEquals(expected, p.encoderPosition(), 15, n + " encoder");
            if (!p.isBusyNow()) notBusy++;
        }
        // The samples finish a move as soon as one side reports it is no longer busy.
        assertTrue(notBusy > 0, "at least one wheel should report it reached its target");
        // 1000 ticks of a 537.7 tick/rev motor on a 104 mm wheel is 0.608 m.
        double travelled = rig.body.position().get0() + 0.9;
        System.out.printf("run to position: travelled %.3f m%n", travelled);
        assertEquals(0.608, travelled, 0.03);
    }

    @Test
    void stopsAtTheWall() throws Exception {
        RobotRig rig = mecanum();
        rig.place(0, -1.0, -Math.PI / 2);   // facing the audience wall, 0.6 m away
        drive(rig, 0.6, 0.6, 0.6, 0.6);
        run(rig, 3.0);
        double y = rig.body.position().get1();
        System.out.printf("wall: y=%.3f m (wall face at -1.829, robot half-length 0.229)%n", y);
        assertEquals(-1.8288 + 0.2286, y, 0.02, "chassis front should rest against the wall");
    }

    /** robots/mecanum.json as a tank4, with {@code motors} as its drive's motors. */
    private static Path tank4(Path dir, String name, String motors) throws Exception {
        String json = Files.readString(Path.of("robots/mecanum.json"));
        String mecanumMotors = json.substring(json.indexOf("\"motors\": {"), json.indexOf('}', json.indexOf("\"motors\": {")) + 1);
        Path file = dir.resolve(name + ".json");
        Files.writeString(file, json.replace("\"type\": \"mecanum\"", "\"type\": \"tank4\"").replace(mecanumMotors, "\"motors\": " + motors));
        return file;
    }

    /** Full power forward for {@code seconds} from x = -0.9: the forward speed reached, the left encoder's count and the distance. */
    private static double[] driveForward(RobotRig rig, String leftMotor, String rightMotor, double seconds) {
        port(rig, leftMotor).power = -1;   // the left side is mirrored
        port(rig, rightMotor).power = 1;
        run(rig, seconds);
        return new double[]{rig.body.bodyVelocity()[0], port(rig, leftMotor).encoderPosition(), rig.body.position().get0() + 0.9};
    }

    @Test
    void aTwoMotorTank4DrivesEachSidesWheelsWithOneMotor(@TempDir Path dir) throws Exception {
        RobotRig four = rig(RobotConfig.load(tank4(dir, "four", """
                {"front_left": "front_left_drive", "back_left": "back_left_drive", "front_right": "front_right_drive", "back_right": "back_right_drive"}""")));
        four.place(-0.9, -1.0, 0);
        port(four, "back_left_drive").power = -1;
        port(four, "back_right_drive").power = 1;
        double[] fourEarly = driveForward(four, "front_left_drive", "front_right_drive", 0.25);
        // Two motors, each chained to both wheels of its side.
        RobotRig two = rig(RobotConfig.load(tank4(dir, "two", """
                {"front_left": "left_drive", "back_left": "left_drive", "front_right": "right_drive", "back_right": "right_drive"}""")));
        two.place(-0.9, -1.0, 0);
        double[] twoEarly = driveForward(two, "left_drive", "right_drive", 0.25);
        System.out.printf("tank4 after 0.25 s: 4 motors %.3f m/s %d ticks, 2 motors %.3f m/s %d ticks%n",
                fourEarly[0], (long) fourEarly[1], twoEarly[0], (long) twoEarly[1]);
        // Half the motors, half the torque: slower off the line (each wheel got a whole motor's torque before).
        assertTrue(twoEarly[0] < 0.85 * fourEarly[0], "2 motors accelerate slower than 4");
        // One encoder per motor, counting its wheels' turns once (it counted them twice before):
        // 537.7 ticks per turn of a 104 mm wheel is 1646 ticks per meter rolled.
        double ticksPerMeter = 537.7 / (Math.PI * 0.104);
        for (double[] r : new double[][]{fourEarly, twoEarly}) {
            assertEquals(r[2] * ticksPerMeter, -r[1], 0.25 * r[2] * ticksPerMeter, "the encoder follows the wheels");
        }
        // One port per motor: the intake moves up to the Control Hub's free ports.
        assertTrue(two.motors.get("intake").port.hub == port(two, "left_drive").hub, "intake on the Control Hub");
    }

    @Test
    void eachHubReportsTheCurrentOfItsOwnMotors() throws Exception {
        RobotRig rig = mecanum();   // drive motors on the Control Hub, intake and launcher on Expansion Hub 2
        HardwareMap map = rig.createHardwareMap(new HalSession());
        LynxModule control = map.get(LynxModule.class, "Control Hub"), expansion = map.get(LynxModule.class, "Expansion Hub 2");
        assertTrue(port(rig, "launcher").hub != port(rig, "front_left_drive").hub, "the launcher is on the Expansion Hub");
        port(rig, "launcher").power = 1;
        run(rig, 0.05);   // spinning up: several amps
        double total = 0;
        for (MotorUnit u : rig.motors.values()) total += u.batteryAmps();
        double c = control.getCurrent(CurrentUnit.AMPS), e = expansion.getCurrent(CurrentUnit.AMPS);
        System.out.printf("hub currents: Control Hub %.3f A, Expansion Hub %.3f A, robot %.3f A%n", c, e, total);
        assertTrue(e > 1, "the Expansion Hub feeds the launcher: " + e + " A");
        assertEquals(0, c, 0.05, "the Control Hub's motors are off");
        assertEquals(total, c + e, 1e-9, "the hubs' currents add up to the robot's");
    }

    @Test
    void theBatterySagsUnderLoadButNotWhileTheMotorsBrake() throws Exception {
        RobotRig rig = mecanum();
        run(rig, 0.2);
        double rest = rig.batteryVolts();
        drive(rig, 1, 1, 1, 1);
        run(rig, 0.1);
        double accelerating = rig.batteryVolts();
        run(rig, 0.9);
        double cruising = rig.batteryVolts();
        // Full speed to power 0 on BRAKE: the motors short their windings, and that current only
        // circulates through the hub's H-bridges.
        drive(rig, 0, 0, 0, 0);
        double braking = Double.MAX_VALUE;
        for (int i = 0; i < 300; i++) {
            rig.step(DT);
            braking = Math.min(braking, rig.batteryVolts());
        }
        System.out.printf("battery: rest %.2f V, accelerating %.2f V, cruising %.2f V, lowest while braking %.2f V%n",
                rest, accelerating, cruising, braking);
        assertTrue(accelerating < rest - 0.3, "four motors starting up pull the battery down");
        assertTrue(cruising > accelerating, "less current at speed");
        assertTrue(braking > cruising - 0.02, "braking draws nothing from the battery");
    }

    @Test
    void aResetPutsTheArmBackWhereItsEncoderSaysItIs() throws Exception {
        RobotRig rig = rig(RobotConfig.load(Path.of("robots/tank.json")));
        Mechanism arm = rig.mechanisms.stream().filter(m -> m.device.name.equals("left_arm")).findFirst().orElseThrow();
        port(rig, "left_arm").power = 1;
        run(rig, 2.0);
        assertEquals(120, Math.toDegrees(arm.position()), 1, "raised to its stop");
        port(rig, "left_arm").power = 0;
        rig.powerCycle();   // what Reset does, with the robot back at its start
        run(rig, 0.2);
        assertEquals(0, port(rig, "left_arm").encoderPosition(), "the encoder starts from 0");
        assertEquals(-35, Math.toDegrees(arm.position()), 0.5, "and the arm from its start angle (it stayed raised before)");
    }

    @Test
    void wheelsChainedToOneMotorShareItsRotor() throws Exception {
        RobotConfig cfg = RobotConfig.load(Path.of("robots/tank.json"));
        RobotRig rig = rig(cfg);
        double gear = cfg.drive.externalGearRatio <= 0 ? 1 : cfg.drive.externalGearRatio;
        double rotor = MotorModels.get(cfg.drive.motor).reflectedInertia() * gear * gear;
        double r = rig.body.wheelRadius;
        for (RobotBody.DriveMotor dm : rig.body.driveMotors) {
            assertEquals(3, dm.wheels.size(), "a tank drives three wheels a side with one motor");
            // What the axles carry beyond the wheels themselves: one rotor, not one per wheel.
            double rotors = 0;
            for (RobotBody.Wheel w : dm.wheels) {
                rotors += w.body.getMass().getI().get11() - 0.5 * ModelBuilder.DEFAULT_WHEEL_MASS_KG * r * r;
            }
            assertEquals(rotor, rotors, rotor * 1e-9);
        }
    }

    @Test
    void aRobotThatCantBeBuiltLeavesNothingInTheWorld(@TempDir Path dir) throws Exception {
        String json = Files.readString(Path.of("robots/mecanum.json"));
        // Built until its launcher, which has no load to move.
        RobotConfig broken = RobotConfig.load(Path.of("robots/mecanum.json"));
        broken.devices.stream().filter(d -> d.name.equals("launcher")).findFirst().orElseThrow().load = null;
        PhysicsWorld world = new PhysicsWorld(FieldModel.loadBiobuzz());
        int before = world.space.getNumGeoms();
        assertThrows(RuntimeException.class, () -> new RobotRig(broken, world));
        assertEquals(before, world.space.getNumGeoms(), "no chassis or wheels left behind to collide with");
        // Such files are refused when they are read: a motor without a load, a wheel of no size.
        Path tiny = dir.resolve("tiny.json");
        Files.writeString(tiny, json.replace("\"wheel_diameter_mm\": 104", "\"wheel_diameter_mm\": -10"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> RobotConfig.load(tiny)).getMessage().contains("wheel_diameter_mm"));
        Path noLoad = dir.resolve("noload.json");
        Files.writeString(noLoad, json.replace("\"load\": { \"kind\": \"flywheel\", \"inertia\": 0.0003 }", "\"load\": null"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> RobotConfig.load(noLoad)).getMessage().contains("load"));
    }

    @Test
    void driveTypesAndTheirMotorsAreChecked(@TempDir Path dir) throws Exception {
        String json = Files.readString(Path.of("robots/mecanum.json"));
        Path skid = dir.resolve("skid.json");
        Files.writeString(skid, json.replace("\"type\": \"mecanum\"", "\"type\": \"skid\""));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> RobotConfig.load(skid)).getMessage().contains("drive.type"));
        Path missing = dir.resolve("missing.json");
        Files.writeString(missing, json.replace("\"back_right\": \"back_right_drive\"", "\"back_rite\": \"back_right_drive\""));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> RobotConfig.load(missing)).getMessage().contains("back_right is missing"));
        Path crossed = tank4(dir, "crossed", """
                {"front_left": "drive", "back_left": "drive", "front_right": "drive", "back_right": "right_drive"}""");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> RobotConfig.load(crossed)).getMessage().contains("both sides"));
    }

    @Test
    void rightMirroredRobotDrivesForwardWhenItsCodeReversesTheRightSide(@TempDir Path dir) throws Exception {
        RobotRig rig = rig(RobotConfig.load(mecanumWith(dir, "\"mirrored_side\": \"right\",")));
        // setDirection(REVERSE) on the right motors: forward on the sticks is negative power at their ports.
        port(rig, "front_left_drive").power = 1;
        port(rig, "back_left_drive").power = 1;
        port(rig, "front_right_drive").power = -1;
        port(rig, "back_right_drive").power = -1;
        run(rig, 1.0);
        double[] v = rig.body.bodyVelocity();
        System.out.printf("right mirrored: v=(%.3f, %.3f) m/s, heading %.2f deg%n", v[0], v[1], Math.toDegrees(rig.body.heading()));
        assertTrue(v[0] > 1.2, "should drive forward, got " + v[0]);
        assertEquals(0, v[1], 0.05, "no sideways drift");
        assertEquals(0, Math.toDegrees(rig.body.heading()), 1.0, "no turning");
    }

    @Test
    void robotWithNoMirroredSideDrivesForwardOnPlainPower(@TempDir Path dir) throws Exception {
        RobotRig rig = rig(RobotConfig.load(mecanumWith(dir, "\"mirrored_side\": \"none\",")));
        for (String n : new String[]{"front_left_drive", "back_left_drive", "front_right_drive", "back_right_drive"}) {
            port(rig, n).power = 1;
        }
        run(rig, 1.0);
        double[] v = rig.body.bodyVelocity();
        assertTrue(v[0] > 1.2, "should drive forward, got " + v[0]);
        assertEquals(0, Math.toDegrees(rig.body.heading()), 1.0, "no turning");
    }

    @Test
    void olderRobotFilesStillSayWhichSideIsMirrored(@TempDir Path dir) throws Exception {
        assertEquals("left", RobotConfig.load(mecanumWith(dir, "")).drive.mirroredSide(), "default");
        assertEquals("left", RobotConfig.load(mecanumWith(dir, "\"left_side_mirrored\": true,")).drive.mirroredSide());
        assertEquals("none", RobotConfig.load(mecanumWith(dir, "\"left_side_mirrored\": false,")).drive.mirroredSide());
        assertEquals("right", RobotConfig.load(mecanumWith(dir, "\"mirrored_side\": \"Right\",")).drive.mirroredSide());
    }

    @Test
    void rejectsAMirroredSideThatIsNotLeftRightOrNone(@TempDir Path dir) throws Exception {
        Path file = mecanumWith(dir, "\"mirrored_side\": \"both\",");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RobotConfig.load(file));
        assertTrue(e.getMessage().contains("mirrored_side"), e.getMessage());
    }
}
