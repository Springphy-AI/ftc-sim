package org.firstinspires.ftc.robotcore.external.hardware.camera;

import java.util.List;

/** Names the cameras available to robot code; reached through ClassFactory.getInstance(). */
public interface CameraManager {

    /** Every webcam in the active robot configuration. */
    List<WebcamName> getAllWebcams();

    /** The phone camera facing this way. A Control Hub has none, so opening it fails. */
    CameraName nameFromCameraDirection(BuiltinCameraDirection cameraDirection);

    CameraName nameForUnknownCamera();
}
