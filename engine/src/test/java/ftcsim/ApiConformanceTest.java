package ftcsim;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

/**
 * Guards the "same TeamCode in the sim and on the robot" promise: every class in the simulator's
 * SDK that robot code can see must exist in the official FTC SDK, with no public or protected
 * member the official SDK lacks, and its constants must have the official values. Simulator internals live in ftcsim.* (TeamCode may not import
 * them) and android.* stand-ins are checked separately by hand.
 *
 * Deliberate exceptions are listed in {@link #ALLOWED} with the reason for each.
 */
class ApiConformanceTest {

    /** Members the simulator must add; none of them is usable from TeamCode in practice. */
    private static final Set<String> ALLOWED = Set.of(
            // Robot code never constructs a HardwareMap; the real constructor needs an Android Context.
            "com.qualcomm.robotcore.hardware.HardwareMap#public com.qualcomm.robotcore.hardware.HardwareMap();"
    );

    /**
     * Classes that are not in the SDK's jars but do exist in the robot app. RobotCore's and
     * Hardware's resource ids (com.qualcomm.robotcore.R, com.qualcomm.hardware.R) are generated into
     * the Robot Controller app when it is built.
     */
    private static final Set<String> ALLOWED_CLASSES = Set.of("com.qualcomm.robotcore.R", "com.qualcomm.robotcore.R$string",
            "com.qualcomm.hardware.R", "com.qualcomm.hardware.R$string");

    private static final ToolProvider JAVAP = ToolProvider.findFirst("javap").orElseThrow();

