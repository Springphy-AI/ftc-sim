package ftcsim.cad;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A URDF robot description, as CAD exporters write it (onshape-to-robot, fusion2urdf, the
 * SolidWorks URDF exporter): links with their mass properties and meshes, joints between them.
 * Geometry is resolved to triangles in each link's own frame.
 */
public final class Urdf {

    public static final class Link {
        public final String name;
        /** Mass in kg, or NaN when the URDF gives none. */
        public double mass = Double.NaN;
        /** Center of mass and inertia tensor (row-major, about the center of mass) in the link frame. */
        public double[] com = {0, 0, 0};
        public double[] inertia;
        public final List<MeshPart> visuals = new ArrayList<>();
        public final List<MeshPart> collisions = new ArrayList<>();

        Link(String name) {
            this.name = name;
        }
    }

    public static final class Joint {
        public String name, type, parent, child;
        /** Child link frame in the parent link frame, at joint position 0. */
        public double[] origin = Geom3.identityTransform();
        /** Joint axis in the child (joint) frame. */
        public double[] axis = {1, 0, 0};
        public double lower = Double.NaN, upper = Double.NaN;
        public String mimicJoint;
        public double mimicMultiplier = 1, mimicOffset = 0;
    }

    public final String name;
    public final Map<String, Link> links = new LinkedHashMap<>();
    public final Map<String, Joint> joints = new LinkedHashMap<>();
    public final List<String> warnings = new ArrayList<>();

    private Urdf(String name) {
        this.name = name;
    }

    /** The link that is no joint's child. */
    public String rootLink() {
        java.util.Set<String> children = new java.util.HashSet<>();
        for (Joint j : joints.values()) children.add(j.child);
        for (String l : links.keySet()) if (!children.contains(l)) return l;
        throw new IllegalArgumentException("the URDF has no root link (its joints form a loop)");
    }

    public static Urdf load(Path file) throws IOException {
        Document doc;
        try (InputStream in = Files.newInputStream(file)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            doc = b.parse(in);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("can't read " + file.getFileName() + ": " + e.getMessage(), e);
        }
        Element robot = doc.getDocumentElement();
        if (!"robot".equals(robot.getTagName())) throw new IOException(file.getFileName() + " is not a URDF (no <robot>)");
        Urdf u = new Urdf(robot.getAttribute("name"));
        Map<String, Integer> materials = new HashMap<>();
        for (Element m : children(robot, "material")) {
            Integer c = color(m);
            if (c != null) materials.put(m.getAttribute("name"), c);
        }
        Map<Path, MeshFiles.Mesh> meshCache = new HashMap<>();
        for (Element le : children(robot, "link")) {
            Link link = new Link(le.getAttribute("name"));
            Element inertial = child(le, "inertial");
            if (inertial != null) {
                double[] o = origin(child(inertial, "origin"));
                Element mass = child(inertial, "mass");
                if (mass != null) link.mass = num(mass, "value", Double.NaN);
                link.com = Geom3.trans(o);
                Element in = child(inertial, "inertia");
                if (in != null) {
                    double ixx = num(in, "ixx", 0), ixy = num(in, "ixy", 0), ixz = num(in, "ixz", 0);
                    double iyy = num(in, "iyy", 0), iyz = num(in, "iyz", 0), izz = num(in, "izz", 0);
                    double[] local = {ixx, ixy, ixz, ixy, iyy, iyz, ixz, iyz, izz};
                    double[] r = Geom3.rot(o);   // inertia given in the inertial frame
                    link.inertia = Geom3.matMul(Geom3.matMul(r, local), Geom3.transpose(r));
                }
            }
            int k = 0;
            for (Element ve : children(le, "visual")) {
                MeshPart p = geometry(file, ve, link.name + "/" + (ve.hasAttribute("name") ? ve.getAttribute("name") : "visual" + k++),
                        materialColor(ve, materials), meshCache, u.warnings);
                if (p != null) link.visuals.add(p);
            }
            k = 0;
            for (Element ce : children(le, "collision")) {
                MeshPart p = geometry(file, ce, link.name + "/collision" + k++, 0x9aa0a6, meshCache, u.warnings);
                if (p != null) link.collisions.add(p);
            }
            u.links.put(link.name, link);
        }
        for (Element je : children(robot, "joint")) {
            Joint j = new Joint();
            j.name = je.getAttribute("name");
            j.type = je.getAttribute("type");
            Element parent = child(je, "parent"), childEl = child(je, "child");
            if (parent == null || childEl == null) throw new IOException("joint " + j.name + " needs a parent and a child");
            j.parent = parent.getAttribute("link");
            j.child = childEl.getAttribute("link");
            if (!u.links.containsKey(j.parent) || !u.links.containsKey(j.child)) {
                throw new IOException("joint " + j.name + " refers to a link that isn't in the file");
            }
            j.origin = origin(child(je, "origin"));
            Element axis = child(je, "axis");
            if (axis != null) j.axis = Geom3.normalize(vec(axis.getAttribute("xyz"), new double[]{1, 0, 0}));
            Element limit = child(je, "limit");
            if (limit != null) {
                j.lower = num(limit, "lower", Double.NaN);
                j.upper = num(limit, "upper", Double.NaN);
            }
            Element mimic = child(je, "mimic");
            if (mimic != null) {
                j.mimicJoint = mimic.getAttribute("joint");
                j.mimicMultiplier = num(mimic, "multiplier", 1);
                j.mimicOffset = num(mimic, "offset", 0);
            }
            u.joints.put(j.name, j);
        }
        if (u.links.isEmpty()) throw new IOException(file.getFileName() + " has no links");
        return u;
    }

