package dbbench.db;

import dbbench.bench.Queries;
import dbbench.bench.Queries.Search;
import dbbench.util.Args;
import dbbench.util.ResultLog;
import dbbench.util.Stats;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The retrieval workload through JDBC.
 *
 * <p>Two times are recorded for every query. {@code latency_ms} is what the client observes: send
 * the statement, then read every result row. {@code server_ms} is the execution time reported by
 * EXPLAIN ANALYZE, i.e. the work inside the engine without the protocol and result transfer. The
 * plan itself is saved so the access path (sequential scan, index scan, ...) is on record.
 *
 * <p>Statements carry literal values on purpose: the planner then sees the pattern and can decide
 * whether an index applies, and the saved plan is exactly the plan that was timed.
 */
public final class DbSearch {
    private static final Pattern SERVER_TIME = Pattern.compile("(?:Execution Time|Total runtime): ([0-9.]+) ms");

    private DbSearch() {}

    public static void run(Args a) throws Exception {
        Db db = Db.from(a);
        String variant = a.get("variant"), scale = a.get("scale");
        // --label records the run under another name; --session-sql changes a session setting first
        // (used to switch parallel query off or on, so its contribution can be measured).
        String label = a.get("label", variant);
        int reps = a.getInt("reps", 5), warmup = a.getInt("warmup", 2);
        Path plans = Path.of(a.get("plans-dir"));
        Files.createDirectories(plans);
        try (ResultLog log = new ResultLog(a, db.label()); Connection c = db.connect("bench_" + scale)) {
            if (a.has("session-sql")) Db.exec(c, a.get("session-sql"));
            for (Search q : Queries.load(Path.of(a.get("meta")))) {
                // With the full-text index only whole-word queries are meaningful; they replace the
                // substring queries (the semantics differ, so results are compared between databases only).
                boolean word = variant.equals("fts");
                if (word && q.kind() != Queries.Kind.CONTAINS) continue;
                String name = word ? q.name().replace("contains", "word") : q.name();
                List<String> sqls = sql(q, word);

                double[] ms = new double[reps];
                long rows = 0, idSum = 0;
                for (int i = -warmup; i < reps; i++) {
                    rows = 0;
                    idSum = 0;
                    long t0 = System.nanoTime();
                    for (String sql : sqls) {
                        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                            while (rs.next()) {
                                idSum += rs.getInt(1);
                                rs.getString(2);
                                rows++;
                            }
                        }
                    }
                    double elapsed = (System.nanoTime() - t0) / 1e6 / sqls.size();
                    if (i >= 0) {
                        ms[i] = elapsed;
                        log.add("search", name, label, i, "latency_ms", elapsed, "ms");
                    }
                }
                log.add("search", name, label, 0, "rows", rows, "rows");
                log.add("search", name, label, 0, "id_sum", idSum, "sum");

                String plan = explain(c, sqls.get(0));
                Files.writeString(plans.resolve(String.join("_", db.label(), scale, a.get("config", "na"), label, name) + ".txt"),
                        sqls.get(0) + "\n\n" + plan, StandardCharsets.UTF_8);
                Matcher m = SERVER_TIME.matcher(plan);
                double serverMs = m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
                log.add("search", name, label, 0, "server_ms", serverMs, "ms", accessPath(plan));
                System.out.printf("%s %-12s %-16s median=%9.3f ms server=%9.3f ms rows=%d [%s]%n",
                        db.label(), label, name, Stats.median(ms), serverMs, rows, accessPath(plan));
            }
        }
    }

    private static List<String> sql(Search q, boolean word) {
        String select = "SELECT id, primary_title FROM titles WHERE ";
        List<String> out = new ArrayList<>();
        if (q.kind() == Queries.Kind.POINT) {
            for (int id : q.ids()) out.add(select + "id = " + id);
            return out;
        }
        String text = q.text().replace("'", "''");
        out.add(select + switch (q.kind()) {
            case EXACT -> "primary_title = '" + text + "'";
            case PREFIX -> "primary_title LIKE '" + text + "%'";
            default -> word
                    ? "to_tsvector('simple', primary_title) @@ plainto_tsquery('simple', '"
                            + text.toLowerCase(Locale.ROOT) + "')"
                    : "primary_title LIKE '%" + text + "%'";
        });
        return out;
    }

    private static String explain(Connection c, String sql) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + sql)) {
            while (rs.next()) sb.append(rs.getString(1)).append('\n');
        }
        return sb.toString();
    }

    /** The scan nodes of a plan, e.g. "Bitmap Heap Scan + Bitmap Index Scan". */
    private static String accessPath(String plan) {
        List<String> nodes = new ArrayList<>();
        Matcher m = Pattern.compile("((?:Parallel )?(?:Seq|Index Only|Index|Bitmap Heap|Bitmap Index|CStore) Scan)").matcher(plan);
        while (m.find()) if (!nodes.contains(m.group(1))) nodes.add(m.group(1));
        return String.join(" + ", nodes);
    }
}
