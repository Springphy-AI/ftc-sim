package ftcsim.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Java files of one robot's TeamCode, for the Driver Station page's code editor: listing,
 * reading and saving them. Saving writes the real file in the robot project, so what you edit in
 * the simulator is what you deploy. Only files inside the project's source folders can be read or
 * written, and the FTC samples folder is read-only (as the SDK's own files are on the robot).
 */
public final class TeamCodeFiles {

    /** A source folder; {@code index} is its position in the robot's list of folders. */
    public record Root(int index, Path dir, boolean readOnly) {
    }

    /** A Java file, {@code path} relative to its root with '/' separators. */
    public record Entry(int root, String path) {
    }

    /** A file's text (line endings as on disk, no byte order mark) and the hash of its bytes. */
    public record Content(String text, String hash, boolean readOnly) {
    }

    /** A request the files can't satisfy; {@code status} is the HTTP status that says why. */
    public static final class Refused extends Exception {
        public final int status;
        /** On a conflict, the hash of the file as it is on disk now (null if it no longer exists). */
        public final String currentHash;

        public Refused(int status, String message) {
            this(status, message, null);
        }

        Refused(int status, String message, String currentHash) {
            super(message);
            this.status = status;
            this.currentHash = currentHash;
        }
    }

    /** Larger than any TeamCode file; keeps a mistake from filling the disk. */
    public static final int MAX_BYTES = 1 << 20;

    private static final Pattern SEGMENT = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*");
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final List<Root> roots = new ArrayList<>();

    public TeamCodeFiles(List<TeamCodeCompiler.SourceRoot> sourceRoots) {
        for (int i = 0; i < sourceRoots.size(); i++) {
            TeamCodeCompiler.SourceRoot r = sourceRoots.get(i);
            roots.add(new Root(i, r.dir().toAbsolutePath().normalize(), r.samples()));
        }
    }

    public List<Root> roots() {
        return roots;
    }

    /** Every Java file, TeamCode first, each root's files in path order. */
    public List<Entry> list() throws IOException {
        List<Entry> out = new ArrayList<>();
        for (Root r : roots) {
            if (!Files.isDirectory(r.dir())) continue;
            for (Path p : TeamCodeCompiler.javaFiles(r.dir())) {
                if (Files.isRegularFile(p)) out.add(new Entry(r.index(), relative(r, p)));
            }
        }
        return out;
    }

    public Content read(int root, String path) throws Refused, IOException {
        Root r = root(root);
        Path file = existing(r, path);
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_BYTES) throw new Refused(413, path + " is too big to edit here");
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        return new Content(text, hash(bytes), r.readOnly());
    }

    /**
     * Saves a file.
     *
     * @param text     its new text; line endings become the file's own (or, for a new file, those of
     *                 the files next to it)
     * @param baseHash the hash of the file as the editor last read it: the save is refused if it
     *                 has changed on disk since (someone saved it in another editor). Null creates a
     *                 new file, refused if it already exists.
     * @return the file and the hash of what was written
     */
    public Saved write(int root, String path, String text, String baseHash) throws Refused, IOException {
        // One save at a time (robots running the same folder have their own TeamCodeFiles): two
        // pages saving at once can't both pass the check and lose one of the saves.
        synchronized (WRITE_LOCK) {
            return writeLocked(root, path, text, baseHash);
        }
    }

    private static final Object WRITE_LOCK = new Object();

    private Saved writeLocked(int root, String path, String text, String baseHash) throws Refused, IOException {
        Root r = root(root);
        if (r.readOnly()) {
            throw new Refused(403, "The FTC samples are read-only here, as on the robot. Copy the code into a TeamCode file to change it.");
        }
        Path file = writable(r, path);
        byte[] old = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        if (baseHash == null && old != null) throw new Refused(409, path + " already exists", hash(old));
        if (baseHash != null && old == null) throw new Refused(409, path + " no longer exists", null);
        if (baseHash != null && !baseHash.equals(hash(old))) {
            throw new Refused(409, path + " was changed on disk since it was opened", hash(old));
        }
        String eol = old != null ? lineEnding(new String(old, StandardCharsets.UTF_8)) : siblingLineEnding(file.getParent(), r.dir());
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("﻿")) normalized = normalized.substring(1);
        if (!eol.equals("\n")) normalized = normalized.replace("\n", eol);
        byte[] body = normalized.getBytes(StandardCharsets.UTF_8);
        if (old != null && startsWithBom(old)) {
            byte[] withBom = new byte[body.length + BOM.length];
            System.arraycopy(BOM, 0, withBom, 0, BOM.length);
            System.arraycopy(body, 0, withBom, BOM.length, body.length);
            body = withBom;
        }
        if (body.length > MAX_BYTES) throw new Refused(413, path + " is too big to save here");
        Files.createDirectories(file.getParent());
        if (old == null) {
            // A new file is created, never written through something that turned up meanwhile
            // (a file, or a link pointing out of the source folder).
            try {
                Files.write(file, body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException e) {
                throw new Refused(409, path + " already exists", null);
            }
        } else {
            Files.write(file, body);
        }
        return new Saved(file, hash(body));
    }

    /** A saved file: where it is, and the hash of its bytes. */
    public record Saved(Path file, String hash) {
    }

    // ---- paths -------------------------------------------------------------------------------------

    private Root root(int index) throws Refused {
        if (index < 0 || index >= roots.size()) throw new Refused(404, "No source folder " + index);
        return roots.get(index);
    }

    /** An existing Java file inside the root, also after following links. */
    private Path existing(Root r, String path) throws Refused, IOException {
        Path file = inside(r, path);
        if (!Files.isRegularFile(file)) throw new Refused(404, path + " not found");
        if (!file.toRealPath().startsWith(r.dir().toRealPath())) throw new Refused(403, path + " is outside the source folder");
        return file;
    }

    /**
     * A file that may be written: existing, or a new one whose folders and name are Java
     * identifiers (a package path and a class name), inside the root also after following links.
     */
    private Path writable(Root r, String path) throws Refused, IOException {
        Path file = inside(r, path);
        if (Files.exists(file)) return existing(r, path);
        // A link to nothing: writing the "new" file would create the link's target, wherever it points.
        if (Files.isSymbolicLink(file)) throw new Refused(403, path + " is a link to a file that doesn't exist");
        String[] parts = path.replace('\\', '/').split("/");
        for (int i = 0; i < parts.length; i++) {
            String name = i == parts.length - 1 ? parts[i].substring(0, parts[i].length() - ".java".length()) : parts[i];
            if (!SEGMENT.matcher(name).matches()) {
                throw new Refused(400, "\"" + name + "\" can't be a " + (i == parts.length - 1 ? "class" : "package") + " name");
            }
        }
        if (!Files.isDirectory(r.dir())) throw new Refused(404, "The source folder " + r.dir() + " doesn't exist");
        Path existingAncestor = file.getParent();
        while (existingAncestor != null && !Files.exists(existingAncestor)) existingAncestor = existingAncestor.getParent();
        if (existingAncestor == null || !existingAncestor.toRealPath().startsWith(r.dir().toRealPath())) {
            throw new Refused(403, path + " is outside the source folder");
        }
        return file;
    }

    /** The path resolved in the root, refused unless it is a .java file that stays inside it. */
    private static Path inside(Root r, String path) throws Refused {
        if (path == null || path.isBlank() || !path.endsWith(".java")) throw new Refused(400, "Not a Java file: " + path);
        Path rel;
        try {
            rel = Paths.get(path.replace('\\', '/'));
        } catch (RuntimeException e) {
            throw new Refused(400, "Not a file path: " + path);
        }
        if (rel.isAbsolute() || rel.getRoot() != null) throw new Refused(400, "Not a path inside the source folder: " + path);
        Path file = r.dir().resolve(rel).normalize();
        if (!file.startsWith(r.dir()) || file.equals(r.dir())) throw new Refused(400, "Not a path inside the source folder: " + path);
        return file;
    }

    private static String relative(Root r, Path p) {
        return r.dir().relativize(p).toString().replace('\\', '/');
    }

    // ---- line endings and hashes ----------------------------------------------------------------

    static String lineEnding(String text) {
        int crlf = 0, lf = 0;
        for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
            if (i > 0 && text.charAt(i - 1) == '\r') crlf++;
            else lf++;
        }
        if (crlf == 0 && lf == 0) return System.lineSeparator();
        return crlf > lf ? "\r\n" : "\n";
    }

    /** What the Java files next to a new file use (in its folder, else anywhere in the root). */
    private static String siblingLineEnding(Path dir, Path root) {
        for (Path d : new Path[]{dir, root}) {
            if (d == null || !Files.isDirectory(d)) continue;
            try (Stream<Path> s = d.equals(root) ? Files.walk(root) : Files.list(d)) {
                Path sibling = s.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p)).findFirst().orElse(null);
                if (sibling != null) return lineEnding(Files.readString(sibling, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException ignored) {
                // unreadable: try the next place
            }
        }
        return System.lineSeparator();
    }

    private static boolean startsWithBom(byte[] b) {
        return b.length >= 3 && b[0] == BOM[0] && b[1] == BOM[1] && b[2] == BOM[2];
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
