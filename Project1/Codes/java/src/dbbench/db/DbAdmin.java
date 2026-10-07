package dbbench.db;

import dbbench.util.Args;
import dbbench.util.ResultLog;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

/** Operational helpers: waiting for a server to accept work, dumping its settings, heavy queries. */
public final class DbAdmin {
    private static final String[] SETTINGS = {
            "server_version", "shared_buffers", "work_mem", "maintenance_work_mem", "max_connections",
            "wal_level", "wal_buffers", "synchronous_commit", "fsync", "full_page_writes", "checkpoint_timeout",
            "autovacuum", "max_parallel_workers_per_gather", "io_method", "max_process_memory", "cstore_buffers",
            "enable_thread_pool", "query_dop", "enable_double_write", "enable_incremental_checkpoint",
            "server_encoding", "lc_collate", "default_transaction_isolation", "deadlock_timeout"};

    private DbAdmin() {}

    /**
     * Optionally runs {@code --pre-cmd} (for example "docker start ..."), then polls until a query
     * succeeds. The elapsed time is the time to become available: start-up, or crash recovery.
     * Exits with status 1 on timeout.
     */
    public static void waitReady(Args a) throws Exception {
        Db db = Db.from(a);
        long timeoutNs = a.getInt("timeout-sec", 180) * 1_000_000_000L;
        long t0 = System.nanoTime();
        if (a.has("pre-cmd")) {
            Process p = new ProcessBuilder("sh", "-c", a.get("pre-cmd")).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            p.waitFor(120, TimeUnit.SECONDS);
        }
        String last = "";
        boolean ready = false;
        while (System.nanoTime() - t0 < timeoutNs) {
            try (Connection c = db.connect(a.get("db", "postgres"))) {
                if (Db.queryLong(c, "SELECT 1") == 1) {
                    ready = true;
                    break;
                }
            } catch (SQLException e) {
                last = Db.describe(e);
            }
            Thread.sleep(50);
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        if (a.has("out")) {
            try (ResultLog log = new ResultLog(a, db.label())) {
                String exp = a.get("experiment", "startup"), op = a.get("operation", "start"), variant = a.get("variant", "ready");
                log.add(exp, op, variant, a.getInt("rep", 0), "available", ready ? 1 : 0, "bool", ready ? "" : last);
                if (ready) log.add(exp, op, variant, a.getInt("rep", 0), "time_to_ready_ms", ms, "ms");
            }
        }
        System.out.printf("%s %s after %.0f ms %s%n", db.label(), ready ? "READY" : "NOT READY", ms, ready ? "" : last);
        if (!ready) System.exit(1);
    }

    /** Prints version and the settings that matter for the comparison (missing ones are skipped). */
    public static void info(Args a) throws Exception {
        Db db = Db.from(a);
        try (Connection c = db.connect(a.get("db", "postgres"))) {
            System.out.println("version = " + Db.queryString(c, "SELECT version()"));
            for (String s : SETTINGS) {
                try {
                    System.out.println(s + " = " + Db.queryString(c, "SHOW " + s));
                } catch (SQLException e) {
                    System.out.println(s + " = (not available)");
                }
            }
        }
    }

    /**
     * Two analytical statements that need far more memory or disk than the small requests: a sort of
     * every title and a join with aggregation. On a small machine they show whether the server
     * finishes, slows down or is killed.
     */
    public static void heavy(Args a) throws Exception {
        Db db = Db.from(a);
        String[][] queries = {
                {"sort_all_titles", "SELECT count(*) FROM (SELECT primary_title FROM titles ORDER BY primary_title) s"},
                {"join_aggregate", "SELECT t.title_type, count(*), avg(r.average_rating) FROM titles t"
                        + " JOIN ratings r ON r.id = t.id GROUP BY t.title_type"}};
        try (ResultLog log = new ResultLog(a, db.label())) {
            for (String[] q : queries) {
                long t0 = System.nanoTime();
                String outcome = "";
                boolean ok = false;
                try (Connection c = db.connect("bench_" + a.get("scale")); Statement st = c.createStatement()) {
                    st.setQueryTimeout(a.getInt("timeout-sec", 300));
                    try (ResultSet rs = st.executeQuery(q[1])) {
                        while (rs.next()) rs.getObject(1);
                    }
                    ok = true;
                } catch (SQLException e) {
                    outcome = Db.describe(e);
                }
                double ms = DbSetup.ms(t0);
                log.add(a.get("experiment", "heavy"), q[0], "single", 0, "completed", ok ? 1 : 0, "bool", outcome);
                if (ok) log.add(a.get("experiment", "heavy"), q[0], "single", 0, "latency_ms", ms, "ms");
                System.out.printf("%s heavy %-16s %s in %.0f ms %s%n", db.label(), q[0], ok ? "ok" : "FAILED", ms, outcome);
            }
        }
    }
}
