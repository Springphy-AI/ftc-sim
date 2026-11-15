package ftcsim.sim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import org.ode4j.math.DQuaternionC;
import org.ode4j.math.DVector3C;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.util.RobotLog;

import ftcsim.config.RobotConfig;
import ftcsim.hal.GamepadAccess;
import ftcsim.hal.HalSession;
import ftcsim.hal.MotorPort;
import ftcsim.hal.OpModeSession;
import ftcsim.hal.RobotContext;
import ftcsim.hal.ServoPort;
import ftcsim.hal.SimHub;
import ftcsim.hal.SimTelemetry;
import ftcsim.physics.Mechanism;
import ftcsim.physics.RobotBody;
import ftcsim.physics.ServoUnit;
import ftcsim.runner.OpModeInfo;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.sim.Simulator.DsState;

/**
 * One simulated robot on the field: its Robot Controller (the hardware rig, its own TeamCode build
 * and the OpMode lifecycle) and its Driver Station (gamepads, telemetry, errors). Robots share the
 * field, the physics world and the game pieces, and nothing else: each has its own TeamCode class
 * loader, so even two robots running the same project don't share static fields.
 */
public final class SimRobot {

    /** 0-based; shown to people as "Robot 1", "Robot 2", ... */
    public final int index;
    /** "red" or "blue". */
    public final String alliance;
    /** 1 or 2: which of the alliance's two starting spots it uses. */
    public final int station;
    public final Gamepad gamepad1 = new Gamepad();
    public final Gamepad gamepad2 = new Gamepad();
    /** This robot's share of the SDK's static state (blackboard, global messages, bus latency). */
    final RobotContext context;

    private final Simulator sim;
    private final TeamCodeCompiler compiler;
    private final String projectLabel;

    // physics: replaced on the physics thread only
    private volatile RobotRig rig;
    private volatile Path robotFile;

    // driver station
    private final Object lock = new Object();
    private DsState state = DsState.IDLE;
    private OpModeSession session;
    private HalSession hal;
    private SimTelemetry telemetry;
    private OpModeInfo active;
    private String selected;
    private long initNanos, startNanos;
    private boolean autoTimerEnabled = true;
    private String lastError;
    /** A robot file that has the device the last error was missing; offered as a one-click switch. */
    private String fixRobot;
    private String globalWarning = "";

    // builds
    private final ExecutorService buildExecutor;
    private volatile TeamCodeCompiler.Result build;
    private volatile boolean building = false;
    private volatile boolean buildPending = false;
    private String sourceFingerprint;

    SimRobot(Simulator sim, int index, String alliance, int station, Path robotFile, RobotConfig config,
             TeamCodeCompiler compiler, String projectLabel) {
        this.sim = sim;
        this.index = index;
        this.alliance = alliance;
        this.station = station;
        this.robotFile = robotFile;
        this.compiler = compiler;
        this.projectLabel = projectLabel;
        this.context = new RobotContext(index);
        applyIo(config);
        this.rig = new RobotRig(config, sim.world, startPose(config));
        GamepadAccess.get().setUser(gamepad1, 1);
        GamepadAccess.get().setUser(gamepad2, 2);
        this.buildExecutor = Executors.newSingleThreadExecutor(r -> Simulator.daemon(r, "teamcode-build-" + (index + 1)));
    }

    /**
     * Where this robot starts. The robot file's {@code start} is the first spot of the home
     * alliance; the second spot mirrors it across the field's center line (or sits beside it when
     * it is on that line), and the other alliance's spots are the same rotated half a turn, as
     * the field is.
     */
    double[] startPose(RobotConfig cfg) {
        double x = cfg.start[0], y = cfg.start[1], h = cfg.start[2];
        if (station == 2) y = Math.abs(y) >= 0.3 ? -y : y + 0.65;
        if (!alliance.equals(GameElements.homeAlliance(sim.field))) {
            x = -x;
            y = -y;
            h += 180;
        }
        h = ((h + 180) % 360 + 360) % 360 - 180;
        return new double[]{x, y, h};
    }

    private void applyIo(RobotConfig config) {
        context.commandNanos = Math.round(config.io.commandMs * 1e6);
        context.i2cNanos = Math.round(config.io.i2cMs * 1e6);
    }

