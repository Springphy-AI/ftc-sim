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
import ftcsim.hal.VisionNatives;
import ftcsim.physics.FieldModel;
import ftcsim.physics.PhysicsWorld;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.season.SeasonGame;
import ftcsim.vision.CameraWorld;

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
    /** The season's game pieces, in the same physics world as the robots. */
    final GameElements elements;
    /** What the robots' webcams see. */
    final CameraWorld cameras;
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
        this.cameras = new CameraWorld(field);
        VisionNatives.startLoading();   // OpenCV and the AprilTag detector, before any OpMode runs
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
        // Nothing that fails in here may end the loop: this one thread moves every robot and game
        // piece, so it would freeze the field for everyone while their OpModes kept running.
        while (running) {
            Runnable task;
            while ((task = physicsTasks.poll()) != null) {
                try {
                    task.run();
                } catch (Throwable e) {
                    loopFailed("A physics task (changing a robot, say)", e);
                }
            }
            long now = System.nanoTime();
            long due = (now - t0) / 1_000_000L;
            if (due - steps > 250) steps = due - 250;     // badly behind: let simulated time slip
            int n = 0;
            rigs.clear();
            for (SimRobot r : robots) rigs.add(r.rig());
            try {
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
            } catch (Throwable e) {
                loopFailed("The physics step", e);
            }
            if (n > 0) {
                try {
                    // Cameras see the field's moving parts (a HIVE that tipped...) where they are now.
                    cameras.update(rigs, elements, world.moverAngles());
                } catch (Throwable e) {
                    loopFailed("The webcams' view of the field", e);
                }
            }
            if (now - rtfWindowStart > 1_000_000_000L) {
                realTimeFactor = rtfSteps * DT / ((now - rtfWindowStart) / 1e9);
                rtfWindowStart = now;
                rtfSteps = 0;
            }
            if (now - lastPublish >= 20_000_000L) {
                lastPublish = now;
                try {
                    publishLogs();
                    publishState(now);
                } catch (Throwable e) {
                    loopFailed("The field state for the Driver Stations", e);
                }
                for (SimRobot r : robots) {
                    try {
                        r.checkAutoTimer(now);
                    } catch (Throwable e) {
                        loopFailed("The autonomous timer", e);
                    }
                }
            }
            if (now - lastDevices >= 100_000_000L) {
                lastDevices = now;
                try {
                    publishDevices();
                } catch (Throwable e) {
                    loopFailed("The device panel", e);
                }
            }
            LockSupport.parkNanos(400_000L);
        }
    }

    /**
     * Each failure seen on the physics thread (where, and the exception's class: the JVM drops the
     * message of an exception thrown over and over) → {when it was last logged, times since}, so a
     * failure that repeats every loop is logged every 5 s, not thousands of times a second.
     */
    private final Map<String, long[]> loopFailures = new HashMap<>();

    /** Something on the physics thread threw: say so in every Driver Station's log, and carry on. */
    private void loopFailed(String what, Throwable e) {
        String key = what + " " + e.getClass().getName();
        long now = System.nanoTime();
        long[] seen = loopFailures.get(key);
        if (seen != null && now - seen[0] < 5_000_000_000L) {
            seen[1]++;
            return;
        }
        String again = seen != null && seen[1] > 0
                ? " (" + seen[1] + " more time" + (seen[1] == 1 ? "" : "s") + " since it was last logged)" : "";
        loopFailures.put(key, new long[]{now, 0});
        RobotLog.ee("Simulator", e, what + " failed" + again + "; the simulator carries on");
    }

    private void watchSources() {
        while (running) {
            LockSupport.parkNanos(1_000_000_000L);
            for (SimRobot r : robots) {
                try {
                    r.checkSources();
                } catch (Throwable e) {
                    // This thread is what notices edits made outside the page: it must outlive any one failure.
                    RobotLog.ee("Simulator", e, "Looking for TeamCode changes failed; still watching");
                }
            }
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
            // The human player: one of the season's buttons (a game piece into the LOADING ZONE...).
            case "action" -> {
                String id = msg.has("id") ? msg.get("id").getAsString() : "";
                post(() -> elements.game().act(id));
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

    /**
     * The code editor saved a TeamCode file: every robot whose TeamCode it is rebuilds now (robots
     * running the same project folder share its files).
     *
     * @return robot index → the number of the build request that includes the save
     */
    public Map<Integer, Long> sourceSaved(Path file) {
        Map<Integer, Long> tickets = new LinkedHashMap<>();
        for (SimRobot r : robots) if (r.ownsSource(file)) tickets.put(r.index, r.rebuildAfterSave());
        return tickets;
    }

    /** Every robot power-cycled and back at its start, game pieces staged for a new MATCH. */
    private void resetField(SimRobot requester) {
        // Checked and done on the physics thread holding every robot's lock, so no INIT can come in
        // between and have the power cycle wipe the modes and PIDF its init() just set.
        post(() -> holdingEveryRobot(0, () -> {
            List<String> busy = robots.stream().filter(r -> !r.isIdle()).map(SimRobot::label).collect(Collectors.toList());
            if (!busy.isEmpty()) {
                requester.setError(robots.size() == 1 ? "Stop the OpMode before resetting the robot."
                        : "Stop every robot's OpMode before resetting the field (" + String.join(" and ", busy)
                        + (busy.size() == 1 ? " is" : " are") + " still running).");
                return;
            }
            for (SimRobot r : robots) r.resetOnPhysicsThread();
            elements.restage();
        }));
    }

    /** Runs {@code task} holding the locks of robots {@code from} on, taken in robot order. */
    private void holdingEveryRobot(int from, Runnable task) {
        if (from == robots.size()) task.run();
        else robots.get(from).holding(() -> holdingEveryRobot(from + 1, task));
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

    /** The season and its field, the robot files to choose from, the game pieces and every robot on the field. */
    public String helloJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "hello");
        m.put("season", field.season.describe());
        m.put("field", field.describe());
        List<Map<String, Object>> actions = new ArrayList<>();
        for (SeasonGame.Action a : elements.game().actions()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", a.id());
            o.put("label", a.label());
            if (a.key() != null) o.put("key", a.key());
            if (a.title() != null) o.put("title", a.title());
            actions.add(o);
        }
        m.put("actions", actions);
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

    /** RobotLog lines, before the state: they still go out when building the state is what fails. */
    private void publishLogs() {
        Map<String, Object> log;
        int n = 0;
        while (n++ < 40 && (log = logQueue.poll()) != null) broadcast(GSON.toJson(log));
    }

    private void publishState(long now) {
        List<Map<String, Object>> rs = new ArrayList<>();
        for (SimRobot r : robots) rs.add(r.state(now));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "state");
        m.put("t", simTime);
        m.put("rtf", realTimeFactor);
        m.put("robots", rs);
        m.put("balls", elements.positions());
        double[] angles = world.moverAngles();
        for (int i = 0; i < angles.length; i++) angles[i] = Math.round(angles[i] * 1e4) / 1e4;
        m.put("movers", angles);
        m.put("game", elements.game().status());
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

    public CameraWorld cameras() {
        return cameras;
    }

    public GameElements elements() {
        return elements;
    }

    /**
     * Runs a task on the physics thread and waits for it. What the task throws (a test's failed
     * assertion, say) is thrown here, to the caller.
     */
    public void onPhysicsThread(Runnable task) throws InterruptedException {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        physicsTasks.add(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                done.countDown();
            }
        });
        done.await();
        Throwable t = failure[0];   // written before countDown(), so visible after await()
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error er) throw er;
        if (t != null) throw new RuntimeException(t);
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
