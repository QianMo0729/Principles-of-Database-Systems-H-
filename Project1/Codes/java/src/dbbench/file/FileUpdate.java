package dbbench.file;

import dbbench.bench.Queries;
import dbbench.util.Args;
import dbbench.util.ResultLog;
import dbbench.util.Stats;
import dbbench.util.Tsv;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/**
 * Bulk update on the text file: replace every "T" in a person's name with "Ttt" (project hint 3).
 *
 * <p>A text file cannot grow a value in place, so any update rewrites the whole file. Three ways of
 * doing that are implemented:
 * <ul>
 *   <li>{@code safe}: stream to a temporary file, fsync it, then atomically rename it over the
 *       original. This is the correct way; a crash leaves either the old or the new file.</li>
 *   <li>{@code nofsync}: the same without fsync, to show what durability costs.</li>
 *   <li>{@code inplace}: read everything into memory and overwrite the original, the shortest code
 *       to write. A crash while writing destroys data.</li>
 * </ul>
 * Rule {@code broad} changes every name containing "T"; rule {@code narrow} changes ten given rows,
 * which still forces a full rewrite. {@code --crash-at f} kills the process (Runtime.halt, the
 * equivalent of kill -9) after the fraction f of the rows has been written.
 */
public final class FileUpdate {
    private FileUpdate() {}

    public static void run(Args a) throws IOException {
        Path pristine = Path.of(a.get("file")), work = Path.of(a.get("work"));
        String mode = a.get("mode", "safe"), rule = a.get("rule", "broad");
        int reps = a.getInt("reps", 5);
        double crashAt = a.getDouble("crash-at", -1);
        int[] narrowIds = Queries.ints(Queries.loadMeta(Path.of(a.get("meta"))).getProperty("narrow_ids"));
        Arrays.sort(narrowIds);
        int[] ids = rule.equals("narrow") ? narrowIds : null;

        try (ResultLog log = new ResultLog(a, "file")) {
            double[] ms = new double[reps];
            long changed = 0;
            for (int r = 0; r < reps; r++) {
                Files.copy(pristine, work, StandardCopyOption.REPLACE_EXISTING); // restore, not timed
                long t0 = System.nanoTime();
                changed = mode.equals("inplace") ? rewriteInPlace(work, ids, crashAt)
                        : rewriteViaTemp(work, ids, mode.equals("safe"), crashAt);
                ms[r] = (System.nanoTime() - t0) / 1e6;
                log.add("update", rule, mode, r, "latency_ms", ms[r], "ms");
            }
            long[] check = summarize(work);
            log.add("update", rule, mode, 0, "rows_changed", changed, "rows");
            log.add("update", rule, mode, 0, "check_rows", check[0], "rows");
            log.add("update", rule, mode, 0, "check_name_bytes", check[1], "bytes");
            log.add("update", rule, mode, 0, "check_rows_with_Ttt", check[2], "rows");
            System.out.printf("file update %-6s %-8s median=%9.1f ms changed=%d rows=%d%n",
                    rule, mode, Stats.median(ms), changed, check[0]);
            Files.deleteIfExists(work);
        }
    }

    /** Reports what is left in a work file after a simulated crash. */
    public static void check(Args a) throws IOException {
        Path pristine = Path.of(a.get("file")), work = Path.of(a.get("work"));
        String variant = a.get("variant");
        try (ResultLog log = new ResultLog(a, "file")) {
            long[] before = summarize(pristine);
            long[] after = Files.exists(work) ? summarize(work) : new long[] {0, 0, 0, 0};
            Path tmp = tempOf(work);
            log.add("crash", "bulk_update", variant, 0, "rows_before", before[0], "rows");
            log.add("crash", "bulk_update", variant, 0, "rows_after", after[0], "rows");
            log.add("crash", "bulk_update", variant, 0, "rows_lost", before[0] - after[0], "rows");
            log.add("crash", "bulk_update", variant, 0, "rows_with_Ttt_before", before[2], "rows");
            log.add("crash", "bulk_update", variant, 0, "rows_with_Ttt_after", after[2], "rows");
            log.add("crash", "bulk_update", variant, 0, "truncated_last_line", after[3], "bool");
            log.add("crash", "bulk_update", variant, 0, "leftover_temp_bytes",
                    Files.exists(tmp) ? Files.size(tmp) : 0, "bytes");
            System.out.printf("file crash %-8s rows %d -> %d (lost %d), rows with Ttt %d -> %d, broken last line=%d%n",
                    variant, before[0], after[0], before[0] - after[0], before[2], after[2], after[3]);
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(work);
        }
    }