    private static MeshPart geometry(Path urdf, Element el, String path, int rgb, Map<Path, MeshFiles.Mesh> cache, List<String> warnings) throws IOException {
        Element g = child(el, "geometry");
        if (g == null) return null;
        double[] tf = origin(child(el, "origin"));
        MeshFiles.Mesh mesh = null;
        Element m;
        if ((m = child(g, "mesh")) != null) {
            Path file = resolveMesh(urdf, m.getAttribute("filename"));
            if (file == null) {
                warnings.add("mesh not found: " + m.getAttribute("filename"));
                return null;
            }
            try {
                mesh = cache.get(file);
                if (mesh == null) {
                    mesh = MeshFiles.read(file);
                    cache.put(file, mesh);
                }
            } catch (IOException e) {
                warnings.add(e.getMessage());
                return null;
            }
            double[] s = vec(m.getAttribute("scale"), new double[]{1, 1, 1});
            float[] p = mesh.positions().clone();
            for (int i = 0; i < p.length; i += 3) {
                p[i] *= (float) s[0];
                p[i + 1] *= (float) s[1];
                p[i + 2] *= (float) s[2];
            }
            mesh = new MeshFiles.Mesh(p, mesh.indices());
        } else if ((m = child(g, "box")) != null) {
            double[] s = vec(m.getAttribute("size"), new double[]{0.1, 0.1, 0.1});
            mesh = MeshFiles.box(s[0], s[1], s[2]);
        } else if ((m = child(g, "cylinder")) != null) {
            mesh = MeshFiles.cylinder(num(m, "radius", 0.05), num(m, "length", 0.1), 24);
        } else if ((m = child(g, "sphere")) != null) {
            mesh = MeshFiles.sphere(num(m, "radius", 0.05), 8, 16);
        }
        if (mesh == null) return null;
        return new MeshPart(path, rgb, Geom3.apply(tf, mesh.positions()), mesh.indices());
    }

    /**
     * Finds a mesh file: {@code package://pkg/dir/file.stl} is looked up relative to the URDF's
     * folder and its parents (exporters put the URDF next to, or inside, the package folder).
     */
    static Path resolveMesh(Path urdf, String filename) {
        if (filename == null || filename.isEmpty()) return null;
        Path dir = urdf.toAbsolutePath().getParent();
        List<String> candidates = new ArrayList<>();
        if (filename.startsWith("package://")) {
            String rest = filename.substring("package://".length());
            candidates.add(rest);
            int slash = rest.indexOf('/');
            if (slash >= 0) candidates.add(rest.substring(slash + 1));
        } else if (filename.startsWith("file://")) {
            Path p = Path.of(filename.substring("file://".length()));
            return Files.isRegularFile(p) ? p : null;
        } else {
            candidates.add(filename);
        }
        for (Path d = dir; d != null; d = d.getParent()) {
            for (String c : candidates) {
                Path p = d.resolve(c).normalize();
                if (Files.isRegularFile(p)) return p;
            }
            if (d.equals(dir.getParent())) break;   // the URDF's folder and its parent only
        }
        return null;
    }

    private static Integer materialColor(Element visual, Map<String, Integer> materials) {
        Element m = child(visual, "material");
        if (m == null) return 0x9aa0a6;
        Integer c = color(m);
        if (c != null) return c;
        return materials.getOrDefault(m.getAttribute("name"), 0x9aa0a6);
    }

    private static Integer color(Element material) {
        Element c = child(material, "color");
        if (c == null) return null;
        double[] rgba = vec(c.getAttribute("rgba"), null);
        if (rgba == null || rgba.length < 3) return null;
        int r = (int) Math.round(Math.max(0, Math.min(1, rgba[0])) * 255);
        int g = (int) Math.round(Math.max(0, Math.min(1, rgba[1])) * 255);
        int b = (int) Math.round(Math.max(0, Math.min(1, rgba[2])) * 255);
        return (r << 16) | (g << 8) | b;
    }

    private static double[] origin(Element o) {
        if (o == null) return Geom3.identityTransform();
        double[] xyz = vec(o.getAttribute("xyz"), new double[]{0, 0, 0});
        double[] rpy = vec(o.getAttribute("rpy"), new double[]{0, 0, 0});
        return Geom3.transform(Geom3.rpy(rpy[0], rpy[1], rpy[2]), xyz);
    }

    private static double[] vec(String s, double[] dflt) {
        if (s == null || s.isBlank()) return dflt;
        String[] f = s.trim().split("\\s+");
        double[] out = new double[f.length];
        for (int i = 0; i < f.length; i++) out[i] = Double.parseDouble(f[i]);
        return out.length >= 3 || dflt == null ? out : dflt;
    }

    private static double num(Element e, String attr, double dflt) {
        String s = e.getAttribute(attr);
        if (s == null || s.isBlank()) return dflt;
        return Double.parseDouble(s.trim());
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element e && e.getTagName().equals(tag)) out.add(e);
        }
        return out;
    }

    private static Element child(Element parent, String tag) {
        if (parent == null) return null;
        List<Element> l = children(parent, tag);
        return l.isEmpty() ? null : l.get(0);
    }
}
