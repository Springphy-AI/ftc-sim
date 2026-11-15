package org.firstinspires.ftc.robotcore.external;

import java.util.Collections;
import java.util.List;

import org.firstinspires.ftc.robotcore.external.hardware.camera.BuiltinCameraDirection;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraManager;
import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraName;
import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;

import ftcsim.hal.RobotContext;

/** Entry point to SDK services robot code obtains by name; here, the camera manager. */
public abstract class ClassFactory {

    private static final CameraManager CAMERA_MANAGER = new CameraManager() {
        @Override
        public List<WebcamName> getAllWebcams() {
            RobotContext robot = RobotContext.current();
            return robot == null ? Collections.<WebcamName>emptyList() : Collections.unmodifiableList(robot.webcams);
        }

        @Override
        public CameraName nameFromCameraDirection(final BuiltinCameraDirection direction) {
            return new CameraName() {
                @Override public boolean isWebcam() { return false; }
                @Override public boolean isCameraDirection() { return true; }
                @Override public boolean isSwitchable() { return false; }
                @Override public boolean isUnknown() { return false; }
                @Override public String toString() { return "builtin camera " + direction; }
            };
        }

        @Override
        public CameraName nameForUnknownCamera() {
            return new CameraName() {
                @Override public boolean isWebcam() { return false; }
                @Override public boolean isCameraDirection() { return false; }
                @Override public boolean isSwitchable() { return false; }
                @Override public boolean isUnknown() { return true; }
                @Override public String toString() { return "unknown camera"; }
            };
        }
    };

    private static final ClassFactory INSTANCE = new ClassFactory() {
        @Override
        public CameraManager getCameraManager() {
            return CAMERA_MANAGER;
        }
    };

    public static ClassFactory getInstance() {
        return INSTANCE;
    }

    public abstract CameraManager getCameraManager();
}
