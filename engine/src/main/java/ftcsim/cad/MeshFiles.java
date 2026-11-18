package ftcsim.cad;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Readers for the mesh files URDF exporters write: STL (binary or ASCII) and Wavefront OBJ. */
public final class MeshFiles {
    private MeshFiles() {
    }

    /** Positions and indices of a mesh file, in the file's own units. */
    public record Mesh(float[] positions, int[] indices) {
    }

    public static Mesh read(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".stl")) return stl(Files.readAllBytes(file));
        if (name.endsWith(".obj")) return obj(Files.readString(file, StandardCharsets.ISO_8859_1));
        throw new IOException("unsupported mesh format " + file.getFileName() + " (use STL or OBJ)");
    }

    public static Mesh stl(byte[] data) throws IOException {
        if (data.length >= 84) {
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            long n = bb.getInt(80) & 0xffffffffL;
            if (84 + n * 50 == data.length) return binaryStl(bb, (int) n);
        }
        String text = new String(data, StandardCharsets.ISO_8859_1);
        if (text.stripLeading().startsWith("solid")) return asciiStl(text);
        throw new IOException("not an STL file");
    }

    private static Mesh binaryStl(ByteBuffer bb, int n) {
        float[] pos = new float[n * 9];
        int[] idx = new int[n * 3];
        bb.position(84);
        for (int t = 0; t < n; t++) {
            bb.position(bb.position() + 12);   // facet normal
            for (int k = 0; k < 9; k++) pos[t * 9 + k] = bb.getFloat();
            bb.position(bb.position() + 2);    // attribute byte count
            idx[t * 3] = t * 3;
            idx[t * 3 + 1] = t * 3 + 1;
            idx[t * 3 + 2] = t * 3 + 2;
        }
        return new Mesh(pos, idx);
    }

    private static Mesh asciiStl(String text) throws IOException {
        List<float[]> verts = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (!s.startsWith("vertex")) continue;
            String[] f = s.split("\\s+");
            if (f.length < 4) throw new IOException("bad STL vertex line: " + s);
            verts.add(new float[]{Float.parseFloat(f[1]), Float.parseFloat(f[2]), Float.parseFloat(f[3])});
        }
        if (verts.size() % 3 != 0) throw new IOException("STL vertices are not whole triangles");
        float[] pos = new float[verts.size() * 3];
        int[] idx = new int[verts.size()];
        for (int i = 0; i < verts.size(); i++) {
            System.arraycopy(verts.get(i), 0, pos, i * 3, 3);
            idx[i] = i;
        }
        return new Mesh(pos, idx);
    }

    public static Mesh obj(String text) throws IOException {
        List<float[]> verts = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (s.startsWith("v ")) {
                String[] f = s.split("\\s+");
                verts.add(new float[]{Float.parseFloat(f[1]), Float.parseFloat(f[2]), Float.parseFloat(f[3])});
            } else if (s.startsWith("f ")) {
                String[] f = s.split("\\s+");
                int[] poly = new int[f.length - 1];
                for (int i = 1; i < f.length; i++) {
                    int v = Integer.parseInt(f[i].split("/")[0]);
                    poly[i - 1] = v < 0 ? verts.size() + v : v - 1;
                }
                for (int i = 1; i + 1 < poly.length; i++) {   // fan
                    idx.add(poly[0]);
                    idx.add(poly[i]);
                    idx.add(poly[i + 1]);
                }
            }
        }
        float[] pos = new float[verts.size() * 3];
        for (int i = 0; i < verts.size(); i++) System.arraycopy(verts.get(i), 0, pos, i * 3, 3);
        int[] ix = idx.stream().mapToInt(Integer::intValue).toArray();
        for (int i : ix) if (i < 0 || i >= verts.size()) throw new IOException("OBJ face refers to a missing vertex");
        return new Mesh(pos, ix);
    }

    // ---- primitives (URDF box, cylinder, sphere), centered on the origin ----------------------

    public static Mesh box(double sx, double sy, double sz) {
        float x = (float) sx / 2, y = (float) sy / 2, z = (float) sz / 2;
        float[] p = {-x, -y, -z, x, -y, -z, x, y, -z, -x, y, -z, -x, -y, z, x, -y, z, x, y, z, -x, y, z};
        int[] f = {0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7};
        return new Mesh(p, f);
    }

    /** A cylinder along z, as URDF defines it. */
    public static Mesh cylinder(double radius, double length, int sides) {
        float[] p = new float[(sides * 2 + 2) * 3];
        float h = (float) length / 2;
        for (int k = 0; k < sides; k++) {
            double t = 2 * Math.PI * k / sides;
            float x = (float) (radius * Math.cos(t)), y = (float) (radius * Math.sin(t));
            p[k * 3] = x;
            p[k * 3 + 1] = y;
            p[k * 3 + 2] = -h;
            p[(sides + k) * 3] = x;
            p[(sides + k) * 3 + 1] = y;
            p[(sides + k) * 3 + 2] = h;
        }
        int c0 = sides * 2, c1 = sides * 2 + 1;
        p[c0 * 3 + 2] = -h;
        p[c1 * 3 + 2] = h;
        int[] f = new int[sides * 12];
        int i = 0;
        for (int k = 0; k < sides; k++) {
            int k2 = (k + 1) % sides;
            f[i++] = k; f[i++] = k2; f[i++] = sides + k2;
            f[i++] = k; f[i++] = sides + k2; f[i++] = sides + k;
            f[i++] = c0; f[i++] = k2; f[i++] = k;
            f[i++] = c1; f[i++] = sides + k; f[i++] = sides + k2;
        }
        return new Mesh(p, f);
    }

    public static Mesh sphere(double radius, int rings, int segments) {
        List<float[]> v = new ArrayList<>();
        for (int r = 0; r <= rings; r++) {
            double phi = Math.PI * r / rings;
            for (int s = 0; s < segments; s++) {
                double th = 2 * Math.PI * s / segments;
                v.add(new float[]{(float) (radius * Math.sin(phi) * Math.cos(th)), (float) (radius * Math.sin(phi) * Math.sin(th)), (float) (radius * Math.cos(phi))});
            }
        }
        List<Integer> f = new ArrayList<>();
        for (int r = 0; r < rings; r++) {
            for (int s = 0; s < segments; s++) {
                int a = r * segments + s, b = r * segments + (s + 1) % segments;
                int c = (r + 1) * segments + s, d = (r + 1) * segments + (s + 1) % segments;
                f.add(a); f.add(c); f.add(d);
                f.add(a); f.add(d); f.add(b);
            }
        }
        float[] p = new float[v.size() * 3];
        for (int i = 0; i < v.size(); i++) System.arraycopy(v.get(i), 0, p, i * 3, 3);
        return new Mesh(p, f.stream().mapToInt(Integer::intValue).toArray());
    }
}
