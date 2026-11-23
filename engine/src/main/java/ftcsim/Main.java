package ftcsim;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ftcsim.runner.TeamCodeCompiler;
import ftcsim.season.Season;
import ftcsim.server.WebServer;
import ftcsim.sim.SimRobot;
import ftcsim.sim.Simulator;

/**
 * FTC simulator entry point. run-sim.bat / run-sim.sh start it in the simulator's folder; its options
 * and sim.properties' keys are in {@link #usageText()} (what --help prints).
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        Path home = Paths.get("").toAbsolutePath();
        Options options;
        try {
            options = parse(readProperties(home.resolve("sim.properties")), args, System.getenv("FTCSIM_CALLER_DIR"));
        } catch (UsageError e) {
            System.err.println(e.getMessage());
            if (e.showUsage) usage();
            else System.err.println("Run with --help to see the options.");
            System.exit(2);
            return;
        }
        if (options.help()) {
            usage();
            return;
        }
        options.warnings().forEach(System.err::println);
        String[] project = options.project(), robot = options.robot(), alliance = options.alliance();
        String season = options.season();
        int count = options.count();
        int port = options.port();
        boolean openBrowser = options.openBrowser();

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

    /**
     * A project folder typed on the command line. run-sim changes to the simulator's folder before
     * starting it and says in {@code callerDir} where it was started: a relative path is relative to
     * that, where the user typed it. (sim.properties paths stay relative to the simulator's folder.)
     */
    static String fromCaller(String path, String callerDir) {
        if (callerDir == null || callerDir.isBlank() || Paths.get(path).isAbsolute()) return path;
        return Paths.get(callerDir).resolve(path).normalize().toString();
    }

    /**
     * A robot file or season typed on the command line: where the user typed it if {@code found}
     * there, else as typed, which the simulator's folder resolves (robots/tank.json, seasons/decode,
     * or a season's name) wherever run-sim was started.
     */
    static String fromCallerIf(String path, String callerDir, Predicate<Path> found) {
        try {
            String there = fromCaller(path, callerDir);
            return !there.equals(path) && found.test(Paths.get(there)) ? there : path;
        } catch (InvalidPathException e) {
            return path;   // not a path: the simulator's folder says what is wrong with it
        }
    }

    private static boolean isSeason(Path p) {
        return Files.isRegularFile(p) || Files.isRegularFile(p.resolve("season.json"));
    }

    /**
     * What to start: sim.properties' settings with the command line's over them. project, robot and
     * alliance are by robot number, from 1 (null where not given); count is how many robots;
     * warnings are for the user.
     */
    record Options(String season, String[] project, String[] robot, String[] alliance, int count, int port,
                   boolean openBrowser, boolean help, List<String> warnings) {
    }

    /** A mistake in the options or in sim.properties, said in a sentence: exit code 2, no stack trace. */
    static final class UsageError extends Exception {
        final boolean showUsage;

        UsageError(String message, boolean showUsage) {
            super(message);
            this.showUsage = showUsage;
        }
    }

    /**
     * The options from sim.properties' settings ({@code props}) and the command line ({@code args});
     * {@code callerDir} is the folder run-sim was started in (null when started some other way).
     */
    static Options parse(Map<String, String> props, String[] args, String callerDir) throws UsageError {
        int max = Simulator.MAX_ROBOTS;
        String[] project = new String[max + 1], robot = new String[max + 1], alliance = new String[max + 1];
        project[1] = props.getOrDefault("project", props.get("project1"));
        robot[1] = props.getOrDefault("robot", props.getOrDefault("robot1", "robots/mecanum.json"));
        alliance[1] = props.getOrDefault("alliance", props.get("alliance1"));
        int count = 1;
        for (int k = 2; k <= max; k++) {
            project[k] = props.get("project" + k);
            robot[k] = props.get("robot" + k);
            alliance[k] = props.get("alliance" + k);
            if (project[k] != null || robot[k] != null || alliance[k] != null) count = k;
        }
        String season = props.getOrDefault("season", Season.DEFAULT);
        int robots = number(props.getOrDefault("robots", "1"), "robots= in sim.properties");
        int port = number(props.getOrDefault("port", "8080"), "port= in sim.properties");
        boolean openBrowser = Boolean.parseBoolean(props.getOrDefault("open_browser", "true"));
        List<String> warnings = new ArrayList<>();
        boolean robotsTyped = false;     // --robots on the command line
        String typedNumbered = null;     // the command line's option for the highest-numbered robot
        int typedCount = 1;

        Pattern numbered = Pattern.compile("--(project|robot|alliance)([1-9])");
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            Matcher m = numbered.matcher(a);
            if (m.matches()) {
                int k = Integer.parseInt(m.group(2));
                if (k > max) throw new UsageError("At most " + max + " robots (" + a + ")", false);
                String v = value(args, i++);
                switch (m.group(1)) {
                    case "project" -> project[k] = fromCaller(v, callerDir);
                    case "robot" -> robot[k] = fromCallerIf(v, callerDir, Files::isRegularFile);
                    default -> alliance[k] = v;
                }
                if (k >= typedCount) {
                    typedCount = k;
                    typedNumbered = a;
                }
                continue;
            }
            switch (a) {
                case "--season", "-s" -> season = fromCallerIf(value(args, i++), callerDir, Main::isSeason);
                case "--project", "-p" -> project[1] = fromCaller(value(args, i++), callerDir);
                case "--robot", "-r" -> robot[1] = fromCallerIf(value(args, i++), callerDir, Files::isRegularFile);
                case "--alliance" -> alliance[1] = value(args, i++);
                case "--robots" -> {
                    robots = number(value(args, i++), a);
                    robotsTyped = true;
                }
                case "--port" -> port = number(value(args, i++), a);
                case "--no-browser" -> openBrowser = false;
                case "--help", "-h" -> {
                    return new Options(season, project, robot, alliance, count, port, openBrowser, true, warnings);
                }
                default -> throw new UsageError("Unknown option " + a, true);
            }
        }
        if (robots < 1 || robots > max) throw new UsageError("The number of robots must be between 1 and " + max + ", not " + robots, false);
        if (port < 1 || port > 65535) throw new UsageError("The port must be between 1 and 65535, not " + port, false);
        if (robotsTyped) {
            // --robots on the command line is how many robots there are: sim.properties' settings
            // for robots after those are left out (they used to add their robots back).
            if (typedCount > robots) throw new UsageError("--robots " + robots + ", but " + typedNumbered + " is for robot " + typedCount, false);
            List<String> left = new ArrayList<>();
            for (int k = robots + 1; k <= count; k++) {
                if (project[k] != null || robot[k] != null || alliance[k] != null) left.add(Integer.toString(k));
            }
            if (!left.isEmpty()) {
                warnings.add("--robots " + robots + ": leaving out robot " + String.join(" and ", left)
                        + ", which sim.properties sets up.");
            }
            count = robots;
        } else {
            count = Math.max(Math.max(count, typedCount), robots);
        }
        return new Options(season, project, robot, alliance, count, port, openBrowser, false, warnings);
    }

    /** The value after option {@code args[i]}: missing when it is the last argument or another option. */
    private static String value(String[] args, int i) throws UsageError {
        if (i + 1 >= args.length || args[i + 1].startsWith("--")) throw new UsageError(args[i] + " needs a value", false);
        return args[i + 1];
    }

    private static int number(String value, String what) throws UsageError {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new UsageError(what + " must be a whole number, not \"" + value + "\"", false);
        }
    }

    /**
     * sim.properties as written: "key=value" lines ("key: value" and "key value" too), # and ! start
     * comments, and spaces around keys and values don't count. Backslashes are kept as they are:
     * java.util.Properties reads them as escapes, so a Windows path lost them (C:\Users\me became
     * C:Usersme, \t a tab) or failed to load (D:\ftc\ users, without the space, is a bad unicode
     * escape). Empty if there is no file.
     */
    static Map<String, String> readProperties(Path file) throws IOException {
        return readProperties(file, nativeCharset());
    }

    /** @param notUtf8 the encoding of a file that isn't UTF-8: an older Notepad saved in Windows' own */
    static Map<String, String> readProperties(Path file, Charset notUtf8) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        byte[] bytes = Files.readAllBytes(file);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            text = new String(bytes, notUtf8);
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);   // Notepad's byte order mark
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty() || t.charAt(0) == '#' || t.charAt(0) == '!') continue;
            int end = 0;
            while (end < t.length() && "=: \t\f".indexOf(t.charAt(end)) < 0) end++;
            String value = t.substring(end).stripLeading();
            if (!value.isEmpty() && (value.charAt(0) == '=' || value.charAt(0) == ':')) value = value.substring(1).stripLeading();
            out.put(t.substring(0, end), value);
        }
        return out;
    }

    private static Charset nativeCharset() {
        try {
            return Charset.forName(System.getProperty("native.encoding", "UTF-8"));
        } catch (RuntimeException e) {
            return StandardCharsets.ISO_8859_1;
        }
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
        System.out.println(usageText());
    }

    /** What --help prints: every option and sim.properties key. */
    static String usageText() {
        return """
                Usage: .\\run-sim.bat [options]     (Windows)
                       sh run-sim.sh [options]     (macOS, Linux)

                  -p, --project DIR   your FtcRobotController project (the folder with TeamCode in it):
                                      the simulator builds its TeamCode/src/main/java and FIRST's
                                      samples, and builds again when you save; without it, the
                                      simulator's examples
                  -r, --robot FILE    the robot's description; default robots/mecanum.json
                  -s, --season NAME   the game: a season in seasons/ (%s) or the path of a
                                      season folder; default %s
                      --alliance A    robot 1's alliance, red or blue; default red
                      --port N        the Driver Station page's port; default 8080. If another
                                      program has it, the next free one up to N+9 is used
                      --no-browser    don't open the Driver Station page in the browser
                  -h, --help          print this and stop

                More robots on the same field (up to 4, at most two per alliance):
                      --robots N      put N robots on the field; each runs robot 1's project and robot
                                      file unless it gets its own with the options below
                      --projectK DIR  robot K's FtcRobotController project (K = 2, 3, 4)
                      --robotK FILE   robot K's description
                      --allianceK A   robot K's alliance, red or blue
                                      (default: robot 1 red, robot 2 blue, robot 3 red, robot 4 blue)

                A relative path is where you typed the command; a robot file or season that isn't
                there is taken from the simulator's folder (robots/..., seasons/...).

                Defaults can be kept in sim.properties, in the simulator's folder, one key=value a line:
                project, robot, season, alliance, port, open_browser (true or false), robots, and
                projectK, robotK, allianceK (project1, robot1 and alliance1 are robot 1's too). Its
                paths are relative to the simulator's folder. Options on the command line win; with
                --robots, robots after N that sim.properties sets up are left out.""".formatted(
                String.join(", ", Season.available(Paths.get("").toAbsolutePath().resolve("seasons"))), Season.DEFAULT);
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
