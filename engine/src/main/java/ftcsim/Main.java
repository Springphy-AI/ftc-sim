package ftcsim;

import java.awt.Desktop;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ftcsim.runner.TeamCodeCompiler;
import ftcsim.season.Season;
import ftcsim.server.WebServer;
import ftcsim.sim.SimRobot;
import ftcsim.sim.Simulator;

/**
 * FTC simulator entry point.
 *
 * <pre>
 *   ftc-sim [--season NAME] [--project DIR] [--robot FILE] [--port N] [--no-browser]
 *           [--robots N] [--project2 DIR] [--robot2 FILE] [--alliance2 red|blue] ...
 * </pre>
 *
 * --project points at your FtcRobotController project; the simulator compiles its
 * TeamCode/src/main/java (and the SDK samples folder) and rebuilds whenever you save.
 * More robots (up to 4) share the field: --project2 / --robot2 / --alliance2 set up robot 2, and so
 * on; a robot without its own project or robot file uses robot 1's.
 * --season picks the game (a folder in seasons/, or the path of one); BIOBUZZ by default.
 * Defaults can be kept in sim.properties next to this program (season=..., project=..., robot=...,
 * port=..., robots=..., project2=..., robot2=..., alliance2=...).
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        Path home = Paths.get("").toAbsolutePath();
        Properties props = new Properties();
        Path propsFile = home.resolve("sim.properties");
        if (Files.isRegularFile(propsFile)) {
            try (Reader r = Files.newBufferedReader(propsFile, StandardCharsets.UTF_8)) {
                props.load(r);
            }
        }
        int max = Simulator.MAX_ROBOTS;
        String[] project = new String[max + 1], robot = new String[max + 1], alliance = new String[max + 1];
        project[1] = props.getProperty("project", props.getProperty("project1"));
        robot[1] = props.getProperty("robot", props.getProperty("robot1", "robots/mecanum.json"));
        alliance[1] = props.getProperty("alliance", props.getProperty("alliance1"));
        int count = 1;
        for (int k = 2; k <= max; k++) {
            project[k] = props.getProperty("project" + k);
            robot[k] = props.getProperty("robot" + k);
            alliance[k] = props.getProperty("alliance" + k);
            if (project[k] != null || robot[k] != null || alliance[k] != null) count = k;
        }
        String season = props.getProperty("season", Season.DEFAULT);
        int robots = Integer.parseInt(props.getProperty("robots", "1"));
        int port = Integer.parseInt(props.getProperty("port", "8080"));
        boolean openBrowser = Boolean.parseBoolean(props.getProperty("open_browser", "true"));

        Pattern numbered = Pattern.compile("--(project|robot|alliance)([1-9])");
        for (int i = 0; i < args.length; i++) {
            Matcher m = numbered.matcher(args[i]);
            if (m.matches()) {
                int k = Integer.parseInt(m.group(2));
                if (k > max || i + 1 >= args.length) {
                    System.err.println(k > max ? "At most " + max + " robots (" + args[i] + ")" : "Missing value for " + args[i]);
                    System.exit(2);
                }
                String v = args[++i];
                switch (m.group(1)) {
                    case "project" -> project[k] = v;
                    case "robot" -> robot[k] = v;
                    default -> alliance[k] = v;
                }
                count = Math.max(count, k);
                continue;
            }
            switch (args[i]) {
                case "--season", "-s" -> season = args[++i];
                case "--project", "-p" -> project[1] = args[++i];
                case "--robot", "-r" -> robot[1] = args[++i];
                case "--alliance" -> alliance[1] = args[++i];
                case "--robots" -> robots = Integer.parseInt(args[++i]);
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--no-browser" -> openBrowser = false;
                case "--help", "-h" -> {
                    usage();
                    return;
                }
                default -> {
                    System.err.println("Unknown option " + args[i]);
                    usage();
                    System.exit(2);
                }
            }
        }

        if (robots < 1 || robots > max) {
            System.err.println("--robots must be between 1 and " + max);
            System.exit(2);
        }
        count = Math.max(count, robots);

        // Each running simulator compiles into its own folder; remove folders of simulators that have exited.
        Path workRoot = home.resolve("build/teamcode-classes");
        Files.createDirectories(workRoot);
        try (var dirs = Files.list(workRoot)) {
            for (Path d : dirs.toList()) {
                String name = d.getFileName().toString();
                if (!name.matches("\\d+")) continue;
                boolean exited = ProcessHandle.of(Long.parseLong(name)).map(p -> !p.isAlive()).orElse(true);
                if (exited) deleteTree(d);
            }
        }
        Path work = workRoot.resolve(Long.toString(ProcessHandle.current().pid()));
        Files.createDirectories(work);

        // Every robot gets its own TeamCode build, so robots never share TeamCode's static fields.
        List<Simulator.RobotSpec> specs = new ArrayList<>();
        Path robotsDir = null;
        for (int k = 1; k <= count; k++) {
            Path robotFile = home.resolve(robot[k] != null ? robot[k] : robot[1]).normalize();
            if (!Files.isRegularFile(robotFile)) {
                System.err.println("Robot file not found: " + robotFile);
                System.exit(2);
            }
            if (alliance[k] != null && !alliance[k].equalsIgnoreCase("red") && !alliance[k].equalsIgnoreCase("blue")) {
                System.err.println("Robot " + k + ": alliance must be red or blue, not \"" + alliance[k] + "\"");
                System.exit(2);
            }
            if (robotsDir == null) robotsDir = robotFile.getParent();
            Project pr = project(project[k] != null ? project[k] : project[1], home, count > 1 ? "Robot " + k + ": " : "");
            Path robotWork = count == 1 ? work : work.resolve("robot" + k);
            Files.createDirectories(robotWork);
            specs.add(new Simulator.RobotSpec(robotFile, new TeamCodeCompiler(pr.roots(), robotWork), pr.label(), alliance[k]));
        }

        Season game;
        try {
            game = Season.load(season, home.resolve("seasons"));
        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        System.out.println("Season: " + game);
        Simulator sim;
        try {
            sim = new Simulator(game.field, robotsDir, specs);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        WebServer server = new WebServer(sim);
        int bound = -1;
        for (int attempt = 0; attempt < 10 && bound < 0; attempt++) {
            try {
                bound = server.start(port + attempt);
            } catch (Exception e) {
                if (attempt == 9) throw e;
            }
        }
        sim.start();
        String url = "http://localhost:" + bound + "/";
        System.out.println();
        System.out.println("FTC simulator running at " + url);
        if (sim.robots().size() == 1) {
            System.out.println("  TeamCode: " + specs.get(0).projectLabel());
            System.out.println("  Robot:    " + specs.get(0).robotFile().getFileName());
        } else {
            for (SimRobot r : sim.robots()) {
                Simulator.RobotSpec s = specs.get(r.index);
                System.out.println("  Robot " + (r.index + 1) + " (" + r.alliance + "): " + s.robotFile().getFileName()
                        + ", TeamCode: " + s.projectLabel());
            }
        }
        System.out.println("Press Ctrl+C to quit.");
        if (openBrowser) open(url);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sim.shutdown();
            server.stop();
        }));
        Thread.currentThread().join();
    }

    /** The TeamCode source folders of one project (or the bundled examples), and how to describe it. */
    private record Project(List<TeamCodeCompiler.SourceRoot> roots, String label) {
    }

    /** @param who "" with one robot, else "Robot K: ", to say whose folder is wrong */
    private static Project project(String project, Path home, String who) {
        List<TeamCodeCompiler.SourceRoot> roots = new ArrayList<>();
        String label;
        if (project != null && !project.isBlank()) {
            Path p = Paths.get(project).toAbsolutePath().normalize();
            if (!Files.isDirectory(p)) {
                System.err.println(who + "Project folder not found: " + p);
                System.err.println("Clone your team's FtcRobotController repository (or the official one,"
                        + " https://github.com/FIRST-Tech-Challenge/FtcRobotController) and pass its folder.");
                System.exit(2);
            }
            // Accept the TeamCode or FtcRobotController module folder too, not just the project root.
            if (!Files.isDirectory(p.resolve("TeamCode")) && p.getParent() != null
                    && Files.isDirectory(p.getParent().resolve("TeamCode/src/main/java"))) {
                p = p.getParent();
            }
            Path teamCode = p.resolve("TeamCode/src/main/java");
            if (!Files.isDirectory(teamCode)) {
                System.err.println(who + "Not an FtcRobotController project (no TeamCode/src/main/java): " + p);
                System.err.println("Pass the folder that contains TeamCode and FtcRobotController.");
                System.exit(2);
            }
            roots.add(new TeamCodeCompiler.SourceRoot(teamCode, false));
            Path samples = p.resolve("FtcRobotController/src/main/java/org/firstinspires/ftc/robotcontroller/external/samples");
            if (Files.isDirectory(samples)) roots.add(new TeamCodeCompiler.SourceRoot(samples, true));
            label = p.toString();
        } else {
            roots.add(new TeamCodeCompiler.SourceRoot(home.resolve("examples/teamcode"), false));
            roots.add(new TeamCodeCompiler.SourceRoot(home.resolve("examples/samples"), true));
            label = "examples (no --project given)";
        }
        return new Project(roots, label);
    }

    private static void deleteTree(Path dir) {
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // in use or already gone
        }
    }

    private static void usage() {
        System.out.println("""
                Usage: ftc-sim [--season NAME] [--project DIR] [--robot FILE] [--port N] [--no-browser]
                               [--robots N] [--project2 DIR] [--robot2 FILE] [--alliance2 red|blue] ...

                  --season NAME     the game to play: a season in seasons/ (%s), or the path
                                    of a season folder; default %s
                  --project DIR     your FtcRobotController project (uses DIR/TeamCode/src/main/java)
                  --robot FILE      robot description, default robots/mecanum.json
                  --port N          web port, default 8080
                  --no-browser      do not open the browser

                More robots on the same field (up to 4, at most two per alliance):
                  --robots N        put N robots on the field; each runs robot 1's project and robot
                                    file unless it gets its own with the options below
                  --projectK DIR    robot K's FtcRobotController project (K = 2, 3, 4)
                  --robotK FILE     robot K's description
                  --allianceK A     robot K's alliance, red or blue
                                    (default: robot 1 red, robot 2 blue, robot 3 red, robot 4 blue)

                Defaults can also be set in sim.properties (season=, project=, robot=, port=, open_browser=,
                robots=, project2=, robot2=, alliance2=, ...).""".formatted(
                String.join(", ", Season.available(Paths.get("").toAbsolutePath().resolve("seasons"))), Season.DEFAULT));
    }

    private static void open(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (IOException | UnsupportedOperationException ignored) {
        }
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            else if (os.contains("mac")) new ProcessBuilder("open", url).start();
            else new ProcessBuilder("xdg-open", url).start();
        } catch (IOException ignored) {
            System.out.println("Open " + url + " in your browser.");
        }
    }
}
