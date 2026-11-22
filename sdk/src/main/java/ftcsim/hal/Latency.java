package ftcsim.hal;

import java.util.concurrent.locks.LockSupport;

/**
 * Simulated bus latency. On a real Control Hub every motor/servo command and every un-cached
 * read is a USB/RS485 transaction that takes about a millisecond, and I2C sensor reads take
 * longer. Reproducing that keeps OpMode loop times (and therefore controller tuning) realistic.
 * Set both to 0 in the robot JSON ("io" section) to run as fast as possible.
 *
 * <p>Each robot's threads use that robot's settings ({@link RobotContext}); the static fields
 * here apply to code running outside any robot, such as tests.
 */
public final class Latency {
    public static volatile long commandNanos = 1000000L;
    public static volatile long i2cNanos = 2500000L;

    private Latency() {
    }

    // Every hardware call comes through here, which is where an OpMode STOP gave up on gets unwound
    // (OpModeSession.checkStillRunning).

    public static void command() {
        OpModeSession.checkStillRunning();
        RobotContext c = RobotContext.current();
        spin(c == null ? commandNanos : c.commandNanos);
    }

    public static void i2c() {
        OpModeSession.checkStillRunning();
        RobotContext c = RobotContext.current();
        spin(c == null ? i2cNanos : c.i2cNanos);
    }

    static void spin(long nanos) {
        if (nanos <= 0) return;
        long end = System.nanoTime() + nanos;
        if (nanos > 3000000L) {
            LockSupport.parkNanos(nanos - 1500000L);
        }
        while (System.nanoTime() < end) {
            Thread.yield();
        }
    }
}
