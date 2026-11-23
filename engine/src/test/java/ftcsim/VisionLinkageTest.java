package ftcsim;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.invoke.MethodType;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

/**
 * The simulator runs FIRST's own compiled classes (VisionPortal, the AprilTag and color
 * processors, RobotCore's math and devices, the Hardware module's Blinkin and hub orientation) on
 * top of stand-ins for the Android parts. This checks, before any OpMode runs, that every class,
 * method, constructor and field those classes refer to exists here as they use it: a field of the
 * type they read, static where they use it statically, and not private to another class. So a
 * stand-in can never surface as a NoSuchMethodError, NoSuchFieldError, IncompatibleClassChangeError
 * or IllegalAccessError in a team's code.
 */
class VisionLinkageTest {

    /** References that need not resolve, with the reason. */
    private static final Set<String> ALLOWED = Set.of();

    private static final ToolProvider JAVAP = ToolProvider.findFirst("javap").orElseThrow();
    private static final Pattern REF = Pattern.compile("(\\w+)\\s+#\\d+(?:,\\s*\\d+)?\\s+// (Method|InterfaceMethod|Field) ([\\w/$]+)\\.(\"?[\\w<>$]+\"?):(\\S+)");
    private static final Pattern CLASS_REF = Pattern.compile("// class ([\\w/$]+)$");

