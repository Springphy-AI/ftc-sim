package ftcsim.vision;

import java.util.List;
import java.util.Locale;

/**
 * Webcams a robot file can name. Each has the USB vendor/product id the real camera reports,
 * which is what the SDK's calibration table (and so FIRST's AprilTag processor) keys its lens
 * calibration by. The field of view only matters at resolutions the table has nothing for.
 */
public final class WebcamModels {

    public record Model(String name, int vendorId, int productId, double horizontalFovDeg) {
    }

    public static final Model DEFAULT = new Model("Logitech C920", 0x046D, 0x082D, 70.4);

    private static final List<Model> MODELS = List.of(
            DEFAULT,
            new Model("Logitech C270", 0x046D, 0x0825, 55.0),
            new Model("Logitech C310", 0x046D, 0x081B, 60.0),
            new Model("Microsoft LifeCam HD-3000", 0x045E, 0x0779, 61.0),
            new Model("goBILDA USB Camera", 0x0BDA, 0x5805, 72.0),
            new Model("Arducam", 0x0C45, 0x6366, 70.0));

    private WebcamModels() {
    }

    /** The model a robot file names ("C920", "logitech c270", ...); null or "" means the C920. */
    public static Model get(String name) {
        if (name == null || name.isBlank()) return DEFAULT;
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (Model m : MODELS) {
            String full = m.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            String last = m.name().toLowerCase(Locale.ROOT).replaceAll(".* ", "").replaceAll("[^a-z0-9]", "");
            if (n.equals(full) || n.equals(last) || full.endsWith(n)) return m;
        }
        throw new IllegalArgumentException("Unknown webcam model \"" + name + "\". Known: "
                + String.join(", ", MODELS.stream().map(Model::name).toList()));
    }
}
