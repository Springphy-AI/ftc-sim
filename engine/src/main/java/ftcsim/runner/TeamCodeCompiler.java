package ftcsim.runner;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
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

    /**
     * A compile error, for the code editor: the file (its root's index in {@link #roots()} and its
     * path in it), where the error starts and ends (lines from 1, columns from 0 in characters, line
     * breaks not counted), and javac's message.
     */
    public record Problem(int root, String file, int line, int column, int endLine, int endColumn, String message) {
    }

    public record Result(boolean ok, String message, ClassLoader loader, List<OpModeInfo> opModes,
                         List<Skipped> skipped, List<Problem> problems, int compiledFiles, long millis) {
    }

    /** An import of the simulator's internals (group 1) or a use of ftcsim.hal (group 2). */
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern SIM_INTERNALS = Pattern.compile("(?m)^[ \\t]*(import\\s+(?:static\\s+)?ftcsim\\.)|\\b(ftcsim\\.hal\\.)");

    /** How many of one file's compile errors the page is given; it is told how many more there are. */
    public static final int PROBLEMS_PER_FILE = 20;

    private final List<SourceRoot> roots;
    private final Path workDir;
    private int buildNumber = 0;
    /** The previous successful build's classes, which a running OpMode may still be loading. */
    private Path previousOut;

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
                    null, List.of(), List.of(), List.of(), 0, 0);
        }
        Map<Path, Boolean> files = new LinkedHashMap<>();   // file -> is sample
        for (SourceRoot root : roots) {
            if (!Files.isDirectory(root.dir())) continue;
            try {
                for (Path p : javaFiles(root.dir())) files.put(p, root.samples());
            } catch (IOException e) {
                return new Result(false, "Could not read " + root.dir() + ": " + e.getMessage(), null, List.of(), List.of(), List.of(), 0, 0);
            }
        }
        List<Skipped> skipped = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        Map<Path, String> texts = new HashMap<>();
        Set<Path> active = new HashSet<>(files.keySet());
        for (Path f : files.keySet()) {
            try {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                texts.put(f, src);
                Matcher internals = SIM_INTERNALS.matcher(src);
                if (internals.find()) {
                    active.remove(f);
                    String why = "uses simulator internals (ftcsim.*), which do not exist on the robot";
                    skipped.add(new Skipped(display(f), why));
                    int g = internals.start(1) >= 0 ? 1 : 2;
                    problems.add(problem(f, src, internals.start(g), internals.end(g), why));
                }
            } catch (java.nio.charset.CharacterCodingException e) {
                active.remove(f);
                skipped.add(new Skipped(display(f), "not UTF-8 text: javac reads TeamCode as UTF-8, for the robot too; save it as UTF-8"));
            } catch (IOException e) {
                active.remove(f);
                skipped.add(new Skipped(display(f), "unreadable: " + e.getMessage()));
            }
        }

        Path out = workDir.resolve("build-" + (++buildNumber) + "-" + System.currentTimeMillis());
        String classpath = sdkClasspath();
        // -Xmaxerrs: javac stops reporting at 100 errors by default, and a file it says nothing about
        // can't be set aside. One old season's Road Runner drive class alone has more than that.
        List<String> options = List.of("--release", "8", "-Xlint:-options", "-nowarn", "-Xmaxerrs", "1000000", "-proc:none",
                "-encoding", "UTF-8", "-g", "-classpath", classpath, "-d", out.toString());
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, Locale.US, StandardCharsets.UTF_8)) {
            Files.createDirectories(out);   // also when there is nothing to compile (an empty TeamCode)
            // Compiles what is left until javac succeeds, setting aside the files it finds errors in,
            // so every file left has its classes. A file can compile against a class whose own file
            // has errors and fail once that file is set aside: a chain of such files takes a round each.
            while (!active.isEmpty()) {
                deleteTree(out);
                Files.createDirectories(out);
                List<File> sources = active.stream().sorted().map(Path::toFile).collect(Collectors.toList());
                Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(sources);
                DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
                if (javac.getTask(null, fm, diags, options, null, units).call()) break;
                Map<Path, List<Diagnostic<? extends JavaFileObject>>> failing = new LinkedHashMap<>();
                for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                    if (d.getKind() != Diagnostic.Kind.ERROR || d.getSource() == null) continue;
                    failing.computeIfAbsent(Paths.get(d.getSource().toUri()), k -> new ArrayList<>()).add(d);
                }
                boolean setAside = false;
                for (Map.Entry<Path, List<Diagnostic<? extends JavaFileObject>>> e : failing.entrySet()) {
                    Path p = e.getKey();
                    if (!active.remove(p)) continue;
                    setAside = true;
                    Diagnostic<? extends JavaFileObject> first = e.getValue().get(0);
                    skipped.add(new Skipped(display(p), "line " + first.getLineNumber() + ": " + firstLine(first.getMessage(Locale.US))));
                    problems.addAll(problems(p, texts.get(p), e.getValue()));
                }
                if (!setAside) {
                    // An error that isn't in one of the files javac was given: nothing to set aside.
                    String msg = diags.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                            .map(d -> firstLine(d.getMessage(Locale.US))).distinct().limit(5).collect(Collectors.joining("; "));
                    return new Result(false, "Compilation failed: " + msg, null, List.of(), skipped, problems, 0, System.currentTimeMillis() - t0);
                }
            }
            if (active.isEmpty()) {
                // Every file was set aside: no class a failed round left behind may pass for the build.
                deleteTree(out);
                Files.createDirectories(out);
            }
        } catch (IOException e) {
            return new Result(false, "Compilation failed: " + e.getMessage(), null, List.of(), skipped, problems, 0, System.currentTimeMillis() - t0);
        }

        List<OpModeInfo> opModes;
        URLClassLoader loader;
        try {
            loader = new URLClassLoader(new URL[]{out.toUri().toURL()}, OpMode.class.getClassLoader());
            // The samples by full name: a team's copy of one in TeamCode may keep its class name
            // (FIRST's instructions are to copy a sample and remove @Disabled) and isn't a sample.
            Set<String> sampleClasses = new HashSet<>();
            for (Path f : active) {
                if (!files.get(f)) continue;
                Matcher pkg = PACKAGE.matcher(texts.get(f));
                String simple = f.getFileName().toString().replace(".java", "");
                sampleClasses.add(pkg.find() ? pkg.group(1) + "." + simple : simple);
            }
            opModes = scan(out, loader, sampleClasses);
        } catch (IOException e) {
            return new Result(false, "Could not load compiled classes: " + e.getMessage(), null, List.of(), skipped, problems, 0, 0);
        }
        cleanOldBuilds(out);
        long millis = System.currentTimeMillis() - t0;
        String msg = String.format(Locale.US, "Built %d file%s in %.1f s%s", active.size(), active.size() == 1 ? "" : "s",
                millis / 1000.0, skipped.isEmpty() ? "" : String.format(Locale.US, "; %d skipped", skipped.size()));
        return new Result(true, msg, loader, opModes, skipped, problems, active.size(), millis);
    }

    /**
     * A file's javac errors as {@link Problem}s for the page: the first {@value #PROBLEMS_PER_FILE},
     * then how many more there are (an old season's file can have hundreds, all for one missing library).
     */
    private List<Problem> problems(Path file, String text, List<Diagnostic<? extends JavaFileObject>> errors) {
        List<Problem> out = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : errors.subList(0, Math.min(errors.size(), PROBLEMS_PER_FILE))) {
            out.add(problem(file, text, d));
        }
        if (errors.size() > PROBLEMS_PER_FILE) {
            Problem last = out.get(out.size() - 1);
            out.add(new Problem(last.root(), last.file(), last.line(), last.column(), last.endLine(), last.endColumn(),
                    "and " + (errors.size() - PROBLEMS_PER_FILE) + " more errors in this file"));
        }
        return out;
    }

    /** A javac error as a {@link Problem}, its position worked out in the file's own text. */
    private Problem problem(Path file, String text, Diagnostic<? extends JavaFileObject> d) {
        String message = d.getMessage(Locale.US);
        long start = d.getStartPosition(), end = d.getEndPosition();
        if (text == null || start == Diagnostic.NOPOS) {
            int line = (int) Math.max(1, d.getLineNumber());
            return new Problem(rootOf(file), display(file), line, 0, line, 0, message);
        }
        if (end == Diagnostic.NOPOS || end < start) end = start;
        return problem(file, text, (int) start, (int) end, message);
    }

    private Problem problem(Path file, String text, int start, int end, String message) {
        int[] from = lineColumn(text, start), to = lineColumn(text, end);
        return new Problem(rootOf(file), display(file), from[0], from[1], to[0], to[1], message);
    }

    /** Line (from 1) and column (from 0) of a character offset; "\r\n", "\n" and "\r" end lines. */
    static int[] lineColumn(String text, int offset) {
        int line = 1, col = 0;
        int n = Math.min(Math.max(offset, 0), text.length());
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                line++;
                col = 0;
            } else if (c != '\r') {
                col++;
            }
        }
        return new int[]{line, col};
    }

    /** Whether a file is in one of these source folders. */
    public boolean owns(Path file) {
        return rootOf(file.toAbsolutePath().normalize()) >= 0;
    }

    /** The index of the source folder a file is in, or -1. */
    private int rootOf(Path f) {
        for (int i = 0; i < roots.size(); i++) if (f.startsWith(roots.get(i).dir())) return i;
        return -1;
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
            String topLevel = c.getName().contains("$") ? c.getName().substring(0, c.getName().indexOf('$')) : c.getName();
            boolean sample = sampleClasses.contains(topLevel);
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

    /**
     * Deletes every build folder but this one and the previous successful one, whose classes a
     * running OpMode may still be loading (its loader is closed lazily, by the garbage collector).
     */
    private void cleanOldBuilds(Path current) {
        Path previous = previousOut;
        previousOut = current;
        try (Stream<Path> s = Files.list(workDir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().startsWith("build-") && !p.equals(current) && !p.equals(previous))
                    .collect(Collectors.toList())) {
                deleteTree(p);
            }
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
            try {
                for (Path p : javaFiles(root.dir())) {
                    try {
                        sb.append(p).append(':').append(Files.getLastModifiedTime(p).toMillis()).append(':').append(Files.size(p)).append(';');
                    } catch (IOException ignored) {
                        // gone since the folder was walked
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
    }

    /**
     * The .java files under {@code dir}, in path order. Files come and go while a folder is
     * walked (editors' temporary files, Android Studio's "safe write"): one that disappears meanwhile
     * is left out, where Files.walk would fail the whole walk with an UncheckedIOException.
     */
    public static List<Path> javaFiles(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().endsWith(".java")) out.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;   // gone since its folder was listed
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) {
                return FileVisitResult.CONTINUE;   // a folder that went away while it was listed
            }
        });
        out.sort(null);
        return out;
    }
}
