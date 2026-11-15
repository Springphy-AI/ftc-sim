package org.firstinspires.ftc.robotcore.internal.camera.calibration;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.firstinspires.ftc.robotcore.external.android.util.Size;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.qualcomm.robotcore.util.RobotLog;

/**
 * Webcam lens calibrations, looked up the way the Robot Controller does: by the camera's USB
 * vendor/product id and the capture resolution.
 *
 * <p>The data is the SDK's own {@code builtinwebcamcalibrations.xml}, copied unmodified from
 * FIRST's RobotCore package at build time. The lookup follows the SDK's CameraCalibrationManager:
 * an exact size wins; otherwise a calibration with the same aspect ratio is scaled to the size;
 * otherwise, if the camera has calibrations but none of that shape, a placeholder that tells the
 * AprilTag processor the aspect ratio does not match.
 */
public class CameraCalibrationHelper {
    public static final String TAG = "CameraCalibrationHelper";

    private static CameraCalibrationHelper instance;

    private final Map<CameraCalibrationIdentity, List<CameraCalibration>> calibrations = new HashMap<CameraCalibrationIdentity, List<CameraCalibration>>();

    protected CameraCalibrationHelper() {
        try (InputStream in = CameraCalibrationHelper.class.getResourceAsStream("builtinwebcamcalibrations.xml")) {
            if (in == null) throw new IllegalStateException("builtinwebcamcalibrations.xml is missing from the simulator's SDK");
            parse(in);
        } catch (Exception e) {
            RobotLog.ee(TAG, e, "could not read the built-in webcam calibrations");
        }
    }

    public static synchronized CameraCalibrationHelper getInstance() {
        if (instance == null) instance = new CameraCalibrationHelper();
        return instance;
    }

    public CameraCalibration getCalibration(CameraCalibrationIdentity identity, int width, int height) {
        Size size = new Size(width, height);
        CameraCalibration result = null;
        if (identity != null && !identity.isDegenerate()) {
            List<CameraCalibration> list = calibrations.get(identity);
            if (list != null) {
                for (CameraCalibration c : list) {
                    if (c.getSize().equals(size)) {
                        result = c;
                        break;
                    }
                }
                if (result == null) {
                    List<CameraCalibration> sameShape = withAspectRatio(list, size);
                    if (!sameShape.isEmpty()) result = sameShape.get(0).scaledTo(size);
                    else if (!list.isEmpty()) result = new PlaceholderCalibratedAspectRatioMismatch(identity, size);
                }
            }
        }
        return result != null ? result : CameraCalibration.forUnavailable(identity, size);
    }

    public List<CameraCalibration> getCalibrations(CameraCalibrationIdentity identity) {
        List<CameraCalibration> list = identity == null ? null : calibrations.get(identity);
        return list == null ? new ArrayList<CameraCalibration>() : new ArrayList<CameraCalibration>(list);
    }

    /** Calibrations of the same aspect ratio, largest first (scaling down loses nothing). */
    private static List<CameraCalibration> withAspectRatio(List<CameraCalibration> list, Size size) {
        double aspect = (double) size.getWidth() / size.getHeight();
        List<CameraCalibration> result = new ArrayList<CameraCalibration>();
        for (CameraCalibration c : list) {
            if (Math.abs(c.getAspectRatio() - aspect) < 1e-3) result.add(c);
        }
        Collections.sort(result, new Comparator<CameraCalibration>() {
            @Override
            public int compare(CameraCalibration a, CameraCalibration b) {
                return Double.compare(b.getDiagonal(), a.getDiagonal());
            }
        });
        return result;
    }

    private void parse(InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        Document doc = factory.newDocumentBuilder().parse(in);
        NodeList cameras = doc.getElementsByTagName("Camera");
        for (int i = 0; i < cameras.getLength(); i++) {
            Element camera = (Element) cameras.item(i);
            CameraCalibrationIdentity identity = new VendorProductCalibrationIdentity(
                    decodeVid(camera.getAttribute("vid")), Integer.decode(camera.getAttribute("pid").trim()));
            NodeList entries = camera.getElementsByTagName("Calibration");
            for (int j = 0; j < entries.getLength(); j++) {
                Element e = (Element) entries.item(j);
                int[] size = ints(attribute(e, "size"));
                CameraCalibration calibration = new CameraCalibration(identity, size,
                        floats(attribute(e, "focalLength", "focal_length")),
                        floats(attribute(e, "principalPoint", "principal_point")),
                        floats(attribute(e, "distortionCoefficients", "distortion_coefficients")),
                        "true".equalsIgnoreCase(attribute(e, "remove")), false);
                List<CameraCalibration> list = calibrations.get(identity);
                if (list == null) calibrations.put(identity, list = new ArrayList<CameraCalibration>());
                list.add(calibration);
            }
        }
    }

    /** USB vendor ids, by the names the SDK's calibration files use. */
    private static int decodeVid(String vid) {
        String v = vid.trim();
        switch (v.toLowerCase(Locale.ROOT)) {
            case "logitech": return 0x046D;
            case "microsoft": return 0x045E;
            default: return Integer.decode(v);
        }
    }

    private static String attribute(Element e, String... names) {
        for (String n : names) {
            if (e.hasAttribute(n)) return e.getAttribute(n);
        }
        return "";
    }

    private static int[] ints(String s) {
        String[] parts = split(s);
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) result[i] = Integer.parseInt(parts[i]);
        return result;
    }

    private static float[] floats(String s) {
        String[] parts = split(s);
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) result[i] = Float.parseFloat(parts[i]);
        return result;
    }

    private static String[] split(String s) {
        String t = s.trim();
        return t.isEmpty() ? new String[0] : t.split("[\\s,]+");
    }
}
