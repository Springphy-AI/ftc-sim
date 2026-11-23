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
                tmp.resolve("build"));
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
    void thePagesGamepadIsLetGoWhenItLeavesWhileOthersStay() throws Exception {
        WebSocket overview = openPage();   // a page that drives nothing (on a projector, say)
        WebSocket driver = openPage();
        driver.sendText("{\"type\":\"gamepad\",\"robot\":0,\"gamepad\":1,\"kind\":\"XBOX_360\",\"axes\":[0,-1,0,0,0,0],\"buttons\":[]}", true)
                .get(5, TimeUnit.SECONDS);
        long deadline = System.currentTimeMillis() + 2000;
        while (sim.robot(0).gamepad1.left_stick_y != -1 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(-1, sim.robot(0).gamepad1.left_stick_y, "the driver holds the stick forward");
        driver.abort();   // the driver's tab crashes, stick held
        deadline = System.currentTimeMillis() + 2000;
        while (sim.robot(0).gamepad1.left_stick_y != 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(0, sim.robot(0).gamepad1.left_stick_y, "its gamepad is let go, though another page is still open");
        overview.abort();
    }

    private static String gamepad1(boolean held) {
        String buttons = "false,".repeat(17) + "false";
        return "{\"type\":\"gamepad\",\"robot\":0,\"gamepad\":1,\"kind\":\"XBOX_360\",\"axes\":[0," + (held ? "-1" : "0")
                + ",0,0,0,0],\"buttons\":[" + (held ? "true" + buttons.substring(5) : buttons) + "]}";
    }

    @Test
    void anotherPagesNothingPressedDoesNotLetGoOfTheDriversGamepad() throws Exception {
        // The driver holds the stick forward and A, and their page repeats that every 250 ms. Another
        // page (an old tab, an overview window) keeps saying "nothing pressed", 125 ms after each.
        WebSocket driver = openPage();
        WebSocket other = openPage();
        com.qualcomm.robotcore.hardware.Gamepad gp = sim.robot(0).gamepad1;
        java.util.concurrent.ScheduledExecutorService pages = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            pages.scheduleAtFixedRate(() -> driver.sendText(gamepad1(true), true).join(), 0, 250, TimeUnit.MILLISECONDS);
            long deadline = System.currentTimeMillis() + 2000;
            while (gp.left_stick_y != -1 && System.currentTimeMillis() < deadline) Thread.sleep(2);
            assertEquals(-1, gp.left_stick_y, "the driver holds the stick forward");
            pages.scheduleAtFixedRate(() -> other.sendText(gamepad1(false), true).join(), 125, 250, TimeUnit.MILLISECONDS);
            int presses = 0, letGo = 0;
            long end = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < end) {
                if (gp.aWasPressed()) presses++;
                if (gp.left_stick_y != -1) letGo++;
                Thread.sleep(2);
            }
            assertEquals(0, letGo, "readings of the stick let go while the driver held it");
            assertEquals(1, presses, "aWasPressed for one press of A");
        } finally {
            pages.shutdownNow();
            pages.awaitTermination(2, TimeUnit.SECONDS);
        }
        // The driver letting go lets go at once.
        driver.sendText(gamepad1(false), true).get(5, TimeUnit.SECONDS);
        long deadline = System.currentTimeMillis() + 2000;
        while (gp.left_stick_y != 0 && System.currentTimeMillis() < deadline) Thread.sleep(2);
        assertEquals(0, gp.left_stick_y, "the driver's own release");
        driver.abort();
        other.abort();
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

    /** A port nothing listens on, at any address, nor on the next 9. */
    private static int freePorts() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            int p;
            try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
                p = s.getLocalPort();
            }
            if (p + 10 > 65535) continue;
            boolean free = true;
            for (int q = p; q < p + 10 && free; q++) {
                try (java.net.ServerSocket s = new java.net.ServerSocket()) {
                    s.bind(new java.net.InetSocketAddress(q));
                } catch (java.io.IOException e) {
                    free = false;
                }
            }
            if (free) return p;
        }
        throw new IllegalStateException("no free ports");
    }

    @Test
    void aPortAnotherProgramHasAtLocalhostIsPassedOver() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket()) {
            s.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("::1"), 0));
        } catch (java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "no IPv6 here");
        }
        // The page is opened at localhost, which a browser may try at [::1] first. A program there,
        // or on every address (Node's listen(port), Docker), would get the page's requests.
        for (String address : List.of("::", "::1")) {
            int p = freePorts();
            try (java.net.ServerSocket other = new java.net.ServerSocket()) {
                other.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName(address), p));
                WebServer second = new WebServer(sim);
                try {
                    int chosen = second.start(p, 10);
                    assertTrue(chosen > p && chosen < p + 10, "another program has [" + address + "]:" + p + ", the simulator took " + chosen);
                } finally {
                    second.stop();
                }
            }
        }
        // Free: the simulator takes it, and its page loads at either address, as localhost may be either.
        int p = freePorts();
        WebServer third = new WebServer(sim);
        try {
            assertEquals(p, third.start(p, 10));
            for (String url : List.of("http://127.0.0.1:" + p + "/", "http://[::1]:" + p + "/", "http://localhost:" + p + "/")) {
                var response = HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(URI.create(url)).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), url);
                assertTrue(response.body().contains("FTC Simulator"), url);
            }
        } finally {
            third.stop();
        }
    }
}
