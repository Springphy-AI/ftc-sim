package ftcsim;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ftcsim.hal.VisionNatives;

/**
 * Vision tests need OpenCV and the AprilTag detector built for this machine (natives/apriltag/).
 * Where the build says both exist (ftcsim.visionExpected), a loading problem fails the test;
 * on platforms without them the test is skipped.
 */
final class VisionAssumptions {
    private VisionAssumptions() {
    }

    static void assumeDetectorAvailable() {
        String problem = VisionNatives.await();
        String where = System.getProperty("os.name") + " " + System.getProperty("os.arch");
        if (Boolean.getBoolean("ftcsim.visionExpected")) {
            assertNull(problem, "vision libraries should load on " + where);
        } else {
            assumeTrue(problem == null, () -> "vision libraries not available on " + where + ": " + problem);
        }
    }
}
