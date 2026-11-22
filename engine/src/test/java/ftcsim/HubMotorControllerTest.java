package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.HardwareMap;

import ftcsim.config.RobotConfig;
import ftcsim.hal.HalSession;
import ftcsim.hal.Latency;
import ftcsim.hal.MotorPort;
import ftcsim.physics.FieldModel;
import ftcsim.physics.PhysicsWorld;
import ftcsim.sim.RobotRig;

/** The hub's motor controller, called through DcMotorEx as TeamCode calls it, against the real SDK's behavior. */
class HubMotorControllerTest {
    private static final double DT = 0.001;

    private final RobotRig rig;
    private final HardwareMap hardwareMap;
    /** robots/mecanum.json's flywheel: a goBILDA 6000 rpm motor, 28 ticks per revolution. */
    private final DcMotorEx launcher;

    HubMotorControllerTest() throws Exception {
        rig = new RobotRig(RobotConfig.load(Path.of("robots/mecanum.json")), new PhysicsWorld(FieldModel.loadBiobuzz()));
        Latency.commandNanos = 0;
        Latency.i2cNanos = 0;
        rig.place(0, -1.0, 0);
        hardwareMap = rig.createHardwareMap(new HalSession());
        launcher = hardwareMap.get(DcMotorEx.class, "launcher");
    }

    /** The hub the launcher is on. */
    private LynxModule launcherHub() {
        MotorPort port = rig.motors.get("launcher").port;
        return hardwareMap.getAll(LynxModule.class).stream()
                .filter(m -> m.getModuleAddress() == port.hub.moduleAddress).findFirst().orElseThrow();
    }

    @Test
    void getBulkDataRefreshesTheManualCache() {
        LynxModule hub = launcherHub();
        hub.setBulkCachingMode(LynxModule.BulkCachingMode.MANUAL);
        MotorPort port = rig.motors.get("launcher").port;
        int before = launcher.getCurrentPosition();   // cached now
        port.rawTicks += 500;                         // the flywheel turned
        assertEquals(before, launcher.getCurrentPosition(), "MANUAL: the cache holds until it is refreshed");
        assertEquals(before + 500, hub.getBulkData().getMotorCurrentPosition(port.port));
        // As on the hub, that bulk read is now the cache: code may refresh with getBulkData() instead of clearBulkCache().
        assertEquals(before + 500, launcher.getCurrentPosition());
    }

    private void run(double seconds) {
        for (int i = 0; i < Math.round(seconds / DT); i++) rig.step(DT);
    }

    /** Spins the flywheel up with setVelocity and checks that the hub holds that speed with its encoder. */
    private void assertHoldsVelocity(String after) {
        launcher.setVelocity(1500);
        assertEquals(DcMotor.RunMode.RUN_USING_ENCODER, launcher.getMode(), "setVelocity after " + after + " switches the mode");
        run(6.0);   // the default velocity PIDF overshoots the light flywheel and settles in about 5 s
        double v = launcher.getVelocity();
        System.out.printf("after %s: setVelocity(1500) -> %.0f ticks/s%n", after, v);
        // Open loop it would settle near 1900 ticks/s (0.63 duty on a fresh battery), and not turn at all after a reset.
        assertEquals(1500, v, 50, "the hub holds the velocity after " + after);
    }

    @Test
    void setVelocityInRunWithoutEncoderSwitchesToRunUsingEncoder() {
        launcher.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        assertHoldsVelocity("RUN_WITHOUT_ENCODER");
    }

    @Test
    void setVelocityAfterStopAndResetEncoderSpinsTheMotor() {
        launcher.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        assertHoldsVelocity("STOP_AND_RESET_ENCODER");
    }

    @Test
    void aModeChangeDropsTheVelocityCommand() {
        launcher.setVelocity(1500);
        run(0.5);
        launcher.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        // The hub sends the last power again (0 here), not the velocity.
        assertEquals(0.0, launcher.getPower(), 1e-9);
        double spinning = launcher.getVelocity();
        run(1.0);
        assertTrue(launcher.getVelocity() < spinning - 100, "the flywheel slows down: " + spinning + " -> " + launcher.getVelocity());
    }

    @Test
    void setVelocityKeepsRunToPositionAndEnablesTheMotor() {
        launcher.setTargetPosition(0);
        launcher.setMode(DcMotor.RunMode.RUN_TO_POSITION);
        launcher.setMotorDisable();
        launcher.setVelocity(1500);
        assertEquals(DcMotor.RunMode.RUN_TO_POSITION, launcher.getMode());
        assertTrue(launcher.isMotorEnabled(), "a velocity command enables the motor, as on the hub");
    }
}
