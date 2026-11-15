package ftcsim.sim;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.util.RobotLog;

import ftcsim.config.RobotConfig;
import ftcsim.hal.GamepadEffects;
import ftcsim.hal.SimLog;
import ftcsim.physics.FieldModel;
import ftcsim.physics.PhysicsWorld;
import ftcsim.runner.TeamCodeCompiler;

/**
 * The simulated field: physics at 1 kHz in real time for one or more robots and the game pieces,
 * routing of Driver Station messages to each robot ({@link SimRobot}), and the state stream for
 * the browser.
 */
public final class Simulator {

    /** Where outgoing messages go (the web server). */
    public interface Output {
        void broadcast(String json);

        void broadcastState(String json);
    }

    public enum DsState {IDLE, INIT, RUNNING, STOPPING}

    /**
     * One robot to put on the field.
     *
     * @param alliance "red" or "blue"; null alternates, starting with the field's home alliance
     */
    public record RobotSpec(Path robotFile, TeamCodeCompiler compiler, String projectLabel, String alliance) {
    }

    /** FTC: two robots per alliance. */
    public static final int MAX_ROBOTS = 4;

    static final double DT = 0.001;
    static final double AUTO_PERIOD_S = 30.0;
    static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();

    final FieldModel field;
    final Path robotsDir;
    final PhysicsWorld world;
    /** POLLEN and NECTAR, in the same physics world as the robots. */
    final GameElements elements;
    private final List<SimRobot> robots = new ArrayList<>();
    private volatile Output output = new Output() {
        @Override
        public void broadcast(String json) {
        }

        @Override
        public void broadcastState(String json) {
        }
    };

    // physics (physics thread only, except construction)
    private final ConcurrentLinkedQueue<Runnable> physicsTasks = new ConcurrentLinkedQueue<>();
    private volatile boolean running = false;
    private volatile double realTimeFactor = 1.0;
    private double simTime = 0;

    private final ConcurrentLinkedQueue<Map<String, Object>> logQueue = new ConcurrentLinkedQueue<>();

    /** A field with one robot. */
    public Simulator(FieldModel field, Path robotFile, Path robotsDir, TeamCodeCompiler compiler, String projectLabel) throws IOException {
        this(field, robotsDir, List.of(new RobotSpec(robotFile, compiler, projectLabel, null)));
    }

