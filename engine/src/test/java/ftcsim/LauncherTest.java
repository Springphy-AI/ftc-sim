package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** run-sim's choices before it builds and starts the simulator. */
class LauncherTest {

    @TempDir
    Path tmp;

    /** A JDK folder that says it is {@code version} (as its release file and its javac do). */
    private Path jdk(Path dir, String version) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Files.writeString(dir.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        Path javac = bin.resolve("javac");
        Files.writeString(javac, "#!/bin/sh\necho \"javac " + version + "\"\n");
        assertTrue(javac.toFile().setExecutable(true));
        return dir;
    }

    /** The JAVA_HOME run-sim.sh would use, with {@code env} as its whole environment. */
    private String chosenJdk(Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", Path.of("run-sim.sh").toAbsolutePath().toString());
        pb.environment().clear();
        pb.environment().putAll(env);
        pb.environment().put("FTCSIM_PRINT_JAVA_HOME", "1");
        pb.directory(tmp.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        assertEquals(0, p.waitFor(), out);
        return out.substring(out.lastIndexOf('\n') + 1);
    }

    @Test
    void runSimPicksTheNewestJdkByVersionNotByFolderName() throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"), "run-sim.sh is for macOS and Linux");
        Path jdks = Files.createDirectories(tmp.resolve("home/.jdks"));
        Path jdk11 = jdk(jdks.resolve("temurin-11.0.2"), "11.0.2");   // last by name
        jdk(jdks.resolve("jdk-8u432"), "1.8.0_432");
        jdk(jdks.resolve("jdk-17.0.9"), "17.0.9");
        Path jdk21 = jdk(jdks.resolve("corretto-21.0.5"), "21.0.5");
        // A javac on PATH too old to build the simulator.
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Files.writeString(bin.resolve("javac"), "#!/bin/sh\necho \"javac 1.8.0_432\" >&2\n");
        assertTrue(bin.resolve("javac").toFile().setExecutable(true));
        Map<String, String> env = Map.of("HOME", tmp.resolve("home").toString(), "PATH", bin + ":/usr/bin:/bin");
        assertEquals(jdk21.toString(), chosenJdk(env));
        // A JAVA_HOME older than 17 isn't used either.
        Map<String, String> oldHome = new java.util.HashMap<>(env);
        oldHome.put("JAVA_HOME", jdk11.toString());
        assertEquals(jdk21.toString(), chosenJdk(oldHome));
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
