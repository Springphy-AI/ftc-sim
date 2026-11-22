package ftcsim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.ExposureControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.GainControl;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibration;
import org.firstinspires.ftc.vision.VisionPortal;
import org.firstinspires.ftc.vision.VisionPortal.CameraState;
import org.firstinspires.ftc.vision.VisionProcessor;
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import android.graphics.Canvas;

import ftcsim.config.RobotConfig;
import ftcsim.hal.CameraPreviews;
import ftcsim.hal.SimWebcamName;
import ftcsim.physics.FieldModel;
import ftcsim.vision.CameraWorld;

/** The simulator's VisionPortal follows the rules of FIRST's: camera states, controls, live view. */
class VisionPortalBehaviorTest {

    private static SimWebcamName webcam() throws Exception {
        CameraWorld world = new CameraWorld(FieldModel.loadBiobuzz());
        world.update(List.of(), null);
        RobotConfig.Device mount = new RobotConfig.Device();
        mount.type = "webcam";
        mount.name = "Webcam 1";
        mount.xM = -1.0;
        mount.yM = -1.2;
        mount.zM = 0.3;
        mount.yawDeg = 45;
        return new SimWebcamName("Webcam 1", world.camera(0, mount));
    }

    /** A processor that needs no vision libraries. */
    private static final class Idle implements VisionProcessor {
        @Override
        public void init(int width, int height, CameraCalibration calibration) {
        }

        @Override
        public Object processFrame(Mat frame, long captureTimeNanos) {
            return null;
        }

        @Override
        public void onDrawFrame(Canvas canvas, int width, int height, float scaleBmpPxToCanvasPx, float scaleCanvasDensity, Object userContext) {
        }
    }

