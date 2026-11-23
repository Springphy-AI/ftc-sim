package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ftcsim.hal.CameraPreviews;
import ftcsim.physics.FieldModel;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.server.WebServer;
import ftcsim.sim.Simulator;

/** The Driver Station page shows a running VisionPortal's live view as an MJPEG stream. */
class CameraStreamTest {

    @Test
    void liveViewIsServedAsMjpeg(@TempDir Path work) throws Exception {
        VisionAssumptions.assumeDetectorAvailable();
        Path home = Path.of("").toAbsolutePath();
        TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(
                new TeamCodeCompiler.SourceRoot(home.resolve("examples/samples"), true)), work);
        Path robot = home.resolve("robots/mecanum.json");
        Simulator sim = new Simulator(FieldModel.loadBiobuzz(), robot, robot.getParent(), compiler, "test");
        WebServer server = new WebServer(sim);
        int port = server.start(0);
        sim.start();
        try {
            long deadline = System.currentTimeMillis() + 60000;
            while ((sim.build() == null || sim.isBuilding()) && System.currentTimeMillis() < deadline) Thread.sleep(50);
            sim.handle("{\"type\":\"place\",\"x\":-0.25,\"y\":-1.15,\"h\":90}");
            sim.handle("{\"type\":\"init\",\"opmode\":\"Concept: AprilTag\"}");
            deadline = System.currentTimeMillis() + 15000;
            while (CameraPreviews.of(0).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
            assertEquals(1, CameraPreviews.of(0).size(), "the sample's portal publishes a live view during INIT");
            int id = CameraPreviews.of(0).get(0).id;

            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/camera/" + id)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, res.statusCode());
            assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("multipart/x-mixed-replace"));
            try (InputStream in = res.body()) {
                for (int frame = 0; frame < 2; frame++) {
                    String header = readHeader(in);
                    assertTrue(header.contains("Content-Type: image/jpeg"), header);
                    int length = Integer.parseInt(header.replaceAll("(?s).*Content-Length: (\\d+).*", "$1"));
                    byte[] jpeg = in.readNBytes(length);
                    assertEquals(length, jpeg.length);
                    assertEquals((byte) 0xFF, jpeg[0], "JPEG start-of-image");
                    assertEquals((byte) 0xD8, jpeg[1], "JPEG start-of-image");
                }
            }
            HttpResponse<String> missing = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/camera/999999")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());
            sim.handle("{\"type\":\"stop\"}");
        } finally {
            sim.shutdown();
            server.stop();
        }
    }

    /** Reads up to the blank line that ends a multipart part's headers. */
    private static String readHeader(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            sb.append((char) b);
            if (sb.length() >= 4 && sb.substring(sb.length() - 4).equals("\r\n\r\n") && sb.indexOf("Content-Length") >= 0) break;
        }
        return new String(sb.toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.US_ASCII);
    }
}
