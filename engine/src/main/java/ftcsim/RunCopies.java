package ftcsim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The copies of the simulator run-sim starts it from, build/run/ID (engine/build.gradle's prepareRun):
 * a build rewrites the jars of engine/build/install in place, under any simulator running from them,
 * so each launch runs from a copy no build changes. A simulator marks the copy it runs from with its
 * process id (running/PID); copies no live simulator marks are deleted, except the newest (named in
 * build/run/current) and one run-sim prepared moments ago, which it may be about to start.
 */
final class RunCopies {

    /** How long after run-sim prepared a copy it may still be about to start the simulator from it. */
    static final Duration STARTING = Duration.ofMinutes(10);

    private RunCopies() {
    }

    /** The install this simulator runs from: the folder whose lib/ holds its jar (null when it runs from classes). */
    static Path ownInstall() {
        try {
            Path jar = Paths.get(RunCopies.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path lib = jar.getParent();
            return Files.isRegularFile(jar) && lib != null && lib.getFileName().toString().equals("lib") ? lib.getParent() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Marks {@code install} as run from by process {@code pid}, when it is one of the copies in {@code root}. */
    static void claim(Path root, Path install, long pid) {
        if (install == null || !Files.isDirectory(root)) return;
        try {
            if (!install.toRealPath().getParent().equals(root.toRealPath())) return;
            Files.createDirectories(install.resolve("running"));
            Files.writeString(install.resolve("running").resolve(Long.toString(pid)), "");
            running(install);   // and forget the simulators that ran from it before
        } catch (IOException | RuntimeException ignored) {
            // not a copy, or not writable: nothing to protect it with, and nothing lost
        }
    }

    /** Deletes the copies in {@code root} that no simulator runs from (see the class comment) but {@code own}. */
    static void clean(Path root, Path own, Instant now) {
        if (!Files.isDirectory(root)) return;
        String current = null;
        try {
            current = Files.readString(root.resolve("current")).trim();
        } catch (IOException ignored) {
            // none yet
        }
        List<Path> copies;
        try (Stream<Path> s = Files.list(root)) {
            copies = s.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            return;
        }
        for (Path copy : copies) {
            String name = copy.getFileName().toString();
            if (name.equals(current) || same(copy, own) || running(copy)) continue;
            // When run-sim last prepared it (a copy it was still making: when that began).
            Path stamp = Files.exists(copy.resolve("launched")) ? copy.resolve("launched") : copy;
            try {
                if (Files.getLastModifiedTime(stamp).toInstant().isAfter(now.minus(STARTING))) continue;
            } catch (IOException ignored) {
                // gone meanwhile
            }
            deleteTree(copy);
        }
    }

    /** Whether a live process marked {@code copy} as run from; marks of exited ones are removed. */
    private static boolean running(Path copy) {
        boolean live = false;
        try (Stream<Path> s = Files.list(copy.resolve("running"))) {
            for (Path mark : s.toList()) {
                long pid;
                try {
                    pid = Long.parseLong(mark.getFileName().toString());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) live = true;
                else Files.deleteIfExists(mark);
            }
        } catch (IOException ignored) {
            // no marks
        }
        return live;
    }

    private static boolean same(Path a, Path b) {
        try {
            return b != null && Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // in use (Windows) or already gone: the next simulator tries again
        }
    }
}
