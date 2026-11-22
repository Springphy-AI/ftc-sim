package ftcsim.server;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;

import ftcsim.cad.CadImport;
import ftcsim.cad.CadMeshFile;
import ftcsim.deploy.RobotDeploy;
import ftcsim.hal.CameraPreviews;
import ftcsim.runner.TeamCodeFiles;
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
        /** The gamepads ("robot:slot") this page has sent input for. */
        final Set<String> fed = ConcurrentHashMap.newKeySet();

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
    private final RobotDeploy deployer = new RobotDeploy();
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
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
            // Every request must be addressed to this computer by name. A web site that points its
            // own name at 127.0.0.1 (DNS rebinding) is the same site as its page to the browser, which
            // then lets that page read what this server answers: robot files and their CAD, the
            // webcams' view, the page itself, and upload a robot. (The WebSocket checks when it connects.)
            config.routes.before(ctx -> {
                if (!isLocalHost(hostOf(ctx.header("Host")))) {
                    ctx.status(403).result("Only pages of this computer may use the simulator");
                    ctx.skipRemainingHandlers();
                }
            });
            config.routes.get("/camera/{id}", WebServer::streamCamera);
            config.routes.get("/cad/{robot}/model", this::cadModel);
            config.routes.get("/cad/{robot}/source", this::cadSource);
            config.routes.post("/cad/{robot}/mesh", this::cadMesh);
            config.routes.post("/cad/{robot}/import", this::cadImport);
            config.routes.get("/code/{robot}/files", this::codeFiles);
            config.routes.get("/code/{robot}/file", this::codeRead);
            config.routes.put("/code/{robot}/file", this::codeWrite);
            config.routes.get("/deploy/{robot}/devices", this::deployDevices);
            config.routes.post("/deploy/{robot}/connect", this::deployConnect);
            config.routes.post("/deploy/{robot}/start", this::deployStart);
            config.routes.post("/deploy/{robot}/cancel", this::deployCancel);
            config.routes.ws("/ws", ws -> {
                ws.onConnect(ctx -> {
                    // Browsers let any web site open a WebSocket to localhost: only the simulator's own
                    // page may watch the robots (and their builds, which quote TeamCode) or drive them.
                    if (!localPage(ctx.header("Origin"), ctx.header("Host"))) {
                        ctx.closeSession(1008, "Only the simulator's own page may connect");
                        return;
                    }
                    ctx.enableAutomaticPings(5, TimeUnit.SECONDS);
                    Client c = new Client(ctx);
                    clients.add(c);
                    for (String m : sim.welcomeMessages()) c.send(m);
                });
                ws.onMessage(ctx -> {
                    Client c = clients.stream().filter(x -> x.ctx.sessionId().equals(ctx.sessionId())).findFirst().orElse(null);
                    if (c == null) return;
                    String text = ctx.message();
                    if (text.startsWith("{\"type\":\"gamepad\"")) noteGamepad(c, text);
                    sim.handle(text);
                });
                // A page that loses its connection without closing it (a crashed tab, a laptop that
                // went to sleep, a dropped network) shows up as an error, not a close.
                ws.onClose(this::clientGone);
                ws.onError(this::clientGone);
            });
        });
        app.start("127.0.0.1", port);
        return app.port();
    }

    public void stop() {
        if (app != null) app.stop();
    }

    /** A page closed or lost its connection: when it was the last one, every OpMode stops, as when a robot loses its Driver Station. */
    private void clientGone(WsContext ctx) {
        Client gone = clients.stream().filter(c -> c.ctx.sessionId().equals(ctx.sessionId())).findFirst().orElse(null);
        if (gone == null || !clients.remove(gone)) return;
        if (clients.isEmpty()) {
            sim.onAllClientsDisconnected();
            return;
        }
        // Other pages are still open: let go of the gamepads this one drove, as a robot does when its
        // Driver Station's controller is unplugged. A page still feeding one sends it again at once.
        for (String slot : gone.fed) {
            String[] rs = slot.split(":");
            sim.handle("{\"type\":\"gamepad\",\"robot\":" + rs[0] + ",\"gamepad\":" + rs[1]
                    + ",\"axes\":[0,0,0,0,0,0],\"buttons\":[]}");
        }
    }

    /** Remembers which gamepad a page sends input for, to let go of it when the page goes away. */
    private static void noteGamepad(Client c, String text) {
        try {
            JsonObject m = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            int robot = m.has("robot") && !m.get("robot").isJsonNull() ? m.get("robot").getAsInt() : 0;
            c.fed.add(robot + ":" + m.get("gamepad").getAsInt());
        } catch (RuntimeException ignored) {
            // not a gamepad message after all; Simulator.handle ignores it too
        }
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

    // ---- the code editor: a robot's TeamCode files ------------------------------------------------

    /**
     * The code endpoints read and write the robot project, and what they write runs, so only the
     * simulator's own page may use them. The request must carry X-Ftcsim-Code, which a page on
     * another site can't send here without the browser asking first (and this server never agrees),
     * and must be addressed to this computer by name, which stops a site whose own name was
     * pointed at 127.0.0.1 from passing as the simulator's page.
     */
    static boolean fromOwnPage(Context ctx) {
        return ctx.header("X-Ftcsim-Code") != null && localPage(ctx.header("Origin"), ctx.header("Host"));
    }

    /**
     * A request addressed to this computer by name, from the simulator's own page (or from no page:
     * a program, which a web site can't make send it). Its page is of this computer and of the port
     * the request came to: a page another local server serves, on another port, is another site.
     */
    static boolean localPage(String origin, String host) {
        if (!isLocalHost(hostOf(host))) return false;
        if (origin == null) return true;
        try {
            java.net.URI o = java.net.URI.create(origin);
            int originPort = o.getPort() >= 0 ? o.getPort() : "https".equals(o.getScheme()) ? 443 : 80;
            return isLocalHost(o.getHost()) && originPort == portOf(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The port in a Host header (80 when it names none). */
    static int portOf(String host) {
        int bracket = host.lastIndexOf(']');
        int colon = host.lastIndexOf(':');
        if (colon <= bracket) return 80;
        try {
            return Integer.parseInt(host.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The host name in a Host header, without the port. */
    static String hostOf(String host) {
        if (host == null) return null;
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end < 0 ? host : host.substring(0, end + 1);
        }
        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }

    static boolean isLocalHost(String host) {
        return host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
    }

    /** The robot a code request is for, or null after answering 403/404. */
    private SimRobot codeRobot(Context ctx) {
        if (!fromOwnPage(ctx)) {
            ctx.status(403).result("Only the simulator's own page may read and change TeamCode.");
            return null;
        }
        SimRobot r = robotOf(ctx);
        if (r == null) ctx.status(404).result("No such robot");
        return r;
    }

    private static void refused(Context ctx, TeamCodeFiles.Refused e) {
        JsonObject o = new JsonObject();
        o.addProperty("error", e.getMessage());
        if (e.currentHash != null) o.addProperty("hash", e.currentHash);
        ctx.status(e.status).contentType("application/json").result(o.toString());
    }

    /** The robot's source folders and every Java file in them. */
    private void codeFiles(Context ctx) throws IOException {
        SimRobot r = codeRobot(ctx);
        if (r == null) return;
        TeamCodeFiles files = r.files();
        JsonObject o = new JsonObject();
        JsonArray roots = new JsonArray();
        for (TeamCodeFiles.Root root : files.roots()) {
            JsonObject j = new JsonObject();
            j.addProperty("index", root.index());
            j.addProperty("dir", root.dir().toString());
            j.addProperty("readOnly", root.readOnly());
            roots.add(j);
        }
        o.add("roots", roots);
        JsonArray list = new JsonArray();
        for (TeamCodeFiles.Entry e : files.list()) {
            JsonObject j = new JsonObject();
            j.addProperty("root", e.root());
            j.addProperty("path", e.path());
            list.add(j);
        }
        o.add("files", list);
        ctx.header("Cache-Control", "no-store");
        ctx.contentType("application/json").result(o.toString());
    }

    /** One file: {@code ?root=} its folder's index, {@code ?path=} its path in the folder. */
    private void codeRead(Context ctx) throws IOException {
        SimRobot r = codeRobot(ctx);
        if (r == null) return;
        try {
            TeamCodeFiles.Content c = r.files().read(intParam(ctx.queryParam("root")), ctx.queryParam("path"));
            JsonObject o = new JsonObject();
            o.addProperty("text", c.text());
            o.addProperty("hash", c.hash());
            o.addProperty("readOnly", c.readOnly());
            ctx.header("Cache-Control", "no-store");
            ctx.contentType("application/json").result(o.toString());
        } catch (TeamCodeFiles.Refused e) {
            refused(ctx, e);
        }
    }

    /**
     * Saves a file, {@code {"root", "path", "text", "base"}}: base is the hash the editor read (null
     * for a new file). Every robot running that file rebuilds; the answer has each one's build
     * request number, {@code {"hash", "builds": {"0": 7}}}.
     */
    private void codeWrite(Context ctx) throws IOException {
        SimRobot r = codeRobot(ctx);
        if (r == null) return;
        JsonObject body;
        try {
            body = JsonParser.parseString(ctx.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            ctx.status(400).result("Expected {\"root\", \"path\", \"text\", \"base\"}");
            return;
        }
        try {
            if (!body.has("text") || !body.get("text").isJsonPrimitive()) throw new TeamCodeFiles.Refused(400, "No text to save");
            String base = body.has("base") && !body.get("base").isJsonNull() ? body.get("base").getAsString() : null;
            int root = body.has("root") ? body.get("root").getAsInt() : -1;
            String path = body.has("path") ? body.get("path").getAsString() : null;
            TeamCodeFiles.Saved saved = r.files().write(root, path, body.get("text").getAsString(), base);
            JsonObject o = new JsonObject();
            o.addProperty("hash", saved.hash());
            JsonObject builds = new JsonObject();
            sim.sourceSaved(saved.file()).forEach((robot, ticket) -> builds.addProperty(String.valueOf(robot), ticket));
            o.add("builds", builds);
            ctx.contentType("application/json").result(o.toString());
        } catch (TeamCodeFiles.Refused e) {
            refused(ctx, e);
        } catch (RuntimeException e) {
            ctx.status(400).result("Expected {\"root\", \"path\", \"text\", \"base\"}");
        }
    }

    // ---- deploying a robot's project to the real robot ----------------------------------------------

    /** The project a robot runs, for deploying: from its TeamCode folder (the first writable one). */
    private static RobotDeploy.Tools deployTools(SimRobot r) {
        Path teamCode = r.files().roots().stream().filter(x -> !x.readOnly()).map(TeamCodeFiles.Root::dir).findFirst().orElse(null);
        return RobotDeploy.tools(teamCode);
    }

    /** @param adbSaid what adb answered to a connect, or null */
    private void sendDeployInfo(Context ctx, SimRobot r, String adbSaid) {
        RobotDeploy.Tools tools = deployTools(r);
        List<RobotDeploy.Device> devices = List.of();
        String deviceProblem = null;
        if (tools.adb() != null) {
            try {
                devices = RobotDeploy.devices(tools.adb());
            } catch (IOException e) {
                deviceProblem = "adb didn't answer: " + e.getMessage();
            }
        }
        Map<String, Object> info = RobotDeploy.describe(tools, devices, deviceProblem, deployer.busyRobot());
        if (adbSaid != null) info.put("adb", adbSaid);
        ctx.header("Cache-Control", "no-store");
        ctx.contentType("application/json").result(GSON.toJson(info));
    }

    /** The robot's project, the Android SDK, and the robots ADB sees. */
    private void deployDevices(Context ctx) {
        SimRobot r = codeRobot(ctx);
        if (r != null) sendDeployInfo(ctx, r, null);
    }

    /** {@code {"address"}}: ADB connects to a robot over Wi-Fi (a Control Hub by default); answers as /devices, plus what adb said. */
    private void deployConnect(Context ctx) {
        SimRobot r = codeRobot(ctx);
        if (r == null) return;
        RobotDeploy.Tools tools = deployTools(r);
        if (tools.adb() == null) {
            ctx.status(409).result(tools.problem() != null ? tools.problem() : "No adb found");
            return;
        }
        String address = RobotDeploy.CONTROL_HUB;
        try {
            JsonObject body = JsonParser.parseString(ctx.body()).getAsJsonObject();
            if (body.has("address") && !body.get("address").isJsonNull()) address = body.get("address").getAsString().trim();
        } catch (RuntimeException ignored) {
            // no body: the Control Hub
        }
        if (!address.matches("[A-Za-z0-9.\\-]+(:\\d{1,5})?")) {
            ctx.status(400).result("Not a robot address: " + address);
            return;
        }
        String said;
        try {
            said = RobotDeploy.connect(tools.adb(), address);
        } catch (IOException e) {
            said = e.getMessage();
        }
        sendDeployInfo(ctx, r, said.replaceAll("[\\r\\n]+", " ").trim());
    }

    /**
     * {@code {"serial"}}: builds the robot's project and installs it on that robot (null: only
     * builds it). Progress comes over the WebSocket as "deploy" messages.
     */
    private void deployStart(Context ctx) {
        SimRobot r = codeRobot(ctx);
        if (r == null) return;
        RobotDeploy.Tools tools = deployTools(r);
        if (tools.problem() != null) {
            ctx.status(409).result(tools.problem());
            return;
        }
        String serial = null;
        try {
            JsonObject body = JsonParser.parseString(ctx.body()).getAsJsonObject();
            if (body.has("serial") && !body.get("serial").isJsonNull()) serial = body.get("serial").getAsString();
        } catch (RuntimeException ignored) {
            // no body: build only
        }
        if (serial != null && !serial.matches("[A-Za-z0-9._:\\-]+")) {
            ctx.status(400).result("Not a device serial: " + serial);
            return;
        }
        boolean started = deployer.start(r.index, tools, serial, e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "deploy");
            m.put("robot", e.robot());
            m.put("state", e.state());
            if (e.message() != null) m.put("message", e.message());
            if (!e.lines().isEmpty()) m.put("lines", e.lines());
            String json = GSON.toJson(m);
            for (Client c : clients) c.send(json);
        });
        if (!started) {
            ctx.status(409).result("Robot " + (deployer.busyRobot() + 1) + " is being deployed; wait for it to finish.");
            return;
        }
        ctx.status(202);
    }

    private void deployCancel(Context ctx) {
        SimRobot r = codeRobot(ctx);
        if (r == null) return;
        if (deployer.busyRobot() == r.index) deployer.cancel();
        ctx.status(204);
    }

    private static int intParam(String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
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
