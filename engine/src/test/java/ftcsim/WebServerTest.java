package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ftcsim.physics.FieldModel;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.server.WebServer;
import ftcsim.sim.Simulator;

/** The Driver Station page's server: who may use it, and what happens when pages go away. */
class WebServerTest {

    private Simulator sim;
    private WebServer server;
    private int port;

    @BeforeEach
    void start() throws Exception {
        Path home = Path.of("").toAbsolutePath();
        Path robot = home.resolve("robots/mecanum.json");
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
