package ftcsim.season;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ftcsim.physics.FieldModel;

/**
 * One FTC season (game): its field, game pieces and rules, from a season pack. A pack is a folder
 * {@code seasons/<id>/} holding a {@code season.json} and, for whatever data can't describe
 * (parts that tip or swing, how a match is staged, what the human player does), a little Java in
 * the same folder (package {@code <id>}) named by season.json's {@code "code"}.
 *
 * <p>The engine itself knows nothing about any particular game: the tiles, the perimeter, the
 * physics, robots and the SDK stay the same from season to season.
 */
public final class Season {

    /** The season used when none is chosen. */
    public static final String DEFAULT = "biobuzz";

    public final String id;
    public final String title;
    public final String years;
    public final JsonObject json;
    /** The pack's folder, or null when it was read from inside the simulator. */
    public final Path dir;
    public final SeasonCode code;
    public final FieldModel field;
    /** The alliance of a single simulated robot. */
    public final String homeAlliance;
    /** At most this many game pieces inside a robot at once. */
    public final int robotCapacity;

    private final Map<String, List<double[]>> starts = new LinkedHashMap<>();
    private final String symmetry;
    private final String partner;

    private Season(JsonObject json, Path dir) throws IOException {
        this.json = json;
        this.dir = dir;
        this.id = json.get("id").getAsString();
        this.title = json.has("title") ? json.get("title").getAsString() : id;
        this.years = json.has("years") ? json.get("years").getAsString() : "";
        this.homeAlliance = json.has("alliance") ? json.get("alliance").getAsString() : "red";
        JsonObject robot = json.has("robot") ? json.getAsJsonObject("robot") : new JsonObject();
        this.robotCapacity = robot.has("capacity") ? robot.get("capacity").getAsInt() : Integer.MAX_VALUE;
        this.symmetry = json.has("symmetry") ? json.get("symmetry").getAsString() : "rotate";
        this.partner = json.has("partner") ? json.get("partner").getAsString() : null;
        if (json.has("start")) {
            JsonObject s = json.getAsJsonObject("start");
            for (String alliance : s.keySet()) {
                List<double[]> spots = new ArrayList<>();
                for (var e : s.getAsJsonArray(alliance)) {
                    JsonArray a = e.getAsJsonArray();
                    spots.add(new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()});
                }
                starts.put(alliance, spots);
            }
        }
        this.code = newCode(json);
        this.field = new FieldModel(this, json);
        code.describe(field);
    }

    private static SeasonCode newCode(JsonObject json) throws IOException {
        if (!json.has("code")) return new SeasonCode() {
        };
        String name = json.get("code").getAsString();
        try {
            return (SeasonCode) Class.forName(name).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IOException("season.json names code \"" + name + "\", which isn't a SeasonCode class built into the simulator", e);
        }
    }

    // ---- loading ---------------------------------------------------------------------------------

    /** A season by id ("biobuzz") or by the path of its folder or season.json. */
    public static Season load(String idOrPath) throws IOException {
        return load(idOrPath, Path.of("seasons"));
    }

    /**
     * A season by id, looked up in {@code seasonsDir} and then among the seasons built into the
     * simulator, or by the path of its folder or season.json.
     */
    public static Season load(String idOrPath, Path seasonsDir) throws IOException {
        Path p = Path.of(idOrPath);
        if (Files.isDirectory(p)) p = p.resolve("season.json");
        if (!Files.isRegularFile(p) && seasonsDir != null) p = seasonsDir.resolve(idOrPath).resolve("season.json");
        if (Files.isRegularFile(p)) {
            try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                return new Season(JsonParser.parseReader(r).getAsJsonObject(), p.getParent());
            } catch (RuntimeException e) {
                throw new IOException(p + ": " + e.getMessage(), e);
            }
        }
        try (InputStream in = Season.class.getResourceAsStream("/seasons/" + idOrPath + "/season.json")) {
            if (in == null) {
                throw new IOException("No season \"" + idOrPath + "\". Seasons: " + String.join(", ", available(seasonsDir)));
            }
            try {
                return new Season(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject(), null);
            } catch (RuntimeException e) {
                throw new IOException("season " + idOrPath + ": " + e.getMessage(), e);
            }
        }
    }

    /** The ids of the season packs in {@code seasonsDir} and the ones built in. */
    public static List<String> available(Path seasonsDir) {
        List<String> out = new ArrayList<>();
        if (seasonsDir != null && Files.isDirectory(seasonsDir)) {
            try (Stream<Path> s = Files.list(seasonsDir)) {
                s.filter(d -> Files.isRegularFile(d.resolve("season.json"))).map(d -> d.getFileName().toString()).sorted().forEach(out::add);
            } catch (IOException e) {
                // fall through to the built-in list
            }
        }
        try (InputStream in = Season.class.getResourceAsStream("/seasons/index.txt")) {
            if (in != null) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    if (!line.isBlank() && !out.contains(line.trim())) out.add(line.trim());
                }
            }
        } catch (IOException e) {
            // none built in
        }
        return out;
    }

    // ---- where robots start ----------------------------------------------------------------------

    /**
     * The season's start spot (x, y in meters, heading in degrees) for a robot at station 1 or 2
     * of an alliance, or null if season.json lists none.
     */
    public double[] startSpot(String alliance, int station) {
        List<double[]> spots = starts.get(alliance);
        if (spots == null || spots.isEmpty()) {
            String other = "red".equals(alliance) ? "blue" : "red";
            List<double[]> o = starts.get(other);
            if (o == null || o.isEmpty()) return null;
            return otherAlliance(o.get(Math.min(station, o.size()) - 1));
        }
        return spots.get(Math.min(station, spots.size()) - 1).clone();
    }

    /**
     * The same spot for the other alliance, by the field's symmetry (season.json "symmetry"):
     * "rotate" half a turn about the field center, or "mirror_x" / "mirror_y" across the x = 0 or
     * y = 0 line.
     */
    public double[] otherAlliance(double[] pose) {
        double x = pose[0], y = pose[1], h = pose[2];
        double[] out = switch (symmetry) {
            case "mirror_y" -> new double[]{x, -y, -h};
            case "mirror_x" -> new double[]{-x, y, 180 - h};
            default -> new double[]{-x, -y, h + 180};
        };
        out[2] = ((out[2] + 180) % 360 + 360) % 360 - 180;
        return out;
    }

    /**
     * Station 2's spot when a robot file gives station 1's: season.json "partner" says how
     * ("mirror_y": across the y = 0 line, beside it when it is on that line), or null to use
     * the season's own station 2 spot.
     */
    public double[] partnerOf(double[] pose) {
        if ("mirror_y".equals(partner)) {
            double y = Math.abs(pose[1]) >= 0.3 ? -pose[1] : pose[1] + 0.65;
            return new double[]{pose[0], y, pose[2]};
        }
        if ("mirror_x".equals(partner)) {
            double x = Math.abs(pose[0]) >= 0.3 ? -pose[0] : pose[0] + 0.65;
            return new double[]{x, pose[1], pose[2]};
        }
        return null;
    }

    /** What the browser needs besides the field: names and where the audience and drivers are. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("years", years);
        m.put("home", homeAlliance);
        if (robotCapacity != Integer.MAX_VALUE) m.put("capacity", robotCapacity);
        JsonObject view = json.has("view") ? json.getAsJsonObject("view") : new JsonObject();
        m.put("audience", view.has("audience") ? view.get("audience").getAsString() : "-y");
        Map<String, String> walls = new LinkedHashMap<>();
        if (view.has("alliance_walls")) {
            JsonObject w = view.getAsJsonObject("alliance_walls");
            for (String k : w.keySet()) walls.put(k, w.get(k).getAsString());
        } else {
            walls.put("red", "-x");
            walls.put("blue", "+x");
        }
        m.put("allianceWalls", walls);
        return m;
    }

    @Override
    public String toString() {
        return title + (years.isEmpty() ? "" : " (" + years + ")");
    }
}
