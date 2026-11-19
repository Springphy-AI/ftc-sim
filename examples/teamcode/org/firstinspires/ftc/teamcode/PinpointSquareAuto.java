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
 * Example autonomous for the default mecanum robot: drives a 20-inch square using goBILDA
 * Pinpoint odometry and a simple proportional go-to-point controller, holding its heading,
 * then returns to the start. Coordinates are relative to where the robot starts
 * (+X forward, +Y left, inches). Afterwards the Driver Station preselects "BIOBUZZ TeleOp".
 */
@Autonomous(name = "Pinpoint Square", group = "Examples", preselectTeleOp = "BIOBUZZ TeleOp")
public class PinpointSquareAuto extends LinearOpMode {

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

        // Forward, then left: from the default start, a square to the right would clip the red FLOWER.
        double[][] waypoints = {{20, 0}, {20, 20}, {0, 20}, {0, 0}};
        for (double[] w : waypoints) {
            if (!driveTo(w[0], w[1], 0, 4.0)) break;
            sleep(250);
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
