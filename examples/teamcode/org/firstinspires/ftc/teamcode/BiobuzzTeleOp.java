package org.firstinspires.ftc.teamcode;

import java.util.List;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.util.Range;

/**
 * Example TeleOp for the simulator's default mecanum robot (robots/mecanum.json). It uses only
 * the FTC SDK, so it runs unchanged on a real robot whose configuration uses the same names.
 *
 * Xbox controller (gamepad1):
 *   left stick      drive. Robot-centric: forward = toward the intake, whichever way the robot faces.
 *   right stick X   turn
 *   BACK (View)     switch to field-centric and back. Field-centric: forward = away from the
 *                   driver, whichever way the robot faces.
 *   A               field-centric: "away from the driver" = the way the robot faces now
 *   RT / LT         intake in / out
 *   Y               launcher on/off (rumbles when it reaches speed)
 *   RB              feed into the launcher
 *   d-pad up/down   raise/lower the hood
 *
 * Shooting: a HIVE CELL is a tube that is open only at its outer end, so shoot into that end, not
 * at the side of the HIVE. The red HIVE's raised CELL opens toward the audience: line the robot up
 * with the HIVE (x = -0.32 m, about half a tile red-ward of the field center) on the audience side,
 * facing the rear wall, with its center anywhere from y = -1.0 m (1.0 m from the field's center
 * line) back to y = -1.6 m, where an 18 in robot's back touches the audience wall. Blue mirrors
 * this from the rear side. At the default hood and speed the ball drops into the CELL from anywhere
 * in that range.
 */
@TeleOp(name = "BIOBUZZ TeleOp", group = "Examples")
public class BiobuzzTeleOp extends OpMode {

    // goBILDA 6000 RPM motor (28 ticks/rev) on a 96 mm wheel: the ball leaves at about 5.2 m/s and
    // 67.5 degrees, rises above the open CELL and drops in. Tune both on your robot.
    static final double LAUNCHER_TICKS_PER_SECOND = 970;
    static final double LAUNCHER_P = 50;
    static final double LAUNCHER_F = 32767.0 / 3000;
    static final double HOOD_START = 0.85;   // servo position: 0.5 is 50 degrees, each 0.05 step 2.5 degrees
    static final double HOOD_STEP = 0.05;

    private DcMotor frontLeft, frontRight, backLeft, backRight, intake;
    private DcMotorEx launcher;
    private Servo hood;
    private CRServo feeder;
    private IMU imu;
    private GoBildaPinpointDriver pinpoint;
    private List<LynxModule> hubs;

    private boolean launcherOn = false;
    private boolean launcherWasReady = false;
    private double hoodPosition = HOOD_START;
    private boolean fieldCentric = false;

