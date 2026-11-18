package ftcsim.server;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;

import ftcsim.cad.CadImport;
import ftcsim.cad.CadMeshFile;
import ftcsim.hal.CameraPreviews;
import ftcsim.sim.SimRobot;
import ftcsim.sim.Simulator;

/**
 * Serves the browser Driver Station (static files from /web on the classpath) and a WebSocket
 * at /ws that streams simulator state and receives gamepad and Driver Station commands.
 */
public final class WebServer {

    /** One connected browser. Each has its own sender thread; state frames are latest-only. */
    private static final class Client {
        final WsContext ctx;
        final ExecutorService out = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ws-send");
            t.setDaemon(true);
            return t;
        });
        final AtomicReference<String> pendingState = new AtomicReference<>();
        final AtomicReference<String> pendingDevices = new AtomicReference<>();

        Client(WsContext ctx) {
            this.ctx = ctx;
        }

        void send(String json) {
            out.execute(() -> sendNow(json));
        }

        void sendLatest(String json) {
            AtomicReference<String> slot = json.startsWith("{\"type\":\"devices\"") ? pendingDevices : pendingState;
            if (slot.getAndSet(json) == null) {
                out.execute(() -> {
                    String latest = slot.getAndSet(null);
                    if (latest != null) sendNow(latest);
                });
            }
        }

        private void sendNow(String json) {
            try {
                if (ctx.session.isOpen()) ctx.send(json);
            } catch (Exception ignored) {
                // the browser went away; onClose cleans up
            }
        }
    }

    private final Simulator sim;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private Javalin app;

    public WebServer(Simulator sim) {
        this.sim = sim;
        sim.setOutput(new Simulator.Output() {
            @Override
            public void broadcast(String json) {
                for (Client c : clients) c.send(json);
            }

            @Override
            public void broadcastState(String json) {
                for (Client c : clients) c.sendLatest(json);
            }
        });
    }

    public int start(int port) {
        app = Javalin.create(config -> {
            config.staticFiles.add("/web", Location.CLASSPATH);
            config.startup.showJavalinBanner = false;
            config.routes.get("/camera/{id}", WebServer::streamCamera);
            config.routes.get("/cad/{robot}/model", this::cadModel);
            config.routes.get("/cad/{robot}/source", this::cadSource);
            config.routes.post("/cad/{robot}/mesh", this::cadMesh);
            config.routes.post("/cad/{robot}/import", this::cadImport);
            config.routes.ws("/ws", ws -> {
                ws.onConnect(ctx -> {
                    ctx.enableAutomaticPings(5, TimeUnit.SECONDS);
                    Client c = new Client(ctx);
                    clients.add(c);
                    for (String m : sim.welcomeMessages()) c.send(m);
                });
                ws.onMessage(ctx -> sim.handle(ctx.message()));
                ws.onClose(ctx -> {
                    clients.removeIf(c -> c.ctx.sessionId().equals(ctx.sessionId()));
                    if (clients.isEmpty()) sim.onAllClientsDisconnected();
                });
                ws.onError(ctx -> clients.removeIf(c -> c.ctx.sessionId().equals(ctx.sessionId())));
            });
        });
        app.start("127.0.0.1", port);
        return app.port();
    }

    public void stop() {
        if (app != null) app.stop();
    }

    // ---- CAD models ----------------------------------------------------------------------------------

    private SimRobot robotOf(Context ctx) {
        try {
            int i = Integer.parseInt(ctx.pathParam("robot"));
            return i >= 0 && i < sim.robots().size() ? sim.robot(i) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A robot's CAD model for the 3D view (see ftcsim.cad.ModelExport). */
    private void cadModel(Context ctx) {
        SimRobot r = robotOf(ctx);
        byte[] model = r == null ? null : r.cadModelBytes();
        if (model == null) {
            ctx.status(404);
            return;
        }
        ctx.header("Cache-Control", "no-store");
        ctx.contentType("application/octet-stream");
        ctx.result(model);
    }

    /** The STEP file a page should convert (only while it needs converting). */
    private void cadSource(Context ctx) throws IOException {
        SimRobot r = robotOf(ctx);
        Path step = r == null ? null : r.cadSource();
        if (step == null) {
            ctx.status(404);
            return;
        }
        ctx.header("Cache-Control", "no-store");
        ctx.contentType("application/octet-stream");
        ctx.result(Files.newInputStream(step));   // streamed: a STEP file can be hundreds of MB
    }

    /**
     * The page's conversion of a STEP file. It must carry the X-Ftcsim-Upload header, which other
     * web sites can't send to this server without the browser asking it first (and it never agrees).
     */
    private void cadMesh(Context ctx) throws IOException {
        SimRobot r = robotOf(ctx);
        if (r == null || ctx.header("X-Ftcsim-Upload") == null) {
            ctx.status(r == null ? 404 : 403);
            return;
        }
        byte[] data;
        try (InputStream in = ctx.bodyInputStream()) {
            data = in.readNBytes((int) CadMeshFile.MAX_BYTES + 1);
        }
        if (data.length > CadMeshFile.MAX_BYTES) {
            ctx.status(413).result("the converted mesh is too big");
            return;
        }
        String problem = r.acceptCadMesh(data);
        if (problem != null) {
            ctx.status(422).result(problem);
            return;
        }
        ctx.status(204);
    }

    /**
     * A STEP file dropped on the page ({@code ?name=} its file name): becomes this robot, with a
     * robot file of its own. Needs X-Ftcsim-Upload, like {@link #cadMesh}.
     */
    private void cadImport(Context ctx) throws IOException {
        SimRobot r = robotOf(ctx);
        if (r == null || ctx.header("X-Ftcsim-Upload") == null) {
            ctx.status(r == null ? 404 : 403);
            return;
        }
        try (InputStream in = ctx.bodyInputStream()) {
            CadImport.Result res = r.importCad(ctx.queryParam("name"), in);
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("robotFile", res.robotFile().getFileName().toString());
            o.addProperty("step", "cad/" + res.step().getFileName());
            o.addProperty("updated", res.updated());
            ctx.contentType("application/json").result(o.toString());
        } catch (CadImport.TooBig e) {
            ctx.status(413).result(e.getMessage());
        } catch (IllegalStateException e) {
            ctx.status(409).result(e.getMessage());
        } catch (IllegalArgumentException e) {
            ctx.status(422).result(e.getMessage());
        }
    }

    /**
     * A VisionPortal's live view as an MJPEG stream (an {@code <img>} shows it), like the camera
     * preview on the robot. Frames are encoded only while someone watches, at most 30 a second.
     */
    private static void streamCamera(Context ctx) throws IOException {
        CameraPreviews.Preview preview;
        try {
            preview = CameraPreviews.byId(Integer.parseInt(ctx.pathParam("id")));
        } catch (NumberFormatException e) {
            preview = null;
        }
        if (preview == null) {
            ctx.status(404);
            return;
        }
        ctx.res().setContentType("multipart/x-mixed-replace; boundary=frame");
        ctx.res().setHeader("Cache-Control", "no-store");
        OutputStream out = ctx.res().getOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(0.85f);
        BufferedImage image = null;
        long sequence = 0, lastSent = 0;
        try {
            while (true) {
                CameraPreviews.Frame f;
                try {
                    f = preview.next(sequence, 1000);
                } catch (InterruptedException e) {
                    return;
                }
                if (f == null) {
                    if (CameraPreviews.byId(preview.id) == null) return;   // the portal closed
                    continue;
                }
                // At most one frame per 33 ms: wait out the rest of the gap, then send the newest
                // frame (so the last one before a pause is never dropped).
                long wait = lastSent + 33_000_000L - System.nanoTime();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                        CameraPreviews.Frame newer = preview.next(f.sequence, 0);
                        if (newer != null) f = newer;
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                sequence = f.sequence;
                lastSent = System.nanoTime();
                if (image == null || image.getWidth() != f.width || image.getHeight() != f.height) {
                    image = new BufferedImage(f.width, f.height, BufferedImage.TYPE_INT_RGB);
                }
                image.setRGB(0, 0, f.width, f.height, f.argb, 0, f.width);
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream(64 * 1024);
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(jpeg)) {
                    writer.setOutput(ios);
                    writer.write(null, new IIOImage(image, null, null), params);
                }
                out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpeg.size() + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                jpeg.writeTo(out);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (IOException e) {
            // the viewer went away
        } finally {
            writer.dispose();
        }
    }
}
