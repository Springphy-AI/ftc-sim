package android.text;

/** Stand-in for Android's TextUtils: the string helpers SDK code uses. */
public class TextUtils {

    private TextUtils() {
    }

    public static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null || a.length() != b.length()) return false;
        return a.toString().contentEquals(b);
    }

    public static String join(CharSequence delimiter, Iterable<?> tokens) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object t : tokens) {
            if (!first) sb.append(delimiter);
            sb.append(t);
            first = false;
        }
        return sb.toString();
    }

    public static String join(CharSequence delimiter, Object[] tokens) {
        return join(delimiter, java.util.Arrays.asList(tokens));
    }
}