    String label() {
        return "Robot " + (index + 1);
    }

    // ---- builds ----------------------------------------------------------------------------------

    void requestBuild() {
        synchronized (lock) {
            if (state != DsState.IDLE) {
                buildPending = true;
                broadcastBuild();
                return;
            }
            buildPending = false;
        }
        building = true;
        broadcastBuild();
        buildExecutor.submit(() -> {
            try {
                TeamCodeCompiler.Result r = compiler.compile();
                build = r;
                // One println, so robots building at the same time don't interleave their reports.
                String who = sim.robots().size() > 1 ? label() + " " : "";
                StringBuilder report = new StringBuilder(who).append("TeamCode: ").append(r.message());
                for (TeamCodeCompiler.Skipped s : r.skipped()) {
                    report.append(System.lineSeparator()).append("  ").append(who).append("skipped ").append(s.file()).append(" - ").append(s.reason());
                }
                System.out.println(report);
                synchronized (lock) {
                    if (selected == null || r.opModes().stream().noneMatch(o -> o.name().equals(selected))) {
                        selected = r.opModes().stream().filter(o -> !o.disabled()).map(OpModeInfo::name).findFirst().orElse(null);
                    }
                }
            } catch (Throwable t) {
                build = new TeamCodeCompiler.Result(false, "Build crashed: " + t, null, List.of(), List.of(), 0, 0);
            } finally {
                building = false;
                broadcastBuild();
                broadcastDs();
            }
        });
    }

    /** Called about once a second by the source watcher; rebuilds when TeamCode changed. */
    void checkSources() {
        String now = compiler.fingerprint();
        if (sourceFingerprint == null) {
            sourceFingerprint = now;
            return;
        }
        if (now.equals(sourceFingerprint)) return;
        LockSupport.parkNanos(400_000_000L);    // let the editor finish writing
        sourceFingerprint = compiler.fingerprint();
        log("TeamCode changed; rebuilding");
        requestBuild();
    }

    // ---- driver station commands ------------------------------------------------------------------

    /** One message from a browser Driver Station, addressed to this robot. */
    void handle(String type, JsonObject msg) {
        switch (type) {
            case "gamepad" -> applyGamepad(msg);
            case "select" -> select(msg.get("opmode").getAsString());
            case "init" -> initOpMode(msg.has("opmode") ? msg.get("opmode").getAsString() : selected());
            case "start" -> startOpMode();
            case "stop" -> stopOpMode();
            case "build" -> requestBuild();
            case "timer" -> {
                synchronized (lock) {
                    autoTimerEnabled = msg.get("enabled").getAsBoolean();
                }
                broadcastDs();
            }
            case "place" -> {
                double x = msg.get("x").getAsDouble(), y = msg.get("y").getAsDouble(), h = Math.toRadians(msg.get("h").getAsDouble());
                double max = sim.field.half - 0.23;
                sim.post(() -> rig.place(clamp(x, max), clamp(y, max), h));
            }
            case "robot" -> switchRobot(msg.get("file").getAsString());
            case "clearError" -> {
                synchronized (lock) {
                    lastError = null;
                    fixRobot = null;
                }
                context.run(RobotLog::clearGlobalErrorMsg);
                broadcastDs();
            }
            default -> {
            }
        }
    }

    private static double clamp(double v, double max) {
        return Math.max(-max, Math.min(max, v));
    }

    private void applyGamepad(JsonObject msg) {
        int slot = msg.get("gamepad").getAsInt();
        Gamepad gp = slot == 2 ? gamepad2 : gamepad1;
        JsonArray axes = msg.getAsJsonArray("axes");
        JsonArray buttons = msg.getAsJsonArray("buttons");
        boolean[] b = new boolean[18];
        for (int i = 0; i < b.length && i < buttons.size(); i++) b[i] = buttons.get(i).getAsBoolean();
        Gamepad.Type t;
        try {
            t = Gamepad.Type.valueOf(msg.has("kind") ? msg.get("kind").getAsString() : "UNKNOWN");
        } catch (IllegalArgumentException e) {
            t = Gamepad.Type.UNKNOWN;
        }
        GamepadAccess.get().update(gp, t, axes.get(0).getAsFloat(), axes.get(1).getAsFloat(),
                axes.get(2).getAsFloat(), axes.get(3).getAsFloat(), axes.get(4).getAsFloat(), axes.get(5).getAsFloat(), b);
    }

