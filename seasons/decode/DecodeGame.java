package decode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.ode4j.math.DVector3C;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import ftcsim.physics.HingeBody;
import ftcsim.season.SeasonGame;
import ftcsim.sim.GameElements;
import ftcsim.sim.GameElements.Ball;

/**
 * The DECODE game on one field. The ARTIFACTS are staged from season.json; this adds the MOTIF
 * (the OBELISK face toward the field, chosen at random for each match) and counts, for each
 * GOAL, the ARTIFACTS that went through its SQUARE, the ones on its RAMP held by the GATE, and
 * how many of those match the MOTIF's PATTERN. That is where the pieces are, not a MATCH score.
 */
public final class DecodeGame extends SeasonGame {

    /** One alliance's GOAL: its RAMP and the SQUARE balls pass on their way in. */
    private static final class Goal {
        final String alliance;
        final double rampX0, rampX1, rampY0, rampY1, rampTop0, rampTop1;
        final double[] square;            // x0, x1, y0, y1, z0, z1
        final Set<Ball> inSquare = new HashSet<>();
        int through;

        Goal(String alliance, JsonObject g) {
            this.alliance = alliance;
            JsonArray x = g.getAsJsonArray("ramp_x_m"), y = g.getAsJsonArray("ramp_y_m"), top = g.getAsJsonArray("ramp_top_m");
            rampX0 = x.get(0).getAsDouble();
            rampX1 = x.get(1).getAsDouble();
            rampY0 = y.get(0).getAsDouble();
            rampY1 = y.get(1).getAsDouble();
            rampTop0 = top.get(0).getAsDouble();
            rampTop1 = top.get(1).getAsDouble();
            JsonObject s = g.getAsJsonObject("square_m");
            square = new double[]{s.getAsJsonArray("x").get(0).getAsDouble(), s.getAsJsonArray("x").get(1).getAsDouble(),
                    s.getAsJsonArray("y").get(0).getAsDouble(), s.getAsJsonArray("y").get(1).getAsDouble(),
                    s.getAsJsonArray("z").get(0).getAsDouble(), s.getAsJsonArray("z").get(1).getAsDouble()};
        }

        boolean inSquare(DVector3C p) {
            return p.get0() > square[0] && p.get0() < square[1] && p.get1() > square[2] && p.get1() < square[3]
                    && p.get2() > square[4] && p.get2() < square[5];
        }

        /** On the RAMP itself (resting on it, not riding over the others as OVERFLOW). */
        boolean onRamp(Ball b) {
            DVector3C p = b.position();
            double x = p.get0();
            if (x < rampX0 || x > rampX1 + 0.03 || p.get1() < rampY0 || p.get1() > rampY1) return false;
            double t = Math.max(0, Math.min(1, (x - rampX0) / (rampX1 - rampX0)));
            double top = rampTop0 + t * (rampTop1 - rampTop0);
            return p.get2() > top && p.get2() < top + b.radius + 0.03;
        }
    }

    private final List<Goal> goals = new ArrayList<>();
    private final Random random = new Random();
    private int motifTag = 21;

    DecodeGame(GameElements elements) {
        super(elements);
        JsonObject g = json.getAsJsonObject("goals");
        for (String alliance : g.keySet()) goals.add(new Goal(alliance, g.getAsJsonObject(alliance)));
    }

    /** The DECODE game on a field that plays DECODE. */
    public static DecodeGame of(GameElements elements) {
        return (DecodeGame) elements.game();
    }

    @Override
    public void restage() {
        super.restage();
        for (Goal g : goals) {
            g.through = 0;
            g.inSquare.clear();
        }
        setMotif(21 + random.nextInt(3));
    }

    // ---- the MOTIF ----------------------------------------------------------------------------------

    /**
     * Turns the OBELISK so the face with AprilTag {@code tag} (21 GPP, 22 PGP, 23 PPG) looks at
     * the field, as FIELD STAFF do before each match.
     */
    public void setMotif(int tag) {
        if (tag < 21 || tag > 23) throw new IllegalArgumentException("the OBELISK has tags 21, 22 and 23, not " + tag);
        motifTag = tag;
        HingeBody obelisk = world.mover("OBELISK");
        // Seen from above the faces run clockwise 21, 22, 23: turning 120 degrees brings the next one round.
        if (obelisk != null) obelisk.setAngle((tag - 21) * 2 * Math.PI / 3);
    }

    /** The AprilTag on the OBELISK face toward the field. */
    public int motifTag() {
        return motifTag;
    }

    /** The MOTIF: "GPP", "PGP" or "PPG". */
    public String motif() {
        return json.getAsJsonObject("motifs").get(String.valueOf(motifTag)).getAsString();
    }

    // ---- counting ---------------------------------------------------------------------------------------

    @Override
    public void beforeStep(double dt) {
        for (Goal g : goals) {
            for (Ball b : elements.balls()) {
                boolean in = b.state() == GameElements.State.FREE && g.inSquare(b.position());
                if (in && g.inSquare.add(b)) g.through++;
                else if (!in) g.inSquare.remove(b);
            }
        }
    }

    private Goal goal(String alliance) {
        for (Goal g : goals) if (g.alliance.equals(alliance)) return g;
        throw new IllegalArgumentException("no " + alliance + " GOAL");
    }

    /** ARTIFACTS that have gone through this alliance's SQUARE since the last reset. */
    public int through(String alliance) {
        return goal(alliance).through;
    }

    /** ARTIFACTS on this alliance's RAMP, from the GATE up (index 1 first). */
    public List<Ball> ramp(String alliance) {
        Goal g = goal(alliance);
        List<Ball> out = new ArrayList<>();
        for (Ball b : elements.balls()) if (b.state() == GameElements.State.FREE && g.onRamp(b)) out.add(b);
        out.sort(Comparator.comparingDouble((Ball b) -> b.position().get0()).reversed());
        return out;
    }

    /** RAMP indices (1 to 9) whose ARTIFACT's color matches the MOTIF repeated three times. */
    public int pattern(String alliance) {
        return pattern(ramp(alliance));
    }

    private int pattern(List<Ball> r) {
        String motif = motif();
        int n = 0;
        for (int i = 0; i < r.size() && i < 9; i++) {
            char want = motif.charAt(i % 3);
            if (Character.toUpperCase(r.get(i).color.charAt(0)) == want) n++;
        }
        return n;
    }

    @Override
    public List<Stat> stats() {
        List<Stat> out = new ArrayList<>();
        out.add(new Stat("MOTIF", motif(), "The OBELISK face toward the field (AprilTag " + motifTag + "), chosen at random at each Reset"));
        for (Goal g : goals) {
            String a = g.alliance;
            List<Ball> ramp = ramp(a);
            out.add(new Stat(Character.toUpperCase(a.charAt(0)) + a.substring(1) + " GOAL",
                    g.through + " in · " + ramp.size() + " on RAMP · PATTERN " + pattern(ramp) + "/9",
                    "ARTIFACTS through the " + a + " SQUARE since Reset, on the " + a
                            + " RAMP now, and RAMP places matching the MOTIF (where the pieces are, not a MATCH score)"));
        }
        return out;
    }
}
