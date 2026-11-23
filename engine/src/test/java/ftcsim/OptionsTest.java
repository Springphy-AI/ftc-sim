package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The simulator's options: sim.properties, the command line, and what it says when they are wrong. */
class OptionsTest {

    @TempDir
    Path tmp;

    private static Main.Options parse(Map<String, String> props, String... args) throws Main.UsageError {
        return Main.parse(props, args, null);
    }

    @Test
    void simPropertiesIsReadAsWritten() throws Exception {
        Path file = tmp.resolve("sim.properties");
        Files.write(file, ("\uFEFF# Windows paths, typed the Windows way\r\n"
                + "project=C:\\Users\\me\\Ftc\r\n"
                + "robot = D:\\ftc\\users\\x.json\r\n"
                + "project2=\\team\\new\r\n"
                + "! another comment\r\n"
                + "port=8080   \r\n"
                + "open_browser : false\r\n"
                + "season decode\r\n"
                + "robot2=robots/tank.json\r\n").getBytes(StandardCharsets.UTF_8));
        Map<String, String> p = Main.readProperties(file);
        assertEquals("C:\\Users\\me\\Ftc", p.get("project"), "backslashes kept");
        assertEquals("D:\\ftc\\users\\x.json", p.get("robot"), "\\u is not an escape");
        assertEquals("\\team\\new", p.get("project2"), "\\t and \\n are not a tab and a new line");
        assertEquals("8080", p.get("port"), "trailing spaces don't count");
        assertEquals("false", p.get("open_browser"));
        assertEquals("decode", p.get("season"));
        assertEquals("robots/tank.json", p.get("robot2"), "forward slashes as before");
        assertEquals(7, p.size(), "comments are not settings: " + p);

        Main.Options o = parse(p);
        assertEquals(8080, o.port());
        assertFalse(o.openBrowser());
        assertEquals("C:\\Users\\me\\Ftc", o.project()[1]);
        assertEquals(2, o.count());

        assertEquals(Map.of(), Main.readProperties(tmp.resolve("none")), "no file, no settings");
    }

    @Test
    void aSimPropertiesFileInWindowsOwnEncodingIsReadToo() throws Exception {
        // An older Notepad saves in Windows' own encoding (here a Western one), not UTF-8.
        Path file = tmp.resolve("sim.properties");
        java.nio.charset.Charset windows1252 = java.nio.charset.Charset.forName("windows-1252");
        Files.write(file, "project=C:\\Users\\José\\Ftc\n".getBytes(windows1252));
        assertEquals("C:\\Users\\José\\Ftc", Main.readProperties(file, windows1252).get("project"));
    }

    @Test
    void anOptionWithoutItsValueIsSaidSo() {
        for (String option : List.of("--port", "--robots", "--project", "-p", "--robot", "-r", "--season", "-s", "--alliance",
                "--project2", "--robot3", "--alliance2")) {
            Main.UsageError e = assertThrows(Main.UsageError.class, () -> parse(Map.of(), option), option);
            assertEquals(option + " needs a value", e.getMessage());
            // Forgetting the value before another option is the same mistake.
            e = assertThrows(Main.UsageError.class, () -> parse(Map.of(), option, "--no-browser"), option);
            assertEquals(option + " needs a value", e.getMessage());
        }
    }

    @Test
    void aNumberThatIsntOneIsSaidSo() throws Exception {
        Main.UsageError e = assertThrows(Main.UsageError.class, () -> parse(Map.of(), "--port", "80a"));
        assertEquals("--port must be a whole number, not \"80a\"", e.getMessage());
        e = assertThrows(Main.UsageError.class, () -> parse(Map.of(), "--robots", "two"));
        assertEquals("--robots must be a whole number, not \"two\"", e.getMessage());
        e = assertThrows(Main.UsageError.class, () -> parse(Map.of("port", "eighty")));
        assertTrue(e.getMessage().startsWith("port= in sim.properties must be a whole number"), e.getMessage());
        e = assertThrows(Main.UsageError.class, () -> parse(Map.of(), "--port", "70000"));
        assertEquals("The port must be between 1 and 65535, not 70000", e.getMessage());
        e = assertThrows(Main.UsageError.class, () -> parse(Map.of(), "--robots", "5"));
        assertEquals("The number of robots must be between 1 and 4, not 5", e.getMessage());
        assertEquals(8090, parse(Map.of("port", "8090")).port());
        assertEquals(8091, parse(Map.of("port", "8090"), "--port", "8091").port(), "the command line wins");
    }

    @Test
    void theSimulatorExplainsAMistakeInsteadOfCrashing() throws Exception {
        // The real program, as run-sim starts it: exit code 2 and a sentence, no stack trace.
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "ftcsim.Main", "--no-browser", "--port")
                .directory(tmp.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(2, p.waitFor(), out);
        assertTrue(out.contains("--port needs a value"), out);
        assertFalse(out.contains("Exception"), out);
    }
}
