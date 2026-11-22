package ftcsim.cad;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;

import ftcsim.config.RobotConfig;

/**
 * Builds a {@link RobotModel} from CAD: the triangles of a STEP file together with the robot
 * file's {@code cad.links} (which parts move, on what axis, driven by what), or a URDF with its
 * own links, joints and mass properties.
 */
public final class ModelBuilder {
    /** Parts smaller than this (bounding-box diagonal) don't get collision boxes: screws, nuts, spacers. */
    static final double MIN_COLLIDER_SIZE_M = 0.02;
    /** Collision boxes are at least this thick, so fast balls don't pass through thin plates. */
    static final double MIN_BOX_THICKNESS_M = 0.006;
    static final int MAX_BOXES_PER_GROUP = 160;
    /** Wheel bodies in the physics weigh this much unless the CAD says otherwise. */
    public static final double DEFAULT_WHEEL_MASS_KG = 0.25;
    private static final Pattern WHEEL_NAME = AutoDetect.WHEEL_NAME;

    private final RobotConfig cfg;
    private final RobotConfig.Cad cad;
    private final List<String> notes = new ArrayList<>();
    private final List<RobotModel.Group> groups = new ArrayList<>();

    private ModelBuilder(RobotConfig cfg) {
        this.cfg = cfg;
        this.cad = cfg.cad;
    }

    /** Wheel names for a drive type, in the order the physics creates them. */
    public static List<String> wheelKeys(String driveType) {
        return switch (driveType) {
            case "tank" -> List.of("left_front", "right_front", "left_middle", "right_middle", "left_back", "right_back");
            case "tank4" -> List.of("left_front", "right_front", "left_back", "right_back");
            default -> List.of("front_left", "front_right", "back_left", "back_right");
        };
    }

    /** Rotation from CAD axes to robot axes for the given forward and up directions ("+x", "-z"...). */
    public static double[] axesRotation(String forward, String up) {
        double[] f = direction(forward), u = direction(up);
        if (Math.abs(Geom3.dot(f, u)) > 1e-9) throw new IllegalArgumentException("cad forward and up must be different axes");
        double[] l = Geom3.cross(u, f);
        return new double[]{f[0], f[1], f[2], l[0], l[1], l[2], u[0], u[1], u[2]};
    }

    /** A cad.forward or cad.up value, or null when it is left to work out ("auto" or nothing). */
    static String given(String axis) {
        return axis == null || axis.isBlank() || "auto".equalsIgnoreCase(axis.trim()) ? null : axis;
    }

    static double[] direction(String s) {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        double sign = t.startsWith("-") ? -1 : 1;
        t = t.replace("+", "").replace("-", "");
        return switch (t) {
            case "x" -> new double[]{sign, 0, 0};
            case "y" -> new double[]{0, sign, 0};
            case "z" -> new double[]{0, 0, sign};
            default -> throw new IllegalArgumentException("cad axis must be +x, -x, +y, -y, +z or -z, not \"" + s + "\"");
        };
    }

    /** Whether a part path matches a name pattern: * and ? wildcards, any assembly level, any case. */
    public static boolean matches(String path, String pattern) {
        Pattern p = Pattern.compile(glob(pattern), Pattern.CASE_INSENSITIVE);
        if (pattern.contains("/")) return p.matcher(path).matches();
        for (String seg : path.split("/")) if (p.matcher(seg).matches()) return true;
        return false;
    }

    private static boolean matchesAny(String path, List<String> patterns) {
        if (patterns == null) return false;
        for (String p : patterns) if (matches(path, p)) return true;
        return false;
    }

    private static String glob(String pattern) {
        StringBuilder sb = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return sb.toString();
    }

    // =============================================================================================
    // STEP
    // =============================================================================================

    /** A robot from the parts of a STEP file (CAD coordinates, meters) and the robot file's cad section. */
    public static RobotModel fromStep(RobotConfig cfg, List<MeshPart> cadParts, String fileName) {
        return new ModelBuilder(cfg).buildStep(cadParts, fileName);
    }

