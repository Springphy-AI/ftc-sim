package ftcsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import biobuzz.BiobuzzGame;
import biobuzz.HiveBody;
import ftcsim.physics.FieldModel;
import ftcsim.runner.TeamCodeCompiler;
import ftcsim.sim.GameElements;
import ftcsim.sim.Simulator;

/** Runs the bundled example TeamCode on the default mecanum robot. */
class ExampleOpModesTest {
    private final List<String> messages = new CopyOnWriteArrayList<>();

    private Simulator start() throws Exception {
        Path home = Path.of("").toAbsolutePath();
        TeamCodeCompiler compiler = new TeamCodeCompiler(List.of(
                new TeamCodeCompiler.SourceRoot(home.resolve("examples/teamcode"), false)), Files.createTempDirectory("ftcsim-ex"));
        Path robot = home.resolve("robots/mecanum.json");
        Simulator sim = new Simulator(FieldModel.loadBiobuzz(), robot, robot.getParent(), compiler, "test");
        sim.setOutput(new Simulator.Output() {
            @Override
            public void broadcast(String json) {
                messages.add(json);
            }

            @Override
            public void broadcastState(String json) {
            }
        });
        sim.start();
        long deadline = System.currentTimeMillis() + 60000;
        while ((sim.build() == null || sim.isBuilding()) && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertNotNull(sim.build());
        assertTrue(sim.build().ok() && sim.build().skipped().isEmpty(), sim.build().message());
        return sim;
    }

    private static void waitFor(Simulator sim, Simulator.DsState state, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (sim.dsState() != state && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(state, sim.dsState(), "Driver Station state (error: " + sim.lastError() + ")");
    }

    private static String buttons(int pressed) {
        return gamepad(0, 0, 0, pressed);
    }

    /** gamepad1 with the left stick (x right, y down as in FTC), right stick x, and one button held (-1 for none). */
    private static String gamepad(double lx, double ly, double rx, int pressed) {
        StringBuilder sb = new StringBuilder("{\"type\":\"gamepad\",\"gamepad\":1,\"kind\":\"XBOX_360\",\"axes\":[")
                .append(lx).append(',').append(ly).append(',').append(rx).append(",0,0,0],\"buttons\":[");
        for (int i = 0; i < 18; i++) sb.append(i > 0 ? "," : "").append(i == pressed);
        return sb.append("]}").toString();
    }

    /** Pushes the left stick forward for a moment and returns how far the robot moved in x and y (m). */
    private static double[] driveForward(Simulator sim) throws InterruptedException {
        double x0 = sim.rig().body.position().get0(), y0 = sim.rig().body.position().get1();
        sim.handle(gamepad(0, -0.5, 0, -1));
        Thread.sleep(500);
        sim.handle(gamepad(0, 0, 0, -1));
        Thread.sleep(500);
        return new double[]{sim.rig().body.position().get0() - x0, sim.rig().body.position().get1() - y0};
    }

    @Test
    void driveIsRobotCentricAndBackSwitchesToFieldCentric() throws Exception {
        Simulator sim = start();
        sim.handle("{\"type\":\"place\",\"x\":-1.0,\"y\":-1.3,\"h\":0}");   // open floor, facing the blue wall (+x)
        sim.handle("{\"type\":\"init\",\"opmode\":\"BIOBUZZ TeleOp\"}");
        waitFor(sim, Simulator.DsState.INIT, 2000);
        sim.handle("{\"type\":\"start\"}");
        waitFor(sim, Simulator.DsState.RUNNING, 2000);
        Thread.sleep(200);

        // Turn left about 90 degrees: the robot now faces the rear wall (+y).
        sim.handle(gamepad(0, 0, -0.4, -1));
        long deadline = System.currentTimeMillis() + 4000;
        while (Math.toDegrees(sim.rig().body.heading()) < 85 && System.currentTimeMillis() < deadline) Thread.sleep(2);
        sim.handle(gamepad(0, 0, 0, -1));
        Thread.sleep(400);
        System.out.printf("turned to %.0f deg%n", Math.toDegrees(sim.rig().body.heading()));

        double[] robot = driveForward(sim);
        System.out.printf("robot-centric forward: dx %.2f m, dy %.2f m%n", robot[0], robot[1]);
        assertTrue(robot[1] > 0.2 && Math.abs(robot[0]) < 0.1, "robot-centric: forward is toward the robot's front (+y)");

        sim.handle(buttons(8));   // BACK: field-centric
        Thread.sleep(150);
        sim.handle(buttons(-1));
        Thread.sleep(150);
        double[] field = driveForward(sim);
        System.out.printf("field-centric forward: dx %.2f m, dy %.2f m%n", field[0], field[1]);
        assertTrue(field[0] > 0.2 && Math.abs(field[1]) < 0.1, "field-centric: forward is away from the red wall (+x)");

        sim.handle("{\"type\":\"stop\"}");
        waitFor(sim, Simulator.DsState.IDLE, 3000);
        assertNull(sim.lastError());
        sim.shutdown();
    }

    @Test
    void pinpointSquareReturnsToStart() throws Exception {
        Simulator sim = start();
        double x0 = sim.rig().body.position().get0(), y0 = sim.rig().body.position().get1();
        sim.handle("{\"type\":\"init\",\"opmode\":\"Pinpoint Square\"}");
        waitFor(sim, Simulator.DsState.INIT, 2000);
        Thread.sleep(400);   // Pinpoint IMU calibration after resetPosAndIMU()
        sim.handle("{\"type\":\"start\"}");
        long deadline = System.currentTimeMillis() + 25000;
        while (messages.stream().noneMatch(m -> m.contains("ended at")) && System.currentTimeMillis() < deadline) Thread.sleep(50);
        double x = sim.rig().body.position().get0(), y = sim.rig().body.position().get1();
        double err = Math.hypot(x - x0, y - y0) / 0.0254;
        double heading = Math.toDegrees(sim.rig().body.heading());
        System.out.printf("Pinpoint Square: true end %.2f in from start, heading %.2f deg%n", err, heading);
        assertTrue(messages.stream().anyMatch(m -> m.contains("ended at")), "autonomous finished");
        assertTrue(err < 1.5, "returned to start, off by " + err + " in");
        assertEquals(0, heading, 3.0);
        sim.handle("{\"type\":\"stop\"}");
        waitFor(sim, Simulator.DsState.IDLE, 3000);
        assertNull(sim.lastError());
        assertTrue(sim.dsJson().contains("\"selected\":\"BIOBUZZ TeleOp\""), "preselectTeleOp applied: " + sim.dsJson());
        sim.shutdown();
    }

    @Test
    void launcherSpinsUpRumblesAndShootsIntoTheHiveCell() throws Exception {
        Simulator sim = start();
        HiveBody red = BiobuzzGame.of(sim.elements()).hive("red");
        assertEquals("red", red.start.alliance());
        // In line with the red HIVE on the audience side, facing its open CELL (the rear wall): the
        // near end of the range the TeleOp documents, the middle, and backed against the audience wall
        // (place keeps the center 0.23 m from the wall, y = -1.60).
        for (double y : new double[]{-1.0, -1.3, -1.6}) {
            messages.clear();
            sim.handle("{\"type\":\"reset\"}");   // a fresh pre-load, the HIVE back on its start stop
            sim.handle("{\"type\":\"place\",\"x\":-0.32,\"y\":" + y + ",\"h\":90}");
            waitUntil(() -> read(sim, () -> sim.elements().heldCount() == 4 && red.tips() == 0
                    && Math.abs(sim.rig().body.position().get1() - y) < 0.01), 3000, "robot placed with 4 POLLEN");
            sim.handle("{\"type\":\"init\",\"opmode\":\"BIOBUZZ TeleOp\"}");
            waitFor(sim, Simulator.DsState.INIT, 2000);
            sim.handle("{\"type\":\"start\"}");
            waitFor(sim, Simulator.DsState.RUNNING, 2000);
            Thread.sleep(200);
            long yPressed = System.currentTimeMillis();
            sim.handle(buttons(3));   // Y toggles the launcher
            Thread.sleep(150);
            sim.handle(buttons(-1));
            // The controller rumbles when the launcher reaches speed; a driver presses RB right then.
            waitUntil(() -> messages.stream().anyMatch(m -> m.contains("\"type\":\"rumble\"")), 4000, "rumble sent to the controller");
            System.out.printf("y %.1f: launcher ready %.1f s after Y%n", y, (System.currentTimeMillis() - yPressed) / 1000.0);
            assertTrue(System.currentTimeMillis() - yPressed < 2000, "launcher reaches speed within 2 s");

            // Two POLLEN: with the 3 starting NECTAR, the CELL holds them and the HIVE stays put.
            List<GameElements.Ball> preload = read(sim, () -> sim.elements().balls().stream()
                    .filter(b -> b.state() == GameElements.State.HELD).collect(Collectors.toList()));
            assertEquals(4, preload.size());
            List<GameElements.Ball> firstTwo = preload.subList(0, 2);   // the feeder takes them in order
            sim.handle(buttons(5));
            waitUntil(() -> read(sim, () -> sim.elements().heldCount() <= 2), 4000, "RB shoots");
            sim.handle(buttons(-1));
            double tps = sim.rig().motors.get("launcher").port.velocityTicksPerSecond;
            System.out.printf("y %.1f: launcher %.0f ticks/s (target 970)%n", y, tps);
            assertEquals(970, tps, 29, "launcher holds speed within 3%");
            assertEquals(2, (int) read(sim, () -> sim.elements().heldCount()), "releasing RB stops the feeder after 2");
            waitForRest(sim, firstTwo);
            for (int i = 0; i < 2; i++) {
                GameElements.Ball b = firstTwo.get(i);
                String cell = read(sim, () -> BiobuzzGame.of(sim.elements()).cellOf(b));
                System.out.printf("y %.1f: shot %d at %s, in CELL: %s%n", y, i + 1, read(sim, () -> b.position().toString()), cell);
                assertEquals("red audience", cell, "from y = " + y + ", shot " + (i + 1) + " lands in the red HIVE's open CELL");
            }
            assertEquals(0, (int) read(sim, red::tips), "3 NECTAR + 2 POLLEN: the HIVE holds");

            // The 3rd POLLEN tips it (Event Field Setup Guide 12.3); the raised CELL is now the rear one.
            sim.handle(buttons(5));
            waitUntil(() -> read(sim, () -> sim.elements().heldCount() == 0), 4000, "RB shoots the other 2");
            sim.handle(buttons(-1));
            waitUntil(() -> read(sim, red::tips) >= 1, 12000, "from y = " + y + ", the 3rd POLLEN tips the red HIVE");
            assertEquals("rear", read(sim, red::upCell));
            System.out.printf("y %.1f: HIVE tipped%n", y);
            sim.handle("{\"type\":\"stop\"}");
            waitFor(sim, Simulator.DsState.IDLE, 3000);
            assertNull(sim.lastError());
        }
        sim.shutdown();
    }

    /** Waits until these balls have been still for 10 polls in a row (not at the top of a bounce). */
    private static void waitForRest(Simulator sim, List<GameElements.Ball> balls) throws InterruptedException {
        int[] still = {0};
        waitUntil(() -> {
            boolean rest = read(sim, () -> balls.stream().allMatch(b -> b.velocity().length() < 0.02));
            still[0] = rest ? still[0] + 1 : 0;
            return still[0] >= 10;
        }, 10000, "the shots come to rest");
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, what);
            Thread.sleep(20);
        }
    }

    /** Reads simulator state on the physics thread. */
    private static <T> T read(Simulator sim, Supplier<T> get) {
        List<T> out = new ArrayList<>(1);
        try {
            sim.onPhysicsThread(() -> out.add(get.get()));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return out.get(0);
    }
}
