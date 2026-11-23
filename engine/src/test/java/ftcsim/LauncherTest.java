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
