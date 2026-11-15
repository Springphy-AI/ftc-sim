package ftcsim.server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;

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
}
