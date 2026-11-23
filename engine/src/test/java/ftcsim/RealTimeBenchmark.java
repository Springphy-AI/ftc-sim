package ftcsim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import ftcsim.runner.TeamCodeCompiler;
import ftcsim.config.RobotConfig;
import ftcsim.physics.FieldModel;
import ftcsim.physics.MotorUnit;
import ftcsim.sim.GameElements;
import ftcsim.sim.RobotRig;
import ftcsim.sim.SimRobot;
import ftcsim.sim.Simulator;
import ftcsim.vision.SimCamera;

/**
 * Experiment E6 of the report: how fast the physics runs with 1, 2 and 4 robots on the BIOBUZZ field
 * with all its game pieces, for robots described by hand and from CAD, and how long the software
 * renderer takes per webcam frame. Every robot drives an arc that reverses every 1.5 s, with its
 * intake and launcher running. Not part of {@code test}: the numbers depend on the computer. Run it
 * with {@code gradlew :engine:experiment --tests ftcsim.RealTimeBenchmark}.
 */
@Tag("experiment")
class RealTimeBenchmark {
    private static final double DT = 0.001;
    private static final int[] ROBOTS = {1, 2, 4};
    private static final String[] FILES = {"mecanum.json", "mecanum-cad.json"};
    private static final int[][] RESOLUTIONS = {{320, 240}, {640, 480}, {1280, 720}};

    /** A match as the simulator builds one, not started: no physics thread, no OpMode. */
    private static Simulator match(String robotFile, int robots) throws Exception {
        Path home = Path.of("").toAbsolutePath();
        Path file = home.resolve("robots/" + robotFile);
        List<Simulator.RobotSpec> specs = new ArrayList<>();
        for (int i = 0; i < robots; i++) {
            TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(
                    new TeamCodeCompiler.SourceRoot(home.resolve("examples/teamcode"), false)), Files.createDirectories(home.resolve("build/benchmark-teamcode/" + i)));
            specs.add(new Simulator.RobotSpec(file, compiler, "bench", null));
        }
        return new Simulator(FieldModel.loadBiobuzz(), file.getParent(), specs);
    }

    private static void power(RobotRig rig, String motor, double power) {
        MotorUnit m = rig.motors.get(motor);
        if (m != null) m.port.power = power;
    }

    /** Each robot drives an arc, reversing every 1.5 s so it stays near its start, intake and launcher on. */
    private static void drive(List<RobotRig> rigs, double t) {
        double dir = ((int) (t / 1.5)) % 2 == 0 ? 1 : -1;
        for (int i = 0; i < rigs.size(); i++) {
            RobotRig rig = rigs.get(i);
            double turn = i % 2 == 0 ? 0.25 : -0.25;
            power(rig, "front_left_drive", dir * (0.6 + turn));
            power(rig, "back_left_drive", dir * (0.6 + turn));
            power(rig, "front_right_drive", dir * (0.6 - turn));
            power(rig, "back_right_drive", dir * (0.6 - turn));
            power(rig, "intake", 1.0);
            power(rig, "launcher", 0.45);
        }
    }

    /** One physics step of the whole match, in the simulator's order. */
    private static void step(List<RobotRig> rigs, GameElements elements) {
        elements.beforeStep(DT);
        for (int i = 1; i < rigs.size(); i++) rigs.get(i).beforeWorldStep(DT);
        rigs.get(0).step(DT);   // the first robot's motors, then the shared world step, then its sensors
        for (int i = 1; i < rigs.size(); i++) rigs.get(i).afterWorldStep(DT);
    }

    private static List<RobotRig> rigs(Simulator sim) {
        List<RobotRig> rigs = new ArrayList<>();
        for (SimRobot r : sim.robots()) rigs.add(r.rig());
        return rigs;
    }

    @Test
    void physicsAndRenderingSpeed() throws Exception {
        System.out.printf("Machine: %s, %d hardware threads, %s; Java %s%n", System.getenv("PROCESSOR_IDENTIFIER"),
                Runtime.getRuntime().availableProcessors(), System.getProperty("os.name"), System.getProperty("java.version"));
        System.out.println("robots  robot file         real-time factor  physics us/step  render ms/frame at 320x240, 640x480, 1280x720");
        for (String file : FILES) {
            for (int n : ROBOTS) {
                Simulator sim = match(file, n);
                List<RobotRig> rigs = rigs(sim);
                GameElements elements = sim.elements();

                // Physics as fast as it goes: simulated seconds per wall-clock second.
                double t = 0;
                for (int i = 0; i < 2000; i++, t += DT) {
                    drive(rigs, t);
                    step(rigs, elements);
                }
                int steps = 10_000;
                long t0 = System.nanoTime();
                for (int i = 0; i < steps; i++, t += DT) {
                    drive(rigs, t);
                    step(rigs, elements);
                }
                double wall = (System.nanoTime() - t0) / 1e9;
                double rtf = steps * DT / wall;

                // Rendering, while a physics thread runs the match in real time as the simulator does.
                RobotConfig.Device webcam = rigs.get(0).config.devices.stream()
                        .filter(d -> "webcam".equals(d.type)).findFirst().orElseThrow();
                SimCamera camera = sim.cameras().camera(0, webcam);
                double[] renderMs = new double[RESOLUTIONS.length];
                final double tStart = t;
                // A flag, not an interrupt: code under a step may swallow an interrupt (the simulator's loop uses a flag too).
                AtomicBoolean running = new AtomicBoolean(true);
                AtomicLong stepped = new AtomicLong(), slipped = new AtomicLong();
                long phaseStart = System.nanoTime();
                Thread physics = new Thread(() -> {
                    long start = System.nanoTime(), done = 0;
                    double simT = tStart;
                    while (running.get()) {
                        long due = (System.nanoTime() - start) / 1_000_000L;
                        if (due - done > 250) {
                            slipped.addAndGet(due - 250 - done);
                            done = due - 250;
                        }
                        while (done < due) {
                            drive(rigs, simT);
                            step(rigs, elements);
                            simT += DT;
                            done++;
                            stepped.incrementAndGet();
                        }
                        sim.cameras().update(rigs, elements);
                        LockSupport.parkNanos(400_000L);
                    }
                }, "bench-physics");
                physics.setDaemon(true);
                physics.start();
                try {
                    for (int r = 0; r < RESOLUTIONS.length; r++) {
                        int w = RESOLUTIONS[r][0], h = RESOLUTIONS[r][1];
                        byte[] rgba = new byte[w * h * 4];
                        for (int i = 0; i < 5; i++) camera.capture(w, h, rgba);
                        int frames = 30;
                        long r0 = System.nanoTime();
                        for (int i = 0; i < frames; i++) camera.capture(w, h, rgba);
                        renderMs[r] = (System.nanoTime() - r0) / 1e6 / frames;
                    }
                } finally {
                    running.set(false);
                    physics.join();
                }
                // While the webcam rendered, did the paced physics keep up with the wall clock?
                double phase = (System.nanoTime() - phaseStart) / 1e9;
                System.out.printf("%6d  %-17s  %16.1f  %15.0f  %.1f, %.1f, %.1f   paced while rendering: %.2f of real time, %d steps slipped%n",
                        n, file, rtf, wall / steps * 1e6, renderMs[0], renderMs[1], renderMs[2],
                        stepped.get() * DT / phase, slipped.get());
                assertTrue(rtf > 0);
            }
        }
    }
}
