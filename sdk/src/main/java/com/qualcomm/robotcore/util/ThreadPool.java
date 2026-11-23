package com.qualcomm.robotcore.util;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import ftcsim.hal.OpModeSession;

/**
 * The SDK's thread pools, as robot code uses them: named executors of its own, and the app's
 * shared default pools. Their threads are daemons (they never keep the simulator running). A
 * shared pool runs each task as part of the robot and OpMode that handed it over.
 */
public class ThreadPool {
    public static final String TAG = "ThreadPool";

    private ThreadPool() {
    }

    /** An executor that can say whether it may run work on a given thread. */
    public interface ThreadBorrowable {
        boolean canBorrowThread(Thread thread);
    }

    private static final ExecutorService DEFAULT = new CallersContext(Executors.newCachedThreadPool(daemons("ThreadPool.default")));
    private static final ExecutorService DEFAULT_SERIAL =
            new CallersContext(Executors.newSingleThreadExecutor(daemons("ThreadPool.defaultSerial")));

    /** The app's shared pool. */
    public static ExecutorService getDefault() {
        return DEFAULT;
    }

    /** The app's shared pool that runs one task at a time, in order. */
    public static ExecutorService getDefaultSerial() {
        return DEFAULT_SERIAL;
    }

    public static ExecutorService newSingleThreadExecutor(String nameRoot) {
        return Executors.newSingleThreadExecutor(daemons(nameRoot));
    }

    public static ExecutorService newFixedThreadPool(int numberOfThreads, String nameRoot) {
        return Executors.newFixedThreadPool(numberOfThreads, daemons(nameRoot));
    }

    public static ExecutorService newCachedThreadPool(String nameRoot) {
        return Executors.newCachedThreadPool(daemons(nameRoot));
    }

    private static ThreadFactory daemons(final String nameRoot) {
        final AtomicInteger count = new AtomicInteger();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, nameRoot == null ? "ThreadPool-" + count.incrementAndGet() : nameRoot + "-" + count.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }

    /** A shared executor whose tasks run in the context of the thread that submitted them. */
    private static final class CallersContext extends AbstractExecutorService {
        private final ExecutorService pool;

        CallersContext(ExecutorService pool) {
            this.pool = pool;
        }

        @Override
        public void execute(Runnable command) {
            pool.execute(OpModeSession.inCallersContext(command));
        }

        // The app's pools last as long as the app: robot code can't shut them down.
        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }
    }
}
