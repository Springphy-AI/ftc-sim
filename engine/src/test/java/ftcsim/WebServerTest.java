package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftcsim.physics.FieldModel;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.server.WebServer;
import ftcsim.sim.Simulator;

/** The Driver Station page's server: who may use it, and what happens when pages go away. */
class WebServerTest {

    private Simulator sim;
    private WebServer server;
    private int port;

    @TempDir
    Path tmp;

    @BeforeEach
    void start() throws Exception {
        Path home = Path.of("").toAbsolutePath();
        // A robots folder of its own: a CAD upload that got through would add a robot file to it.
        Path robot = Files.copy(home.resolve("robots/mecanum.json"), Files.createDirectories(tmp.resolve("robots")).resolve("mecanum.json"));
        TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(new TeamCodeCompiler.SourceRoot(home.resolve("examples/teamcode"), false)),
                Files.createTempDirectory("ftcsim-test"));
        sim = new Simulator(FieldModel.loadBiobuzz(), robot, robot.getParent(), compiler, "test");
        server = new WebServer(sim);
        port = server.start(0);
        sim.start();
        long deadline = System.currentTimeMillis() + 60000;
        while ((sim.build() == null || sim.isBuilding()) && System.currentTimeMillis() < deadline) Thread.sleep(50);
    }

    @AfterEach
    void stop() {
        sim.shutdown();
        server.stop();
    }

    /** A WebSocket as the simulator's own page opens it. */
    private WebSocket openPage() throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", "http://localhost:" + port)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public java.util.concurrent.CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        ws.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
    }

    private void waitFor(Simulator.DsState state, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (sim.dsState() != state && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(state, sim.dsState(), "Driver Station state (error: " + sim.lastError() + ")");
    }

    private WebSocket runTeleOp() throws Exception {
        WebSocket page = openPage();
        page.sendText("{\"type\":\"init\",\"opmode\":\"BIOBUZZ TeleOp\"}", true).get(5, TimeUnit.SECONDS);
        waitFor(Simulator.DsState.INIT, 3000);
        page.sendText("{\"type\":\"start\"}", true).get(5, TimeUnit.SECONDS);
        waitFor(Simulator.DsState.RUNNING, 3000);
        return page;
    }

    @Test
    void whenTheLastPageClosesTheOpModeStops() throws Exception {
        WebSocket page = runTeleOp();
        page.sendClose(WebSocket.NORMAL_CLOSURE, "tab closed").get(5, TimeUnit.SECONDS);
        waitFor(Simulator.DsState.IDLE, 3000);
    }

    @Test
    void whenTheLastPageLosesItsConnectionTheOpModeStops() throws Exception {
        WebSocket page = runTeleOp();
        page.abort();   // a crashed tab or a laptop gone to sleep: the connection drops without a close
        waitFor(Simulator.DsState.IDLE, 3000);
    }

    /** The status of a request sent as written (HttpClient won't send another Host header). */
    private int rawStatus(String head) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            OutputStream out = s.getOutputStream();
            out.write((head + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String status = new String(s.getInputStream().readNBytes(12), StandardCharsets.US_ASCII);
            return Integer.parseInt(status.substring(9, 12));
        }
    }

    @Test
    void onlyRequestsAddressedToThisComputerAreAnswered() throws Exception {
        // A site that pointed its own name at 127.0.0.1 sends its name: refused everywhere, with or
        // without the header the page's own requests carry.
        String rebound = "Host: attacker.example:" + port + "\r\n";
        for (String path : List.of("/", "/index.html", "/cad/0/model", "/cad/0/source", "/camera/0", "/code/0/files")) {
            assertEquals(403, rawStatus("GET " + path + " HTTP/1.1\r\n" + rebound + "X-Ftcsim-Code: 1\r\n"), path);
        }
        assertEquals(403, rawStatus("POST /cad/0/import?name=Rebound.step HTTP/1.1\r\n" + rebound
                + "X-Ftcsim-Upload: 1\r\nContent-Length: 0\r\n"));
        assertEquals(403, rawStatus("POST /cad/0/mesh HTTP/1.1\r\n" + rebound + "X-Ftcsim-Upload: 1\r\nContent-Length: 0\r\n"));
        try (var files = Files.list(tmp.resolve("robots"))) {
            assertEquals(List.of("mecanum.json"), files.map(p -> p.getFileName().toString()).toList(), "no robot was added");
        }
        // This computer by name or address: the page loads.
        for (String host : List.of("localhost", "127.0.0.1", "[::1]")) {
            assertEquals(200, rawStatus("GET / HTTP/1.1\r\nHost: " + host + ":" + port + "\r\n"), host);
        }
    }

    /** How many messages a WebSocket opened by a page of {@code origin} gets in its first second. */
    private int messagesTo(String origin) throws Exception {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        WebSocket ws;
        try {
            ws = HttpClient.newHttpClient().newWebSocketBuilder().header("Origin", origin)
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                        @Override
                        public java.util.concurrent.CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                            if (last) n.incrementAndGet();
                            w.request(1);
                            return null;
                        }
                    }).get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            return 0;   // refused outright
        }
        Thread.sleep(1000);
        ws.abort();
        return n.get();
    }

    @Test
    void onlyTheSimulatorsOwnPageMayOpenTheWebSocket() throws Exception {
        assertTrue(messagesTo("http://localhost:" + port) > 0, "the simulator's own page");
        assertTrue(messagesTo("http://127.0.0.1:" + port) > 0, "the same page by address");
        // Another program's page on this computer (a development server on port 3000, say) is
        // another site: it may not watch or drive the robots.
        assertEquals(0, messagesTo("http://localhost:" + (port == 3000 ? 3001 : 3000)));
        assertEquals(0, messagesTo("http://localhost"));
    }

    @Test
    void theOpModeKeepsRunningWhileAnotherPageIsOpen() throws Exception {
        WebSocket other = openPage();
        WebSocket page = runTeleOp();
        page.abort();
        Thread.sleep(1000);
        assertEquals(Simulator.DsState.RUNNING, sim.dsState(), "the other page still drives the robot");
        other.abort();
        waitFor(Simulator.DsState.IDLE, 3000);
    }
}
