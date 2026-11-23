package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftcsim.config.RobotConfig;
import ftcsim.physics.FieldModel;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.sim.Simulator;

/** A robot file that can't describe a robot is refused when it is read, with a message naming what is wrong. */
class RobotFileTest {

    /** robots/mecanum.json with {@code from} replaced by {@code to}. */
    private static Path mecanumWith(Path dir, String from, String to) throws Exception {
        String json = Files.readString(Path.of("robots/mecanum.json"));
        assertTrue(json.contains(from), "robots/mecanum.json has " + from);
        Path file = dir.resolve("robot.json");
        Files.writeString(file, json.replace(from, to));
        return file;
    }

    private static String refused(Path file) {
        return assertThrows(IllegalArgumentException.class, () -> RobotConfig.load(file)).getMessage();
    }

    private static final String HOOD = "{ \"type\": \"servo\", \"name\": \"hood\",";

    @Test
    void twoDevicesWithOneNameAreRefused(@TempDir Path dir) throws Exception {
        String m = refused(mecanumWith(dir, HOOD, "{ \"type\": \"servo\", \"name\": \"launcher\","));
        assertTrue(m.contains("\"launcher\" is used twice"), m);
    }

    @Test
    void aDeviceNamedLikeADriveMotorIsRefused(@TempDir Path dir) throws Exception {
        String m = refused(mecanumWith(dir, "\"name\": \"intake\"", "\"name\": \"front_left_drive\""));
        assertTrue(m.contains("\"front_left_drive\" is used twice") && m.contains("drive.motors.front_left"), m);
    }

    @Test
    void anAliasThatIsAnotherDevicesNameIsRefused(@TempDir Path dir) throws Exception {
        String m = refused(mecanumWith(dir, HOOD, HOOD + " \"aliases\": [\"feeder\"],"));
        assertTrue(m.contains("\"feeder\" is used twice") && m.contains("aliases of hood"), m);
        m = refused(mecanumWith(dir, HOOD, HOOD + " \"aliases\": [\"imu\"],"));
        assertTrue(m.contains("\"imu\" is used twice"), m);
    }

    private static final String INTAKE_MOTOR = "\"motor\": \"goBILDA 5203 1150rpm\", \"role\": \"intake\"";

    @Test
    void aMotorWithoutAModelIsRefusedListingTheModels(@TempDir Path dir) throws Exception {
        for (String motor : new String[]{"\"motor\": null, \"role\": \"intake\"", "\"role\": \"intake\""}) {
            String m = refused(mecanumWith(dir, INTAKE_MOTOR, motor));
            assertTrue(m.contains("device intake: motor is missing") && m.contains("goBILDA 5203 312rpm"), m);
        }
        String m = refused(mecanumWith(dir, "\"motor\": \"goBILDA 5203 312rpm\",", "\"motor\": null,"));
        assertTrue(m.contains("drive.motor is missing"), m);
        m = refused(mecanumWith(dir, INTAKE_MOTOR, "\"motor\": \"goBILDA 1150\", \"role\": \"intake\""));
        assertTrue(m.contains("device intake: motor: Unknown motor model \"goBILDA 1150\"") && m.contains("REV Core Hex"), m);
    }

    @Test
    void aStartThatIsNotASpotIsRefused(@TempDir Path dir) throws Exception {
        String io = "\"io\": { \"command_ms\": 1.0, \"i2c_ms\": 2.5 }";
        String m = refused(mecanumWith(dir, io, io + ", \"start\": {\"biobuzz\": [0.5]}"));
        assertTrue(m.contains("start.biobuzz must be [x, y] or [x, y, heading]"), m);
        m = refused(mecanumWith(dir, io, io + ", \"start\": [0.5, \"left\", 90]"));
        assertTrue(m.contains("start must be [x, y] or [x, y, heading]"), m);
        m = refused(mecanumWith(dir, io, io + ", \"start\": \"red 1\""));
        assertTrue(m.contains("start must be {"), m);
        assertEquals(90, RobotConfig.load(mecanumWith(dir, io, io + ", \"start\": {\"decode\": [0.5, 1, 90]}")).startFor("decode")[2]);
    }

