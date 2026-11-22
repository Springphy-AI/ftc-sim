package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.Servo;

import ftcsim.config.RobotConfig;
import ftcsim.hal.HalSession;
import ftcsim.hal.Latency;
import ftcsim.physics.FieldModel;
import ftcsim.physics.PhysicsWorld;
import ftcsim.sim.RobotRig;

/** Servos through the SDK's Servo and CRServo, as TeamCode uses them, against the real SDK's behavior. */
class ServoTest {

    private final RobotRig rig;
    private final HardwareMap hardwareMap;

    ServoTest() throws Exception {
        rig = new RobotRig(RobotConfig.load(Path.of("robots/mecanum.json")), new PhysicsWorld(FieldModel.loadBiobuzz()));
        Latency.commandNanos = 0;
        Latency.i2cNanos = 0;
        hardwareMap = rig.createHardwareMap(new HalSession());
    }

    @Test
    void aServoReadsZeroBeforeItsFirstPositionSoNudgingItWorks() {
        Servo hood = hardwareMap.get(Servo.class, "hood");
        assertEquals(0.0, hood.getPosition(), "the hub reads a servo that has had no pulse yet as 0");
        // A common TeleOp pattern: nudge the servo from where it is.
        hood.setPosition(hood.getPosition() + 0.05);
        assertEquals(0.05, hood.getPosition(), 1e-9);
        assertTrue(rig.servos.stream().filter(s -> s.port.name.equals("hood")).findFirst().orElseThrow().port.pwmEnabled,
                "the servo holds its position");
    }

    @Test
    void aReversedServoReadsOneBeforeItsFirstPosition() {
        Servo hood = hardwareMap.get(Servo.class, "hood");
        hood.setDirection(Servo.Direction.REVERSE);
        assertEquals(1.0, hood.getPosition());
    }

    @Test
    void notANumberIsRefusedAndOutOfRangeIsClipped() {
        Servo hood = hardwareMap.get(Servo.class, "hood");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> hood.setPosition(Double.NaN));
        assertTrue(e.getMessage().startsWith("illegal servo position NaN"), e.getMessage());
        hood.setPosition(1.5);   // Servo.setPosition clips
        assertEquals(1.0, hood.getPosition());
        // The controller itself refuses what's outside [0, 1].
        assertThrows(IllegalArgumentException.class, () -> hood.getController().setServoPosition(hood.getPortNumber(), 1.2));
    }

    @Test
    void aContinuousServoReadsFullReverseBeforeItsFirstPower() {
        // As FIRST's CRServoImpl: the power is read back from the position, which is 0 before any command.
        assertEquals(-1.0, hardwareMap.get(CRServo.class, "feeder").getPower());
    }
}
