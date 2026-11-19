package org.firstinspires.ftc.teamcode;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D;

import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.util.ElapsedTime;
import com.qualcomm.robotcore.util.Range;

/**
 * Parking autonomous: drives the robot from its start (back against the audience wall, facing
 * into the open side of the red HIVE's near box) to the red LOADING ZONE (the red taped box
 * along the red wall), going around the HIVE's A-frame and the red FLOWER.
 *
 * Positions are FIELD positions in inches. (0, 0) is the middle of the field.
 * X goes toward the blue wall, Y goes away from the audience.
 * Heading 0 means facing the blue wall; 90 means facing away from the audience.
 */
@Autonomous(name = "BIOBUZZ Parking", group = "Examples", preselectTeleOp = "BIOBUZZ TeleOp")
public class BiobuzzParkingAuto extends LinearOpMode {

    // Where the robot starts: back pressed against the audience wall, facing into the
    // open side of the red HIVE's near box (heading 90 = facing away from the audience).
    static final double START_X = -12.75;
    static final double START_Y = -62.85;
    static final double START_HEADING = 90;

    // The stops on the way, as {x, y, heading}.
    static final double[][] PATH = {
            {-12.75, -45, 90},   // 1. drive straight forward, off the wall
            {-45,    -40,  0},   // 2. slide toward the red wall and turn to face the blue wall
            {-45,     36,  0},   // 3. drive up the field, next to the loading zone
            {-61,     36,  0},   // 4. back into the loading zone, against the red wall = PARKED
    };

    static final double POSITION_TOLERANCE_IN = 0.75;
    static final double HEADING_TOLERANCE_DEG = 2.0;
    static final double K_TRANSLATE = 0.06;   // power per inch of error
    static final double K_TURN = 0.025;       // power per degree of error
    static final double MAX_POWER = 0.6;

    private DcMotor frontLeft, frontRight, backLeft, backRight;
    private GoBildaPinpointDriver pinpoint;

    @Override
    public void runOpMode() {
        frontLeft = hardwareMap.get(DcMotor.class, "front_left_drive");
        frontRight = hardwareMap.get(DcMotor.class, "front_right_drive");
        backLeft = hardwareMap.get(DcMotor.class, "back_left_drive");
        backRight = hardwareMap.get(DcMotor.class, "back_right_drive");
        frontLeft.setDirection(DcMotor.Direction.REVERSE);
        backLeft.setDirection(DcMotor.Direction.REVERSE);
        for (DcMotor m : new DcMotor[]{frontLeft, frontRight, backLeft, backRight}) {
            m.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        }

        pinpoint = hardwareMap.get(GoBildaPinpointDriver.class, "pinpoint");
        pinpoint.setOffsets(-84.0, -168.0, DistanceUnit.MM);
        pinpoint.setEncoderResolution(GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD);
        pinpoint.setEncoderDirections(GoBildaPinpointDriver.EncoderDirection.FORWARD, GoBildaPinpointDriver.EncoderDirection.FORWARD);
        pinpoint.resetPosAndIMU();

        while (opModeInInit()) {
            pinpoint.update();
            telemetry.addData("Pinpoint", pinpoint.getDeviceStatus());
            telemetry.addData("Pose", format(pinpoint.getPosition()));
            telemetry.update();
        }

        // START was pressed. Tell the Pinpoint where on the field the robot is,
        // so all the positions below are field positions.
        pinpoint.setPosition(new Pose2D(DistanceUnit.INCH, START_X, START_Y, AngleUnit.DEGREES, START_HEADING));

        // Visit each stop in order. Give up on a stop after 5 seconds.
        for (double[] stop : PATH) {
            if (!driveTo(stop[0], stop[1], stop[2], 5.0)) break;
        }
        stopDrive();
        telemetry.addData("Done", "ended at %s", format(pinpoint.getPosition()));
        telemetry.update();
        while (opModeIsActive()) {
            pinpoint.update();
            idle();
        }
    }

    /** Drives to (x, y) inches facing {@code headingDeg}; returns false if the OpMode was stopped. */
    private boolean driveTo(double x, double y, double headingDeg, double timeoutS) {
        ElapsedTime timer = new ElapsedTime();
        while (opModeIsActive() && timer.seconds() < timeoutS) {
            pinpoint.update();
            Pose2D pose = pinpoint.getPosition();
            double ex = x - pose.getX(DistanceUnit.INCH);
            double ey = y - pose.getY(DistanceUnit.INCH);
            double heading = pose.getHeading(AngleUnit.RADIANS);
            double eh = AngleUnit.normalizeDegrees(headingDeg - pose.getHeading(AngleUnit.DEGREES));
            if (Math.hypot(ex, ey) < POSITION_TOLERANCE_IN && Math.abs(eh) < HEADING_TOLERANCE_DEG) {
                stopDrive();
                return true;
            }
            // Field error -> robot frame (Pinpoint: +X forward, +Y left, heading counter-clockwise).
            double forward = ex * Math.cos(heading) + ey * Math.sin(heading);
            double left = -ex * Math.sin(heading) + ey * Math.cos(heading);
            double f = Range.clip(forward * K_TRANSLATE, -MAX_POWER, MAX_POWER);
            double l = Range.clip(left * K_TRANSLATE, -MAX_POWER, MAX_POWER);
            double ccw = Range.clip(eh * K_TURN, -MAX_POWER, MAX_POWER);
            setDrive(f, l, ccw);
            telemetry.addData("Target", "(%.1f, %.1f) in", x, y);
            telemetry.addData("Pose", format(pose));
            telemetry.addData("Error", "%.2f in, %.1f°", Math.hypot(ex, ey), eh);
            telemetry.update();
        }
        stopDrive();
        return opModeIsActive();
    }

    /** forward / left / counter-clockwise powers to mecanum wheel powers. */
    private void setDrive(double forward, double left, double ccw) {
        double fl = forward - left - ccw;
        double fr = forward + left + ccw;
        double bl = forward + left - ccw;
        double br = forward - left + ccw;
        double max = Math.max(1.0, Math.max(Math.max(Math.abs(fl), Math.abs(fr)), Math.max(Math.abs(bl), Math.abs(br))));
        frontLeft.setPower(fl / max);
        frontRight.setPower(fr / max);
        backLeft.setPower(bl / max);
        backRight.setPower(br / max);
    }

    private void stopDrive() {
        setDrive(0, 0, 0);
    }

    private static String format(Pose2D p) {
        return String.format("x %.1f in, y %.1f in, h %.1f°",
                p.getX(DistanceUnit.INCH), p.getY(DistanceUnit.INCH), p.getHeading(AngleUnit.DEGREES));
    }
}

