package ftcsim.cad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import ftcsim.config.RobotConfig;

/**
 * What reading a STEP file works out by itself, so a robot file needs little more than the STEP
 * file's name: which way the robot faces, which parts are its drive wheels, which parts spin with
 * which motor, and the shaft a moving link turns on. All from part names and shapes.
 */
final class AutoDetect {
    static final Pattern WHEEL_NAME = Pattern.compile("(?i).*(wheel|mecanum|omni).*");
    /**
     * Wheels that don't drive the robot: flywheels, compliant intake wheels, feeder wheels, and
     * odometry (dead) wheels, which ride on the floor too. ("Pod" alone isn't one: teams call a
     * drive wheel's module a pod as well. A small odometry wheel by any name is left out by its size.)
     */
    private static final Pattern NOT_DRIVE = Pattern.compile(
            "(?i).*(fly|compliant|intake|shoot|launch|feed|index|transfer|kick|turret|gecko|star|odo|dead|tracking|encoder).*");
    /** A wheel this much smaller than the others is an odometry wheel, not a drive wheel. */
    private static final double SMALL_WHEEL = 0.6;
    private static final Pattern SHAFT = Pattern.compile("(?i).*(shaft|axle|pivot|hinge|spline|axis).*");
    /** Round parts that turn with the roller or flywheel they belong to. */
    private static final Pattern TURNS_WITH = Pattern.compile("(?i).*(shaft|axle|hub|spacer|collar|bushing|bearing|pulley|sprocket|gear).*");
    private static final Pattern FRONT = Pattern.compile("(?i).*\\bfront\\b.*");
    private static final Pattern BACK = Pattern.compile("(?i).*\\b(back|rear)\\b.*");
    /** Words in device names that say nothing about the part they drive. */
    private static final Set<String> FILLER = Set.of("motor", "servo", "crservo", "left", "right", "front", "back", "rear",
            "top", "bottom", "upper", "lower", "drive", "the", "main");
    private static final Pattern SPINNER_WORD = Pattern.compile("(?i).*(intake|launch|shoot|flywheel|feed|roller|spinner|transfer|index).*");

    private AutoDetect() {
    }

    /** The names that say what a part is: its path without the top assembly's name. */
    static List<String> names(String path) {
        List<String> all = Arrays.asList(path.split("/"));
        return all.size() > 1 ? all.subList(1, all.size()) : all;
    }

    private static boolean anyName(String path, Pattern p) {
        for (String n : names(path)) if (p.matcher(n).matches()) return true;
        return false;
    }

    private static boolean anyNameContains(String path, String word) {
        String w = word.toLowerCase(Locale.ROOT);
        for (String n : names(path)) if (n.toLowerCase(Locale.ROOT).contains(w)) return true;
        return false;
    }

    /** The words of a name, lower case: "intakeMotor" and "intake_motor" are intake, motor. */
    static List<String> words(String name) {
        List<String> out = new ArrayList<>();
        if (name == null) return out;
        for (String t : name.split("(?<=[a-z])(?=[A-Z])|[^A-Za-z]+")) {
            String w = t.toLowerCase(Locale.ROOT);
            if (w.length() >= 3 && !FILLER.contains(w)) out.add(w);
        }
        return out;
    }

    // ---- drive wheels ---------------------------------------------------------------------------

    /** One wheel: the parts that make it up (indices into the part list) and their bounds. */
    record Wheel(List<Integer> parts, double[] bounds) {
        double[] center() {
            return ModelBuilder.centerOf(bounds);
        }

        double size(int axis) {
            return bounds[3 + axis] - bounds[axis];
        }

        /** Its largest side: a wheel's diameter, however it is turned. */
        double diameter() {
            return Math.max(size(0), Math.max(size(1), size(2)));
        }

        /** Round: its two largest sides about equal. */
        boolean disk() {
            double[] s = {size(0), size(1), size(2)};
            Arrays.sort(s);
            return s[2] > 0.02 && s[1] / s[2] > 0.8 && s[0] / s[2] < 0.8;
        }