    /** Rotation about the robot's axis {@code axis} (0 x, 1 y, 2 z) by {@code deg}, right-handed. */
    private static double[][] rot(int axis, double deg) {
        double c = Math.cos(Math.toRadians(deg)), s = Math.sin(Math.toRadians(deg));
        return switch (axis) {
            case 0 -> new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}};
            case 1 -> new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
            default -> new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
        };
    }

    private static double[] apply(double[][] m, double[] v) {
        return new double[]{m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2], m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    /**
     * The webcam's pose from its mount as the README describes it (robot frame x forward, y left,
     * z up; yaw to the left, pitch up, roll clockwise seen from behind the camera), worked out here
     * with rotation matrices rather than taken from SimCamera, whose pose the vision tests compare
     * FIRST's solved poses with.
     */
    @Test
    void theWebcamSitsAndLooksWhereItsMountSays() throws Exception {
        CameraWorld world = new CameraWorld(FieldModel.loadBiobuzz());
        world.update(List.of(), null);   // no robot: the robot frame is the field's
        RobotConfig.Device mount = new RobotConfig.Device();
        mount.type = "webcam";
        mount.name = "Webcam 1";
        mount.xM = 0.15;
        mount.yM = -0.12;
        mount.zM = 0.3;
        mount.yawDeg = 25;
        mount.pitchDeg = 15;
        mount.rollDeg = 10;
        double[][] pose = world.camera(0, mount).currentPose();
        assertArrayEquals(new double[]{0.15, -0.12, 0.3}, pose[0], 1e-9, "where the camera is");
        // Unturned, the camera looks forward (+x) with its right to -y and its down to -z. Roll turns it
        // about its own forward axis, clockwise seen from behind (right toward down); pitch then tips it
        // up about the robot's y axis, and yaw turns it left about z.
        double c = Math.cos(Math.toRadians(10)), s = Math.sin(Math.toRadians(10));
        double[] right0 = {0, -1, 0}, down0 = {0, 0, -1};
        double[] rolledRight = {right0[0] * c + down0[0] * s, right0[1] * c + down0[1] * s, right0[2] * c + down0[2] * s};
        double[] rolledDown = {down0[0] * c - right0[0] * s, down0[1] * c - right0[1] * s, down0[2] * c - right0[2] * s};
        double[][] turn = rot(2, 25);
        double[][] tip = rot(1, -15);   // about +y, a negative angle tips +x up toward +z
        double[][] want = {apply(turn, apply(tip, rolledRight)), apply(turn, apply(tip, rolledDown)), apply(turn, apply(tip, new double[]{1, 0, 0}))};
        String[] row = {"right", "down", "forward"};
        for (int k = 0; k < 3; k++) {
            assertArrayEquals(want[k], new double[]{pose[1][3 * k], pose[1][3 * k + 1], pose[1][3 * k + 2]}, 1e-9, "camera " + row[k]);
        }
    }

    @Test
    void stopAndResumeStreamingThrowWhileTheCameraOpensAndOnceItIsClosed() throws Exception {
        VisionPortal portal = new VisionPortal.Builder().setCamera(webcam()).addProcessor(new Idle()).build();
        try {
            // Right after build() the webcam is still opening. FIRST's portal throws then, so OpModes
            // that stop the stream straight away fail on the robot; they must fail here too.
            assertEquals(CameraState.OPENING_CAMERA_DEVICE, portal.getCameraState());
            RuntimeException e = assertThrows(RuntimeException.class, portal::stopStreaming);
            assertEquals("Illegal CameraState OPENING_CAMERA_DEVICE when calling stopStreaming()", e.getMessage());
            e = assertThrows(RuntimeException.class, portal::resumeStreaming);
            assertEquals("Illegal CameraState OPENING_CAMERA_DEVICE when calling resumeStreaming()", e.getMessage());
        } finally {
            portal.close();
        }
        assertEquals(CameraState.CAMERA_DEVICE_CLOSED, portal.getCameraState());
        assertThrows(RuntimeException.class, portal::stopStreaming);
        assertThrows(RuntimeException.class, portal::resumeStreaming);
    }

    @Test
    void statesControlsAndLiveViewFollowTheSdk() throws Exception {
        VisionAssumptions.assumeDetectorAvailable();
        VisionPortal portal = new VisionPortal.Builder()
                .setCamera(webcam())
                .addProcessor(AprilTagProcessor.easyCreateWithDefaults())
                .build();
        try {
            // FIRST's portal refuses camera controls until the camera streams; so must this one.
            assertThrows(IllegalStateException.class, () -> portal.getCameraControl(ExposureControl.class));
            waitFor(() -> portal.getCameraState() == CameraState.STREAMING, 10000, "streaming");
            ExposureControl exposure = portal.getCameraControl(ExposureControl.class);
            assertTrue(exposure.setMode(ExposureControl.Mode.Manual));
            assertTrue(exposure.setExposure(6, TimeUnit.MILLISECONDS));
            assertEquals(6, exposure.getExposure(TimeUnit.MILLISECONDS));

            // The live view shows while streaming, and stays gone after stopLiveView().
            waitFor(() -> !CameraPreviews.of(0).isEmpty(), 3000, "live view shown");
            portal.stopLiveView();
            Thread.sleep(300);
            assertTrue(CameraPreviews.of(0).isEmpty(), "no live view after stopLiveView()");
            portal.resumeLiveView();
            waitFor(() -> !CameraPreviews.of(0).isEmpty(), 3000, "live view back");

            // stopStreaming() settles on CAMERA_DEVICE_READY, where another one does nothing; resumeStreaming() streams again.
            portal.stopStreaming();
            waitFor(() -> portal.getCameraState() == CameraState.CAMERA_DEVICE_READY, 3000, "camera ready after stopStreaming");
            portal.stopStreaming();
            assertThrows(IllegalStateException.class, () -> portal.getCameraControl(GainControl.class));
            portal.resumeStreaming();
            waitFor(() -> portal.getCameraState() == CameraState.STREAMING, 3000, "streaming again");
        } finally {
            portal.close();
        }
        assertEquals(CameraState.CAMERA_DEVICE_CLOSED, portal.getCameraState());
        assertTrue(CameraPreviews.of(0).isEmpty(), "closing the portal removes its live view");
    }

    private static void waitFor(BooleanSupplier condition, long timeoutMs, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), what);
    }
}
