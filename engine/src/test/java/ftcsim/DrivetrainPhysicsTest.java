package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qualcomm.robotcore.hardware.DcMotor;

import ftcsim.config.RobotConfig;
import ftcsim.hal.Latency;
import ftcsim.hal.MotorPort;
import ftcsim.physics.FieldModel;
import ftcsim.physics.PhysicsWorld;
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
