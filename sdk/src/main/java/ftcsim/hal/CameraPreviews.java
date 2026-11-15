package ftcsim.hal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The camera previews the Robot Controller would show (its "live view"): each streaming
 * VisionPortal publishes its latest frame here, with the processors' overlays drawn on it, and
 * the Driver Station page displays them.
 */
public final class CameraPreviews {

    /** One portal's preview. */
    public static final class Preview {
        /** Unique while the simulator runs, so a viewer can ask for this preview by number. */
        public final int id = NEXT_ID.incrementAndGet();
        public final int robot;
        public final String camera;
        private int[] argb = new int[0];
        private int width, height;
        private long sequence;
        private volatile float fps;

        Preview(int robot, String camera) {
            this.robot = robot;
            this.camera = camera;
        }

        /** Publishes a frame (ARGB pixels, row by row); the array is copied. */
        public synchronized void publish(int[] pixels, int w, int h, float fps) {
            if (argb.length != pixels.length) argb = new int[pixels.length];
            System.arraycopy(pixels, 0, argb, 0, pixels.length);
            width = w;
            height = h;
            sequence++;
            this.fps = fps;
            notifyAll();
        }

        /** The latest frame if newer than {@code afterSequence}, waiting up to {@code timeoutMs}; else null. */
        public synchronized Frame next(long afterSequence, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (sequence <= afterSequence) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0 || !PREVIEWS.contains(this)) return null;
                wait(left);
            }
            return new Frame(argb.clone(), width, height, sequence, fps);
        }

        public float fps() {
            return fps;
        }

        synchronized void wake() {
            notifyAll();
        }
    }

    /** A copy of one published frame. */
    public static final class Frame {
        public final int[] argb;
        public final int width, height;
        public final long sequence;
        public final float fps;

        Frame(int[] argb, int width, int height, long sequence, float fps) {
            this.argb = argb;
            this.width = width;
            this.height = height;
            this.sequence = sequence;
            this.fps = fps;
        }
    }

    private static final List<Preview> PREVIEWS = new CopyOnWriteArrayList<Preview>();
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_ID = new java.util.concurrent.atomic.AtomicInteger();

    /** The open preview with this id, or null. */
    public static Preview byId(int id) {
        for (Preview p : PREVIEWS) if (p.id == id) return p;
        return null;
    }

    private CameraPreviews() {
    }

    public static Preview open(int robot, String camera) {
        Preview p = new Preview(robot, camera);
        PREVIEWS.add(p);
        return p;
    }

    public static void close(Preview p) {
        PREVIEWS.remove(p);
        p.wake();
    }

    /** Previews of one robot, oldest first. */
    public static List<Preview> of(int robot) {
        List<Preview> out = new ArrayList<Preview>();
        for (Preview p : PREVIEWS) if (p.robot == robot) out.add(p);
        return out;
    }
}
