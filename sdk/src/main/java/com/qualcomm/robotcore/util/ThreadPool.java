package com.qualcomm.robotcore.util;

/**
 * The part of the SDK's thread pool utilities that FIRST's {@code Continuation} refers to. The
 * robot's thread pools themselves are Android app plumbing that robot code does not use.
 */
public class ThreadPool {
    public static final String TAG = "ThreadPool";

    private ThreadPool() {
    }

    /** An executor that can say whether it may run work on a given thread. */
    public interface ThreadBorrowable {
        boolean canBorrowThread(Thread thread);
    }
}
