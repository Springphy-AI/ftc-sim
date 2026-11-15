package ftcsim.hal;

import java.util.Locale;

import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;

import com.qualcomm.robotcore.util.SerialNumber;

/** A configured webcam, backed by a simulated camera that VisionPortal streams from. */
public final class SimWebcamName implements WebcamName {
    private final String name;
    private final SerialNumber serialNumber;
    private final CameraBackend camera;

    public SimWebcamName(String name, CameraBackend camera) {
        this.name = name;
        this.camera = camera;
        this.serialNumber = SerialNumber.fromString("SIM-CAM-" + Integer.toHexString(name.hashCode()));
    }

    /** The simulated camera behind this name (null if the robot has no renderer, e.g. in some tests). */
    public CameraBackend camera() {
        return camera;
    }

    @Override
    public SerialNumber getSerialNumber() {
        return serialNumber;
    }

    @Override
    public String getUsbDeviceNameIfAttached() {
        return "/dev/bus/usb/sim/" + name;
    }

    @Override
    public boolean isAttached() {
        return true;
    }

    @Override
    public boolean isWebcam() {
        return true;
    }

    @Override
    public boolean isCameraDirection() {
        return false;
    }

    @Override
    public boolean isSwitchable() {
        return false;
    }

    @Override
    public boolean isUnknown() {
        return false;
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.Other;
    }

    @Override
    public String getDeviceName() {
        return name;
    }

    @Override
    public String getConnectionInfo() {
        return camera == null ? "USB " + serialNumber
                : String.format(Locale.US, "USB %s (vid 0x%04X, pid 0x%04X)", serialNumber, camera.vendorId(), camera.productId());
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public void resetDeviceConfigurationForOpMode() {
    }

    @Override
    public void close() {
    }

    @Override
    public String toString() {
        return "Webcam \"" + name + "\"";
    }
}
