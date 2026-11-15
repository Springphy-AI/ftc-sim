package ftcsim.hal;

import java.util.concurrent.TimeUnit;

import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.CameraControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.ExposureControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.FocusControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.GainControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.PtzControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.WhiteBalanceControl;
import org.firstinspires.ftc.robotcore.internal.collections.MutableReference;

/**
 * A simulated webcam's UVC controls (VisionPortal.getCameraControl), with the ranges of a
 * Logitech C920. Settings are kept and read back; the rendered image does not change with them.
 */
public final class SimCameraControls {

    private final Exposure exposure = new Exposure();
    private final Gain gain = new Gain();
    private final Focus focus = new Focus();
    private final WhiteBalance whiteBalance = new WhiteBalance();
    private final Ptz ptz = new Ptz();

    /** The control of the requested kind, or null if the camera has none. */
    public <T extends CameraControl> T get(Class<T> type) {
        if (type == ExposureControl.class) return type.cast(exposure);
        if (type == GainControl.class) return type.cast(gain);
        if (type == FocusControl.class) return type.cast(focus);
        if (type == WhiteBalanceControl.class) return type.cast(whiteBalance);
        if (type == PtzControl.class) return type.cast(ptz);
        return null;
    }

    static final class Exposure implements ExposureControl {
        private Mode mode = Mode.AperturePriority;   // what UVC webcams report for auto exposure
        private long exposureNanos = TimeUnit.MILLISECONDS.toNanos(30);
        private boolean aePriority = false;
        private static final long MIN = TimeUnit.MICROSECONDS.toNanos(300), MAX = TimeUnit.MILLISECONDS.toNanos(204);

        @Override public synchronized Mode getMode() { return mode; }

        @Override public synchronized boolean setMode(Mode m) {
            if (!isModeSupported(m)) return false;
            mode = m;
            return true;
        }

        @Override public boolean isModeSupported(Mode m) { return m == Mode.Manual || m == Mode.AperturePriority; }
        @Override public long getMinExposure(TimeUnit unit) { return unit.convert(MIN, TimeUnit.NANOSECONDS); }
        @Override public long getMaxExposure(TimeUnit unit) { return unit.convert(MAX, TimeUnit.NANOSECONDS); }
        @Override public synchronized long getExposure(TimeUnit unit) { return unit.convert(exposureNanos, TimeUnit.NANOSECONDS); }

        @Override
        public long getCachedExposure(TimeUnit unit, MutableReference<Boolean> refreshed, long permittedStaleness, TimeUnit permittedStalenessUnit) {
            if (refreshed != null) refreshed.setValue(Boolean.FALSE);
            return getExposure(unit);
        }

        @Override public synchronized boolean setExposure(long duration, TimeUnit unit) {
            long n = unit.toNanos(duration);
            if (n < MIN || n > MAX) return false;
            exposureNanos = n;
            return true;
        }

        @Override public boolean isExposureSupported() { return true; }
        @Override public synchronized boolean getAePriority() { return aePriority; }
        @Override public synchronized boolean setAePriority(boolean priority) { aePriority = priority; return true; }
    }

    static final class Gain implements GainControl {
        private int gain = 0;

        @Override public int getMinGain() { return 0; }
        @Override public int getMaxGain() { return 255; }
        @Override public synchronized int getGain() { return gain; }

        @Override public synchronized boolean setGain(int g) {
            if (g < 0 || g > 255) return false;
            gain = g;
            return true;
        }
    }

    static final class Focus implements FocusControl {
        private Mode mode = Mode.ContinuousAuto;
        private double length = 0;

        @Override public synchronized Mode getMode() { return mode; }

        @Override public synchronized boolean setMode(Mode m) {
            if (!isModeSupported(m)) return false;
            mode = m;
            return true;
        }

        @Override public boolean isModeSupported(Mode m) { return m == Mode.ContinuousAuto || m == Mode.Fixed; }
        @Override public double getMinFocusLength() { return 0; }
        @Override public double getMaxFocusLength() { return 250; }
        @Override public synchronized double getFocusLength() { return length; }

        @Override public synchronized boolean setFocusLength(double l) {
            if (l < 0 || l > 250) return false;
            length = l;
            return true;
        }

        @Override public boolean isFocusLengthSupported() { return true; }
    }

    static final class WhiteBalance implements WhiteBalanceControl {
        private Mode mode = Mode.AUTO;
        private int temperature = 4000;

        @Override public synchronized Mode getMode() { return mode; }

        @Override public synchronized boolean setMode(Mode m) {
            if (m != Mode.AUTO && m != Mode.MANUAL) return false;
            mode = m;
            return true;
        }

        @Override public int getMinWhiteBalanceTemperature() { return 2000; }
        @Override public int getMaxWhiteBalanceTemperature() { return 6500; }
        @Override public synchronized int getWhiteBalanceTemperature() { return temperature; }

        @Override public synchronized boolean setWhiteBalanceTemperature(int t) {
            if (t < 2000 || t > 6500) return false;
            temperature = t;
            return true;
        }
    }

    static final class Ptz implements PtzControl {
        private final PanTiltHolder panTilt = new PanTiltHolder();
        private int zoom = 100;

        @Override public synchronized PanTiltHolder getPanTilt() {
            PanTiltHolder h = new PanTiltHolder();
            h.pan = panTilt.pan;
            h.tilt = panTilt.tilt;
            return h;
        }

        @Override public synchronized boolean setPanTilt(PanTiltHolder h) {
            if (Math.abs(h.pan) > 36000 || Math.abs(h.tilt) > 36000) return false;
            panTilt.pan = h.pan;
            panTilt.tilt = h.tilt;
            return true;
        }

        @Override public PanTiltHolder getMinPanTilt() {
            PanTiltHolder h = new PanTiltHolder();
            h.pan = -36000;
            h.tilt = -36000;
            return h;
        }

        @Override public PanTiltHolder getMaxPanTilt() {
            PanTiltHolder h = new PanTiltHolder();
            h.pan = 36000;
            h.tilt = 36000;
            return h;
        }

        @Override public synchronized int getZoom() { return zoom; }

        @Override public synchronized boolean setZoom(int z) {
            if (z < 100 || z > 500) return false;
            zoom = z;
            return true;
        }

        @Override public int getMinZoom() { return 100; }
        @Override public int getMaxZoom() { return 500; }
    }
}
