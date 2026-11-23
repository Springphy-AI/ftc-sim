package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftcsim.config.RobotConfig;

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
