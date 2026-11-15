package ftcsim.runner;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.Disabled;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/**
 * Compiles TeamCode the way Android Studio would (Java 8, FTC SDK 12.0 API) but tolerantly:
 * a file that uses something the simulator does not provide (vision, Android UI, third-party
 * hardware) is set aside with the reason, and everything else still runs.
 */
public final class TeamCodeCompiler {

    /** A directory of Java sources; {@code samples} marks the FTC sample OpModes. */
    public record SourceRoot(Path dir, boolean samples) {
    }

    public record Skipped(String file, String reason) {
    }

    public record Result(boolean ok, String message, ClassLoader loader, List<OpModeInfo> opModes,
                         List<Skipped> skipped, int compiledFiles, long millis) {
    }

    private static final Pattern SIM_INTERNALS = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?ftcsim\\.|\\bftcsim\\.hal\\.");

    private final List<SourceRoot> roots;
    private final Path workDir;
    private int buildNumber = 0;
    private URLClassLoader lastLoader;

    public TeamCodeCompiler(List<SourceRoot> roots, Path workDir) {
        this.roots = roots;
        this.workDir = workDir;
    }

    public List<SourceRoot> roots() {
        return roots;
    }

    public synchronized Result compile() {
        long t0 = System.currentTimeMillis();
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            return new Result(false, "No Java compiler available: run the simulator with a JDK (not a JRE).",
                    null, List.of(), List.of(), 0, 0);
        }
        Map<Path, Boolean> files = new LinkedHashMap<>();   // file -> is sample
        for (SourceRoot root : roots) {
            if (!Files.isDirectory(root.dir())) continue;
            try (Stream<Path> s = Files.walk(root.dir())) {
                s.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> files.put(p, root.samples()));
            } catch (IOException e) {
                return new Result(false, "Could not read " + root.dir() + ": " + e.getMessage(), null, List.of(), List.of(), 0, 0);
            }
        }
        List<Skipped> skipped = new ArrayList<>();
        Set<Path> active = new HashSet<>(files.keySet());
        for (Path f : files.keySet()) {
            try {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                if (SIM_INTERNALS.matcher(src).find()) {
                    active.remove(f);
                    skipped.add(new Skipped(display(f), "uses simulator internals (ftcsim.*), which do not exist on the robot"));
                }
            } catch (IOException e) {
                active.remove(f);
                skipped.add(new Skipped(display(f), "unreadable: " + e.getMessage()));
            }
        }

        Path out = workDir.resolve("build-" + (++buildNumber) + "-" + System.currentTimeMillis());
        String classpath = sdkClasspath();
        List<String> options = List.of("--release", "8", "-Xlint:-options", "-nowarn", "-proc:none",
                "-encoding", "UTF-8", "-g", "-classpath", classpath, "-d", out.toString());
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, Locale.US, StandardCharsets.UTF_8)) {
            Files.createDirectories(out);   // also when there is nothing to compile (an empty TeamCode)
            for (int round = 0; round < 12 && !active.isEmpty(); round++) {
                deleteTree(out);
                Files.createDirectories(out);
                List<File> sources = active.stream().sorted().map(Path::toFile).collect(Collectors.toList());
                Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(sources);
                DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
                boolean ok = javac.getTask(null, fm, diags, options, null, units).call();
                if (ok) break;
                Map<Path, String> failing = new LinkedHashMap<>();
                for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                    if (d.getKind() != Diagnostic.Kind.ERROR || d.getSource() == null) continue;
                    Path p = Paths.get(d.getSource().toUri());
                    failing.putIfAbsent(p, "line " + d.getLineNumber() + ": " + firstLine(d.getMessage(Locale.US)));
                }
                if (failing.isEmpty()) {
                    String msg = diags.getDiagnostics().stream().map(d -> d.getMessage(Locale.US)).collect(Collectors.joining("; "));
                    return new Result(false, "Compilation failed: " + msg, null, List.of(), skipped, 0, System.currentTimeMillis() - t0);
                }
                for (Map.Entry<Path, String> e : failing.entrySet()) {
                    if (active.remove(e.getKey())) skipped.add(new Skipped(display(e.getKey()), e.getValue()));
                }
            }
        } catch (IOException e) {
            return new Result(false, "Compilation failed: " + e.getMessage(), null, List.of(), skipped, 0, System.currentTimeMillis() - t0);
        }

        List<OpModeInfo> opModes;
        URLClassLoader loader;
        try {
            loader = new URLClassLoader(new URL[]{out.toUri().toURL()}, OpMode.class.getClassLoader());
            Set<String> sampleClasses = new HashSet<>();
            for (Path f : active) if (files.get(f)) sampleClasses.add(f.getFileName().toString().replace(".java", ""));
            opModes = scan(out, loader, sampleClasses);
        } catch (IOException e) {
            return new Result(false, "Could not load compiled classes: " + e.getMessage(), null, List.of(), skipped, 0, 0);
        }
        URLClassLoader previous = lastLoader;
        lastLoader = loader;
        cleanOldBuilds(out);
        if (previous != null) {
            // Old OpMode classes stay usable by a running OpMode; the loader is closed lazily by GC.
        }
        long millis = System.currentTimeMillis() - t0;
        String msg = String.format(Locale.US, "Built %d file%s in %.1f s%s", active.size(), active.size() == 1 ? "" : "s",
                millis / 1000.0, skipped.isEmpty() ? "" : String.format(Locale.US, "; %d skipped", skipped.size()));
        return new Result(true, msg, loader, opModes, skipped, active.size(), millis);
    }

    private List<OpModeInfo> scan(Path out, ClassLoader loader, Set<String> sampleClasses) throws IOException {
        List<OpModeInfo> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        List<Path> classFiles;
        try (Stream<Path> s = Files.walk(out)) {
            classFiles = s.filter(p -> p.toString().endsWith(".class")).sorted().collect(Collectors.toList());
        }
        for (Path p : classFiles) {
            String rel = out.relativize(p).toString().replace(File.separatorChar, '.');
            String className = rel.substring(0, rel.length() - ".class".length());
            Class<?> c;
            try {
                c = Class.forName(className, false, loader);
            } catch (Throwable t) {
                continue;
            }
            if (!OpMode.class.isAssignableFrom(c) || Modifier.isAbstract(c.getModifiers()) || !Modifier.isPublic(c.getModifiers())) {
                continue;
            }
            TeleOp teleOp = c.getAnnotation(TeleOp.class);
            Autonomous auto = c.getAnnotation(Autonomous.class);
            if (teleOp == null && auto == null) continue;
            String name = teleOp != null ? teleOp.name() : auto.name();
            String group = teleOp != null ? teleOp.group() : auto.group();
            if (name == null || name.isEmpty()) name = c.getSimpleName();
            String unique = name;
            for (int i = 2; names.contains(unique); i++) unique = name + " (" + i + ")";
            names.add(unique);
            String outer = c.getSimpleName();
            boolean sample = sampleClasses.contains(outer) || c.getName().startsWith("org.firstinspires.ftc.robotcontroller.external.samples");
            result.add(new OpModeInfo(unique, group, teleOp != null ? OpModeInfo.Kind.TELEOP : OpModeInfo.Kind.AUTONOMOUS,
                    c.getName(), c.isAnnotationPresent(Disabled.class), sample,
                    auto != null ? auto.preselectTeleOp() : "", c));
        }
        result.sort(Comparator.comparing((OpModeInfo o) -> o.group().isEmpty() ? "￿" : o.group()).thenComparing(OpModeInfo::name));
        return result;
    }

    /**
     * What TeamCode compiles against, as on the robot: the simulator's SDK classes, FIRST's own
     * Vision and RobotCore classes it runs unmodified, the AprilTag detector's Java wrapper, and
     * OpenCV's Java API (the robot's version).
     */
    static String sdkClasspath() {
        Class<?>[] markers = {
                OpMode.class,                                                              // simulator SDK
                org.firstinspires.ftc.vision.VisionPortal.class,                           // FIRST Vision
                org.firstinspires.ftc.robotcore.external.matrices.MatrixF.class,           // FIRST RobotCore
                org.openftc.apriltag.AprilTagDetectorJNI.class,                            // AprilTag wrapper
                org.opencv.core.Mat.class};                                                // OpenCV
        Set<String> entries = new java.util.LinkedHashSet<>();
        for (Class<?> c : markers) {
            try {
                entries.add(Paths.get(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            } catch (Exception e) {
                throw new IllegalStateException("Cannot locate the classes of " + c.getName(), e);
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private String display(Path f) {
        for (SourceRoot r : roots) {
            if (f.startsWith(r.dir())) return r.dir().relativize(f).toString().replace(File.separatorChar, '/');
        }
        return f.getFileName().toString();
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private void cleanOldBuilds(Path keep) {
        try (Stream<Path> s = Files.list(workDir)) {
            List<Path> old = s.filter(p -> p.getFileName().toString().startsWith("build-") && !p.equals(keep))
                    .sorted(Comparator.comparing(Path::toString)).collect(Collectors.toList());
            // keep the previous build too: a running OpMode may still be loading classes from it
            for (int i = 0; i < old.size() - 1; i++) deleteTree(old.get(i));
        } catch (IOException ignored) {
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.deleteIfExists(p);
        }
    }

    /** A cheap fingerprint of the sources, to notice edits. */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (SourceRoot root : roots) {
            if (!Files.isDirectory(root.dir())) continue;
            try (Stream<Path> s = Files.walk(root.dir())) {
                s.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> {
                    try {
                        sb.append(p).append(':').append(Files.getLastModifiedTime(p).toMillis()).append(':').append(Files.size(p)).append(';');
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
        }
        return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
    }
}
