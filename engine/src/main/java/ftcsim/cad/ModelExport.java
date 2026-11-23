package ftcsim.cad;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** A {@link RobotModel} in the shapes its viewers need: the browser's 3D view and the webcam renderer. */
final class ModelExport {
    /** Triangle budget per robot for the webcam renderer. */
    static final int VISION_TRIANGLES = 30_000;

    private ModelExport() {
    }

    /** Merges a group's parts by color: fewer, bigger meshes draw faster. */
    static List<MeshPart> byColor(List<MeshPart> parts) {
        Map<Integer, List<MeshPart>> byRgb = new LinkedHashMap<>();
        for (MeshPart p : parts) byRgb.computeIfAbsent(p.rgb(), k -> new ArrayList<>()).add(p);
        List<MeshPart> out = new ArrayList<>();
        for (Map.Entry<Integer, List<MeshPart>> e : byRgb.entrySet()) {
            int nv = 0, ni = 0;
            for (MeshPart p : e.getValue()) {
                nv += p.positions().length;
                ni += p.indices().length;
            }
            float[] pos = new float[nv];
            int[] idx = new int[ni];
            int vo = 0, io = 0;
            for (MeshPart p : e.getValue()) {
                System.arraycopy(p.positions(), 0, pos, vo, p.positions().length);
                int base = vo / 3;
                for (int i : p.indices()) idx[io++] = i + base;
                vo += p.positions().length;
            }
            out.add(new MeshPart(String.format("#%06x", e.getKey()), e.getKey(), pos, idx));
        }
        return out;
    }

    /**
     * The browser model, little-endian: "FTCMODL1", a uint32 length and that much UTF-8 JSON
     * {@code {"groups": [{"name", "kind", "parent", "meshes": [{"color", "vertices", "triangles"}]}]}},
     * zero padding to a multiple of 4, then each mesh's float32 positions and uint32 indices.
     * Positions are in the robot frame with every joint at its CAD position.
     */
    static byte[] browserModel(RobotModel model) {
        JsonObject h = new JsonObject();
        JsonArray groups = new JsonArray();
        List<MeshPart> meshes = new ArrayList<>();
        for (RobotModel.Group g : model.groups) {
            JsonObject go = new JsonObject();
            go.addProperty("name", g.name);
            go.addProperty("kind", g.kind.name().toLowerCase());
            go.addProperty("parent", g.parentIndex);
            JsonArray ms = new JsonArray();
            for (MeshPart m : byColor(g.parts)) {
                JsonObject mo = new JsonObject();
                mo.addProperty("color", String.format("#%06x", m.rgb()));
                mo.addProperty("vertices", m.positions().length / 3);
                mo.addProperty("triangles", m.indices().length / 3);
                ms.add(mo);
                meshes.add(m);
            }
            go.add("meshes", ms);
            groups.add(go);
        }
        h.add("groups", groups);
        byte[] json = h.toString().getBytes(StandardCharsets.UTF_8);
        int pad = (4 - (8 + 4 + json.length) % 4) % 4;
        long body = 0;
        for (MeshPart m : meshes) body += 4L * (m.positions().length + m.indices().length);
        ByteBuffer bb = ByteBuffer.allocate((int) (12 + json.length + pad + body)).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("FTCMODL1".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(json.length);
        bb.put(json);
        for (int i = 0; i < pad; i++) bb.put((byte) 0);
        for (MeshPart m : meshes) {
            bb.asFloatBuffer().put(m.positions());
            bb.position(bb.position() + m.positions().length * 4);
            bb.asIntBuffer().put(m.indices());
            bb.position(bb.position() + m.indices().length * 4);
        }
        return bb.array();
    }

    /** Each group's triangles, simplified to the webcam budget, merged by color. */
    static List<List<MeshPart>> visionMesh(RobotModel model, List<double[]> cameras) {
        long total = 0;
        for (RobotModel.Group g : model.groups) for (MeshPart p : g.parts) total += p.triangleCount();
        double cell = 0.004;
        List<List<MeshPart>> out;
        do {
            out = new ArrayList<>();
            long n = 0;
            for (RobotModel.Group g : model.groups) {
                List<MeshPart> simple = new ArrayList<>();
                for (MeshPart m : byColor(g.parts)) {
                    MeshPart s = total <= VISION_TRIANGLES ? m : MeshMath.simplify(m, cell);
                    if (s.triangleCount() > 0) simple.add(s);
                    n += s.triangleCount();
                }
                out.add(simple);
            }
            if (n <= VISION_TRIANGLES || total <= VISION_TRIANGLES) break;
            cell *= 1.5;
        } while (cell < 0.2);
        if (!cameras.isEmpty() && !out.isEmpty()) {
            List<MeshPart> base = new ArrayList<>();
            for (MeshPart m : out.get(0)) base.add(clearAround(m, cameras, RobotModel.CAMERA_CLEARANCE_M));
            out.set(0, base);
        }
        return out;
    }

    /**
     * {@code m} without the triangles that come within {@code radius} of any of {@code points}
     * (anywhere on the triangle: a big panel's center can be far from a lens its edge touches).
     */
    static MeshPart clearAround(MeshPart m, List<double[]> points, double radius) {
        float[] p = m.positions();
        int[] ix = m.indices();
        int[] keep = new int[ix.length];
        int n = 0;
        for (int t = 0; t < ix.length; t += 3) {
            double[][] v = new double[3][];
            for (int c = 0; c < 3; c++) {
                int i = ix[t + c] * 3;
                v[c] = new double[]{p[i], p[i + 1], p[i + 2]};
            }
            boolean near = false;
            for (double[] q : points) {
                if (Geom3.distanceSqToTriangle(q, v[0], v[1], v[2]) < radius * radius) {
                    near = true;
                    break;
                }
            }
            if (near) continue;
            keep[n++] = ix[t];
            keep[n++] = ix[t + 1];
            keep[n++] = ix[t + 2];
        }
        return n == ix.length ? m : new MeshPart(m.path(), m.rgb(), p, java.util.Arrays.copyOf(keep, n));
    }
}
