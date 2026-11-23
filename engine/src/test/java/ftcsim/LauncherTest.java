package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * run-sim's choices before it builds and starts the simulator: run-sim.bat on Windows, run-sim.sh
 * on macOS and Linux, each with fake JDKs.
 */
class LauncherTest {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    @TempDir
    Path tmp;

    /** A JDK folder that says it is {@code version} (as its release file and its javac do). */
    private static Path jdk(Path dir, String version) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Files.writeString(dir.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        if (WINDOWS) {
            Files.writeString(bin.resolve("javac.exe"), "");   // run-sim.bat only looks for it
        } else {
            Path javac = bin.resolve("javac");
            Files.writeString(javac, "#!/bin/sh\necho \"javac " + version + "\"\n");
            assertTrue(javac.toFile().setExecutable(true));
        }
        return dir;
    }

    /** A JRE: java, no javac. */
    private static Path jre(Path dir, String version) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Files.writeString(dir.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        Path java = bin.resolve(WINDOWS ? "java.exe" : "java");
        Files.writeString(java, "");
        assertTrue(java.toFile().setExecutable(true));
        return dir;
    }

    /**
     * A folder for PATH with a javac that says, as a real one does when asked, that its JDK is
     * {@code home} (null: a javac that finds no JDK, like macOS's stub).
     */
    private Path pathJavac(Path home, String version) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("path-bin"));
        if (WINDOWS) {
            Files.writeString(bin.resolve("javac.cmd"), String.join("\r\n", "@echo off",
                    "1>&2 echo Property settings:",
                    home == null ? "" : "1>&2 echo     java.home = " + home,
                    "1>&2 echo     java.specification.version = " + version,
                    "echo javac " + version, ""));
        } else {
            Path javac = bin.resolve("javac");
            Files.writeString(javac, home == null ? "#!/bin/sh\necho 'Unable to locate a Java Runtime.' >&2\nexit 1\n"
                    : "#!/bin/sh\nprintf 'Property settings:\\n    java.home = %s\\n    java.specification.version = %s\\n' '"
                    + home + "' '" + version + "' >&2\necho 'javac " + version + "'\n");
            assertTrue(javac.toFile().setExecutable(true));
        }
        return bin;
    }

    /** The user's folder, with its .jdks (spaces and parentheses, as user names may have). */
    private Path home() throws Exception {
        return Files.createDirectories(tmp.resolve("Sam Lee (lab PC)"));
    }

    /**
     * The environment of a user whose folder is {@link #home()}, with {@code pathBin} first on PATH.
     * On Windows Android Studio's folders (Program Files, AppData\Local) are in {@code tmp} too;
     * elsewhere FTCSIM_ANDROID_STUDIO_JBR says where it is (nowhere unless a test says).
     */
    private Map<String, String> env(Path pathBin) throws Exception {
        Map<String, String> env = new HashMap<>();
        if (WINDOWS) {
            for (String k : new String[]{"SystemRoot", "SystemDrive", "ComSpec", "PATHEXT", "windir", "TEMP", "TMP"}) {
                if (System.getenv(k) != null) env.put(k, System.getenv(k));
            }
            env.put("PATH", pathBin + ";" + System.getenv("SystemRoot") + "\\System32");
            env.put("USERPROFILE", home().toString());
            env.put("ProgramFiles", Files.createDirectories(tmp.resolve("Program Files")).toString());
            env.put("LOCALAPPDATA", Files.createDirectories(tmp.resolve("AppData/Local")).toString());
        } else {
            env.put("HOME", home().toString());
            env.put("PATH", pathBin + ":/usr/bin:/bin");
            env.put("FTCSIM_ANDROID_STUDIO_JBR", tmp.resolve("no Android Studio").toString());
        }
        return env;
    }

    /** The JAVA_HOME run-sim would use, with {@code env} as its whole environment. */
    private String chosenJdk(Map<String, String> env) throws Exception {
        ProcessBuilder pb = WINDOWS ? new ProcessBuilder("cmd.exe", "/c", Path.of("run-sim.bat").toAbsolutePath().toString())
                : new ProcessBuilder("sh", Path.of("run-sim.sh").toAbsolutePath().toString());
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.environment().put("FTCSIM_PRINT_JAVA_HOME", "1");
        pb.directory(tmp.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        assertEquals(0, p.waitFor(), out);
        return out.substring(out.lastIndexOf('\n') + 1).trim();
    }

    @Test
    void runSimPicksTheNewestJdkByVersionNotByFolderName() throws Exception {
        Path jdks = Files.createDirectories(home().resolve(".jdks"));
        Path jdk11 = jdk(jdks.resolve("temurin-11.0.2"), "11.0.2");   // last by name
        Path jdk8 = jdk(jdks.resolve("jdk-8u432"), "1.8.0_432");
        jdk(jdks.resolve("jdk-17.0.9"), "17.0.9");
        Path jdk21 = jdk(jdks.resolve("corretto-21.0.5"), "21.0.5");
        // A javac on PATH too old to build the simulator.
        Map<String, String> env = env(pathJavac(jdk8.resolve("jre"), "1.8"));
        assertEquals(jdk21.toString(), chosenJdk(env));
        // A JAVA_HOME older than 17 isn't used either.
        env.put("JAVA_HOME", jdk11.toString());
        assertEquals(jdk21.toString(), chosenJdk(env));
    }

    @Test
    void theJavacOnPathIsUsedThroughItsOwnJdk() throws Exception {
        Path pathJdk = jdk(tmp.resolve("Program Files (x86)/Eclipse Adoptium/jdk-21.0.5"), "21.0.5");
        jdk(Files.createDirectories(home().resolve(".jdks")).resolve("jdk-25"), "25");
        Map<String, String> env = env(pathJavac(pathJdk, "21"));
        assertEquals(pathJdk.toString(), chosenJdk(env), "the JDK of the javac on PATH, before ~/.jdks");
        // A JAVA_HOME that is a JRE (no javac: the simulator couldn't build TeamCode) isn't kept.
        env.put("JAVA_HOME", jre(tmp.resolve("jre-21"), "21.0.5").toString());
        assertEquals(pathJdk.toString(), chosenJdk(env));
    }

    @Test
    void androidStudiosOwnJdkIsOneToo() throws Exception {
        // Android Studio puts neither its JDK on PATH nor JAVA_HOME.
        Path jbr;
        Map<String, String> env = env(pathJavac(null, "0"));
        if (WINDOWS) {
            jbr = tmp.resolve("Program Files/Android/Android Studio/jbr");
        } else {
            jbr = tmp.resolve("Android Studio (2025).app/Contents/jbr/Contents/Home");
            env.put("FTCSIM_ANDROID_STUDIO_JBR", tmp.resolve("nothing here") + ":" + jbr);
        }
        jdk(jbr, "21.0.8");
        assertEquals(jbr.toString(), chosenJdk(env), "the only JDK");
        Path jdks = Files.createDirectories(home().resolve(".jdks"));
        Path jdk17 = jdk(jdks.resolve("jdk-17.0.9"), "17.0.9");
        assertEquals(jbr.toString(), chosenJdk(env), "newer than ~/.jdks' JDK 17");
        // An older Android Studio's JDK 11 isn't one the simulator can be built with.
        jdk(jbr, "11.0.15");
        assertEquals(jdk17.toString(), chosenJdk(env));
    }

    /** The path after {@code prefix} on a line of {@code out}, as the file system has it. */
    private static Path pathAfter(String out, String prefix) throws Exception {
        for (String line : out.split("\\R")) {
            if (line.startsWith(prefix)) return Path.of(line.substring(prefix.length()).trim()).toRealPath();
        }
        throw new AssertionError("no \"" + prefix + "\" in:\n" + out);
    }

    @Test
    void runSimStartsTheSimulatorFromACopyNoBuildRewrites() throws Exception {
        // The simulator's folder, with a stand-in for Gradle that says what it was asked to do and
        // names the copy it made; the copy's start script says where it runs.
        Path sim = Files.createDirectories(tmp.resolve("ftc sim"));
        String launcher = WINDOWS ? "run-sim.bat" : "run-sim.sh";
        Files.copy(Path.of(launcher), sim.resolve(launcher));
        Path copyBin = Files.createDirectories(sim.resolve("build/run/0123abcd/bin"));
        if (WINDOWS) {
            Files.writeString(sim.resolve("gradlew.bat"), String.join("\r\n", "@echo off",
                    ">>\"%~dp0gradle.log\" echo %*", ">\"%~dp0build\\run\\current\" echo 0123abcd", "exit /b 0", ""));
            Files.writeString(copyBin.resolve("ftc-sim.bat"), String.join("\r\n", "@echo off",
                    "echo started %~dp0", "echo in %CD%", "echo args %*", ""));
        } else {
            Files.writeString(sim.resolve("gradlew"), "#!/bin/sh\necho \"$*\" >> gradle.log\nprintf 0123abcd > build/run/current\n");
            Files.writeString(copyBin.resolve("ftc-sim"), "#!/bin/sh\necho \"started $(cd \"$(dirname \"$0\")\" && pwd -P)\"\n"
                    + "echo \"in $(pwd -P)\"\necho \"args $*\"\n");
        }
        ProcessBuilder pb = WINDOWS ? new ProcessBuilder("cmd.exe", "/c", sim.resolve(launcher).toString(), "--no-browser", "--port", "8123")
                : new ProcessBuilder("sh", sim.resolve(launcher).toString(), "--no-browser", "--port", "8123");
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        pb.directory(Files.createDirectories(tmp.resolve("work")).toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        assertEquals(0, p.waitFor(), out);
        assertTrue(Files.readString(sim.resolve("gradle.log")).contains(":engine:prepareRun"), "builds and copies the simulator");
        assertEquals(copyBin.toRealPath(), pathAfter(out, "started "), "runs from the copy, not engine/build/install: " + out);
        assertEquals(sim.toRealPath(), pathAfter(out, "in "), "in the simulator's folder, where robots/ and sim.properties are");
        assertTrue(out.contains("args --no-browser --port 8123"), out);
    }

    /** A copy in {@code root} that run-sim last prepared {@code age} ago. */
    private static Path runCopy(Path root, String name, java.time.Duration age) throws Exception {
        Path copy = Files.createDirectories(root.resolve(name).resolve("lib"));
        Path launched = Files.writeString(copy.getParent().resolve("launched"), "");
        Files.setLastModifiedTime(launched, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(age)));
        return copy.getParent();
    }

    @Test
    void theCopiesNoSimulatorRunsFromAreDeleted() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("build/run"));
        java.time.Duration old = java.time.Duration.ofHours(2), justNow = java.time.Duration.ofSeconds(5);
        Path own = runCopy(root, "own", old);
        RunCopies.claim(root, own, ProcessHandle.current().pid());
        assertTrue(Files.exists(own.resolve("running/" + ProcessHandle.current().pid())), "this simulator marks its copy");
        Path elsewhere = runCopy(tmp, "elsewhere", old);
        RunCopies.claim(root, elsewhere, ProcessHandle.current().pid());
        assertTrue(Files.notExists(elsewhere.resolve("running")), "an install that isn't a copy is left alone");

        Path other = runCopy(root, "other", old);   // another simulator runs from it
        long parent = ProcessHandle.current().parent().orElseThrow().pid();
        RunCopies.claim(root, other, parent);
        Path exited = runCopy(root, "exited", old);
        Process gone = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version").start();
        gone.waitFor();
        RunCopies.claim(root, exited, gone.pid());
        Path unused = runCopy(root, "unused", old);
        Path starting = runCopy(root, "starting", justNow);   // run-sim is about to start it
        Path newest = runCopy(root, "newest", old);
        Files.writeString(root.resolve("current"), "newest");
        Path partialOld = Files.createDirectories(root.resolve("abc.partial-1-2"));
        Files.setLastModifiedTime(partialOld, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(old)));
        Path partialNew = Files.createDirectories(root.resolve("abc.partial-1-3"));

        RunCopies.clean(root, own, java.time.Instant.now());
        for (Path kept : new Path[]{own, other, starting, newest, partialNew}) assertTrue(Files.isDirectory(kept), kept + " is kept");
        for (Path deleted : new Path[]{exited, unused, partialOld}) assertTrue(Files.notExists(deleted), deleted + " is deleted");
    }

    @Test
    void aRelativeProjectIsWhereTheUserTypedIt() {
        // run-sim starts the simulator in its own folder and passes the folder it was started in.
        String caller = tmp.resolve("work").toString();
        assertEquals(tmp.resolve("work/FtcRobotController").toString(), Main.fromCaller("FtcRobotController", caller));
        assertEquals(tmp.resolve("FtcRobotController").toString(), Main.fromCaller("../FtcRobotController", caller));
        String absolute = tmp.resolve("elsewhere").toString();
        assertEquals(absolute, Main.fromCaller(absolute, caller));
        assertEquals("FtcRobotController", Main.fromCaller("FtcRobotController", null), "started some other way: as before");
    }
}
