package dbbench.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal "--key value" command-line parser; a key without a value is a boolean flag. */
public final class Args {
    private final Map<String, String> values = new LinkedHashMap<>();

    public Args(String[] argv, int from) {
        for (int i = from; i < argv.length; i++) {
            String a = argv[i];
            if (!a.startsWith("--")) throw new IllegalArgumentException("unexpected argument: " + a);
            boolean hasValue = i + 1 < argv.length && !argv[i + 1].startsWith("--");
            values.put(a.substring(2), hasValue ? argv[++i] : "true");
        }
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public String get(String key) {
        String v = values.get(key);
        if (v == null) throw new IllegalArgumentException("missing required option --" + key);
        return v;
    }

    public String get(String key, String dflt) {
        return values.getOrDefault(key, dflt);
    }

    public int getInt(String key, int dflt) {
        return has(key) ? Integer.parseInt(get(key)) : dflt;
    }

    public double getDouble(String key, double dflt) {
        return has(key) ? Double.parseDouble(get(key)) : dflt;
    }
}
