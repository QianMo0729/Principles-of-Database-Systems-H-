package dbbench.util;

/**
 * Helpers for the cleaned data files.
 *
 * <p>The files use PostgreSQL's COPY text format (tab separated, {@code \N} for NULL, backslash
 * doubled) so exactly the same bytes are read by the Java file programs and loaded into both
 * databases.
 */
public final class Tsv {
    public static final String NULL = "\\N";

    private Tsv() {}

    /** Splits {@code line} on tabs into {@code out}; returns the number of fields found. */
    public static int split(String line, String[] out) {
        int n = 0, start = 0;
        while (n < out.length - 1) {
            int tab = line.indexOf('\t', start);
            if (tab < 0) break;
            out[n++] = line.substring(start, tab);
            start = tab + 1;
        }
        out[n++] = line.substring(start);
        return n;
    }

    /** Escapes one raw value for the cleaned file. */
    public static String escape(String raw) {
        if (raw.indexOf('\\') < 0 || raw.equals(NULL)) return raw;
        return raw.replace("\\", "\\\\");
    }

    /** Reverses {@link #escape}; the common case (no backslash) returns the same object. */
    public static String unescape(String field) {
        return field.indexOf('\\') < 0 ? field : field.replace("\\\\", "\\");
    }

    /** Parses the non-negative integer in {@code s[from, to)} without allocating. */
    public static int parseInt(String s, int from, int to) {
        int v = 0;
        for (int i = from; i < to; i++) v = v * 10 + (s.charAt(i) - '0');
        return v;
    }
}
