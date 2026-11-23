package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.ServoImplEx;

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
    void aPulseWidthOutsideThePwmRangeIsSentAsItIs() {
        // As LynxServoController.setPulseWidth: the microseconds go to the hub as they are (1..65535),
        // not through the PWM range.
        ServoImplEx hood = hardwareMap.get(ServoImplEx.class, "hood");
        hood.setPulseWidth(2500);   // past the default range's 2400
        assertEquals(2500, hood.getPulseWidth());
        assertEquals(1.0, hood.getPosition(), "read back as a position, clipped to 1");
        for (int i = 0; i < 1000; i++) rig.step(0.001);
        double angle = rig.servos.stream().filter(s -> s.port.name.equals("hood")).findFirst().orElseThrow().angleDeg();
        assertEquals(150, angle, 1e-6, "the 300 degree servo goes as far as it can");
        hood.setPulseWidth(1000);
        assertEquals(1000, hood.getPulseWidth());
        assertEquals((1000 - 600) / 1800.0, hood.getPosition(), 1e-9);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> hood.setPulseWidth(0.5));
        assertEquals("illegal pulse width: 0", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> hood.setPulseWidth(70000));
        hood.setPosition(0.5);
        assertEquals(1500, hood.getPulseWidth(), "a position's pulse, in whole microseconds");
    }

    @Test
    void aContinuousServoReadsFullReverseBeforeItsFirstPower() {
        // As FIRST's CRServoImpl: the power is read back from the position, which is 0 before any command.
        assertEquals(-1.0, hardwareMap.get(CRServo.class, "feeder").getPower());
    }
}