    @Override
    public void init() {
        hubs = hardwareMap.getAll(LynxModule.class);
        for (LynxModule hub : hubs) hub.setBulkCachingMode(LynxModule.BulkCachingMode.MANUAL);

        frontLeft = hardwareMap.get(DcMotor.class, "front_left_drive");
        frontRight = hardwareMap.get(DcMotor.class, "front_right_drive");
        backLeft = hardwareMap.get(DcMotor.class, "back_left_drive");
        backRight = hardwareMap.get(DcMotor.class, "back_right_drive");
        frontLeft.setDirection(DcMotor.Direction.REVERSE);
        backLeft.setDirection(DcMotor.Direction.REVERSE);
        for (DcMotor m : new DcMotor[]{frontLeft, frontRight, backLeft, backRight}) {
            m.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
            m.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }

        intake = hardwareMap.get(DcMotor.class, "intake");
        launcher = hardwareMap.get(DcMotorEx.class, "launcher");
        launcher.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
        launcher.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
        // Flywheel velocity PIDF. The hub's default (P 10, I 3, F from the motor's rated speed)
        // overshoots by about 30% and needs ~4 s to settle, so the launcher keeps dropping out of
        // READY. Tune it like most teams do: F = 32767 / (ticks/s at full power, about 3000 here),
        // P to hold the speed, and no I.
        launcher.setVelocityPIDFCoefficients(LAUNCHER_P, 0, 0, LAUNCHER_F);
        hood = hardwareMap.get(Servo.class, "hood");
        feeder = hardwareMap.get(CRServo.class, "feeder");

        imu = hardwareMap.get(IMU.class, "imu");
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP, RevHubOrientationOnRobot.UsbFacingDirection.FORWARD)));
        imu.resetYaw();

        pinpoint = hardwareMap.get(GoBildaPinpointDriver.class, "pinpoint");
        pinpoint.setOffsets(-84.0, -168.0, DistanceUnit.MM);
        pinpoint.setEncoderResolution(GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD);
        pinpoint.setEncoderDirections(GoBildaPinpointDriver.EncoderDirection.FORWARD, GoBildaPinpointDriver.EncoderDirection.FORWARD);
        pinpoint.resetPosAndIMU();

        telemetry.addLine("Ready. Press START.");
    }

    @Override
    public void loop() {
        for (LynxModule hub : hubs) hub.clearBulkCache();
        pinpoint.update();

        // ---- drive ----
        if (gamepad1.backWasPressed()) fieldCentric = !fieldCentric;
        if (gamepad1.aWasPressed()) imu.resetYaw();
        double forward = -gamepad1.left_stick_y;
        double right = gamepad1.left_stick_x;
        double turn = gamepad1.right_stick_x;
        if (fieldCentric) {
            // Rotate the stick by -heading so "forward" stays away from the driver.
            double heading = imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.RADIANS);
            double rotX = right * Math.cos(-heading) - forward * Math.sin(-heading);
            double rotY = right * Math.sin(-heading) + forward * Math.cos(-heading);
            right = rotX;
            forward = rotY;
        }
        double fl = forward + right + turn;
        double fr = forward - right - turn;
        double bl = forward - right + turn;
        double br = forward + right - turn;
        double max = Math.max(1.0, Math.max(Math.max(Math.abs(fl), Math.abs(fr)), Math.max(Math.abs(bl), Math.abs(br))));
        frontLeft.setPower(fl / max);
        frontRight.setPower(fr / max);
        backLeft.setPower(bl / max);
        backRight.setPower(br / max);

        // ---- intake ----
        intake.setPower(gamepad1.right_trigger - gamepad1.left_trigger);

        // ---- launcher ----
        if (gamepad1.yWasPressed()) launcherOn = !launcherOn;
        launcher.setVelocity(launcherOn ? LAUNCHER_TICKS_PER_SECOND : 0);
        double speed = launcher.getVelocity();
        boolean ready = launcherOn && Math.abs(speed - LAUNCHER_TICKS_PER_SECOND) < 0.03 * LAUNCHER_TICKS_PER_SECOND;
        if (ready && !launcherWasReady) gamepad1.rumbleBlips(2);
        launcherWasReady = ready;

        if (gamepad1.dpadUpWasPressed()) hoodPosition += HOOD_STEP;
        if (gamepad1.dpadDownWasPressed()) hoodPosition -= HOOD_STEP;
        hoodPosition = Range.clip(hoodPosition, 0.0, 1.0);
        hood.setPosition(hoodPosition);
        feeder.setPower(gamepad1.right_bumper && ready ? 1.0 : 0.0);

        // ---- telemetry ----
        telemetry.addData("Drive", fieldCentric ? "field-centric (BACK: robot-centric)" : "robot-centric (BACK: field-centric)");
        telemetry.addData("Heading (IMU)", "%.1f°", imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES));
        telemetry.addData("Pinpoint", "x %.1f in  y %.1f in  h %.1f°",
                pinpoint.getPosX(DistanceUnit.INCH), pinpoint.getPosY(DistanceUnit.INCH), pinpoint.getHeading(AngleUnit.DEGREES));
        telemetry.addData("Launcher", "%s  %.0f / %.0f ticks/s %s", launcherOn ? "ON " : "off", speed,
                LAUNCHER_TICKS_PER_SECOND, ready ? "READY" : "");
        if (gamepad1.right_bumper && !ready) {
            telemetry.addLine(launcherOn ? "Feeding waits for the launcher to reach speed" : "Press Y to start the launcher, then RB");
        }
        telemetry.addData("Hood", "%.2f", hoodPosition);
        telemetry.addData("Intake", "%.2f", intake.getPower());
    }

    @Override
    public void stop() {
        launcherOn = false;
    }
}