    /** The Driver Station went away: sticks centered, buttons up, OpMode stopped. */
    void releaseGamepadsAndStop() {
        boolean[] none = new boolean[18];
        GamepadAccess.get().update(gamepad1, gamepad1.type, 0, 0, 0, 0, 0, 0, none);
        GamepadAccess.get().update(gamepad2, gamepad2.type, 0, 0, 0, 0, 0, 0, none);
        stopOpMode();
    }

    /** INIT the selected OpMode, if the robot is stopped and has one selected. */
    void initSelected() {
        initOpMode(selected());
    }

    /** START, if the robot is in INIT. */
    void start() {
        startOpMode();
    }

    private String selected() {
        synchronized (lock) {
            return selected;
        }
    }

    private void select(String name) {
        synchronized (lock) {
            if (state == DsState.IDLE) selected = name;
        }
        broadcastDs();
    }

    private void initOpMode(String name) {
        synchronized (lock) {
            if (state != DsState.IDLE || name == null) return;
            TeamCodeCompiler.Result b = build;
            OpModeInfo info = b == null ? null : b.opModes().stream().filter(o -> o.name().equals(name)).findFirst().orElse(null);
            if (info == null) {
                lastError = "OpMode \"" + name + "\" is not available. Check the build output.";
                broadcastDsLocked();
                return;
            }
            OpMode opMode;
            try {
                opMode = (OpMode) info.type().getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                lastError = "Could not create " + info.className() + ": " + Simulator.describe(cause);
                context.run(() -> RobotLog.ee("Simulator", cause, "OpMode construction failed"));
                broadcastDsLocked();
                return;
            }
            RobotRig r = rig;
            r.resetForOpMode();
            hal = new HalSession();
            HardwareMap hw = r.createHardwareMap(hal);
            telemetry = new SimTelemetry();
            gamepad1.resetEdgeDetection();
            gamepad2.resetEdgeDetection();
            active = info;
            selected = info.name();
            lastError = null;
            fixRobot = null;
            context.run(RobotLog::clearGlobalErrorMsg);
            state = DsState.INIT;
            initNanos = System.nanoTime();
            final OpModeSession s = new OpModeSession(context, info.name(), opMode, hw, gamepad1, gamepad2, telemetry, sessionListener);
            session = s;
            context.run(() -> RobotLog.i(RobotLog.OPMODE_START_TAG));
            log("INIT " + info.name() + " (" + info.className() + ")");
            s.init();
            broadcastDsLocked();
        }
    }

    private void startOpMode() {
        synchronized (lock) {
            if (state != DsState.INIT || session == null) return;
            state = DsState.RUNNING;
            startNanos = System.nanoTime();
            log("START " + active.name());
            session.start();
            broadcastDsLocked();
        }
    }

    void stopOpMode() {
        final OpModeSession s;
        synchronized (lock) {
            if ((state != DsState.INIT && state != DsState.RUNNING) || session == null) return;
            state = DsState.STOPPING;
            s = session;
            log("STOP " + active.name());
            broadcastDsLocked();
        }
        Thread t = new Thread(() -> {
            boolean ended = s.stop(1000);
            if (!ended) {
                setError("The OpMode \"" + s.getName() + "\" did not stop within a second (stuck in a loop that ignores "
                        + "isStopRequested()/opModeIsActive()?). On a robot the app would restart; here its thread "
                        + "is abandoned and disconnected from the hardware.");
            }
            finishSession(s);
        }, "opmode-stopper");
        t.setDaemon(true);
        t.start();
    }

