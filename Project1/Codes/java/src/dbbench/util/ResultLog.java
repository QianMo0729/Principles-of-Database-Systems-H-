package dbbench.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Appends measurements to a "tidy" CSV: one row per measured value.
 *
 * <p>Every experiment writes the same columns, so results from the file programs, PostgreSQL and
 * openGauss, and from every VM spec, can be aggregated with one script.
 */
public final class ResultLog implements AutoCloseable {
    public static final String HEADER =
            "run_ts,spec,placement,target,config,scale,experiment,operation,variant,rep,metric,value,unit,note";

    private final PrintWriter out;
    private final String context;

    /**
     * @param args supplies --out plus the context columns --spec, --placement, --config, --scale
     * @param target "file", "postgres" or "opengauss"
     */
    public ResultLog(Args args, String target) throws IOException {
        Path path = Path.of(args.get("out"));
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        boolean fresh = !Files.exists(path) || Files.size(path) == 0;
        out = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        if (fresh) out.println(HEADER);
        context = String.join(",",
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                args.get("spec", "na"), args.get("placement", "na"), target,
                args.get("config", "na"), args.get("scale", "na"));
    }

    public synchronized void add(String experiment, String operation, String variant, int rep,
                                 String metric, double value, String unit, String note) {
        out.println(String.join(",", context, experiment, operation, variant, Integer.toString(rep),
                metric, format(value), unit, quote(note)));
        out.flush();
    }

    public void add(String experiment, String operation, String variant, int rep,
                    String metric, double value, String unit) {
        add(experiment, operation, variant, rep, metric, value, unit, "");
    }

    private static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        // Six significant digits: in-memory lookups are measured in fractions of a microsecond.
        return String.format(Locale.ROOT, "%.6g", v);
    }

    private static String quote(String s) {
        if (s == null || s.isEmpty()) return "";
        String flat = s.replace('\n', ' ').replace('\r', ' ');
        return '"' + flat.replace("\"", "\"\"") + '"';
    }

    @Override
    public void close() {
        out.close();
    }
}