    @Test
    void simulatorSdkIsASubsetOfTheRealSdk() throws IOException {
        Path simClasses = Paths.get(System.getProperty("ftcsim.simSdkClasses"));
        Path realDir = Paths.get(System.getProperty("ftcsim.realSdkDir"));
        List<Path> realJars;
        try (Stream<Path> s = Files.list(realDir)) {
            realJars = s.filter(p -> p.toString().endsWith(".jar")).sorted().collect(Collectors.toList());
        }
        assertTrue(realJars.size() >= 2, "official SDK jars not found in " + realDir);
        Set<String> realClassNames = new LinkedHashSet<>();
        for (Path jar : realJars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                zip.stream().map(e -> e.getName())
                        .filter(n -> n.endsWith(".class"))
                        .forEach(n -> realClassNames.add(n.substring(0, n.length() - 6).replace('/', '.')));
            }
        }
        String realClasspath = realJars.stream().map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator));

        List<String> simClassNames;
        try (Stream<Path> s = Files.walk(simClasses)) {
            simClassNames = s.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> simClasses.relativize(p).toString().replace('\\', '/'))
                    .filter(n -> !n.startsWith("ftcsim/") && !n.startsWith("android/"))
                    .filter(n -> !n.matches(".*\\$\\d+\\.class")) // anonymous classes are not API
                    .map(n -> n.substring(0, n.length() - 6).replace('/', '.'))
                    .sorted()
                    .collect(Collectors.toList());
        }

        TreeMap<String, List<String>> problems = new TreeMap<>();
        int checkedClasses = 0, checkedMembers = 0;
        for (String cls : simClassNames) {
            List<String> simLines = javap(simClasses.toString(), cls);
            if (simLines.isEmpty()) continue;
            String header = simLines.get(0);
            boolean visible = header.startsWith("public ") || header.startsWith("protected ");
            if (!realClassNames.contains(cls)) {
                if (visible && !ALLOWED_CLASSES.contains(cls)) problems.computeIfAbsent(cls, k -> new ArrayList<>()).add("class does not exist in the FTC SDK");
                continue;
            }
            checkedClasses++;
            List<String> realLines = javap(realClasspath, cls);
            if (realLines.isEmpty()) {
                problems.computeIfAbsent(cls, k -> new ArrayList<>()).add("class is not public in the FTC SDK");
                continue;
            }
            if (header.contains(" interface ") != realLines.get(0).contains(" interface ")) {
                problems.computeIfAbsent(cls, k -> new ArrayList<>()).add("class/interface kind differs: " + header);
            }
            Set<String> realMembers = new LinkedHashSet<>();
            collectMembers(realClasspath, cls, realMembers, new LinkedHashSet<>());
            for (String line : simLines.subList(1, simLines.size())) {
                String member = normalize(line);
                checkedMembers++;
                // A protected member may be public in the FTC SDK (what compiles here still compiles
                // there), but a public one must be public there too.
                if (realMembers.contains(member) || OBJECT_METHODS.contains(member)) continue;
                if (member.startsWith("protected ") && realMembers.contains("public " + member.substring("protected ".length()))) continue;
                if (member.contains("ftcsim.")) continue; // only reachable with ftcsim types, which TeamCode can't import
                if (ALLOWED.contains(cls + "#" + member)) continue;
                problems.computeIfAbsent(cls, k -> new ArrayList<>()).add(member);
            }
        }

        System.out.printf("API conformance: %d classes, %d members checked against FTC SDK %s%n",
                checkedClasses, checkedMembers, realJars.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(", ")));
        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder("The simulator's SDK exposes API the real FTC SDK does not have:\n");
            problems.forEach((cls, list) -> {
                sb.append("  ").append(cls).append('\n');
                list.forEach(m -> sb.append("      ").append(m).append('\n'));
            });
            fail(sb.toString());
        }
    }

    private static final Set<String> OBJECT_METHODS = Set.of(
            "public boolean equals(java.lang.Object);", "public int hashCode();", "public java.lang.String toString();");

    private final java.util.Map<String, List<String>> realCache = new java.util.HashMap<>();

    /** Members of a real class including everything it inherits from its superclasses and interfaces. */
    private void collectMembers(String classpath, String cls, Set<String> into, Set<String> visited) {
        if (!visited.add(cls)) return;
        List<String> lines = realCache.computeIfAbsent(cls, c -> javap(classpath, c));
        if (lines.isEmpty()) return;
        for (String line : lines.subList(1, lines.size())) into.add(normalize(line));
        for (String sup : supertypes(lines.get(0))) collectMembers(classpath, sup, into, visited);
    }

    /** Super class and interfaces named in a javap class header, without type arguments. */
    static List<String> supertypes(String header) {
        StringBuilder plain = new StringBuilder();
        int depth = 0;
        for (char ch : header.toCharArray()) {
            if (ch == '<') depth++;
            else if (ch == '>') depth--;
            else if (depth == 0) plain.append(ch);
        }
        String h = plain.toString().replace("{", " ");
        List<String> result = new ArrayList<>();
        for (String keyword : new String[]{" extends ", " implements "}) {
            int at = h.indexOf(keyword);
            if (at < 0) continue;
            String rest = h.substring(at + keyword.length());
            int next = rest.indexOf(" implements ");
            if (next >= 0) rest = rest.substring(0, next);
            for (String t : rest.split(",")) {
                String name = t.trim();
                if (!name.isEmpty()) result.add(name);
            }
        }
        return result;
    }

    /**
     * javap -protected -constants output: first element is the class header, the rest are members,
     * constants with their values (TeamCode that uses a constant has its value compiled in).
     */
    private static List<String> javap(String classpath, String className) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int rc = JAVAP.run(new PrintWriter(out), new PrintWriter(err), "-protected", "-constants", "-cp", classpath, className);
        List<String> lines = new ArrayList<>();
        if (rc != 0) return lines;
        for (String line : out.toString().split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("Compiled from") || t.equals("}")) continue;
            lines.add(t);
        }
        return lines;
    }

    /** Drops modifiers and throws-clauses that do not change what source code can call. */
    static String normalize(String member) {
        String m = member;
        int throwsAt = m.indexOf(" throws ");
        if (throwsAt >= 0) m = m.substring(0, throwsAt) + ";";
        for (String mod : new String[]{"synchronized ", "final ", "native ", "transient ", "volatile ", "abstract ", "default ", "strictfp "}) {
            m = m.replace(mod, "");
        }
        return m.replaceAll("\\s+", " ").trim();
    }
}