    @Test
    void anImuThatCannotBeMountedIsRefused(@TempDir Path dir) throws Exception {
        String imu = "\"imu\": { \"name\": \"imu\", \"logo\": \"UP\", \"usb\": \"FORWARD\" }";
        String m = refused(mecanumWith(dir, imu, "\"imu\": { \"name\": \"imu\", \"logo\": \"TOP\", \"usb\": \"FORWARD\" }"));
        assertTrue(m.contains("imu.logo must be one of UP, DOWN, FORWARD, BACKWARD, LEFT, RIGHT, not \"TOP\""), m);
        m = refused(mecanumWith(dir, imu, "\"imu\": { \"name\": \"imu\", \"logo\": \"UP\", \"usb\": \"DOWN\" }"));
        assertTrue(m.contains("imu: logo UP and usb DOWN"), m);
        m = refused(mecanumWith(dir, imu, "\"imu\": null"));
        assertTrue(m.contains("imu must be an object"), m);
        assertEquals("left", RobotConfig.load(mecanumWith(dir, imu, "\"imu\": { \"name\": \"imu\", \"logo\": \"left\", \"usb\": \"up\" }")).imu.logo);
    }

    @Test
    void aFileThatIsNotARobotFileIsRefused(@TempDir Path dir) throws Exception {
        String m = refused(mecanumWith(dir, "\"chassis\": {", "\"chassis\": 5, \"x\": {"));
        assertTrue(m.startsWith("not a robot file: ") && m.contains("$.chassis"), m);
        m = refused(mecanumWith(dir, "\"devices\": [", "\"devices\": [null, "));
        assertTrue(m.contains("devices[0]"), m);
    }

    @Test
    void theSimulatorSaysWhichRobotFileIsWrong(@TempDir Path dir) throws Exception {
        Path file = mecanumWith(dir, INTAKE_MOTOR, "\"role\": \"intake\"");
        TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(new TeamCodeCompiler.SourceRoot(dir, false)), dir.resolve("build"));
        String m = assertThrows(IllegalArgumentException.class, () -> new Simulator(FieldModel.loadBiobuzz(), dir,
                List.of(new Simulator.RobotSpec(file, compiler, "test", null)))).getMessage();
        assertTrue(m.startsWith("Robot file " + file + ": device intake: motor is missing"), m);
    }

    @Test
    void anIntakeDirectionIsForwardOrReverseOnAnIntake(@TempDir Path dir) throws Exception {
        assertTrue(RobotConfig.load(mecanumWith(dir, INTAKE_MOTOR, INTAKE_MOTOR + ", \"intake_direction\": \"Reverse\""))
                .devices.get(0).intakeReversed());
        assertTrue(!RobotConfig.load(mecanumWith(dir, INTAKE_MOTOR, INTAKE_MOTOR + ", \"intake_direction\": \"forward\""))
                .devices.get(0).intakeReversed());
        String m = refused(mecanumWith(dir, INTAKE_MOTOR, INTAKE_MOTOR + ", \"intake_direction\": \"backward\""));
        assertTrue(m.contains("device intake: intake_direction must be \"forward\" or \"reverse\", not \"backward\""), m);
        m = refused(mecanumWith(dir, HOOD, HOOD + " \"intake_direction\": \"reverse\","));
        assertTrue(m.contains("device hood: intake_direction is for an intake"), m);
    }

    @Test
    void wheelsChainedToOneMotorMayShareItsName(@TempDir Path dir) throws Exception {
        Path file = mecanumWith(dir, "\"type\": \"mecanum\"", "\"type\": \"tank4\"");
        String json = Files.readString(file)
                .replace("\"back_left\": \"back_left_drive\"", "\"back_left\": \"front_left_drive\"")
                .replace("\"back_right\": \"back_right_drive\"", "\"back_right\": \"front_right_drive\"");
        Files.writeString(file, json);
        assertEquals("front_left_drive", RobotConfig.load(file).driveMotorName("back_left"));
    }
}
