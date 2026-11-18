package ftcsim.cad;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The triangles of a STEP file, as the Driver Station page tessellated them with OpenCascade
 * (occt-import-js), saved next to the STEP file as {@code <name>.step.cadmesh}. It records the
 * SHA-256 of the STEP file it came from, so an edited STEP file is converted again.
 *
 * <p>Layout (little-endian): the 8 bytes {@code FTCMESH1}; a uint32 length and that many bytes of
 * UTF-8 JSON {@code {"source", "sha256", "converter", "parts": [{"path", "color": "#rrggbb",
 * "vertices", "triangles"}]}}; zero padding to a multiple of 4; then for each part its float32
 * positions (meters) followed by its uint32 indices.
 */
public final class CadMeshFile {
    public static final String MAGIC = "FTCMESH1";
    public static final String SUFFIX = ".cadmesh";
    /** Largest file accepted from a browser. */
    public static final long MAX_BYTES = 400L << 20;

    /** What the header says, without the triangles. */
    public record Header(String source, String sha256, String converter, int parts, long triangles) {
    }

    private CadMeshFile() {
    }

    /** Where the converted mesh of {@code step} lives. */
    public static Path cacheFor(Path step) {
        return step.resolveSibling(step.getFileName().toString() + SUFFIX);
    }

    /**
     * The STEP file's fingerprint: SHA-256 of its bytes with carriage returns left out, so the
     * same file checked out with Windows or Unix line endings needs no new conversion.
     */
    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                int start = 0;
                for (int i = 0; i < n; i++) {
                    if (buf[i] != 13) continue;   // carriage return
                    md.update(buf, start, i - start);
                    start = i + 1;
                }
                md.update(buf, start, n - start);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Header header(byte[] data) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        JsonObject h = readHeader(bb);
        try {
            JsonArray parts = h.getAsJsonArray("parts");
            long tris = 0;
            for (JsonElement e : parts) tris += e.getAsJsonObject().get("triangles").getAsLong();
            return new Header(str(h, "source"), str(h, "sha256"), str(h, "converter"), parts.size(), tris);
        } catch (RuntimeException e) {
            throw new IOException("malformed mesh file header: " + e.getMessage(), e);
        }
    }

    public static List<MeshPart> read(Path file) throws IOException {
        return read(Files.readAllBytes(file));
    }

    /** Parses and checks a mesh file: every index must point at a vertex of its own part. */
    public static List<MeshPart> read(byte[] data) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        JsonObject h = readHeader(bb);
        List<MeshPart> out = new ArrayList<>();
        try {
            for (JsonElement e : h.getAsJsonArray("parts")) {
                JsonObject p = e.getAsJsonObject();
                int nv = p.get("vertices").getAsInt(), nt = p.get("triangles").getAsInt();
                if (nv < 0 || nt < 0 || (long) nv * 12 + (long) nt * 12 > bb.remaining()) throw new IOException("truncated mesh file");
                float[] pos = new float[nv * 3];
                bb.asFloatBuffer().get(pos);
                bb.position(bb.position() + pos.length * 4);
                int[] idx = new int[nt * 3];
                bb.asIntBuffer().get(idx);
                bb.position(bb.position() + idx.length * 4);
                for (int i : idx) if (i < 0 || i >= nv) throw new IOException("bad vertex index in part " + str(p, "path"));
                for (float f : pos) if (!Float.isFinite(f)) throw new IOException("bad coordinate in part " + str(p, "path"));
                out.add(new MeshPart(str(p, "path"), parseColor(str(p, "color")), pos, idx));
            }
        } catch (RuntimeException e) {
            throw new IOException("malformed mesh file: " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * Writes {@code data} to {@code file} through a temporary file of its own, so readers never see
     * half of it and two writers (two pages sending the same conversion) don't share one.
     */
    public static void writeAtomically(Path file, byte[] data) throws IOException {
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), file.getFileName() + ".", ".tmp");
        try {
            Files.write(tmp, data);
            for (int attempt = 0; ; attempt++) {
                try {
                    try {
                        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return;
                } catch (java.nio.file.FileSystemException e) {
                    // Windows: someone is reading the file right now (a robot loading it); try again.
                    if (attempt >= 20) throw e;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Encodes parts in this format (used by tests and by the URDF path's browser model). */
    public static byte[] encode(String source, String sha256, String converter, List<MeshPart> parts) {
        JsonObject h = new JsonObject();
        h.addProperty("source", source);
        h.addProperty("sha256", sha256);
        h.addProperty("converter", converter);
        JsonArray arr = new JsonArray();
        long body = 0;
        for (MeshPart p : parts) {
            JsonObject o = new JsonObject();
            o.addProperty("path", p.path());
            o.addProperty("color", String.format("#%06x", p.rgb() & 0xffffff));
            o.addProperty("vertices", p.positions().length / 3);
            o.addProperty("triangles", p.indices().length / 3);
            arr.add(o);
            body += p.positions().length * 4L + p.indices().length * 4L;
        }
        h.add("parts", arr);
        byte[] json = h.toString().getBytes(StandardCharsets.UTF_8);
        int pad = (4 - (8 + 4 + json.length) % 4) % 4;
        ByteBuffer bb = ByteBuffer.allocate((int) (8 + 4 + json.length + pad + body)).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(MAGIC.getBytes(StandardCharsets.US_ASCII));
        bb.putInt(json.length);
        bb.put(json);
        for (int i = 0; i < pad; i++) bb.put((byte) 0);
        for (MeshPart p : parts) {
            for (float f : p.positions()) bb.putFloat(f);
            for (int i : p.indices()) bb.putInt(i);
        }
        return bb.array();
    }

    private static JsonObject readHeader(ByteBuffer bb) throws IOException {
        if (bb.remaining() < 12) throw new IOException("not a mesh file");
        byte[] magic = new byte[8];
        bb.get(magic);
        if (!MAGIC.equals(new String(magic, StandardCharsets.US_ASCII))) throw new IOException("not a mesh file");
        int len = bb.getInt();
        if (len <= 0 || len > bb.remaining()) throw new IOException("truncated mesh file");
        byte[] json = new byte[len];
        bb.get(json);
        int pad = (4 - (8 + 4 + len) % 4) % 4;
        bb.position(bb.position() + Math.min(pad, bb.remaining()));
        try {
            JsonObject h = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!h.has("parts")) throw new IOException("mesh file has no parts");
            return h;
        } catch (RuntimeException e) {
            throw new IOException("malformed mesh file header", e);
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    static int parseColor(String css) {
        try {
            return Integer.parseInt(css.replace("#", ""), 16) & 0xffffff;
        } catch (RuntimeException e) {
            return 0x9aa0a6;
        }
    }
}