    @Test
    void firstsClassesLinkAgainstTheSimulator() throws Exception {
        List<Path> jars = new ArrayList<>();
        for (Class<?> marker : new Class<?>[]{org.firstinspires.ftc.vision.VisionPortal.class,
                org.firstinspires.ftc.robotcore.external.matrices.MatrixF.class, org.openftc.apriltag.AprilTagDetectorJNI.class,
                com.qualcomm.hardware.rev.RevBlinkinLedDriver.class}) {
            jars.add(Paths.get(marker.getProtectionDomain().getCodeSource().getLocation().toURI()));
        }
        List<String> classes = new ArrayList<>();
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                zip.stream().map(e -> e.getName()).filter(n -> n.endsWith(".class"))
                        .forEach(n -> classes.add(n.substring(0, n.length() - 6).replace('/', '.')));
            }
        }
        assertTrue(classes.size() > 100, "FIRST's classes found: " + classes.size());

        String cp = String.join(java.io.File.pathSeparator, jars.stream().map(Path::toString).toList());
        ClassLoader loader = getClass().getClassLoader();
        TreeMap<String, Set<String>> missing = new TreeMap<>();
        Set<String> checked = new HashSet<>();
        for (int i = 0; i < classes.size(); i += 40) {
            List<String> batch = classes.subList(i, Math.min(classes.size(), i + 40));
            List<String> args = new ArrayList<>(List.of("-c", "-p", "-cp", cp));
            args.addAll(batch);
            StringWriter out = new StringWriter();
            JAVAP.run(new PrintWriter(out), new PrintWriter(new StringWriter()), args.toArray(new String[0]));
            String current = "?";
            for (String line : out.toString().split("\\R")) {
                String t = line.trim();
                if (t.startsWith("Compiled from")) continue;
                if (!line.startsWith(" ") && (t.contains(" class ") || t.contains(" interface ") || t.startsWith("class ") || t.startsWith("interface "))) {
                    current = t;
                }
                Matcher m = REF.matcher(t);
                if (m.find()) {
                    String op = m.group(1), owner = m.group(3).replace('/', '.'), name = m.group(4).replace("\"", ""), desc = m.group(5);
                    String key = op + " " + m.group(2) + " " + owner + "." + name + ":" + desc;
                    if (!checked.add(key) || owner.startsWith("[")) continue;
                    String problem = resolve(loader, op, m.group(2), owner, name, desc, user(current));
                    if (problem != null && !ALLOWED.contains(key)) missing.computeIfAbsent(owner, k -> new TreeSet<>()).add(problem + "   (from " + shortName(current) + ")");
                    continue;
                }
                Matcher c = CLASS_REF.matcher(t);
                if (c.find()) {
                    String owner = c.group(1).replace('/', '.');
                    if (owner.startsWith("[") || !checked.add("class " + owner)) continue;
                    if (load(loader, owner) == null) missing.computeIfAbsent(owner, k -> new TreeSet<>()).add("class is missing");
                }
            }
        }
        System.out.printf("Vision linkage: %d references from %d FIRST classes checked%n", checked.size(), classes.size());
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("FIRST's classes refer to things the simulator does not provide:\n");
            missing.forEach((owner, list) -> {
                sb.append("  ").append(owner).append('\n');
                list.forEach(p -> sb.append("      ").append(p).append('\n'));
            });
            fail(sb.toString());
        }
    }

    /** The class a javap header names (the class whose code makes the references below it). */
    private static String user(String header) {
        Matcher m = Pattern.compile("(?:class|interface) ([\\w.$]+)").matcher(header);
        return m.find() ? m.group(1) : "";
    }

    private static String shortName(String header) {
        Matcher m = Pattern.compile("([\\w.$]+)(<|\\s|$)").matcher(header.replaceAll("^(public |protected |private |abstract |final |static )*(class|interface) ", ""));
        return m.find() ? m.group(1) : header;
    }

    private static Class<?> load(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Null if the reference resolves as {@code op} (javap's instruction: getstatic, invokevirtual...)
     * uses it from class {@code user}, else what is wrong.
     */
    private static String resolve(ClassLoader loader, String op, String kind, String owner, String name, String desc, String user) {
        Class<?> c = load(loader, owner);
        if (c == null) return "class is missing";
        boolean wantStatic = op.equals("getstatic") || op.equals("putstatic") || op.equals("invokestatic");
        if ("Field".equals(kind)) {
            for (Class<?> k : hierarchy(c)) {
                Field f;
                try {
                    f = k.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    continue;
                } catch (Throwable t) {
                    return "field " + name + ": " + t;
                }
                if (!f.getType().descriptorString().equals(desc)) return "field " + name + " is " + f.getType().descriptorString() + ", not " + desc;
                return usable(f, wantStatic, user, "field " + name);
            }
            return "field " + name + " " + desc;
        }
        if (c == java.lang.invoke.MethodHandle.class || c == java.lang.invoke.VarHandle.class) return null;   // signature-polymorphic
        for (Class<?> k : hierarchy(c)) {
            try {
                List<Executable> candidates = new ArrayList<>(List.of(k.getDeclaredMethods()));
                if ("<init>".equals(name)) candidates = new ArrayList<>(List.of(k.getDeclaredConstructors()));
                for (Executable e : candidates) {
                    if (!"<init>".equals(name) && !e.getName().equals(name)) continue;
                    Class<?> ret = e instanceof Method ? ((Method) e).getReturnType() : void.class;
                    if (MethodType.methodType(ret, e.getParameterTypes()).toMethodDescriptorString().equals(desc)) {
                        return usable(e, wantStatic, user, ("<init>".equals(name) ? "constructor " : "method " + name) + desc);
                    }
                }
            } catch (Throwable t) {
                return name + desc + ": " + t;
            }
            if ("<init>".equals(name)) break;   // constructors are not inherited
        }
        return ("<init>".equals(name) ? "constructor " : "method " + name) + desc;
    }

    /** Null if {@code m} is static exactly when it is used so, and not private to a class other than {@code user}'s. */
    private static String usable(Member m, boolean wantStatic, String user, String what) {
        boolean isStatic = Modifier.isStatic(m.getModifiers());
        if (isStatic != wantStatic) return what + " is " + (isStatic ? "static" : "not static") + " here";
        if (Modifier.isPrivate(m.getModifiers()) && !m.getDeclaringClass().getName().equals(user)
                && !user.startsWith(m.getDeclaringClass().getName() + "$") && !m.getDeclaringClass().getName().startsWith(user + "$")) {
            return what + " is private here";
        }
        return null;
    }

    /** The class, its superclasses and all their interfaces. */
    private static List<Class<?>> hierarchy(Class<?> c) {
        List<Class<?>> out = new ArrayList<>();
        Deque<Class<?>> todo = new ArrayDeque<>();
        todo.add(c);
        Set<Class<?>> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            Class<?> k = todo.poll();
            if (k == null || !seen.add(k)) continue;
            out.add(k);
            try {
                if (k.getSuperclass() != null) todo.add(k.getSuperclass());
                todo.addAll(List.of(k.getInterfaces()));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
