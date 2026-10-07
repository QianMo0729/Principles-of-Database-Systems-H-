package dbbench.file;

import dbbench.bench.Queries;
import dbbench.bench.Queries.Search;
import dbbench.util.Args;
import dbbench.util.ResultLog;
import dbbench.util.Stats;
import dbbench.util.Tsv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Retrieval directly on the text file: every query is one sequential scan.
 *
 * <p>This is the straightforward way to search a file in Java. The scan locates the title column by
 * its tab positions and compares inside the line, so it does no avoidable work, but it has no way
 * to skip rows: its cost is proportional to the file size whatever the query returns. Matching rows
 * are materialised as (id, title) pairs, mirroring a client that fetches the whole result set.
 */
public final class FileSearch {
    /** One result row. */
    public record Hit(int id, String title) {}

    private FileSearch() {}

    public static void run(Args a) throws IOException {
        Path file = Path.of(a.get("file"));
        List<Search> queries = Queries.load(Path.of(a.get("meta")));
        int reps = a.getInt("reps", 5), warmup = a.getInt("warmup", 2);
        try (ResultLog log = new ResultLog(a, "file")) {
            for (Search q : queries) {
                double[] ms = new double[reps];
                List<Hit> hits = List.of();
                for (int i = -warmup; i < reps; i++) {
                    long t0 = System.nanoTime();
                    hits = execute(file, q);
                    double elapsed = (System.nanoTime() - t0) / 1e6;
                    if (q.kind() == Queries.Kind.POINT) elapsed /= q.ids().length; // per lookup
                    if (i >= 0) {
                        ms[i] = elapsed;
                        log.add("search", q.name(), "scan", i, "latency_ms", elapsed, "ms");
                    }
                }
                log.add("search", q.name(), "scan", 0, "rows", hits.size(), "rows");
                log.add("search", q.name(), "scan", 0, "id_sum", idSum(hits), "sum");
                System.out.printf("file scan %-16s median=%9.3f ms rows=%d%n", q.name(), Stats.median(ms), hits.size());
            }
        }
    }

    static List<Hit> execute(Path file, Search q) throws IOException {
        List<Hit> hits = new ArrayList<>();
        if (q.kind() == Queries.Kind.POINT) {
            for (int id : q.ids()) scan(file, q, id, hits);
        } else {
            scan(file, q, -1, hits);
        }
        return hits;
    }

    private static void scan(Path file, Search q, int targetId, List<Hit> hits) throws IOException {
        String text = q.text();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8), 1 << 20)) {
            String line;
            while ((line = in.readLine()) != null) {
                int idEnd = line.indexOf('\t');
                int titleStart = line.indexOf('\t', idEnd + 1) + 1;
                int titleEnd = line.indexOf('\t', titleStart);
                boolean match;
                switch (q.kind()) {
                    case POINT -> match = Tsv.parseInt(line, 0, idEnd) == targetId;
                    case EXACT -> match = titleEnd - titleStart == text.length()
                            && line.startsWith(text, titleStart);
                    case PREFIX -> match = titleEnd - titleStart >= text.length()
                            && line.startsWith(text, titleStart);
                    default -> {
                        int at = line.indexOf(text, titleStart);
                        match = at >= 0 && at + text.length() <= titleEnd;
                    }
                }
                if (match) {
                    hits.add(new Hit(Tsv.parseInt(line, 0, idEnd), Tsv.unescape(line.substring(titleStart, titleEnd))));
                    if (q.kind() == Queries.Kind.POINT) return; // ids are unique: stop at the first match
                }
            }
        }
    }

    static long idSum(List<Hit> hits) {
        long sum = 0;
        for (Hit h : hits) sum += h.id();
        return sum;
    }
}