        int thinnest() {
            int k = 0;
            for (int i = 1; i < 3; i++) if (size(i) < size(k)) k = i;
            return k;
        }
    }

    /** Whether a part path names a drive wheel (and not a flywheel, an intake wheel...). */
    static boolean driveWheelName(String path) {
        boolean wheel = false;
        for (String n : names(path)) {
            if (NOT_DRIVE.matcher(n).matches()) return false;
            wheel |= WHEEL_NAME.matcher(n).matches();
        }
        return wheel;
    }

    /** The parts named like drive wheels, grouped into wheels: parts that touch are one wheel. */
    static List<Wheel> wheels(List<MeshPart> parts, Collection<Integer> candidates) {
        List<Integer> idx = new ArrayList<>();
        for (int i : candidates) if (driveWheelName(parts.get(i).path())) idx.add(i);
        int n = idx.size();
        int[] root = new int[n];
        double[][] b = new double[n][];
        for (int i = 0; i < n; i++) {
            root[i] = i;
            b[i] = parts.get(idx.get(i)).bounds();
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) if (touch(b[i], b[j], 0.002)) root[find(root, i)] = find(root, j);
        }
        Map<Integer, List<Integer>> sets = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) sets.computeIfAbsent(find(root, i), k -> new ArrayList<>()).add(idx.get(i));
        List<Wheel> out = new ArrayList<>();
        for (List<Integer> s : sets.values()) {
            double[] bb = MeshPart.emptyBounds();
            for (int i : s) parts.get(i).addBounds(bb);
            out.add(new Wheel(s, bb));
        }
        return withoutSmallWheels(out);
    }

    /**
     * Leaves out wheels whose diameter is under {@link #SMALL_WHEEL} of the median: odometry pods
     * (35 to 48 mm omni wheels) among drive wheels (75 mm and up).
     */
    static List<Wheel> withoutSmallWheels(List<Wheel> wheels) {
        if (wheels.size() < 3) return wheels;
        double[] d = new double[wheels.size()];
        for (int i = 0; i < d.length; i++) d[i] = wheels.get(i).diameter();
        double[] sorted = d.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        double median = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
        List<Wheel> out = new ArrayList<>();
        for (int i = 0; i < d.length; i++) if (d[i] >= SMALL_WHEEL * median) out.add(wheels.get(i));
        return out;
    }

    private static int find(int[] root, int i) {
        while (root[i] != i) i = root[i] = root[root[i]];
        return i;
    }

    static boolean touch(double[] a, double[] b, double gap) {
        for (int k = 0; k < 3; k++) if (a[k] > b[3 + k] + gap || b[k] > a[3 + k] + gap) return false;
        return true;
    }

    /** The wheels that touch the floor (robot axes, so z is up): drive wheels, not a raised intake wheel. */
    static List<Wheel> onTheFloor(List<Wheel> wheels, List<MeshPart> robotParts) {
        List<Wheel> placed = new ArrayList<>();
        double lowest = Double.MAX_VALUE;
        for (Wheel w : wheels) {
            double[] bb = MeshPart.emptyBounds();
            for (int i : w.parts()) robotParts.get(i).addBounds(bb);
            placed.add(new Wheel(w.parts(), bb));
            lowest = Math.min(lowest, bb[2]);
        }
        List<Wheel> out = new ArrayList<>();
        for (Wheel w : placed) if (w.bounds()[2] <= lowest + 0.02) out.add(w);
        return out;
    }

    // ---- which way the robot faces --------------------------------------------------------------

    /** The CAD axes that point to the robot's front and up ("+y", "+z"), and how they were chosen. */
    record Axes(String forward, String up, String note) {
    }

    /**
     * Works out the CAD axes that aren't given ({@code forward} or {@code up} null). Up: the drive
     * wheels' centers lie in a level plane, and the robot is mostly above them. Front: across the
     * wheels' axles, toward the intake (or parts named front, away from parts named back).
     */
    static Axes orient(List<MeshPart> parts, List<Wheel> wheels, String forward, String up) {
        double[] all = MeshPart.emptyBounds();
        for (MeshPart p : parts) p.addBounds(all);
        int[] votes = new int[3];
        for (Wheel w : wheels) if (w.disk()) votes[w.thinnest()]++;
        int axle = votes[0] + votes[1] + votes[2] == 0 ? -1 : argmax(votes);
        List<Wheel> disks = new ArrayList<>();
        for (Wheel w : wheels) if (w.disk() && w.thinnest() == axle) disks.add(w);
        double[] mid = ModelBuilder.centerOf(all);
        if (!disks.isEmpty()) {
            mid = new double[3];
            for (Wheel w : disks) mid = Geom3.add(mid, Geom3.scale(w.center(), 1.0 / disks.size()));
        }
        List<String> said = new ArrayList<>();

        int ua, us;
        int fa = forward == null ? -1 : axisOf(forward);
        if (up != null) {
            ua = axisOf(up);
            us = signOf(up);
        } else {
            ua = -1;
            if (disks.size() >= 2) {
                int i = (axle + 1) % 3, j = (axle + 2) % 3;
                double si = spread(disks, i), sj = spread(disks, j);
                if (Math.max(si, sj) > 0.05 && Math.min(si, sj) < 0.25 * Math.max(si, sj)) ua = si < sj ? i : j;
            }
            boolean fromWheels = ua >= 0 && ua != fa;
            if (!fromWheels) ua = fa == 2 ? 1 : 2;
            // the robot reaches much further above its wheels' centers than below them
            us = all[3 + ua] - mid[ua] >= mid[ua] - all[ua] ? 1 : -1;
            said.add("up is CAD " + axisName(ua, us) + (fromWheels ? " (from the wheels)" : " (a guess: no wheels found)"));
        }

        int fs;
        if (forward != null) {
            fs = signOf(forward);
        } else {
            fa = axle >= 0 && axle != ua ? 3 - axle - ua : (ua == 0 ? 1 : 0);
            double intake = 0, named = 0;
            for (MeshPart p : parts) {
                double d = ModelBuilder.centerOf(p.bounds())[fa] - mid[fa];
                if (Math.abs(d) < 0.02) continue;
                if (anyNameContains(p.path(), "intake")) intake += Math.signum(d);
                if (anyName(p.path(), FRONT)) named += Math.signum(d);
                if (anyName(p.path(), BACK)) named -= Math.signum(d);
            }
            String why;
            if (intake != 0) {
                fs = intake > 0 ? 1 : -1;
                why = "the end with the intake";
            } else if (named != 0) {
                fs = named > 0 ? 1 : -1;
                why = "from parts named front and back";
            } else {
                fs = 1;
                why = "a guess: no intake or parts named front or back";
            }
            said.add(0, "the front is CAD " + axisName(fa, fs) + " (" + why + ")");
        }
        String note = said.isEmpty() ? null
                : String.join("; ", said) + ". Wrong? Set \"forward\" and \"up\" in cad";
        return new Axes(axisName(fa, fs), axisName(ua, us), note);
    }

    private static double spread(List<Wheel> wheels, int axis) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Wheel w : wheels) {
            lo = Math.min(lo, w.center()[axis]);
            hi = Math.max(hi, w.center()[axis]);
        }
        return hi - lo;
    }

    private static int argmax(int[] v) {
        int k = 0;
        for (int i = 1; i < v.length; i++) if (v[i] > v[k]) k = i;
        return k;
    }

    private static int axisOf(String dir) {
        double[] d = ModelBuilder.direction(dir);
        return d[0] != 0 ? 0 : d[1] != 0 ? 1 : 2;
    }

    private static int signOf(String dir) {
        double[] d = ModelBuilder.direction(dir);
        return d[0] + d[1] + d[2] < 0 ? -1 : 1;
    }

    private static String axisName(int axis, int sign) {
        return (sign < 0 ? "-" : "+") + "xyz".charAt(axis);
    }

    // ---- round parts: rollers, flywheels, shafts ------------------------------------------------

    /**
     * A round part's axis, {point, unit direction}: the principal axis it is round about (its
     * outline the same width across, along and diagonally). Null when it isn't round.
     */
    static double[][] roundAxis(MeshPart p) {
        MeshMath.Axes ax = MeshMath.principalAxes(p.positions(), p.indices());
        float[] pos = p.positions();
        double best = Double.MAX_VALUE;
        double[][] out = null;
        for (int k = 0; k < 3; k++) {
            double[] u = ax.axis((k + 1) % 3), v = ax.axis((k + 2) % 3);
            double[][] dirs = {u, v, Geom3.normalize(Geom3.add(u, v)), Geom3.normalize(Geom3.sub(u, v))};
            double[] lo = new double[4], hi = new double[4];
            Arrays.fill(lo, Double.MAX_VALUE);
            Arrays.fill(hi, -Double.MAX_VALUE);
            double[] c0 = ax.center();
            for (int i = 0; i < pos.length; i += 3) {
                double x = pos[i] - c0[0], y = pos[i + 1] - c0[1], z = pos[i + 2] - c0[2];
                for (int d = 0; d < 4; d++) {
                    double s = x * dirs[d][0] + y * dirs[d][1] + z * dirs[d][2];
                    lo[d] = Math.min(lo[d], s);
                    hi[d] = Math.max(hi[d], s);
                }
            }
            double min = Double.MAX_VALUE, max = 0;
            for (int d = 0; d < 4; d++) {
                min = Math.min(min, hi[d] - lo[d]);
                max = Math.max(max, hi[d] - lo[d]);
            }
            if (min < 0.003) continue;
            double ratio = max / min;
            if (ratio < best) {
                best = ratio;
                // the middle of its outline, which a set screw or a key doesn't shift
                double[] c = Geom3.add(ax.center(), Geom3.add(Geom3.scale(u, (lo[0] + hi[0]) / 2), Geom3.scale(v, (lo[1] + hi[1]) / 2)));
                out = new double[][]{c, ModelBuilder.canonical(ax.axis(k))};
            }
        }
        return best <= 1.2 ? out : null;
    }

    private static boolean coaxial(double[][] a, double[][] b) {
        if (Geom3.norm(Geom3.cross(a[1], b[1])) > 0.05) return false;
        double[] d = Geom3.sub(b[0], a[0]);
        double along = Geom3.dot(d, a[1]);
        return Geom3.norm(Geom3.sub(d, Geom3.scale(a[1], along))) < 0.005;
    }

    /**
     * Words the parts a device spins would be named with (its own name's, and its role's), or
     * null when the device doesn't spin anything: an arm or slide motor, a positional servo.
     */
    static List<String> spinnerWords(RobotConfig.Device d) {
        String role = d.role == null ? "" : d.role.toLowerCase(Locale.ROOT);
        String kind = d.load == null || d.load.kind == null ? "free" : d.load.kind;
        boolean spins = "crservo".equals(d.type) || "motor".equals(d.type) && !"arm".equals(kind) && !"slide".equals(kind)
                && ("roller".equals(kind) || "flywheel".equals(kind) || SPINNER_WORD.matcher(role).matches()
                    || SPINNER_WORD.matcher(d.name).matches());
        if (!spins) return null;
        Set<String> w = new LinkedHashSet<>(words(d.name));
        if (role.contains("intake")) w.add("intake");
        if (role.contains("launch") || role.contains("shoot") || "flywheel".equals(kind)) w.addAll(List.of("launcher", "shooter", "flywheel"));
        if (role.contains("feed")) w.addAll(List.of("feeder", "transfer", "indexer", "kicker"));
        return new ArrayList<>(w);
    }

    /** A roller on its shaft, a flywheel with its hub: round parts that turn together about one axis. */
    record Spinner(List<Integer> parts, double[] anchor, double[] axis) {
    }

    /**
     * The parts a spinning mechanism turns: the biggest round part named with one of {@code words},
     * and the round parts on the same axis that are named with them too or are its shaft, hub,
     * spacers, pulleys. Null when no round part has such a name.
     */
    static Spinner spinner(List<MeshPart> parts, Collection<Integer> free, List<String> words) {
        Map<Integer, double[][]> round = new LinkedHashMap<>();
        for (int i : free) {
            String path = parts.get(i).path();
            if (words.stream().noneMatch(w -> anyNameContains(path, w))) continue;
            double[][] a = roundAxis(parts.get(i));
            if (a != null) round.put(i, a);
        }
        if (round.isEmpty()) return null;
        int seed = -1;
        double biggest = -1;
        for (int i : round.keySet()) {
            double v = Math.abs(MeshMath.massProps(parts.get(i)).volume());
            if (v > biggest) {
                biggest = v;
                seed = i;
            }
        }
        double[][] axis = round.get(seed);
        double[] near = parts.get(seed).bounds();
        for (int k = 0; k < 3; k++) {
            near[k] -= 0.01;
            near[3 + k] += 0.01;
        }
        List<Integer> mine = new ArrayList<>();
        for (int i : free) {
            double[][] a = round.get(i);
            if (a == null && anyName(parts.get(i).path(), TURNS_WITH) && touch(parts.get(i).bounds(), near, 0)) a = roundAxis(parts.get(i));
            if (a != null && coaxial(axis, a)) mine.add(i);
        }
        return new Spinner(mine, axis[0], axis[1]);
    }

    /**
     * The shaft a link turns on: a round part named like a shaft, axle or pivot, best one of the
     * link's own parts, else one touching the link (preferring names with the link's words).
     * Returns {point, direction, name} or null.
     */
    static Object[] pivot(List<MeshPart> link, List<MeshPart> all, List<String> words) {
        double[] lb = MeshPart.emptyBounds();
        for (MeshPart p : link) p.addBounds(lb);
        double[] near = lb.clone();
        for (int k = 0; k < 3; k++) {
            near[k] -= 0.005;
            near[3 + k] += 0.005;
        }
        double[] center = ModelBuilder.centerOf(lb);
        Set<MeshPart> own = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        own.addAll(link);
        double bestScore = 0;
        Object[] best = null;
        for (MeshPart p : all) {
            if (!anyName(p.path(), SHAFT)) continue;
            double score = own.contains(p) ? 4 : (touch(p.bounds(), near, 0) ? 2 : 0);
            if (score == 0) continue;
            if (words.stream().anyMatch(w -> anyNameContains(p.path(), w))) score += 1;
            double[][] a = roundAxis(p);
            if (a == null) continue;
            score += 1 - Math.min(1, Geom3.norm(Geom3.sub(a[0], center)));   // nearer wins a tie
            if (score > bestScore) {
                bestScore = score;
                List<String> n = names(p.path());
                best = new Object[]{a[0], a[1], n.get(n.size() - 1)};
            }
        }
        return best;
    }

    /** The direction a link is longest in: the way a slide stage moves. Returns {center, direction}. */
    static double[][] lengthAxis(List<MeshPart> link) {
        int nv = 0, nt = 0;
        for (MeshPart p : link) {
            nv += p.positions().length;
            nt += p.indices().length;
        }
        float[] pos = new float[nv];
        int[] ix = new int[nt];
        int vo = 0, to = 0;
        for (MeshPart p : link) {
            System.arraycopy(p.positions(), 0, pos, vo, p.positions().length);
            for (int i : p.indices()) ix[to++] = i + vo / 3;
            vo += p.positions().length;
        }
        MeshMath.Axes ax = MeshMath.principalAxes(pos, ix);
        double[] b = new MeshPart("", 0, pos, ix).bounds();
        return new double[][]{ModelBuilder.centerOf(b), ModelBuilder.canonical(ax.axis(0))};
    }
}
