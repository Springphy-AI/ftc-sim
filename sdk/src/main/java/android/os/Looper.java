package android.os;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stand-in for Android's message loop. There is one, the "main" looper: a single daemon thread
 * that runs what {@link Handler#post(Runnable)} gives it, in order, like the app's UI thread.
 */
public final class Looper {
    private static final Looper MAIN = new Looper();

    final ExecutorService executor;
    private volatile Thread thread;

    private Looper() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "main");
            t.setDaemon(true);
            thread = t;
            return t;
        });
    }

    public static Looper getMainLooper() {
        return MAIN;
    }

    /** The looper of the calling thread: the main looper on its own thread, otherwise null. */
    public static Looper myLooper() {
        return Thread.currentThread() == MAIN.thread ? MAIN : null;
    }

    public Thread getThread() {
        return thread;
    }
}
