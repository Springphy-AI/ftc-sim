package decode;

import ftcsim.season.SeasonCode;
import ftcsim.season.SeasonGame;
import ftcsim.sim.GameElements;

/**
 * DECODE presented by RTX (FTC 2025-26). Its field, ARTIFACTS, staging and human player are all
 * season.json data; the code here is only the OBELISK's MOTIF and what the top bar counts.
 */
public final class Decode implements SeasonCode {

    @Override
    public SeasonGame newGame(GameElements elements) {
        return new DecodeGame(elements);
    }
}
