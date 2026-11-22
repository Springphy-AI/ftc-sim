package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.junit.jupiter.api.Test;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.hardware.sparkfun.SparkFunOTOS;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.IMU;

import ftcsim.config.RobotConfig;
import ftcsim.hal.HalSession;
import ftcsim.hal.Latency;
import ftcsim.physics.FieldModel;
import ftcsim.physics.PhysicsWorld;
import ftcsim.sim.RobotRig;

/** Sensors through the SDK's classes, as TeamCode reads them, against the real drivers' behavior. */
class SensorTest {

    private final RobotRig rig;
    private final HardwareMap hardwareMap;

    SensorTest() throws Exception {
        rig = new RobotRig(RobotConfig.load(Path.of("robots/mecanum.json")), new PhysicsWorld(FieldModel.loadBiobuzz()));
        Latency.commandNanos = 0;
        Latency.i2cNanos = 0;
        rig.place(0, -1.0, 0);
        hardwareMap = rig.createHardwareMap(new HalSession());
    }

    /** Turns the robot to {@code deg} without moving the IMU's power-on reference (as driving there would). */
    private void turnTo(double deg) {
        rig.body.place(0, -1.0, Math.toRadians(deg));
        for (int i = 0; i < 5; i++) rig.step(0.001);
    }

    private IMU imu(HardwareMap map) {
        IMU imu = map.get(IMU.class, "imu");
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP, RevHubOrientationOnRobot.UsbFacingDirection.FORWARD)));
        return imu;
    }

    private static double yaw(IMU imu) {
        return imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES);
    }

    @Test
    void resetYawCarriesOverToTheNextOpModeUntilAPowerCycle() {
        turnTo(30);
        IMU first = imu(hardwareMap);
        assertEquals(30, yaw(first), 0.5);
        first.resetYaw();
        assertEquals(0, yaw(first), 0.5);
        // The next OpMode initializes the IMU again: the zero the driver set stays, as on the robot.
        rig.resetForOpMode();
        IMU next = imu(rig.createHardwareMap(new HalSession()));
        assertEquals(0, yaw(next), 0.5);
        // Turning the robot off and on clears it.
        rig.powerCycle();
        turnTo(60);
        assertEquals(30, yaw(imu(rig.createHardwareMap(new HalSession()))), 0.5);
    }

    @Test
    void otosReportsInMillimetersAndCentimetersToo() {
        SparkFunOTOS otos = hardwareMap.get(SparkFunOTOS.class, "sensor_otos");
        otos.setAngularUnit(AngleUnit.DEGREES);
        otos.setLinearUnit(DistanceUnit.METER);
        otos.setPosition(new SparkFunOTOS.Pose2D(0.5, 0.25, 30));
        otos.setLinearUnit(DistanceUnit.MM);
        assertEquals(DistanceUnit.MM, otos.getLinearUnit());
        SparkFunOTOS.Pose2D mm = otos.getPosition();
        assertEquals(500, mm.x, 1e-6);
        assertEquals(250, mm.y, 1e-6);
        assertEquals(30, mm.h, 1e-6);
        otos.setLinearUnit(DistanceUnit.CM);
        assertEquals(50, otos.getPosition().x, 1e-6);
    }
}