    /** A field with up to {@link #MAX_ROBOTS} robots, at most two per alliance. */
    public Simulator(FieldModel field, Path robotsDir, List<RobotSpec> specs) throws IOException {
        if (specs.isEmpty() || specs.size() > MAX_ROBOTS) {
            throw new IllegalArgumentException("Between 1 and " + MAX_ROBOTS + " robots, not " + specs.size());
        }
        this.field = field;
        this.robotsDir = robotsDir;
        this.world = new PhysicsWorld(field);
        String home = GameElements.homeAlliance(field);
        String other = "red".equals(home) ? "blue" : "red";
        Map<String, Integer> perAlliance = new HashMap<>();
        List<RobotRig> rigs = new ArrayList<>();
        List<String> alliances = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            RobotSpec s = specs.get(i);
            String alliance = s.alliance() == null ? (i % 2 == 0 ? home : other) : s.alliance().toLowerCase();
            if (!"red".equals(alliance) && !"blue".equals(alliance)) {
                throw new IllegalArgumentException("Robot " + (i + 1) + ": alliance must be red or blue, not \"" + s.alliance() + "\"");
            }
            int station = perAlliance.merge(alliance, 1, Integer::sum);
            if (station > 2) {
                throw new IllegalArgumentException("Robot " + (i + 1) + ": the " + alliance + " alliance already has two robots");
            }
            SimRobot r = new SimRobot(this, i, alliance, station, s.robotFile(), RobotConfig.load(s.robotFile()),
                    s.compiler(), s.projectLabel());
            robots.add(r);
            rigs.add(r.rig());
            alliances.add(alliance);
        }
        this.elements = new GameElements(field, rigs, alliances);
        installListeners();
    }

    public void setOutput(Output output) {
        this.output = output;
    }

    private void installListeners() {
        SimLog.setListener(new SimLog.Listener() {
            @Override
            public void onLog(int robot, char level, String tag, String message) {
                String who = robots.size() > 1 && robot >= 0 ? "[Robot " + (robot + 1) + "] " : "";
                System.out.println(who + level + "/" + tag + ": " + message);
                if (level == 'V' || level == 'D') return;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "log");
                m.put("robot", robot);
                m.put("level", String.valueOf(level));
                m.put("tag", tag);
                m.put("msg", message);
                if (logQueue.size() < 500) logQueue.add(m);
            }

            @Override
            public void onGlobalError(int robot, String message) {
                forRobot(robot, r -> r.onGlobalError(message));
            }

            @Override
            public void onGlobalWarning(int robot, String message) {
                forRobot(robot, r -> r.onGlobalWarning(message));
            }
        });
        GamepadEffects.setListener(new GamepadEffects.Listener() {
            @Override
            public void onRumble(Gamepad gamepad, int user, double[] strong, double[] weak, int[] durationsMs) {
                SimRobot r = ownerOf(gamepad);
                if (r == null) return;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "rumble");
                m.put("robot", r.index);
                m.put("gamepad", user);
                m.put("strong", strong);
                m.put("weak", weak);
                m.put("durations", durationsMs);
                broadcast(GSON.toJson(m));
            }

            @Override
            public void onLed(Gamepad gamepad, int user, double red, double green, double blue, int durationMs) {
                SimRobot r = ownerOf(gamepad);
                if (r == null) return;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "led");
                m.put("robot", r.index);
                m.put("gamepad", user);
                m.put("rgb", new double[]{red, green, blue});
                broadcast(GSON.toJson(m));
            }
        });
    }

    /** Runs {@code action} for robot {@code index}, or for every robot when the caller is outside any robot (-1). */
    private void forRobot(int index, Consumer<SimRobot> action) {
        if (index >= 0 && index < robots.size()) action.accept(robots.get(index));
        else if (index < 0) robots.forEach(action);
    }

    private SimRobot ownerOf(Gamepad gamepad) {
        for (SimRobot r : robots) if (r.gamepad1 == gamepad || r.gamepad2 == gamepad) return r;
        return null;
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    public void start() {
        running = true;
        for (SimRobot r : robots) r.checkSources();   // remembers the sources as they are now
        Thread physics = new Thread(this::physicsLoop, "physics");
        physics.setDaemon(true);
        physics.setPriority(Thread.MAX_PRIORITY);
        physics.start();
        Thread watcher = new Thread(this::watchSources, "source-watcher");
        watcher.setDaemon(true);
        watcher.start();
        for (SimRobot r : robots) r.requestBuild();
    }

    public void shutdown() {
        for (SimRobot r : robots) r.stopOpMode();
        running = false;
    }

    private void physicsLoop() {
        long t0 = System.nanoTime();
        long steps = 0;
        long lastPublish = 0, lastDevices = 0;
        long rtfWindowStart = t0;
        long rtfSteps = 0;
        List<RobotRig> rigs = new ArrayList<>();
        while (running) {
            Runnable task;
            while ((task = physicsTasks.poll()) != null) {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    RobotLog.ee("Simulator", e, "physics task failed");
                }
            }
            long now = System.nanoTime();
            long due = (now - t0) / 1_000_000L;
            if (due - steps > 250) steps = due - 250;     // badly behind: let simulated time slip
            int n = 0;
            rigs.clear();
            for (SimRobot r : robots) rigs.add(r.rig());
            while (steps < due && n < 100) {
                elements.beforeStep(DT);
                for (RobotRig rig : rigs) rig.beforeWorldStep(DT);
                world.step(DT);
                for (RobotRig rig : rigs) rig.afterWorldStep(DT);
                simTime += DT;
                steps++;
                n++;
                rtfSteps++;
            }
            if (now - rtfWindowStart > 1_000_000_000L) {
                realTimeFactor = rtfSteps * DT / ((now - rtfWindowStart) / 1e9);
                rtfWindowStart = now;
                rtfSteps = 0;
            }
            if (now - lastPublish >= 20_000_000L) {
                lastPublish = now;
                publishState(now);
                for (SimRobot r : robots) r.checkAutoTimer(now);
            }
            if (now - lastDevices >= 100_000_000L) {
                lastDevices = now;
                publishDevices();
            }
            LockSupport.parkNanos(400_000L);
        }
    }

    private void watchSources() {
        while (running) {
            LockSupport.parkNanos(1_000_000_000L);
            for (SimRobot r : robots) r.checkSources();
        }
    }

    /** Runs a task on the physics thread, without waiting. */
    void post(Runnable task) {
        physicsTasks.add(task);
    }

    void broadcast(String json) {
        output.broadcast(json);
    }

    // ---- driver station commands ------------------------------------------------------------------

    /**
     * Handles one message from a browser Driver Station. {@code "robot"} is the robot it is for
     * (0-based, default 0).
     */
    public void handle(String text) {
        JsonObject msg;
        try {
            msg = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        String type = msg.has("type") ? msg.get("type").getAsString() : "";
        switch (type) {
            // Every robot at once, like drivers pressing their buttons when the match starts.
            case "initAll" -> {
                for (SimRobot r : robots) r.initSelected();
                return;
            }
            case "startAll" -> {
                for (SimRobot r : robots) r.start();
                return;
            }
            case "stopAll" -> {
                for (SimRobot r : robots) r.stopOpMode();
                return;
            }
            default -> {
            }
        }
        int index = 0;
        try {
            if (msg.has("robot") && !msg.get("robot").isJsonNull()) index = msg.get("robot").getAsInt();
        } catch (RuntimeException e) {
            return;
        }
        if (index < 0 || index >= robots.size()) return;
        if ("reset".equals(type)) resetField(robots.get(index));
        else robots.get(index).handle(type, msg);
    }

    /** All Driver Stations disconnected: like losing the DS on a real robot, every OpMode stops. */
    public void onAllClientsDisconnected() {
        for (SimRobot r : robots) r.releaseGamepadsAndStop();
    }

    /** Every robot power-cycled and back at its start, game pieces staged for a new MATCH. */
    private void resetField(SimRobot requester) {
        List<String> busy = robots.stream().filter(r -> !r.isIdle()).map(SimRobot::label).collect(Collectors.toList());
        if (!busy.isEmpty()) {
            requester.setError(robots.size() == 1 ? "Stop the OpMode before resetting the robot."
                    : "Stop every robot's OpMode before resetting the field (" + String.join(" and ", busy)
                    + (busy.size() == 1 ? " is" : " are") + " still running).");
            return;
        }
        post(() -> {
            for (SimRobot r : robots) r.resetOnPhysicsThread();
            elements.restage();
        });
    }

    static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(" - ").append(t.getMessage());
        for (StackTraceElement e : t.getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("ftcsim.") || c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("com.qualcomm.")
                    || c.startsWith("org.firstinspires.ftc.robotcore.")) continue;
            sb.append(" (at ").append(e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1))
                    .append('.').append(e.getMethodName()).append(':').append(e.getLineNumber()).append(')');
            break;
        }
        return sb.toString();
    }

    // ---- outgoing messages ----------------------------------------------------------------------

    /** The field, the robot files to choose from, the game pieces and every robot on the field. */
    public String helloJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "hello");
        m.put("field", field.json);
        m.put("robotFiles", robotFiles());
        m.put("balls", elements.describe());
        m.put("robots", robots.stream().map(SimRobot::describe).collect(Collectors.toList()));
        return GSON.toJson(m);
    }

    /** Everything a newly connected browser needs, in order. */
    public List<String> welcomeMessages() {
        List<String> out = new ArrayList<>();
        out.add(helloJson());
        for (SimRobot r : robots) {
            out.add(r.dsJson());     // before the OpMode list, so the page knows which OpMode is selected
            out.add(r.buildJson());
        }
        return out;
    }

    List<String> robotFiles() {
        try (Stream<Path> s = Files.list(robotsDir)) {
            return s.filter(p -> p.toString().endsWith(".json")).map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The first robot's build status. */
    public String buildJson() {
        return robots.get(0).buildJson();
    }

    /** The first robot's Driver Station status. */
    public String dsJson() {
        return robots.get(0).dsJson();
    }

    private void publishState(long now) {
        List<Map<String, Object>> rs = new ArrayList<>();
        for (SimRobot r : robots) rs.add(r.state(now));
        Map<String, Object> log;
        int n = 0;
        while (n++ < 40 && (log = logQueue.poll()) != null) broadcast(GSON.toJson(log));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "state");
        m.put("t", simTime);
        m.put("rtf", realTimeFactor);
        m.put("robots", rs);
        m.put("balls", elements.positions());
        output.broadcastState(GSON.toJson(m));
    }

    private void publishDevices() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "devices");
        m.put("robots", robots.stream().map(SimRobot::devices).collect(Collectors.toList()));
        output.broadcastState(GSON.toJson(m));
    }

    // ---- read-only views (tests and tools) ---------------------------------------------------------

    public List<SimRobot> robots() {
        return robots;
    }

    public SimRobot robot(int index) {
        return robots.get(index);
    }

    /** The first robot's Driver Station state. */
    public DsState dsState() {
        return robots.get(0).dsState();
    }

    public String lastError() {
        return robots.get(0).lastError();
    }

    public TeamCodeCompiler.Result build() {
        return robots.get(0).build();
    }

    public boolean isBuilding() {
        return robots.get(0).isBuilding();
    }

    /** The first robot's hardware. */
    public RobotRig rig() {
        return robots.get(0).rig();
    }

    public GameElements elements() {
        return elements;
    }

    /** Runs a task on the physics thread and waits for it. */
    public void onPhysicsThread(Runnable task) throws InterruptedException {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        physicsTasks.add(() -> {
            try {
                task.run();
            } finally {
                done.countDown();
            }
        });
        done.await();
    }

    static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
