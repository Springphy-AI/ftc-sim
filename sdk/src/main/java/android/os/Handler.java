package android.os;

/** Stand-in for Android's Handler: posts work to a {@link Looper}'s thread. */
public class Handler {
    private final Looper looper;

    /** Like Android, uses the calling thread's looper; threads without one get the main looper. */
    public Handler() {
        Looper mine = Looper.myLooper();
        this.looper = mine != null ? mine : Looper.getMainLooper();
    }

    public Handler(Looper looper) {
        if (looper == null) throw new NullPointerException("looper");
        this.looper = looper;
    }

    public final Looper getLooper() {
        return looper;
    }

    public final boolean post(Runnable r) {
        looper.executor.execute(r);
        return true;
    }
}
