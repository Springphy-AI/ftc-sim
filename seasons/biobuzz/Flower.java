package biobuzz;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * A FLOWER (Competition Manual 9.7): an upright tube against the perimeter wall. Balls go in at the
 * top ({@code height}) and POLLEN come out of the Retrieval Opening at the bottom, which faces
 * {@code yaw} (into the field). {@code innerRadius} is the inside of the tube; {@code retrievalTop}
 * the height of the Retrieval Opening, where the middle ring starts.
 */
public record Flower(String name, double x, double y, double yaw, double innerRadius, double height, double retrievalTop) {

    /**
     * The FLOWERS in season.json "flowers". The tube is as wide as the 10.15 cm top opening, so
     * POLLEN stack single file and two can't wedge side by side (a 12.2 cm tube, the field data's
     * "scoring_volume", let them jam), and a NECTAR (9.1 cm) still fits.
     */
    static List<Flower> of(JsonObject json) {
        List<Flower> out = new ArrayList<>();
        JsonObject flowers = json.getAsJsonObject("flowers");
        for (String name : flowers.keySet()) {
            JsonObject f = flowers.getAsJsonObject(name);
            JsonArray axis = f.getAsJsonArray("axis_m");
            out.add(new Flower(name, axis.get(0).getAsDouble(), axis.get(1).getAsDouble(), f.get("inward_yaw_rad").getAsDouble(),
                    f.get("opening_diameter_m").getAsDouble() / 2, f.get("opening_height_m").getAsDouble(),
                    f.getAsJsonArray("retrieval_opening_m").get(1).getAsDouble()));
        }
        return out;
    }

    /** Whether a point is inside the tube, below its top. */
    public boolean holds(double wx, double wy, double wz) {
        return Math.hypot(wx - x, wy - y) < innerRadius && wz < height;
    }

    /** A point in the FLOWER's frame (on its axis, +x into the field) to world. */
    double[] toWorld(double lx, double ly, double z) {
        double c = Math.cos(yaw), s = Math.sin(yaw);
        return new double[]{x + c * lx - s * ly, y + s * lx + c * ly, z};
    }
}