    private final OpModeSession.Listener sessionListener = new OpModeSession.Listener() {
        @Override
        public void onStateChanged(OpModeSession s, OpModeSession.State st) {
            if (st == OpModeSession.State.STOPPED) finishSession(s);
        }

        @Override
        public void onStopRequestedByOpMode(OpModeSession s) {
            Thread t = new Thread(SimRobot.this::stopOpMode, "opmode-stopper");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void onError(OpModeSession s, Throwable error) {
            String msg = "User code threw an uncaught exception: " + Simulator.describe(error);
            RobotLog.ee("Simulator", error, msg);
            DeviceHint hint = missingDeviceHint(error);
            if (hint != null) System.err.println(hint.text());
            synchronized (lock) {
                lastError = hint == null ? msg : msg + "\n\n" + hint.text();
                fixRobot = hint == null ? null : hint.robot();
                broadcastDsLocked();
            }
        }
    };

    /** Advice for a missing hardware device; robot is a robot file that has it, or null. */
    record DeviceHint(String text, String robot) {
    }

    private static final java.util.regex.Pattern MISSING_DEVICE =
            java.util.regex.Pattern.compile("Unable to find a hardware device with name \"(.*)\" and type");

    /**
     * Simulator-only advice after the Driver Station's own message, when code asks for a device the
     * simulated robot doesn't have: which robot file does have it, or where to add it.
     */
    DeviceHint missingDeviceHint(Throwable error) {
        if (!(error instanceof IllegalArgumentException) || error.getMessage() == null) return null;
        java.util.regex.Matcher m = MISSING_DEVICE.matcher(error.getMessage());
        if (!m.find()) return null;
        String device = m.group(1);
        String current = robotFile.getFileName().toString();
        List<String> others = new ArrayList<>();
        for (String f : sim.robotFiles()) {
            if (f.equals(current)) continue;
            try {
                if (RobotConfig.load(sim.robotsDir.resolve(f)).deviceNames().contains(device)) others.add(f);
            } catch (Exception ignored) {
                // a broken robot file is reported when it is selected
            }
        }
        String who = sim.robots().size() > 1 ? label() + " (" + current + ")" : "the simulated robot (" + current + ")";
        String start = "Simulator: " + who + " has no device named \"" + device + "\"";
        if (!others.isEmpty()) {
            return new DeviceHint(start + ", but " + String.join(" and ", others) + (others.size() == 1 ? " does" : " do")
                    + ". Switch to it with the button below or the Robot menu in the top bar (not the OpMode list),"
                    + " then INIT again.", others.get(0));
        }
        return new DeviceHint(start + ". Add it to robots/" + current
                + " with the same name as in your Driver Station configuration.", null);
    }

    private void finishSession(OpModeSession s) {
        boolean rebuild;
        synchronized (lock) {
            if (s != session) return;
            if (hal != null) hal.close();
            rig.failsafe();
            SimTelemetry.Frame f = telemetry == null ? null : telemetry.flush();
            if (f != null) sim.broadcast(telemetryJson(f));
            String preselect = active != null && active.kind() == OpModeInfo.Kind.AUTONOMOUS ? active.preselectTeleOp() : "";
            if (preselect != null && !preselect.isEmpty() && build != null
                    && build.opModes().stream().anyMatch(o -> o.name().equals(preselect))) {
                selected = preselect;
            }
            context.run(() -> RobotLog.i(RobotLog.OPMODE_STOP_TAG));
            session = null;
            hal = null;
            active = null;
            state = DsState.IDLE;
            rebuild = buildPending;
            broadcastDsLocked();
        }
        if (rebuild) requestBuild();
    }

    void checkAutoTimer(long now) {
        boolean stop;
        synchronized (lock) {
            stop = state == DsState.RUNNING && active != null && active.kind() == OpModeInfo.Kind.AUTONOMOUS
                    && autoTimerEnabled && (now - startNanos) / 1e9 >= Simulator.AUTO_PERIOD_S;
        }
        if (stop) stopOpMode();
    }

    /** Power-cycles the robot and puts it back where it starts. Physics thread only. */
    void resetOnPhysicsThread() {
        RobotRig r = rig;
        r.powerCycle();
        r.place(r.start[0], r.start[1], Math.toRadians(r.start[2]));
    }

    private void switchRobot(String fileName) {
        Path file = sim.robotsDir.resolve(fileName).normalize();
        if (!isIdle() || !file.startsWith(sim.robotsDir.normalize()) || !Files.isRegularFile(file)) {
            sim.broadcast(sim.helloJson());   // puts the page's Robot menu back on the robot in use
            if (!isIdle()) setError("Stop the OpMode before changing the robot.");
            return;
        }
        sim.post(() -> {
            try {
                RobotConfig cfg = RobotConfig.load(file);
                RobotRig previous = rig;
                RobotRig next = new RobotRig(cfg, sim.world, startPose(cfg));
                previous.destroy();
                sim.elements.replaceRobot(index, next);
                applyIo(cfg);
                rig = next;
                robotFile = file;
                sim.broadcast(sim.helloJson());
                synchronized (lock) {
                    if (fixRobot != null) {   // the error was about the previous robot's devices
                        lastError = null;
                        fixRobot = null;
                    }
                    broadcastDsLocked();
                }
            } catch (Exception e) {
                sim.broadcast(sim.helloJson());
                setError("Could not load robot " + fileName + ": " + e.getMessage());
            }
        });
    }

    void setError(String message) {
        synchronized (lock) {
            lastError = message;
            fixRobot = null;
            broadcastDsLocked();
        }
    }

    /** RobotLog.setGlobalErrorMsg / addGlobalWarningMessage from this robot's code. */
    void onGlobalError(String message) {
        if (message != null && !message.isEmpty()) setError(message);
    }

    void onGlobalWarning(String message) {
        synchronized (lock) {
            globalWarning = message == null ? "" : message;
        }
        broadcastDs();
    }

    /** A line in this robot's log, as if the Robot Controller app wrote it. */
    private void log(String message) {
        context.run(() -> RobotLog.ii("Simulator", message));
    }

    // ---- outgoing messages ----------------------------------------------------------------------

    /** This robot's part of the hello message. */
    Map<String, Object> describe() {
        RobotRig r = rig;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("alliance", alliance);
        m.put("project", projectLabel);
        m.put("robotFile", robotFile.getFileName().toString());
        m.put("robot", JsonParser.parseString(RobotConfig.gson().toJson(r.config)));
        m.put("wheels", r.body.wheels.stream().map(w -> Map.of("key", w.key, "x", w.x, "y", w.y)).collect(Collectors.toList()));
        m.put("wheelRadius", r.body.wheelRadius);
        m.put("chassisZ", r.body.chassisRestZ());
        return m;
    }

    String buildJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "build");
        m.put("robot", index);
        m.put("building", building);
        m.put("pending", buildPending);
        TeamCodeCompiler.Result b = build;
        if (b != null) {
            m.put("ok", b.ok());
            m.put("message", b.message());
            m.put("skipped", b.skipped());
            m.put("opmodes", b.opModes().stream().map(OpModeInfo::toJson).collect(Collectors.toList()));
        }
        m.put("roots", compiler.roots().stream().map(r -> r.dir().toString()).collect(Collectors.toList()));
        return Simulator.GSON.toJson(m);
    }

    private void broadcastBuild() {
        sim.broadcast(buildJson());
    }

    String dsJson() {
        synchronized (lock) {
            return dsJsonLocked();
        }
    }

    private String dsJsonLocked() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "ds");
        m.put("robot", index);
        m.put("state", state.name());
        m.put("selected", selected);
        m.put("active", active == null ? null : active.name());
        m.put("activeKind", active == null ? null : active.kind().name());
        m.put("timer", autoTimerEnabled);
        m.put("error", lastError);
        m.put("fixRobot", fixRobot);
        m.put("robotFile", robotFile.getFileName().toString());
        m.put("warning", globalWarning);
        return Simulator.GSON.toJson(m);
    }

    private void broadcastDs() {
        synchronized (lock) {
            broadcastDsLocked();
        }
    }

    private void broadcastDsLocked() {
        sim.broadcast(dsJsonLocked());
    }

    private String telemetryJson(SimTelemetry.Frame f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "telemetry");
        m.put("robot", index);
        m.put("lines", f.lines);
        m.put("log", f.log);
        m.put("format", f.format.name());
        return Simulator.GSON.toJson(m);
    }

    /** Pose, mechanisms and Driver Station timer for the state stream; sends telemetry when due. */
    Map<String, Object> state(long now) {
        SimTelemetry t;
        Map<String, Object> ds = new LinkedHashMap<>();
        synchronized (lock) {
            t = telemetry;
            ds.put("state", state.name());
            if (state == DsState.RUNNING) {
                double elapsed = (now - startNanos) / 1e9;
                ds.put("elapsed", elapsed);
                if (active != null && active.kind() == OpModeInfo.Kind.AUTONOMOUS && autoTimerEnabled) {
                    ds.put("remaining", Math.max(0, Simulator.AUTO_PERIOD_S - elapsed));
                }
            } else if (state == DsState.INIT) {
                ds.put("elapsed", (now - initNanos) / 1e9);
            }
        }
        if (t != null) {
            SimTelemetry.Frame f = t.takeFrameIfDue(now);
            if (f != null) sim.broadcast(telemetryJson(f));
        }
        RobotRig r = rig;
        RobotBody body = r.body;
        DVector3C p = body.position();
        DQuaternionC q = body.chassis.getQuaternion();
        double[] wheels = new double[body.wheels.size()];
        for (int i = 0; i < wheels.length; i++) wheels[i] = body.wheels.get(i).angle();
        Map<String, Object> mech = new LinkedHashMap<>();
        for (Mechanism m : r.mechanisms) mech.put(m.device.name, m.position());
        for (ServoUnit s : r.servos) mech.put(s.device.name, Math.toRadians(s.angleDeg()));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("p", new double[]{p.get0(), p.get1(), p.get2()});
        m.put("q", new double[]{q.get0(), q.get1(), q.get2(), q.get3()});
        m.put("wheels", wheels);
        m.put("mech", mech);
        m.put("ds", ds);
        m.put("battery", r.batteryVolts());
        m.put("held", sim.elements.heldCount(index));
        return m;
    }

    /** Motors, servos, sensors and hubs for the device panel. */
    Map<String, Object> devices() {
        RobotRig r = rig;
        List<Map<String, Object>> motors = new ArrayList<>();
        List<Map<String, Object>> servos = new ArrayList<>();
        List<Map<String, Object>> hubs = new ArrayList<>();
        for (SimHub h : r.hubs) {
            hubs.add(Map.of("name", h.name, "led", String.format("#%06x", h.ledColor), "cache", h.getBulkCachingMode().name()));
            for (MotorPort mp : h.motors) {
                if (mp == null) continue;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("name", mp.name);
                d.put("hub", h.name);
                d.put("port", mp.port);
                d.put("mode", mp.mode.name());
                d.put("power", mp.velocityCommand ? null : mp.power);
                d.put("velocityTarget", mp.velocityCommand ? mp.velocityTarget : null);
                d.put("target", mp.targetPositionSet ? mp.targetPosition : null);
                d.put("pos", mp.encoderPosition());
                d.put("vel", Math.round(mp.velocityTicksPerSecond));
                d.put("amps", Math.round(mp.currentAmps * 100) / 100.0);
                d.put("duty", Math.round(mp.appliedDuty * 1000) / 1000.0);
                d.put("zpb", mp.zeroPowerBehavior.name());
                motors.add(d);
            }
            for (ServoPort sp : h.servos) {
                if (sp == null) continue;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("name", sp.name);
                d.put("hub", h.name);
                d.put("port", sp.port);
                d.put("continuous", sp.continuous);
                d.put("pos", Double.isNaN(sp.position) ? null : sp.position);
                d.put("pwm", sp.pwmEnabled);
                d.put("angle", Math.round(sp.angleDeg * 10) / 10.0);
                servos.add(d);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hubs", hubs);
        m.put("motors", motors);
        m.put("servos", servos);
        m.put("sensors", r.sensorReadings());
        return m;
    }

    // ---- read-only views (tests and tools) ---------------------------------------------------------

    public DsState dsState() {
        synchronized (lock) {
            return state;
        }
    }

    public boolean isIdle() {
        return dsState() == DsState.IDLE;
    }

    public String lastError() {
        synchronized (lock) {
            return lastError;
        }
    }

    public TeamCodeCompiler.Result build() {
        return build;
    }

    public boolean isBuilding() {
        return building;
    }

    public RobotRig rig() {
        return rig;
    }

    public Path robotFile() {
        return robotFile;
    }
}
