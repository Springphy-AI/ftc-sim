package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;
import java.util.Map;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D;
import org.junit.jupiter.api.Test;

import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.hardware.rev.Rev2mDistanceSensor;
import com.qualcomm.hardware.rev.RevColorSensorV3;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.hardware.sparkfun.SparkFunOTOS;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.IMU;

import ftcsim.config.RobotConfig;
import ftcsim.hal.HalSession;
import ftcsim.hal.Latency;
import ftcsim.hal.SensorBackends;
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
    void the2mDistanceSensorReadsWholeMillimetersAnd8190WithNothingInRange() {
        SensorBackends.Range range = new SensorBackends.Range();
        Rev2mDistanceSensor sensor = new Rev2mDistanceSensor(range);
        assertEquals(8190, sensor.getDistance(DistanceUnit.MM), "nothing in range: the chip's out-of-range reading");
        assertEquals(819.0, sensor.getDistance(DistanceUnit.CM), 1e-9);
        range.meters = 0.5004;
        assertEquals(500, sensor.getDistance(DistanceUnit.MM));
        assertEquals(500 / 25.4, sensor.getDistance(DistanceUnit.INCH), 1e-9);
    }

    @Test
    void theColorSensorsDistanceComesFromItsProximityFitAndStopsAtSixInches() {
        SensorBackends.Color seen = new SensorBackends.Color();
        RevColorSensorV3 sensor = new RevColorSensorV3(seen);
        // Nothing in front of it, or something past the fit's reach: 6 in, as FIRST's driver says.
        assertEquals(6.0, sensor.getDistance(DistanceUnit.INCH), 1e-9);
        seen.distanceMeters = 0.20;
        assertEquals(6.0, sensor.getDistance(DistanceUnit.INCH), 1e-9);
        // Close up, the counts it reads go back through FIRST's fit to about the true distance.
        seen.distanceMeters = 0.0254;
        assertEquals(1.0, sensor.getDistance(DistanceUnit.INCH), 0.01);
        assertEquals(353, sensor.rawOptical(), 1);
        seen.distanceMeters = 0.10;
        assertEquals(10.0, sensor.getDistance(DistanceUnit.CM), 0.1);
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

    @Test
    void theDevicePanelIsToldWhichUnitsEachOdometryPoseIsIn() {
        // Near x = 0 a Pinpoint's millimeters and an OTOS's meters look alike: the panel can't guess.
        hardwareMap.get(GoBildaPinpointDriver.class, "pinpoint").setPosition(new Pose2D(DistanceUnit.MM, 3, -2, AngleUnit.DEGREES, 10));
        SparkFunOTOS otos = hardwareMap.get(SparkFunOTOS.class, "sensor_otos");
        otos.setLinearUnit(DistanceUnit.INCH);
        otos.setAngularUnit(AngleUnit.DEGREES);
        otos.setPosition(new SparkFunOTOS.Pose2D(0.1, 12, -30));
        Map<String, Object> readings = rig.sensorReadings();
        Map<?, ?> pinpoint = assertInstanceOf(Map.class, readings.get("pinpoint"));
        assertEquals("mm", pinpoint.get("unit"));
        assertEquals(3, (double) pinpoint.get("x"), 1e-9);
        assertEquals(-2, (double) pinpoint.get("y"), 1e-9);
        assertEquals(10, (double) pinpoint.get("headingDeg"), 1e-9);
        Map<?, ?> fromOtos = assertInstanceOf(Map.class, readings.get("sensor_otos"));
        assertEquals("in", fromOtos.get("unit"));
        assertEquals(0.1, (double) fromOtos.get("x"), 1e-9);
        assertEquals(12, (double) fromOtos.get("y"), 1e-9);
        assertEquals(-30, (double) fromOtos.get("headingDeg"), 1e-9);
    }
}