    private RobotModel buildStep(List<MeshPart> cadParts, String fileName) {
        List<MeshPart> raw = new ArrayList<>();   // CAD coordinates
        int ignored = 0;
        for (MeshPart p : cadParts) {
            if (matchesAny(p.path(), cad.ignore)) {
                ignored++;
                continue;
            }
            if (p.triangleCount() == 0) continue;
            raw.add(p);
        }
        if (ignored > 0) notes.add(ignored + " parts left out (cad.ignore)");
        if (raw.isEmpty()) throw new IllegalArgumentException(fileName + " has no parts");

        RobotModel.Group base = new RobotModel.Group("base", RobotModel.Kind.BASE);
        groups.add(base);
        // links, in the order the robot file lists them (a parent before its children)
        Map<String, RobotModel.Group> byName = new LinkedHashMap<>();
        Map<RobotModel.Group, RobotConfig.CadLink> linkCfg = new LinkedHashMap<>();
        for (RobotConfig.CadLink l : cad.links) {
            if (l.name == null || l.name.isBlank()) throw new IllegalArgumentException("every cad link needs a name");
            if (byName.containsKey(l.name) || "base".equals(l.name)) throw new IllegalArgumentException("two cad links are called " + l.name);
            String type = jointType(l);
            if (!List.of("revolute", "prismatic", "continuous").contains(type)) {
                throw new IllegalArgumentException("cad link " + l.name + ": joint must be revolute, prismatic or continuous");
            }
            RobotModel.Group g = new RobotModel.Group(l.name, "continuous".equals(type) ? RobotModel.Kind.SPINNER : RobotModel.Kind.LINK);
            g.jointType = type;
            String parent = l.parent == null ? "base" : l.parent;
            if (!"base".equals(parent)) {
                RobotModel.Group pg = byName.get(parent);
                if (pg == null) throw new IllegalArgumentException("cad link " + l.name + ": parent " + parent + " must be listed before it");
                if (pg.kind != RobotModel.Kind.LINK) throw new IllegalArgumentException("cad link " + l.name + ": parent " + parent + " is a continuous joint");
                g.parentIndex = groups.indexOf(pg);
            }
            groups.add(g);
            byName.put(l.name, g);
            linkCfg.put(g, l);
        }

        // which parts belong to which link
        Map<Integer, RobotModel.Group> owner = new HashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            for (Map.Entry<RobotModel.Group, RobotConfig.CadLink> e : linkCfg.entrySet()) {
                if (matchesAny(raw.get(i).path(), linkParts(e.getValue()))) {
                    owner.put(i, e.getKey());
                    break;
                }
            }
        }
        for (Map.Entry<RobotModel.Group, RobotConfig.CadLink> e : linkCfg.entrySet()) {
            if (!owner.containsValue(e.getKey())) {
                throw new IllegalArgumentException("cad link " + e.getKey().name + ": no part matches " + linkParts(e.getValue()));
            }
        }
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) if (!owner.containsKey(i)) free.add(i);

        // which way the robot faces: as the robot file says, or worked out from the wheels and names.
        // Wheels the robot file lists (by name, whichever way the CAD is turned) show it as well as
        // wheels found would: the robot file says to list them when they aren't found.
        Map<String, List<Integer>> listed = cad.wheels == null ? null : listedWheels(new ArrayList<>(free), i -> raw.get(i).path());
        List<AutoDetect.Wheel> wheelSets;
        if (listed == null) {
            wheelSets = AutoDetect.wheels(raw, free);
        } else {
            wheelSets = new ArrayList<>();
            for (List<Integer> ix : listed.values()) {
                double[] b = MeshPart.emptyBounds();
                for (int i : ix) raw.get(i).addBounds(b);
                wheelSets.add(new AutoDetect.Wheel(ix, b));
            }
        }
        String forward = given(cad.forward), up = given(cad.up);
        if (forward == null || up == null) {
            AutoDetect.Axes ax = AutoDetect.orient(raw, wheelSets, forward, up);
            forward = ax.forward();
            up = ax.up();
            if (ax.note() != null) notes.add(ax.note());
        }
        double[] r = axesRotation(forward, up);
        double[] rt = Geom3.transform(r, new double[3]);
        List<MeshPart> parts = new ArrayList<>();
        for (MeshPart p : raw) parts.add(p.transformed(rt));

        // wheels
        Map<String, List<Integer>> wheelParts = new LinkedHashMap<>();
        if (listed != null) {
            wheelParts = listed;
        } else {
            List<AutoDetect.Wheel> drive = AutoDetect.onTheFloor(wheelSets, parts);
            for (Map.Entry<String, List<AutoDetect.Wheel>> e : placeWheels(drive, AutoDetect.Wheel::center).entrySet()) {
                List<Integer> ix = new ArrayList<>();
                for (AutoDetect.Wheel w : e.getValue()) ix.addAll(w.parts());
                wheelParts.put(e.getKey(), ix);
            }
        }
        for (String key : wheelKeys(cfg.drive.type)) {
            List<Integer> wp = wheelParts.get(key);
            if (wp == null || wp.isEmpty()) continue;
            RobotModel.Group w = new RobotModel.Group("wheel " + key, RobotModel.Kind.WHEEL);
            w.wheelKey = key;
            w.jointType = "continuous";
            groups.add(w);
            for (int i : wp) owner.put(i, w);
        }
        free.removeIf(owner::containsKey);

        // rollers and flywheels the robot file doesn't list: found by their motor's name
        java.util.Set<String> driving = new java.util.HashSet<>();
        for (RobotConfig.CadLink l : cad.links) if (l.device != null) driving.add(l.device);
        for (RobotConfig.Device d : cfg.devices) {
            List<String> words = driving.contains(d.name) ? null : AutoDetect.spinnerWords(d);
            if (words == null || words.isEmpty()) continue;
            AutoDetect.Spinner s = AutoDetect.spinner(parts, free, words);
            if (s == null) continue;
            RobotModel.Group g = new RobotModel.Group(byName.containsKey(d.name) ? d.name + " (found)" : d.name, RobotModel.Kind.SPINNER);
            g.jointType = "continuous";
            g.device = d.name;
            g.found = true;
            g.anchor = s.anchor();
            g.axis = s.axis();
            groups.add(g);
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            for (int i : s.parts()) {
                owner.put(i, g);
                List<String> n = AutoDetect.names(parts.get(i).path());
                names.add(n.get(n.size() - 1));
            }
            free.removeIf(owner::containsKey);
            notes.add(d.name + " turns " + String.join(", ", names) + " (found by name)");
        }

        for (int i = 0; i < parts.size(); i++) owner.getOrDefault(i, base).parts.add(parts.get(i));

        // joint axes (still in rotated CAD coordinates; the origin shift comes later)
        for (Map.Entry<RobotModel.Group, RobotConfig.CadLink> e : linkCfg.entrySet()) {
            RobotModel.Group g = e.getKey();
            RobotConfig.CadLink l = e.getValue();
            double[][] ax = stepAxis(l, parts, g, r);
            g.anchor = ax[0];
            g.axis = l.reversed ? Geom3.scale(ax[1], -1) : ax[1];
            configureJoint(g, l, Double.NaN, Double.NaN);
        }
        for (Map.Entry<RobotModel.Group, RobotConfig.CadLink> e : linkCfg.entrySet()) {
            RobotConfig.CadLink l = e.getValue();
            if (l.mimic == null) continue;
            RobotModel.Group m = byName.get(l.mimic);
            if (m == null || m.kind != RobotModel.Kind.LINK) throw new IllegalArgumentException("cad link " + l.name + ": mimic " + l.mimic + " is not a revolute or prismatic link");
            e.getKey().mimic = groups.indexOf(m);
            e.getKey().mimicRatio = l.mimicRatio;
        }
        wheelGeometry();

        double[] origin = origin(r);
        shift(Geom3.scale(origin, -1));

        // mass: chassis.mass_kg spread over the parts by volume, except what the robot file pins down
        stepMasses(linkCfg);
        for (RobotModel.Group g : groups) {
            if (g.kind == RobotModel.Kind.WHEEL) continue;
            RobotModel.Group target = g.kind == RobotModel.Kind.SPINNER ? groups.get(g.parentIndex) : g;
            for (MeshPart p : g.parts) addBoxes(target, p);
        }
        return finish(fileName, parts.size());
    }

    /** The parts of a STEP link: its name patterns, or the parts and sub-assemblies called its name. */
    private static List<String> linkParts(RobotConfig.CadLink l) {
        return l.parts == null || l.parts.isEmpty() ? List.of(l.name) : l.parts;
    }

    /** A link's joint type: as given, else continuous for a roller or flywheel motor or a CR servo. */
    private String jointType(RobotConfig.CadLink l) {
        if (l.joint != null && !l.joint.isBlank()) return l.joint.trim().toLowerCase(Locale.ROOT);
        for (RobotConfig.Device d : cfg.devices) {
            if (!d.name.equals(l.device)) continue;
            String kind = d.load == null ? "" : d.load.kind;
            if ("crservo".equals(d.type) || "motor".equals(d.type) && ("roller".equals(kind) || "flywheel".equals(kind))) return "continuous";
        }
        return "revolute";
    }

    /** {point, direction} of a STEP link's joint axis, in rotated CAD coordinates (meters). */
    private double[][] stepAxis(RobotConfig.CadLink l, List<MeshPart> parts, RobotModel.Group g, double[] r) {
        double[] point = null, dir = null;
        if (l.axisPart == null && l.axisDir == null && l.axisPointMm == null) {
            if ("prismatic".equals(g.jointType)) {
                notes.add(l.name + " slides along its longest side");
                return AutoDetect.lengthAxis(g.parts);
            }
            if ("continuous".equals(g.jointType)) {
                double[][] own = null;
                double biggest = -1;
                for (MeshPart p : g.parts) {
                    double[][] a = AutoDetect.roundAxis(p);
                    double v = Math.abs(MeshMath.massProps(p).volume());
                    if (a != null && v > biggest) {
                        biggest = v;
                        own = a;
                    }
                }
                if (own != null) return own;
            }
            List<String> words = new ArrayList<>(AutoDetect.words(l.name));
            words.addAll(AutoDetect.words(l.device));
            Object[] found = AutoDetect.pivot(g.parts, parts, words);
            if (found == null) {
                throw new IllegalArgumentException("cad link " + l.name + ": give axis_part, the shaft, axle or pivot it turns on"
                        + " (no round part named like one is in it or next to it)");
            }
            notes.add(l.name + " turns on " + found[2] + " (found by name)");
            return new double[][]{(double[]) found[0], (double[]) found[1]};
        }
        if (l.axisPart != null) {
            MeshPart shaft = null;
            for (MeshPart p : parts) {
                if (matches(p.path(), l.axisPart)) {
                    shaft = p;
                    break;
                }
            }
            if (shaft == null) throw new IllegalArgumentException("cad link " + l.name + ": no part matches axis_part " + l.axisPart);
            double[][] a = MeshMath.partAxis(shaft);
            point = a[0];
            dir = a[1];
        }
        if (l.axisPointMm != null) {
            if (l.axisPointMm.length != 3) throw new IllegalArgumentException("cad link " + l.name + ": axis_point_mm needs 3 numbers");
            point = Geom3.mul(r, Geom3.scale(l.axisPointMm, 0.001));
        }
        if (l.axisDir != null) {
            if (l.axisDir.length != 3) throw new IllegalArgumentException("cad link " + l.name + ": axis_dir needs 3 numbers");
            dir = Geom3.normalize(Geom3.mul(r, l.axisDir));
        }
        if (dir == null || Geom3.norm(dir) < 0.5) {
            throw new IllegalArgumentException("cad link " + l.name + ": give axis_part (the shaft, rail or spline it moves on) or axis_dir");
        }
        if (point == null) {
            if (!"prismatic".equals(g.jointType)) throw new IllegalArgumentException("cad link " + l.name + ": give axis_part or axis_point_mm");
            point = centerOf(g.bounds());
        }
        return new double[][]{point, canonical(dir)};
    }

    /** A direction with its largest component positive, so a fitted axis always points the same way. */
    static double[] canonical(double[] d) {
        int k = 0;
        for (int i = 1; i < 3; i++) if (Math.abs(d[i]) > Math.abs(d[k])) k = i;
        return d[k] < 0 ? Geom3.scale(d, -1) : d.clone();
    }

    private void configureJoint(RobotModel.Group g, RobotConfig.CadLink l, double urdfLower, double urdfUpper) {
        boolean prismatic = "prismatic".equals(g.jointType);
        double unit = prismatic ? 0.001 : Math.PI / 180;
        if (l != null && l.limits != null) {
            if (l.limits.length != 2 || l.limits[0] > l.limits[1]) throw new IllegalArgumentException("cad link " + l.name + ": limits must be [min, max]");
            g.lower = l.limits[0] * unit;
            g.upper = l.limits[1] * unit;
        } else if ("continuous".equals(g.jointType)) {
            g.lower = g.upper = Double.NaN;
        } else {
            g.lower = urdfLower;
            g.upper = urdfUpper;
        }
        if (!prismatic && !"continuous".equals(g.jointType) && !Double.isNaN(g.lower)) {
            // ODE hinge stops must lie within ±180°.
            if (g.lower < -Math.PI || g.upper > Math.PI) {
                notes.add(g.name + ": stops beyond ±180° are clamped");
                g.lower = Math.max(-Math.PI, g.lower);
                g.upper = Math.min(Math.PI, g.upper);
            }
        }
        if (l == null) return;
        g.device = l.device;
        g.start = l.start * unit;
        g.servoCenter = l.servoCenter * unit;
        g.ratio = l.ratio;
        g.spoolRadius = Math.max(0.002, l.spoolDiameterMm / 2000.0);
        g.torqueNm = l.torqueNm;
        if (!Double.isNaN(g.lower) && (g.start < g.lower - 1e-9 || g.start > g.upper + 1e-9)) {
            throw new IllegalArgumentException("cad link " + l.name + ": start is outside its limits");
        }
    }

    private void stepMasses(Map<RobotModel.Group, RobotConfig.CadLink> linkCfg) {
        int physicalWheels = wheelKeys(cfg.drive.type).size();
        double remaining = cfg.chassis.massKg - physicalWheels * DEFAULT_WHEEL_MASS_KG;
        Map<RobotModel.Group, MeshMath.MassProps> props = new HashMap<>();
        double freeVolume = 0;
        for (RobotModel.Group g : groups) {
            if (g.kind == RobotModel.Kind.WHEEL) continue;
            MeshMath.MassProps mp = combined(g.parts);
            props.put(g, mp);
            RobotConfig.CadLink l = linkCfg.get(g);
            if (l != null && l.massKg != null) {
                g.mass = l.massKg;
                remaining -= l.massKg;
            } else {
                freeVolume += mp.volume();
            }
        }
        if (remaining <= 0.5) throw new IllegalArgumentException("the link masses add up to more than chassis.mass_kg");
        for (RobotModel.Group g : groups) {
            if (g.kind == RobotModel.Kind.WHEEL) {
                g.mass = DEFAULT_WHEEL_MASS_KG;
                continue;
            }
            MeshMath.MassProps mp = props.get(g);
            RobotConfig.CadLink l = linkCfg.get(g);
            if (l == null || l.massKg == null) g.mass = freeVolume > 0 ? remaining * mp.volume() / freeVolume : 0;
            if (mp.volume() > 0) {
                g.com = mp.com();
                g.inertia = mp.inertiaForMass(g.mass);
            } else {
                g.com = centerOf(g.bounds());
                double[] b = g.bounds();
                g.inertia = MeshMath.boxInertia(g.mass, b[3] - b[0], b[4] - b[1], b[5] - b[2]);
            }
        }
        mergeSpinnerMasses();
    }

    private static MeshMath.MassProps combined(List<MeshPart> parts) {
        List<double[]> m = new ArrayList<>(), c = new ArrayList<>(), in = new ArrayList<>();
        for (MeshPart p : parts) {
            MeshMath.MassProps mp = MeshMath.massProps(p);
            if (mp.volume() <= 0) continue;
            m.add(new double[]{mp.volume()});
            c.add(mp.com());
            in.add(mp.inertia());
        }
        double[][] r = MeshMath.combine(m, c, in);
        return new MeshMath.MassProps(r[0][0], r[1], r[2]);
    }

    /** A spinner turns with its mechanism but its mass rides on its parent's body. */
    private void mergeSpinnerMasses() {
        for (RobotModel.Group s : groups) {
            if (s.kind != RobotModel.Kind.SPINNER) continue;
            // one found by name keeps the robot file's inertia: its mass share is only a guess
            s.axisInertia = s.found ? Double.NaN : Math.max(1e-6, Geom3.dot(s.axis, Geom3.mul(s.inertia, s.axis)));
            RobotModel.Group p = groups.get(s.parentIndex);
            double[][] c = MeshMath.combine(List.of(new double[]{p.mass}, new double[]{s.mass}), List.of(p.com, s.com), List.of(p.inertia, s.inertia));
            p.mass = c[0][0];
            p.com = c[1];
            p.inertia = c[2];
        }
    }

    // =============================================================================================
    // URDF
    // =============================================================================================

    /** A robot from a URDF and the robot file's cad section. */
    public static RobotModel fromUrdf(RobotConfig cfg, Urdf urdf, String fileName) {
        return new ModelBuilder(cfg).buildUrdf(urdf, fileName);
    }

    private RobotModel buildUrdf(Urdf u, String fileName) {
        notes.addAll(u.warnings);
        // URDF's own convention: x forward, z up
        double[] r = axesRotation(given(cad.forward) == null ? "+x" : cad.forward, given(cad.up) == null ? "+z" : cad.up);
        double[] rootToRobot = Geom3.transform(r, new double[3]);
        String root = u.rootLink();
        // forward kinematics at rest
        Map<String, double[]> tf = new LinkedHashMap<>();
        Map<String, List<Urdf.Joint>> byParent = new HashMap<>();
        for (Urdf.Joint j : u.joints.values()) byParent.computeIfAbsent(j.parent, k -> new ArrayList<>()).add(j);
        List<Urdf.Joint> order = new ArrayList<>();
        tf.put(root, rootToRobot);
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of(root));
        while (!queue.isEmpty()) {
            String link = queue.poll();
            for (Urdf.Joint j : byParent.getOrDefault(link, List.of())) {
                if (tf.containsKey(j.child)) throw new IllegalArgumentException("URDF link " + j.child + " has two parents");
                tf.put(j.child, Geom3.compose(tf.get(link), j.origin));
                order.add(j);
                queue.add(j.child);
            }
        }
        for (String l : u.links.keySet()) if (!tf.containsKey(l)) notes.add("URDF link " + l + " is not connected to " + root + "; left out");

        Map<String, RobotConfig.CadLink> jointCfg = new HashMap<>();
        for (RobotConfig.CadLink l : cad.links) {
            if (!u.joints.containsKey(l.name)) throw new IllegalArgumentException("cad link " + l.name + ": the URDF has no joint by that name");
            jointCfg.put(l.name, l);
        }
        java.util.Set<String> devices = new java.util.HashSet<>();
        for (RobotConfig.Device d : cfg.devices) devices.add(d.name);

        // wheels: listed links/joints, or found by name
        Map<String, String> wheelOfLink = new HashMap<>();
        List<String> wheelCands = urdfWheelCandidates(u, order, jointCfg, devices);
        Map<String, List<String>> wheelLinks = cad.wheels != null ? listedWheels(new ArrayList<>(wheelCands), name -> name)
                : placeWheels(urdfDriveWheels(u, tf, wheelCands), name -> Geom3.trans(tf.get(name)));
        for (Map.Entry<String, List<String>> e : wheelLinks.entrySet()) for (String l : e.getValue()) wheelOfLink.put(l, e.getKey());

        RobotModel.Group base = new RobotModel.Group("base", RobotModel.Kind.BASE);
        groups.add(base);
        Map<String, RobotModel.Group> groupOf = new HashMap<>();
        groupOf.put(root, base);
        Map<String, RobotModel.Group> groupOfJoint = new HashMap<>();
        for (Urdf.Joint j : order) {
            RobotModel.Group parent = groupOf.get(j.parent);
            String wheel = wheelOfLink.get(j.child);
            RobotConfig.CadLink lc = jointCfg.get(j.name);
            String device = lc != null && lc.device != null ? lc.device : (devices.contains(j.name) ? j.name : null);
            boolean movable = List.of("revolute", "prismatic", "continuous").contains(j.type);
            if (wheel != null && parent.kind == RobotModel.Kind.BASE) {
                RobotModel.Group w = new RobotModel.Group("wheel " + wheel, RobotModel.Kind.WHEEL);
                w.wheelKey = wheel;
                w.jointType = "continuous";
                groups.add(w);
                groupOf.put(j.child, w);
            } else if (movable && (device != null || j.mimicJoint != null) && parent.kind != RobotModel.Kind.WHEEL && parent.kind != RobotModel.Kind.SPINNER) {
                RobotModel.Group g = new RobotModel.Group(j.name, "continuous".equals(j.type) ? RobotModel.Kind.SPINNER : RobotModel.Kind.LINK);
                g.jointType = j.type;
                g.parentIndex = groups.indexOf(parent);
                double[] ct = tf.get(j.child);
                g.anchor = Geom3.trans(ct);
                double[] axis = Geom3.normalize(Geom3.mul(Geom3.rot(ct), j.axis));
                g.axis = lc != null && lc.reversed ? Geom3.scale(axis, -1) : axis;
                configureJoint(g, lc, j.lower, j.upper);
                g.device = device;
                groups.add(g);
                groupOf.put(j.child, g);
                groupOfJoint.put(j.name, g);
            } else {
                if (movable && wheel == null) notes.add("joint " + j.name + " has no device driving it; it is held at its CAD position");
                groupOf.put(j.child, parent);
            }
        }
        for (Urdf.Joint j : order) {
            RobotModel.Group g = groupOfJoint.get(j.name);
            if (g == null || j.mimicJoint == null) continue;
            RobotModel.Group m = groupOfJoint.get(j.mimicJoint);
            if (m == null || m.kind != RobotModel.Kind.LINK) {
                notes.add("joint " + j.name + " mimics " + j.mimicJoint + ", which doesn't move; it is held instead");
                continue;
            }
            g.mimic = groups.indexOf(m);
            g.mimicRatio = j.mimicMultiplier;
            g.mimicOffset = j.mimicOffset;
        }

        // geometry and mass properties, robot axes (origin shift later)
        boolean anyMass = false;
        for (Urdf.Link l : u.links.values()) anyMass |= !Double.isNaN(l.mass) && l.mass > 0;
        Map<RobotModel.Group, List<double[]>> gm = new HashMap<>(), gc = new HashMap<>(), gi = new HashMap<>();
        Map<RobotModel.Group, List<MeshPart>> colliders = new HashMap<>();
        int partCount = 0;
        for (Map.Entry<String, double[]> e : tf.entrySet()) {
            Urdf.Link l = u.links.get(e.getKey());
            RobotModel.Group g = groupOf.get(e.getKey());
            double[] t = e.getValue();
            for (MeshPart p : l.visuals) {
                if (matchesAny(p.path(), cad.ignore)) continue;
                g.parts.add(p.transformed(t));
                partCount++;
            }
            for (MeshPart p : (l.collisions.isEmpty() ? l.visuals : l.collisions)) {
                colliders.computeIfAbsent(g, k -> new ArrayList<>()).add(p.transformed(t));
            }
            if (anyMass && !Double.isNaN(l.mass) && l.mass > 0) {
                double[] rr = Geom3.rot(t);
                gm.computeIfAbsent(g, k -> new ArrayList<>()).add(new double[]{l.mass});
                gc.computeIfAbsent(g, k -> new ArrayList<>()).add(Geom3.apply(t, l.com));
                double[] in = l.inertia != null ? l.inertia : new double[9];
                gi.computeIfAbsent(g, k -> new ArrayList<>()).add(Geom3.matMul(Geom3.matMul(rr, in), Geom3.transpose(rr)));
            }
        }
        wheelGeometry();
        double[] origin = origin(r);
        double[] shiftV = Geom3.scale(origin, -1);
        shift(shiftV);
        double[] shiftTf = Geom3.transform(Geom3.IDENTITY, shiftV);
        if (anyMass) {
            for (RobotModel.Group g : groups) {
                List<double[]> m = gm.getOrDefault(g, List.of());
                if (m.isEmpty()) {
                    g.mass = g.kind == RobotModel.Kind.WHEEL ? DEFAULT_WHEEL_MASS_KG : 0.05;
                    if (g.kind != RobotModel.Kind.WHEEL && g.kind != RobotModel.Kind.BASE) notes.add(g.name + " has no mass in the URDF; using 50 g");
                    g.com = centerOf(g.bounds());
                    double[] b = g.bounds();
                    g.inertia = MeshPart.isEmpty(b) ? MeshMath.boxInertia(g.mass, 0.05, 0.05, 0.05) : MeshMath.boxInertia(g.mass, b[3] - b[0], b[4] - b[1], b[5] - b[2]);
                    continue;
                }
                List<double[]> coms = new ArrayList<>();
                for (double[] c : gc.get(g)) coms.add(Geom3.add(c, shiftV));
                double[][] c = MeshMath.combine(m, coms, gi.get(g));
                g.mass = c[0][0];
                g.com = c[1];
                g.inertia = c[2];
            }
            double total = 0;
            for (RobotModel.Group g : groups) total += g.mass;
            if (cad.massKg != null && total > 0) {
                double k = cad.massKg / total;
                for (RobotModel.Group g : groups) {
                    g.mass *= k;
                    g.inertia = Geom3.scale(g.inertia, k);
                }
                notes.add(String.format(Locale.ROOT, "URDF masses scaled by %.2f to cad.mass_kg", k));
            }
            mergeSpinnerMasses();
        } else {
            notes.add("the URDF has no masses; chassis.mass_kg is spread over the parts by volume");
            stepMasses(new HashMap<>());
        }
        for (Map.Entry<RobotModel.Group, List<MeshPart>> e : colliders.entrySet()) {
            RobotModel.Group g = e.getKey();
            if (g.kind == RobotModel.Kind.WHEEL) continue;
            RobotModel.Group target = g.kind == RobotModel.Kind.SPINNER ? groups.get(g.parentIndex) : g;
            for (MeshPart p : e.getValue()) addBoxes(target, p.transformed(shiftTf));
        }
        return finish(fileName, partCount);
    }

    private List<String> urdfWheelCandidates(Urdf u, List<Urdf.Joint> order, Map<String, RobotConfig.CadLink> jointCfg, java.util.Set<String> devices) {
        List<String> out = new ArrayList<>();
        if (cad.wheels != null) {
            out.addAll(u.links.keySet());
            return out;
        }
        for (Urdf.Joint j : order) {
            boolean bound = jointCfg.containsKey(j.name) || devices.contains(j.name);
            if (!bound && "continuous".equals(j.type) || WHEEL_NAME.matcher(j.child).matches() && !bound) out.add(j.child);
        }
        return out;
    }

    // =============================================================================================
    // shared
    // =============================================================================================

    /**
     * Of the URDF's possible wheel links (on continuous joints nothing drives, or named like
     * wheels), the ones that reach the floor: the drive wheels, not a free-spinning roller up on
     * the robot. A link without meshes counts if it is named like a wheel.
     */
    private static List<String> urdfDriveWheels(Urdf u, Map<String, double[]> tf, List<String> cands) {
        Map<String, Double> bottom = new LinkedHashMap<>();
        double lowest = Double.MAX_VALUE;
        for (String n : cands) {
            double[] b = MeshPart.emptyBounds();
            for (MeshPart p : u.links.get(n).visuals) p.transformed(tf.get(n)).addBounds(b);
            if (MeshPart.isEmpty(b)) {
                if (WHEEL_NAME.matcher(n).matches()) bottom.put(n, Double.NaN);
                continue;
            }
            bottom.put(n, b[2]);
            lowest = Math.min(lowest, b[2]);
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : bottom.entrySet()) {
            if (e.getValue().isNaN() || e.getValue() <= lowest + 0.02) out.add(e.getKey());
        }
        return out;
    }

    /** The parts (or URDF links) of each wheel the robot file's cad.wheels lists. */
    private <T> Map<String, List<T>> listedWheels(List<T> items, java.util.function.Function<T, String> name) {
        List<String> keys = wheelKeys(cfg.drive.type);
        Map<String, List<T>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : cad.wheels.entrySet()) {
            if (!keys.contains(e.getKey())) {
                throw new IllegalArgumentException("cad.wheels: \"" + e.getKey() + "\" is not a wheel of a " + cfg.drive.type + " drive " + keys);
            }
            List<String> patterns = new ArrayList<>();
            if (e.getValue().isJsonArray()) e.getValue().getAsJsonArray().forEach(x -> patterns.add(x.getAsString()));
            else patterns.add(e.getValue().getAsString());
            List<T> mine = new ArrayList<>();
            for (T t : items) if (matchesAny(name.apply(t), patterns)) mine.add(t);
            if (mine.isEmpty()) throw new IllegalArgumentException("cad.wheels." + e.getKey() + ": nothing matches " + patterns);
            out.put(e.getKey(), mine);
            items.removeAll(mine);
        }
        return out;
    }

    /** Drive wheels (or their parts) sorted by which side and end of the robot they are on. */
    private <T> Map<String, List<T>> placeWheels(List<T> cands, java.util.function.Function<T, double[]> center) {
        List<String> keys = wheelKeys(cfg.drive.type);
        Map<String, List<T>> out = new LinkedHashMap<>();
        if (cands.isEmpty()) {
            notes.add("no wheel parts found (name them *wheel* or list them in cad.wheels); the CAD wheels won't turn");
            return out;
        }
        double[] mid = new double[3];
        for (T t : cands) mid = Geom3.add(mid, center.apply(t));
        mid = Geom3.scale(mid, 1.0 / cands.size());
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        for (T t : cands) {
            double x = center.apply(t)[0];
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
        }
        for (T t : cands) {
            double[] c = center.apply(t);
            boolean left = c[1] > mid[1];
            String key;
            if ("tank".equals(cfg.drive.type)) {
                double f = (c[0] - minX) / Math.max(1e-6, maxX - minX);
                String pos = f > 2 / 3.0 ? "front" : (f < 1 / 3.0 ? "back" : "middle");
                key = (left ? "left_" : "right_") + pos;
            } else if ("tank4".equals(cfg.drive.type)) {
                key = (left ? "left_" : "right_") + (c[0] > mid[0] ? "front" : "back");
            } else {
                key = (c[0] > mid[0] ? "front_" : "back_") + (left ? "left" : "right");
            }
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        if (cands.get(0) instanceof AutoDetect.Wheel && cands.size() != keys.size()) {
            notes.add("found " + cands.size() + " drive wheels, but a " + cfg.drive.type + " drive has " + keys.size()
                    + "; set drive.type (mecanum, tank or tank4) or list them in cad.wheels");
        } else if (out.size() < keys.size()) {
            notes.add("found wheel parts for only " + out.keySet() + "; list them in cad.wheels");
        }
        return out;
    }

    private void wheelGeometry() {
        for (RobotModel.Group w : groups) {
            if (w.kind != RobotModel.Kind.WHEEL) continue;
            double[] b = w.bounds();
            if (MeshPart.isEmpty(b)) {
                // Its size would come out as -infinity and the physics engine would refuse the robot.
                // Said here, the robot falls back on its robot file's numbers.
                List<String> meshes = notes.stream().filter(n -> n.toLowerCase(Locale.ROOT).contains("mesh")).toList();
                throw new IllegalArgumentException("the wheel " + w.wheelKey + " has no shape to size it from"
                        + (meshes.isEmpty() ? "" : " (" + String.join("; ", meshes) + ")"));
            }
            w.anchor = centerOf(b);
            w.axis = new double[]{0, 1, 0};
            w.wheelRadius = Math.max(b[3] - b[0], b[5] - b[2]) / 2;
            w.wheelWidth = b[4] - b[1];
        }
    }

    /** The robot-frame origin in rotated CAD coordinates. */
    private double[] origin(double[] r) {
        if (cad.originMm != null) {
            if (cad.originMm.length != 3) throw new IllegalArgumentException("cad.origin_mm needs 3 numbers");
            return Geom3.mul(r, Geom3.scale(cad.originMm, 0.001));
        }
        double[] all = MeshPart.emptyBounds();
        for (RobotModel.Group g : groups) for (MeshPart p : g.parts) p.addBounds(all);
        List<double[]> wheels = new ArrayList<>();
        for (RobotModel.Group g : groups) if (g.kind == RobotModel.Kind.WHEEL) wheels.add(g.anchor);
        double x, y;
        if (wheels.size() >= 2) {
            double[] c = new double[3];
            for (double[] w : wheels) c = Geom3.add(c, w);
            x = c[0] / wheels.size();
            y = c[1] / wheels.size();
        } else {
            double[] b = MeshPart.emptyBounds();
            for (MeshPart p : groups.get(0).parts) p.addBounds(b);
            if (MeshPart.isEmpty(b)) b = all;
            x = (b[0] + b[3]) / 2;
            y = (b[1] + b[4]) / 2;
        }
        double z = all[2];
        if (!wheels.isEmpty()) {
            z = Double.MAX_VALUE;   // the wheels touch the floor
            for (RobotModel.Group g : groups) if (g.kind == RobotModel.Kind.WHEEL) z = Math.min(z, g.anchor[2] - g.wheelRadius);
        }
        return new double[]{x, y, z};
    }

    private void shift(double[] d) {
        double[] t = Geom3.transform(Geom3.IDENTITY, d);
        for (RobotModel.Group g : groups) {
            List<MeshPart> moved = new ArrayList<>();
            for (MeshPart p : g.parts) moved.add(p.transformed(t));
            g.parts.clear();
            g.parts.addAll(moved);
            g.anchor = Geom3.add(g.anchor, d);
            g.com = Geom3.add(g.com, d);
        }
    }

    private void addBoxes(RobotModel.Group g, MeshPart p) {
        double[] b = p.bounds();
        double diag = Math.sqrt(Math.pow(b[3] - b[0], 2) + Math.pow(b[4] - b[1], 2) + Math.pow(b[5] - b[2], 2));
        if (diag < MIN_COLLIDER_SIZE_M) return;
        g.boxes.addAll(MeshMath.fitBoxes(p, MIN_BOX_THICKNESS_M));
    }

    private RobotModel finish(String fileName, int partCount) {
        double total = 0;
        for (RobotModel.Group g : groups) {
            if (g.kind == RobotModel.Kind.SPINNER) continue;   // counted in its parent
            total += g.mass;
            if (g.boxes.size() > MAX_BOXES_PER_GROUP) {
                g.boxes.sort(Comparator.comparingDouble(MeshMath.Box::volume).reversed());
                int dropped = g.boxes.size() - MAX_BOXES_PER_GROUP;
                g.boxes.subList(MAX_BOXES_PER_GROUP, g.boxes.size()).clear();
                notes.add(g.name + ": kept the " + MAX_BOXES_PER_GROUP + " largest collision boxes, " + dropped + " small ones left out");
            }
            if (g.kind == RobotModel.Kind.BASE || g.kind == RobotModel.Kind.LINK) g.inertia = sanitizeInertia(g.inertia, g.mass);
        }
        for (RobotModel.Group g : groups) {
            if (g.kind == RobotModel.Kind.LINK && g.device == null && g.mimic < 0) {
                notes.add(g.name + " has no device; it swings freely");
            }
            if (g.kind == RobotModel.Kind.SPINNER && g.device == null) notes.add(g.name + " has no device; it doesn't turn");
        }
        double[] base = groups.get(0).bounds();
        if (!MeshPart.isEmpty(base) && base[2] < 0.004) {
            notes.add(String.format(Locale.ROOT, "some chassis parts reach %.0f mm from the floor and may drag", base[2] * 1000));
        }
        int links = 0;
        for (RobotModel.Group g : groups) if (g.kind == RobotModel.Kind.LINK || g.kind == RobotModel.Kind.SPINNER) links++;
        long wheels = groups.stream().filter(g -> g.kind == RobotModel.Kind.WHEEL).count();
        String summary = String.format(Locale.ROOT, "%s: %d parts, %d moving link%s, %d wheel%s, %.1f kg", fileName, partCount, links,
                links == 1 ? "" : "s", wheels, wheels == 1 ? "" : "s", total);
        return new RobotModel(List.copyOf(groups), total, List.copyOf(notes), summary);
    }

    /** A physically valid inertia tensor: positive principal moments that satisfy the triangle inequality. */
    static double[] sanitizeInertia(double[] in, double mass) {
        double[] sym = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) sym[i * 3 + j] = (in[i * 3 + j] + in[j * 3 + i]) / 2;
        double[] vals = new double[3];
        double[] rot = Geom3.eigenSymmetric(sym, vals);
        double floor = Math.max(1e-7, mass * 0.02 * 0.02 / 6);   // like a 2 cm cube of the same mass
        for (int k = 0; k < 3; k++) vals[k] = Math.max(vals[k], floor);
        for (int k = 0; k < 3; k++) {
            double others = vals[(k + 1) % 3] + vals[(k + 2) % 3];
            if (vals[k] > others) vals[k] = others;
        }
        double[] d = {vals[0], 0, 0, 0, vals[1], 0, 0, 0, vals[2]};
        return Geom3.matMul(Geom3.matMul(rot, d), Geom3.transpose(rot));
    }

    static double[] centerOf(double[] b) {
        if (MeshPart.isEmpty(b)) return new double[3];
        return new double[]{(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2};
    }

    /**
     * Updates the robot description's numbers from the model, so everything that still reads
     * them (footprint checks, ball staging, the Driver Station) sees the CAD robot.
     */
    public static void applyTo(RobotConfig cfg, RobotModel model) {
        double[] all = MeshPart.emptyBounds();
        for (RobotModel.Group g : model.groups) for (MeshPart p : g.parts) p.addBounds(all);
        double[] base = model.base().bounds();
        if (MeshPart.isEmpty(base)) base = all;
        cfg.chassis.lengthM = Math.max(0.05, 2 * Math.max(Math.abs(all[0]), Math.abs(all[3])));
        cfg.chassis.widthM = Math.max(0.05, 2 * Math.max(Math.abs(all[1]), Math.abs(all[4])));
        cfg.chassis.groundClearanceM = Math.max(0, base[2]);
        cfg.chassis.frameHeightM = Math.max(0.02, Math.min(0.15, base[5] - Math.max(0, base[2])));
        cfg.chassis.massKg = model.totalMass;
        List<RobotModel.Group> wheels = new ArrayList<>();
        for (String k : wheelKeys(cfg.drive.type)) {
            RobotModel.Group w = model.wheel(k);
            if (w != null) wheels.add(w);
        }
        if (!wheels.isEmpty()) {
            double r = 0, x = 0, y = 0;
            for (RobotModel.Group w : wheels) {
                r += w.wheelRadius;
                x += Math.abs(w.anchor[0]);
                y += Math.abs(w.anchor[1]);
            }
            cfg.drive.wheelDiameterMm = r / wheels.size() * 2000;
            if ("tank".equals(cfg.drive.type)) {
                double front = 0;
                int n = 0;
                for (RobotModel.Group w : wheels) {
                    if (w.wheelKey.endsWith("_front") || w.wheelKey.endsWith("_back")) {
                        front += Math.abs(w.anchor[0]);
                        n++;
                    }
                }
                if (n > 0) cfg.drive.wheelbaseM = 2 * front / n;
            } else {
                cfg.drive.wheelbaseM = 2 * x / wheels.size();
            }
            cfg.drive.trackWidthM = 2 * y / wheels.size();
        }
    }
}