    private static long rewriteViaTemp(Path work, int[] ids, boolean fsync, double crashAt) throws IOException {
        Path tmp = tempOf(work);
        long total = crashAt >= 0 ? countLines(work) : 0, written = 0, changed = 0;
        try (BufferedReader in = reader(work);
             FileOutputStream fos = new FileOutputStream(tmp.toFile());
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8), 1 << 20)) {
            String line;
            while ((line = in.readLine()) != null) {
                String updated = apply(line, ids);
                if (updated != line) changed++;
                out.write(updated);
                out.write('\n');
                if (crashAt >= 0 && ++written >= crashAt * total) Runtime.getRuntime().halt(137);
            }
            out.flush();
            if (fsync) fos.getChannel().force(true);
        }
        Files.move(tmp, work, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        if (fsync) {
            try (FileChannel dir = FileChannel.open(work.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
                dir.force(true); // make the rename itself durable
            }
        }
        return changed;
    }

    private static long rewriteInPlace(Path work, int[] ids, double crashAt) throws IOException {
        List<String> lines = Files.readAllLines(work, StandardCharsets.UTF_8);
        long written = 0, changed = 0;
        try (BufferedWriter out = new BufferedWriter(Files.newBufferedWriter(work, StandardCharsets.UTF_8), 1 << 20)) {
            for (String line : lines) {
                String updated = apply(line, ids);
                if (updated != line) changed++;
                out.write(updated);
                out.write('\n');
                if (crashAt >= 0 && ++written >= crashAt * lines.size()) Runtime.getRuntime().halt(137);
            }
        }
        return changed;
    }

    /** Returns the same String object when the row is not affected. */
    private static String apply(String line, int[] ids) {
        int idEnd = line.indexOf('\t');
        int nameEnd = line.indexOf('\t', idEnd + 1);
        if (line.indexOf('T', idEnd + 1) < 0 || line.indexOf('T', idEnd + 1) >= nameEnd) return line;
        if (ids != null && Arrays.binarySearch(ids, Tsv.parseInt(line, 0, idEnd)) < 0) return line;
        return line.substring(0, idEnd + 1)
                + line.substring(idEnd + 1, nameEnd).replace("T", "Ttt")
                + line.substring(nameEnd);
    }

    /**
     * {rows, total UTF-8 bytes of the names, rows whose name contains "Ttt", 1 if the last line is cut}.
     * The first three are computed the same way in SQL to prove both sides hold identical data.
     */
    static long[] summarize(Path file) throws IOException {
        long rows = 0, nameBytes = 0, withTtt = 0, broken = 0;
        try (BufferedReader in = reader(file)) {
            String line;
            while ((line = in.readLine()) != null) {
                int idEnd = line.indexOf('\t');
                int nameEnd = idEnd < 0 ? -1 : line.indexOf('\t', idEnd + 1);
                int tabs = 0;
                for (int i = 0; i < line.length(); i++) if (line.charAt(i) == '\t') tabs++;
                if (nameEnd < 0 || tabs != 5) {
                    broken = 1; // an incomplete record: the write stopped in the middle of a row
                    continue;
                }
                rows++;
                String name = line.substring(idEnd + 1, nameEnd);
                if (name.equals(Tsv.NULL)) continue;
                name = Tsv.unescape(name);
                nameBytes += name.getBytes(StandardCharsets.UTF_8).length;
                if (name.contains("Ttt")) withTtt++;
            }
        }
        return new long[] {rows, nameBytes, withTtt, broken};
    }

    private static long countLines(Path file) throws IOException {
        long n = 0;
        try (BufferedReader in = reader(file)) {
            while (in.readLine() != null) n++;
        }
        return n;
    }

    private static BufferedReader reader(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8), 1 << 20);
    }

    private static Path tempOf(Path work) {
        return work.resolveSibling(work.getFileName() + ".tmp");
    }
}
