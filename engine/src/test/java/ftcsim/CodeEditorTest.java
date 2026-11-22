package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ftcsim.physics.FieldModel;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.runner.TeamCodeFiles;
import ftcsim.server.WebServer;
import ftcsim.sim.SimRobot;
import ftcsim.sim.Simulator;

/** The Driver Station page's code editor: reading and saving TeamCode, and the compile errors it shows. */
class CodeEditorTest {

    private static final String PKG = "org/firstinspires/ftc/teamcode/";

    private static final String OPMODE = String.join("\r\n",
            "package org.firstinspires.ftc.teamcode;",
            "",
            "import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;",
            "import com.qualcomm.robotcore.eventloop.opmode.TeleOp;",
            "",
            "@TeleOp(name = \"Editor Test\")",
            "public class EditorTest extends LinearOpMode {",
            "    @Override",
            "    public void runOpMode() {",
            "        waitForStart();",
            "    }",
            "}",
            "");

    @TempDir
    Path tmp;

    private TeamCodeFiles files(Path teamCode, Path samples) {
        return new TeamCodeFiles(List.of(new TeamCodeCompiler.SourceRoot(teamCode, false),
                new TeamCodeCompiler.SourceRoot(samples, true)));
    }

    @Test
    void aNewFileIsNeverWrittenThroughALinkToNothing() throws Exception {
        Path teamCode = Files.createDirectories(tmp.resolve("TeamCode/src/main/java"));
        Path outside = Files.createDirectories(tmp.resolve("outside")).resolve("escaped.txt");
        try {
            Files.createSymbolicLink(teamCode.resolve("Escape.java"), outside);   // its target doesn't exist
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "no symbolic links here: " + e);
        }
        TeamCodeFiles f = files(teamCode, Files.createDirectories(tmp.resolve("samples")));
        TeamCodeFiles.Refused e = assertThrows(TeamCodeFiles.Refused.class, () -> f.write(0, "Escape.java", "class Escape {}\n", null));
        assertEquals(403, e.status);
        assertFalse(Files.exists(outside), "nothing was written outside the source folder");
    }

    @Test
    void aTeamsCopyOfASampleThatKeepsItsClassNameIsTheTeamsOwn() throws Exception {
        // FIRST's instructions: copy a sample into TeamCode and remove @Disabled. Many teams keep its name.
        Path teamCode = Files.createDirectories(tmp.resolve("TeamCode/src/main/java").resolve(PKG));
        Files.writeString(teamCode.resolve("BasicOpMode_Linear.java"), String.join("\n",
                "package org.firstinspires.ftc.teamcode;",
                "import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;",
                "import com.qualcomm.robotcore.eventloop.opmode.TeleOp;",
                "@TeleOp(name = \"Our Linear OpMode\")",
                "public class BasicOpMode_Linear extends LinearOpMode {",
                "    @Override public void runOpMode() { waitForStart(); }",
                "}", ""));
        TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(
                new TeamCodeCompiler.SourceRoot(tmp.resolve("TeamCode/src/main/java"), false),
                new TeamCodeCompiler.SourceRoot(Path.of("examples/samples").toAbsolutePath(), true)), Files.createDirectories(tmp.resolve("out")));
        TeamCodeCompiler.Result r = compiler.compile();
        assertTrue(r.message().startsWith("Built"), r.message());
        var ours = r.opModes().stream().filter(o -> o.name().equals("Our Linear OpMode")).findFirst().orElseThrow();
        assertFalse(ours.sample(), "the team's copy is listed as the team's OpMode, not hidden with the samples");
        var firsts = r.opModes().stream().filter(o -> o.name().equals("Basic: Linear OpMode")).findFirst().orElseThrow();
        assertTrue(firsts.sample(), "FIRST's sample is still a sample");
    }

    @Test
    void sourcesAreReadWhileAnEditorWritesAndDeletesTemporaryFiles() throws Exception {
        Path teamCode = Files.createDirectories(tmp.resolve("TeamCode/src/main/java"));
        Path pkg = Files.createDirectories(teamCode.resolve(PKG));
        Files.writeString(pkg.resolve("EditorTest.java"), OPMODE);
        TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(new TeamCodeCompiler.SourceRoot(teamCode, false)),
                Files.createDirectories(tmp.resolve("out")));
        TeamCodeFiles files = files(teamCode, Files.createDirectories(tmp.resolve("samples")));
        // Android Studio's "safe write": a temporary file next to the real one, written and gone again.
        java.util.concurrent.atomic.AtomicBoolean editing = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread editor = new Thread(() -> {
            for (int i = 0; editing.get(); i++) {
                try {
                    Path t = pkg.resolve("EditorTest.java___jb_tmp___" + (i % 20));
                    Files.writeString(t, "x");
                    Files.deleteIfExists(t);
                } catch (java.io.IOException ignored) {
                }
            }
        });
        editor.start();
        try {
            long end = System.currentTimeMillis() + 2000;
            int compiles = 0;
            while (System.currentTimeMillis() < end) {
                compiler.fingerprint();   // threw UncheckedIOException once a file vanished mid-walk, ending the source watcher
                assertTrue(files.list().contains(new TeamCodeFiles.Entry(0, PKG + "EditorTest.java")));
                if (compiles++ < 3) assertTrue(compiler.compile().message().startsWith("Built"), "builds while the editor writes");
            }
        } finally {
            editing.set(false);
            editor.join();
        }
    }

    @Test
    void savesKeepTheFilesLineEndingsAndRefuseStaleOrOutsideWrites() throws Exception {
        Path teamCode = Files.createDirectories(tmp.resolve("TeamCode/src/main/java"));
        Path samples = Files.createDirectories(tmp.resolve("samples"));
        Files.createDirectories(teamCode.resolve(PKG));
        Files.writeString(teamCode.resolve(PKG + "EditorTest.java"), OPMODE);
        Files.writeString(samples.resolve("Sample.java"), "class Sample {}\n");
        Files.writeString(tmp.resolve("Secret.java"), "class Secret {}\n");
        TeamCodeFiles f = files(teamCode, samples);

        assertEquals(List.of(new TeamCodeFiles.Entry(0, PKG + "EditorTest.java"), new TeamCodeFiles.Entry(1, "Sample.java")), f.list());
        TeamCodeFiles.Content c = f.read(0, PKG + "EditorTest.java");
        assertEquals(OPMODE, c.text());
        assertFalse(c.readOnly());

        // The editor sends "\n"; the file keeps its "\r\n".
        String edited = OPMODE.replace("\r\n", "\n").replace("waitForStart();", "waitForStart();\n        telemetry.update();");
        TeamCodeFiles.Saved saved = f.write(0, PKG + "EditorTest.java", edited, c.hash());
        String onDisk = Files.readString(teamCode.resolve(PKG + "EditorTest.java"));
        assertEquals(edited.replace("\n", "\r\n"), onDisk);
        assertEquals(TeamCodeFiles.hash(onDisk.getBytes(StandardCharsets.UTF_8)), saved.hash());

        // Saved from another editor since it was opened: refused, with the hash on disk now.
        TeamCodeFiles.Refused stale = assertThrows(TeamCodeFiles.Refused.class, () -> f.write(0, PKG + "EditorTest.java", "x", c.hash()));
        assertEquals(409, stale.status);
        assertEquals(saved.hash(), stale.currentHash);

        // New files: Java names only, never over an existing file; folders are made.
        f.write(0, PKG + "auto/NewAuto.java", "package org.firstinspires.ftc.teamcode.auto;\nclass NewAuto {}\n", null);
        assertTrue(Files.readString(teamCode.resolve(PKG + "auto/NewAuto.java")).contains("\r\nclass NewAuto"),
                "a new file uses its neighbours' line endings");
        assertEquals(409, assertThrows(TeamCodeFiles.Refused.class, () -> f.write(0, PKG + "EditorTest.java", "x", null)).status);
        assertEquals(400, assertThrows(TeamCodeFiles.Refused.class, () -> f.write(0, PKG + "my-op.java", "x", null)).status);

        // Nothing outside the source folders, nothing but .java, and the samples are read-only.
        assertEquals(400, assertThrows(TeamCodeFiles.Refused.class, () -> f.read(0, "../../../../Secret.java")).status);
        assertEquals(400, assertThrows(TeamCodeFiles.Refused.class, () -> f.write(0, "../../../../Secret.java", "x", null)).status);
        assertEquals(400, assertThrows(TeamCodeFiles.Refused.class, () -> f.read(0, tmp.resolve("Secret.java").toString())).status);
        assertEquals(400, assertThrows(TeamCodeFiles.Refused.class, () -> f.write(0, PKG + "notes.txt", "x", null)).status);
        assertEquals(403, assertThrows(TeamCodeFiles.Refused.class, () -> f.write(1, "Sample.java", "x", f.read(1, "Sample.java").hash())).status);
        assertTrue(f.read(1, "Sample.java").readOnly());
        assertEquals("class Secret {}\n", Files.readString(tmp.resolve("Secret.java")));
    }

    @Test
    void compileErrorsSayWhereTheyAre() throws Exception {
        Path teamCode = Files.createDirectories(tmp.resolve("TeamCode/src/main/java").resolve(PKG));
        // line 10 is "        waitForStart()" without its semicolon; CRLF line breaks don't count as columns
        Files.writeString(teamCode.resolve("EditorTest.java"), OPMODE.replace("waitForStart();", "waitForStart()\r\n        int x = \"no\";"));
        TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(new TeamCodeCompiler.SourceRoot(tmp.resolve("TeamCode/src/main/java"), false)),
                Files.createDirectories(tmp.resolve("out")));
        TeamCodeCompiler.Result r = compiler.compile();
        assertTrue(r.opModes().isEmpty());
        assertFalse(r.problems().isEmpty());
        TeamCodeCompiler.Problem p = r.problems().get(0);
        assertEquals(0, p.root());
        assertEquals(PKG + "EditorTest.java", p.file());
        assertEquals(10, p.line(), p.toString());
        assertEquals("        waitForStart()".length(), p.column(), p.toString());
        assertTrue(p.message().contains("';' expected"), p.message());

        // A file that imports the simulator's internals: marked on its import, not the blank line above.
        Files.writeString(teamCode.resolve("EditorTest.java"), OPMODE.replace("import com.qualcomm.robotcore.eventloop.opmode.TeleOp;",
                "import com.qualcomm.robotcore.eventloop.opmode.TeleOp;\r\n\r\n    import ftcsim.hal.SimLog;"));
        TeamCodeCompiler.Problem internals = compiler.compile().problems().get(0);
        assertEquals(6, internals.line(), internals.toString());
        assertEquals(4, internals.column(), internals.toString());
        assertTrue(internals.message().contains("simulator internals"), internals.message());
    }

    @Test
    void thePageSavesRebuildsAndSeesTheErrors() throws Exception {
        Path teamCode = Files.createDirectories(tmp.resolve("TeamCode/src/main/java"));
        Files.createDirectories(teamCode.resolve(PKG));
        Files.writeString(teamCode.resolve(PKG + "EditorTest.java"), OPMODE);
        Path home = Path.of("").toAbsolutePath();
        Path robot = home.resolve("robots/mecanum.json");
        Simulator sim = new Simulator(FieldModel.loadBiobuzz(), robot.getParent(), List.of(
                new Simulator.RobotSpec(robot, new TeamCodeCompiler(List.of(new TeamCodeCompiler.SourceRoot(teamCode, false)),
                        Files.createDirectories(tmp.resolve("out1"))), "test", null),
                new Simulator.RobotSpec(robot, new TeamCodeCompiler(List.of(new TeamCodeCompiler.SourceRoot(teamCode, false)),
                        Files.createDirectories(tmp.resolve("out2"))), "test", null)));
        WebServer server = new WebServer(sim);
        int port = server.start(0);
        sim.start();
        try {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://localhost:" + port;

            // Without the header (what another site's page could send) or by another host name: refused.
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + "/code/0/files")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, rawStatus(port, "GET /code/0/files HTTP/1.1\r\nHost: attacker.example:" + port + "\r\nX-Ftcsim-Code: 1\r\n"));
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + "/code/0/files")).header("X-Ftcsim-Code", "1")
                    .header("Origin", "http://attacker.example").build(), HttpResponse.BodyHandlers.ofString()).statusCode());

            // The WebSocket (robot state, builds with their errors, deploy logs): another site's page
            // is turned away; the simulator's own page is welcome.
            assertEquals(0, webSocketMessages(http, port, "http://attacker.example"));
            assertTrue(webSocketMessages(http, port, "http://localhost:" + port) > 0);

            HttpResponse<String> list = http.send(HttpRequest.newBuilder(URI.create(base + "/code/0/files")).header("X-Ftcsim-Code", "1").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, list.statusCode(), list.body());
            assertTrue(list.body().contains(PKG + "EditorTest.java"), list.body());

            HttpResponse<String> read = http.send(HttpRequest.newBuilder(URI.create(base + "/code/0/file?root=0&path=" + PKG + "EditorTest.java"))
                    .header("X-Ftcsim-Code", "1").build(), HttpResponse.BodyHandlers.ofString());
            JsonObject file = JsonParser.parseString(read.body()).getAsJsonObject();
            String hash = file.get("hash").getAsString();

            // Save a mistake: both robots run this folder, so both rebuild, and both see the error.
            JsonObject save = new JsonObject();
            save.addProperty("root", 0);
            save.addProperty("path", PKG + "EditorTest.java");
            save.addProperty("text", file.get("text").getAsString().replace("waitForStart();", "waitForStart()"));
            save.addProperty("base", hash);
            JsonObject saved = put(http, base, save);
            JsonObject builds = saved.getAsJsonObject("builds");
            assertEquals(2, builds.size(), saved.toString());
            for (SimRobot r : sim.robots()) {
                waitForBuild(r, builds.get(String.valueOf(r.index)).getAsLong());
                assertEquals(1, r.build().problems().size(), r.build().problems().toString());
                assertTrue(r.build().opModes().isEmpty());
            }

            // Fix it: the OpMode is back.
            save.addProperty("text", OPMODE);
            save.addProperty("base", saved.get("hash").getAsString());
            JsonObject fixed = put(http, base, save);
            waitForBuild(sim.robot(0), fixed.getAsJsonObject("builds").get("0").getAsLong());
            assertTrue(sim.robot(0).build().problems().isEmpty());
            assertEquals("Editor Test", sim.robot(0).build().opModes().get(0).name());

            // A save based on an old version (the one with the mistake) is refused.
            save.addProperty("base", saved.get("hash").getAsString());
            HttpResponse<String> conflict = http.send(HttpRequest.newBuilder(URI.create(base + "/code/0/file")).header("X-Ftcsim-Code", "1")
                    .PUT(HttpRequest.BodyPublishers.ofString(save.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, conflict.statusCode(), conflict.body());
            assertEquals(fixed.get("hash").getAsString(), JsonParser.parseString(conflict.body()).getAsJsonObject().get("hash").getAsString());
            assertNotEquals(saved.get("hash").getAsString(), fixed.get("hash").getAsString());
        } finally {
            sim.shutdown();
            server.stop();
        }
    }

    private static JsonObject put(HttpClient http, String base, JsonObject body) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(base + "/code/0/file")).header("X-Ftcsim-Code", "1")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    /** Waits for the robot's build that includes build request {@code ticket}. */
    private static void waitForBuild(SimRobot r, long ticket) throws Exception {
        long deadline = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < deadline) {
            JsonObject b = JsonParser.parseString(buildJson(r)).getAsJsonObject();
            if (!b.get("building").getAsBoolean() && b.get("built").getAsLong() >= ticket) return;
            Thread.sleep(50);
        }
        throw new AssertionError("no build for request " + ticket);
    }

    private static String buildJson(SimRobot r) throws Exception {
        var m = SimRobot.class.getDeclaredMethod("buildJson");
        m.setAccessible(true);
        return (String) m.invoke(r);
    }

    /** How many messages a WebSocket opened from a page of {@code origin} gets in its first second. */
    private static int webSocketMessages(HttpClient http, int port, String origin) throws Exception {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        java.net.http.WebSocket.Listener listener = new java.net.http.WebSocket.Listener() {
            @Override
            public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket ws, CharSequence data, boolean last) {
                if (last) n.incrementAndGet();
                ws.request(1);
                return null;
            }
        };
        java.net.http.WebSocket ws;
        try {
            ws = http.newWebSocketBuilder().header("Origin", origin).buildAsync(URI.create("ws://localhost:" + port + "/ws"), listener)
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            return 0;   // refused outright
        }
        Thread.sleep(1000);
        ws.abort();
        return n.get();
    }

    /** The status of a request sent as written (HttpClient won't send another Host header). */
    private static int rawStatus(int port, String head) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            OutputStream out = s.getOutputStream();
            out.write((head + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            String status = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
            return Integer.parseInt(status.substring(9, 12));
        }
    }
}
