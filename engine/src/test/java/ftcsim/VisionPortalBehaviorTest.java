package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.ExposureControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.GainControl;
import org.firstinspires.ftc.vision.VisionPortal;
import org.firstinspires.ftc.vision.VisionPortal.CameraState;
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor;
import org.junit.jupiter.api.Test;

import ftcsim.config.RobotConfig;
import ftcsim.hal.CameraPreviews;
import ftcsim.hal.SimWebcamName;
import ftcsim.physics.FieldModel;
import ftcsim.vision.CameraWorld;

/** The simulator's VisionPortal follows the rules of FIRST's: camera states, controls, live view. */
class VisionPortalBehaviorTest {

    @Test
    void statesControlsAndLiveViewFollowTheSdk() throws Exception {
        VisionAssumptions.assumeDetectorAvailable();
        CameraWorld world = new CameraWorld(FieldModel.loadBiobuzz());
        world.update(List.of(), null);
        RobotConfig.Device mount = new RobotConfig.Device();
        mount.type = "webcam";
        mount.name = "Webcam 1";
        mount.xM = -1.0;
        mount.yM = -1.2;
        mount.zM = 0.3;
        mount.yawDeg = 45;
        VisionPortal portal = new VisionPortal.Builder()
                .setCamera(new SimWebcamName("Webcam 1", world.camera(0, mount)))
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

            // stopStreaming() settles on CAMERA_DEVICE_READY; resumeStreaming() streams again.
            portal.stopStreaming();
            waitFor(() -> portal.getCameraState() == CameraState.CAMERA_DEVICE_READY, 3000, "camera ready after stopStreaming");
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
