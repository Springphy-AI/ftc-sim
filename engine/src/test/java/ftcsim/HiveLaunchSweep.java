package ftcsim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The launch sweep behind the report's HIVE calibration (section 4). From a grid of distances,
 * launcher powers and hood positions, the default robot launches 2 of its POLLEN at the red HIVE's
 * up CELL, which holds the 3 starting NECTAR, and then, from a fresh field, 3. A setting counts only
 * if both of the first 2 POLLEN land in the CELL. It reports what happened and checks nothing about
 * the outcome. Not part of {@code test}; run it with
 * {@code gradlew :engine:experiment --tests ftcsim.HiveLaunchSweep} (about 15 minutes).
 */
@Tag("experiment")
class HiveLaunchSweep {
    private static final double[] YS = {-1.0, -1.1, -1.2, -1.3};
    private static final double[] POWERS = {0.38, 0.40, 0.42, 0.44, 0.46, 0.48};
    private static final double[] HOODS = {0.70, 0.75, 0.80, 0.85, 0.90, 0.95};

    @Test
    void twoLaunchedPollenThenAThird() throws Exception {
        int settings = 0, landed = 0, twoTipped = 0, thirdTipped = 0, thirdHeld = 0, thirdMissed = 0;
        double nearest = Double.MAX_VALUE, farthest = 0;
        StringBuilder tippedByTwo = new StringBuilder();
        System.out.println("distance_m  power  hood  2 launched: landed, tipped  3 launched: tipped");
        for (double y : YS) {
            for (double power : POWERS) {
                for (double hood : HOODS) {
                    settings++;
                    HiveTipTest.Launch two = HiveTipTest.launch(2, power, hood, y);
                    if (!two.tipped() && two.inCell() != 5) {
                        System.out.printf("%10.2f  %5.2f  %4.2f  %d, no%n", two.distanceM(), power, hood, two.inCell() - 3);
                        continue;
                    }
                    landed++;
                    nearest = Math.min(nearest, two.distanceM());
                    farthest = Math.max(farthest, two.distanceM());
                    if (two.tipped()) {
                        twoTipped++;
                        tippedByTwo.append(String.format(" (%.2f m, power %.2f, hood %.2f)", two.distanceM(), power, hood));
                        System.out.printf("%10.2f  %5.2f  %4.2f  2, yes%n", two.distanceM(), power, hood);
                        continue;
                    }
                    HiveTipTest.Launch three = HiveTipTest.launch(3, power, hood, y);
                    String third;
                    if (three.tipped()) { thirdTipped++; third = "yes"; }
                    else if (three.inCell() != 6) { thirdMissed++; third = "no, the 3rd missed"; }
                    else { thirdHeld++; third = "no"; }
                    System.out.printf("%10.2f  %5.2f  %4.2f  2, no   %s%n", two.distanceM(), power, hood, third);
                }
            }
        }
        System.out.printf("SUMMARY: %d settings; both POLLEN landed at %d (%.2f to %.2f m from the CELL's open end)."
                        + " 2 POLLEN tipped the HIVE at %d:%s. Of the rest, a 3rd tipped it at %d, landed without tipping it"
                        + " at %d, and missed at %d.%n",
                settings, landed, nearest, farthest, twoTipped, tippedByTwo, thirdTipped, thirdHeld, thirdMissed);
        assertTrue(landed > 0, "no setting landed both POLLEN");
    }
}
