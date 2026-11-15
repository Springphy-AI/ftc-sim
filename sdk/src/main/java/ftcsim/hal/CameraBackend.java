package ftcsim.hal;

/**
 * The simulated webcam itself: the engine renders what it sees from where it is mounted on the
 * robot. VisionPortal pulls frames from it the way the robot's VisionPortal pulls them over USB.
 */
public interface CameraBackend {

    /** USB vendor id of the webcam model; FIRST's lens calibrations are keyed by vendor/product. */
    int vendorId();

    /** USB product id of the webcam model. */
    int productId();

    /** Frames per second the camera delivers. */
    double fps();

    /**
     * The frame VisionPortal receives now. Like a real webcam's, it was exposed a little while ago
     * (the camera's latency) and shows the world as it was then.
     *
     * @param width  image width in pixels
     * @param height image height in pixels
     * @param rgba   receives width * height * 4 bytes, row by row, R G B A
     * @return {@link System#nanoTime()} at the moment the image shows (its exposure)
     */
    long capture(int width, int height, byte[] rgba);
}
