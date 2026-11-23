package ftcsim.cad;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import ftcsim.config.RobotConfig;

/**
 * A robot file's CAD model and whether it is usable. A STEP file needs converting to triangles
 * once, which the Driver Station page does (OpenCascade in WebAssembly); until then, or when the
 * CAD can't be read, the robot is built from the robot file's numbers.
 */
public final class CadState {

    public enum Status {
        /** The model is loaded. */
        READY,
        /** A STEP file has no up-to-date converted mesh yet; a Driver Station page converts it. */
        CONVERT,
        /** The CAD couldn't be read; {@link #message} says why. */
        ERROR
    }

    public final Status status;
    public final String message;
    /** The model, or null. With {@link Status#CONVERT} it may be an older conversion. */
    public final RobotModel model;
    /** The CAD file. */
    public final Path file;
    /** STEP: where the converted mesh is kept, and the SHA-256 of the STEP file. */
    public final Path cache;
    public final String sha256;

    private CadState(Status status, String message, RobotModel model, Path file, Path cache, String sha256) {
        this.status = status;
        this.message = message;
        this.model = model;
        this.file = file;
        this.cache = cache;
        this.sha256 = sha256;
    }

    public boolean isStep() {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".step") || n.endsWith(".stp");
    }

    /** Reads the robot file's CAD, and on success updates the robot's numbers to match it. */
    public static CadState load(RobotConfig cfg) {
        Path dir = cfg.source == null ? Path.of(".") : cfg.source.toAbsolutePath().getParent();
        Path file = dir.resolve(cfg.cad.file).normalize();
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!Files.isRegularFile(file)) {
            return new CadState(Status.ERROR, "CAD file not found: " + cfg.cad.file, null, file, null, null);
        }
        try {
            if (lower.endsWith(".urdf")) {
                RobotModel m = ModelBuilder.fromUrdf(cfg, Urdf.load(file), name);
                ModelBuilder.applyTo(cfg, m);
                return new CadState(Status.READY, m.summary, m, file, null, null);
            }
            if (lower.endsWith(".step") || lower.endsWith(".stp")) {
                String sha = CadMeshFile.sha256(file);
                Path cache = CadMeshFile.cacheFor(file);
                if (!Files.isRegularFile(cache)) {
                    return new CadState(Status.CONVERT, "Converting " + name + " in the Driver Station page...", null, file, cache, sha);
                }
                byte[] data;
                CadMeshFile.Header h;
                List<MeshPart> parts;
                try {
                    data = Files.readAllBytes(cache);
                    h = CadMeshFile.header(data);
                    parts = CadMeshFile.read(data);
                } catch (IOException | RuntimeException e) {
                    return new CadState(Status.CONVERT, "The saved conversion of " + name + " is damaged; converting it again in the Driver Station page...",
                            null, file, cache, sha);
                }
                if (!sha.equals(h.sha256())) {
                    // Show the previous conversion meanwhile, if the robot file still fits it (it
                    // may name parts that only the new STEP file has).
                    RobotModel stale = null;
                    try {
                        stale = ModelBuilder.fromStep(cfg, parts, name);
                        ModelBuilder.applyTo(cfg, stale);
                    } catch (RuntimeException e) {
                        stale = null;
                    }
                    return new CadState(Status.CONVERT, name + " changed; converting it again in the Driver Station page...", stale, file, cache, sha);
                }
                if (h.triangles() == 0) {
                    // Saved before empty conversions were refused; converting it in the page again would fail the same way.
                    return new CadState(Status.ERROR, "Can't use " + name + ": " + CadMeshFile.EMPTY, null, file, null, null);
                }
                RobotModel m = ModelBuilder.fromStep(cfg, parts, name);
                ModelBuilder.applyTo(cfg, m);
                return new CadState(Status.READY, m.summary, m, file, cache, sha);
            }
            return new CadState(Status.ERROR, "CAD file must be .step, .stp or .urdf: " + name, null, file, null, null);
        } catch (IOException | RuntimeException e) {
            String why = e.getMessage() == null ? e.toString() : e.getMessage();
            return new CadState(Status.ERROR, "Can't use " + name + ": " + why, null, file, null, null);
        }
    }
}
