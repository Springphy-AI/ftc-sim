package ftcsim.season;

import ftcsim.physics.FieldModel;
import ftcsim.sim.GameElements;

/**
 * A season's own code, for what season.json can't say. season.json names the class
 * ({@code "code": "biobuzz.Biobuzz"}); a season without one runs on the data alone.
 */
public interface SeasonCode {

    /**
     * Adds shapes worked out from season.json to the field: structures, tape, AprilTags,
     * {@link FieldModel.Mover}s. Called once when the season loads, after the shapes season.json
     * lists itself.
     */
    default void describe(FieldModel field) {
    }

    /** The game on one simulated field: staging, the human player, what the top bar shows. */
    default SeasonGame newGame(GameElements elements) {
        return new SeasonGame(elements);
    }
}
