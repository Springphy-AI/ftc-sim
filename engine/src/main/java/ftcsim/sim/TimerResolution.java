package ftcsim.sim;

import java.util.Locale;

/**
 * Keeps Windows' timer at 1 ms resolution while the simulator runs.
 *
 * <p>The physics loop, the bus latency of hardware calls, the webcams and VisionPortal pause with
 * {@code LockSupport.parkNanos}. On Windows the JVM turns a park into a wait of at least 1 ms on
 * the system timer, and without help that timer ticks every 15.6 ms: the physics loop then ran
 * about 16 steps at once every 15.6 ms, so OpModes saw sensors and the field saw motor commands
 * about 64 times a second instead of every millisecond. The JVM raises the timer to 1 ms
 * ({@code timeBeginPeriod(1)}) only around {@code Thread.sleep} waits whose length in milliseconds
 * isn't a multiple of 10, and holds it for as long as the wait lasts. So one thread that sleeps
 * forever holds it for the whole process: a {@code Thread.sleep(Long.MAX_VALUE)} waits in chunks of
 * 0x10000000 ms, each inside its own raised period. (Since Windows 10 2004 this changes the timer
 * for this process only; it costs a little power, as for any real-time program.) Elsewhere the
 * timer is fine and nothing is started.
 */
final class TimerResolution {
    private static boolean started;

    private TimerResolution() {
    }

    /** Starts the thread that holds the 1 ms timer, once, on Windows. */
    static synchronized void hold() {
        if (started || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) return;
        started = true;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    // nothing to stop: it is a daemon, and the timer must stay raised
                }
            }
        }, "timer-resolution");
        t.setDaemon(true);
        t.start();
    }
}
