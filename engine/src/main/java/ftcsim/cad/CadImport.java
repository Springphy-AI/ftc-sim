package ftcsim.cad;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ftcsim.config.RobotConfig;

/**
 * A robot from a STEP file dropped on the Driver Station page. The STEP file is saved in
 * robots/cad/ and a robot file is written for it with the devices and drive of the robot file in
 * use, so TeamCode finds the same hardware; everything else comes from the CAD. Dropping a newer
 * export of the same file replaces the STEP file and keeps its robot file, with whatever was
 * added to it since.
 */
public final class CadImport {
    /** Largest STEP file accepted. */
    public static final long MAX_BYTES = 400L << 20;
    private static final Pattern WINDOWS_RESERVED = Pattern.compile("(?i)con|prn|aux|nul|com\\d|lpt\\d");
    private static final Object LOCK = new Object();

    /** The robot file and STEP file, and whether they already existed (a newer export). */
    public record Result(Path robotFile, Path step, boolean updated) {
    }

    /** The file was bigger than {@link #MAX_BYTES}. */
    public static final class TooBig extends IOException {
        TooBig() {
            super("the STEP file is bigger than " + (MAX_BYTES >> 20) + " MB");
        }
    }

    private CadImport() {
    }

    /** A file name for robots/ from the STEP file's: "Our Robot v3.STEP" is our-robot-v3. */
    public static String baseName(String fileName) {
        String stem = stem(fileName).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-+|-+$", "");
        if (stem.length() > 60) stem = stem.substring(0, 60).replaceAll("-+$", "");
        if (stem.isEmpty()) stem = "robot";
        if (WINDOWS_RESERVED.matcher(stem).matches()) stem += "-robot";
        return stem;
    }

    private static String stem(String fileName) {
        String n = fileName == null ? "" : fileName.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /**
     * Saves the STEP file read from {@code in} and writes its robot file, copying the devices and
     * drive of {@code template}. Throws IllegalArgumentException when it isn't a STEP file.
     */
    public static Result add(Path robotsDir, String fileName, InputStream in, Path template) throws IOException {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".step") && !lower.endsWith(".stp")) {
            throw new IllegalArgumentException("only STEP files (.step or .stp) can be added here; export the robot's assembly as STEP from your CAD");
        }
        Path cadDir = robotsDir.resolve("cad");
        Files.createDirectories(cadDir);
        Path upload = Files.createTempFile(cadDir, ".import-", ".tmp");
        try {
            copy(in, upload);
            if (!looksLikeStep(upload)) throw new IllegalArgumentException(fileName + " is not a STEP file (it doesn't start with ISO-10303-21)");
            String sha = CadMeshFile.sha256(upload);
            synchronized (LOCK) {
                String base = baseName(fileName);
                for (int k = 1; ; k++) {
                    String name = k == 1 ? base : base + "-" + k;
                    Path json = robotsDir.resolve(name + ".json"), step = cadDir.resolve(name + ".step");
                    if (Files.exists(json)) {
                        if (!usesStep(json, step)) continue;
                        move(upload, step);   // a newer export: the robot file stays as it is
                        return new Result(json, step, true);
                    }
                    if (Files.exists(step) && !sha.equals(CadMeshFile.sha256(step))) continue;   // someone else's
                    move(upload, step);
                    Files.writeString(json, robotJson(template, stem(fileName), fileName, robotsDir.relativize(step)), StandardCharsets.UTF_8);
                    return new Result(json, step, false);
                }
            }
        } finally {
            Files.deleteIfExists(upload);
        }
    }

    private static long copy(InputStream in, Path to) throws IOException {
        byte[] buf = new byte[1 << 16];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(to)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new TooBig();
                out.write(buf, 0, n);
            }
        }
        return total;
    }

    /** A STEP file starts with ISO-10303-21 (after a byte order mark or blank lines, sometimes). */
    static boolean looksLikeStep(Path file) throws IOException {
        byte[] head = new byte[64];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(head, 0, head.length);
        }
        String s = new String(head, 0, n, StandardCharsets.ISO_8859_1).replace("ï»¿", "").stripLeading();
        return s.startsWith("ISO-10303-21");
    }

    private static boolean usesStep(Path json, Path step) {
        try {
            RobotConfig c = RobotConfig.load(json, false);
            return c.cad != null && c.cad.file != null
                    && json.toAbsolutePath().getParent().resolve(c.cad.file).normalize().equals(step.toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The new robot file: the template's devices, drive, IMU and start, and a cad section with
     * only the STEP file (the CAD gives the rest), plus an empty list for moving parts.
     */
    static String robotJson(Path template, String name, String fileName, Path step) throws IOException {
        JsonObject t = JsonParser.parseString(Files.readString(template, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject out = new JsonObject();
        out.addProperty("name", name);
        out.addProperty("description", "From " + fileName + ", with the devices and drive of " + template.getFileName()
                + ". The simulator works out which way it faces, its wheels, and the rollers and flywheels its motors turn;"
                + " list arms, slides and claws in cad.links.");
        JsonObject cad = new JsonObject();
        cad.addProperty("file", step.toString().replace('\\', '/'));
        cad.add("links", new JsonArray());
        out.add("cad", cad);
        for (Map.Entry<String, JsonElement> e : t.entrySet()) {
            if (!out.has(e.getKey())) out.add(e.getKey(), e.getValue());
        }
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out) + "\n";
    }
}
