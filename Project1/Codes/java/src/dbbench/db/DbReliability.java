package dbbench.db;

import dbbench.util.Args;
import dbbench.util.ResultLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reliability experiments: each one sets up a situation that goes wrong in real applications and
 * then checks the data that is left, instead of trusting that the request "returned OK".
 *
 * <ul>
 *   <li>lostupdate: concurrent increments of one row, written four different ways;</li>
 *   <li>uniquerace: many clients insert the same key at the same instant;</li>
 *   <li>errors: what each system answers to common mistakes (SQLSTATE and message);</li>
 *   <li>connlimit: what happens when clients keep opening connections;</li>
 *   <li>ledger-run / ledger-verify: the server is killed under write load; afterwards every
 *       transaction the client saw committed must exist completely (durability) and no transaction
 *       may exist partially (atomicity);</li>
 *   <li>bulk-start / bulk-verify: the server is killed in the middle of one large UPDATE.</li>
 * </ul>
 */
public final class DbReliability {
    private DbReliability() {}

    // ------------------------------------------------------------------ lost update

    public static void lostUpdate(Args a) throws Exception {
        Db db = Db.from(a);
        String dbName = "bench_" + a.get("scale"), mode = a.get("mode");
        int threads = a.getInt("threads", 8), iterations = a.getInt("iters", 2000), reps = a.getInt("reps", 3);
        try (ResultLog log = new ResultLog(a, db.label())) {
            for (int r = 0; r < reps; r++) {
                try (Connection c = db.connect(dbName)) {
                    Db.exec(c, "DROP TABLE IF EXISTS counter", "CREATE TABLE counter (id integer PRIMARY KEY, val bigint)",
                            "INSERT INTO counter VALUES (1, 0)");
                }
                AtomicLong retries = new AtomicLong(), failures = new AtomicLong();
                CountDownLatch start = new CountDownLatch(1);
                Thread[] workers = new Thread[threads];
                for (int t = 0; t < threads; t++) {
                    workers[t] = new Thread(() -> {
                        try (Connection c = db.connect(dbName)) {
                            start.await();
                            for (int i = 0; i < iterations; i++) increment(c, mode, retries, failures);
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    });
                    workers[t].start();
                }
                long t0 = System.nanoTime();
                start.countDown();
                for (Thread w : workers) w.join();
                double seconds = (System.nanoTime() - t0) / 1e9;
                long expected = (long) threads * iterations, actual;
                try (Connection c = db.connect(dbName)) {
                    actual = Db.queryLong(c, "SELECT val FROM counter WHERE id = 1");
                }
                log.add("lost_update", "counter", mode, r, "expected", expected, "count");
                log.add("lost_update", "counter", mode, r, "actual", actual, "count");
                log.add("lost_update", "counter", mode, r, "lost", expected - actual, "count");
                log.add("lost_update", "counter", mode, r, "retries", retries.get(), "count");
                log.add("lost_update", "counter", mode, r, "failures", failures.get(), "count");
                log.add("lost_update", "counter", mode, r, "throughput_ops", expected / seconds, "ops/s");
                System.out.printf("%s counter %-12s expected=%d actual=%d lost=%d retries=%d (%.0f ops/s)%n",
                        db.label(), mode, expected, actual, expected - actual, retries.get(), expected / seconds);
            }
        }
    }

    /**
     * atomic: one UPDATE computes the new value inside the database.<br>
     * naive: the application reads, adds 1 and writes back in a READ COMMITTED transaction.<br>
     * forupdate: the same, but the read locks the row.<br>
     * repeatable: the naive code at REPEATABLE READ, retrying when the database reports a conflict.
     */
    private static void increment(Connection c, String mode, AtomicLong retries, AtomicLong failures) throws SQLException {
        if (mode.equals("atomic")) {
            Db.exec(c, "UPDATE counter SET val = val + 1 WHERE id = 1");
            return;
        }
        c.setAutoCommit(false);
        c.setTransactionIsolation(mode.equals("repeatable")
                ? Connection.TRANSACTION_REPEATABLE_READ : Connection.TRANSACTION_READ_COMMITTED);
        for (int attempt = 0; ; attempt++) {
            try {
                long val = Db.queryLong(c, "SELECT val FROM counter WHERE id = 1" + (mode.equals("forupdate") ? " FOR UPDATE" : ""));
                Db.exec(c, "UPDATE counter SET val = " + (val + 1) + " WHERE id = 1");
                c.commit();
                break;
            } catch (SQLException e) {
                c.rollback();
                if ("40001".equals(e.getSQLState()) && attempt < 1000) {
                    retries.incrementAndGet(); // serialization failure: the database refused to lose an update
                } else {
                    failures.incrementAndGet();
                    break;
                }
            }
        }
        c.setAutoCommit(true);
    }

    // ------------------------------------------------------------------ unique race

    public static void uniqueRace(Args a) throws Exception {
        Db db = Db.from(a);
        String dbName = "bench_" + a.get("scale");
        int threads = a.getInt("threads", 16), rounds = a.getInt("rounds", 300);
        try (Connection c = db.connect(dbName)) {
            Db.exec(c, "DROP TABLE IF EXISTS signup", "CREATE TABLE signup (k integer PRIMARY KEY, who integer)");
        }
        AtomicLong inserted = new AtomicLong(), duplicates = new AtomicLong(), other = new AtomicLong();
        List<String> otherErrors = Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int who = t;
            workers[t] = new Thread(() -> {
                try (Connection c = db.connect(dbName);
                     PreparedStatement ps = c.prepareStatement("INSERT INTO signup VALUES (?, ?)")) {
                    for (int k = 0; k < rounds; k++) {
                        barrier.await(); // all clients fire the same key together
                        try {
                            ps.setInt(1, k);
                            ps.setInt(2, who);
                            ps.executeUpdate();
                            inserted.incrementAndGet();
                        } catch (SQLException e) {
                            if ("23505".equals(e.getSQLState())) duplicates.incrementAndGet();
                            else {
                                other.incrementAndGet();
                                otherErrors.add(Db.describe(e));
                            }
                        }
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                    otherErrors.add(e.toString());
                }
            });
            workers[t].start();
        }
        for (Thread w : workers) w.join();
        long rows;
        try (Connection c = db.connect(dbName)) {
            rows = Db.queryLong(c, "SELECT count(*) FROM signup");
            Db.exec(c, "DROP TABLE signup");
        }
        try (ResultLog log = new ResultLog(a, db.label())) {
            String variant = "t" + threads;
            log.add("unique_race", "signup", variant, 0, "rounds", rounds, "count");
            log.add("unique_race", "signup", variant, 0, "accepted", inserted.get(), "count");
            log.add("unique_race", "signup", variant, 0, "rejected_duplicate", duplicates.get(), "count");
            log.add("unique_race", "signup", variant, 0, "other_errors", other.get(), "count",
                    otherErrors.isEmpty() ? "" : otherErrors.get(0));
            log.add("unique_race", "signup", variant, 0, "rows_in_table", rows, "rows");
        }
        System.out.printf("%s unique race: rounds=%d accepted=%d rejected=%d other=%d rows=%d%n",
                db.label(), rounds, inserted.get(), duplicates.get(), other.get(), rows);
    }

    // ------------------------------------------------------------------ error catalogue

    public static void errors(Args a) throws Exception {
        Db db = Db.from(a);
        String[][] cases = {
                {"duplicate_key", "INSERT INTO err_parent VALUES (1, 'again')"},
                {"not_null", "INSERT INTO err_parent VALUES (2, NULL)"},
                {"foreign_key", "INSERT INTO err_child VALUES (1, 999)"},
                {"check_constraint", "INSERT INTO err_child VALUES (-5, 1)"},
                {"value_too_long", "INSERT INTO err_parent VALUES (3, 'far too long for the column')"},
                {"bad_cast", "SELECT 'abc'::integer"},
                {"division_by_zero", "SELECT 1 / 0"},
                {"syntax_error", "SELEC 1"},
                {"unknown_table", "SELECT * FROM no_such_table"},
                {"unknown_column", "SELECT no_such_column FROM err_parent"},
        };
        try (ResultLog log = new ResultLog(a, db.label()); Connection c = db.connect("bench_" + a.get("scale"))) {
            Db.exec(c, "DROP TABLE IF EXISTS err_child", "DROP TABLE IF EXISTS err_parent",
                    "CREATE TABLE err_parent (id integer PRIMARY KEY, name varchar(8) NOT NULL)",
                    "CREATE TABLE err_child (qty integer CHECK (qty > 0), parent integer REFERENCES err_parent (id))",
                    "INSERT INTO err_parent VALUES (1, 'first')");
            for (String[] cs : cases) {
                String outcome = "no error raised";
                try {
                    Db.exec(c, cs[1]);
                } catch (SQLException e) {
                    outcome = Db.describe(e);
                }
                log.add("errors", cs[0], "message", 0, "raised", outcome.equals("no error raised") ? 0 : 1, "bool", outcome);
                System.out.printf("%s %-18s %s%n", db.label(), cs[0], outcome);
            }
            Db.exec(c, "DROP TABLE err_child", "DROP TABLE err_parent");
        }
    }

    // ------------------------------------------------------------------ connection limit

    public static void connLimit(Args a) throws Exception {
        Db db = Db.from(a);
        int cap = a.getInt("cap", 1500);
        List<Connection> open = new ArrayList<>();
        String refusal = "none up to the cap";
        try {
            while (open.size() < cap) open.add(db.connect("bench_" + a.get("scale")));
        } catch (SQLException e) {
            refusal = Db.describe(e);
        }
        int reached = open.size();
        for (Connection c : open) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // best effort
            }
        }
        boolean usable;
        try (Connection c = db.connect("bench_" + a.get("scale"))) {
            usable = Db.queryLong(c, "SELECT 1") == 1;
        } catch (SQLException e) {
            usable = false;
        }
        try (ResultLog log = new ResultLog(a, db.label())) {
            log.add("conn_limit", "open_until_refused", "connections", 0, "accepted", reached, "count", refusal);
            log.add("conn_limit", "open_until_refused", "connections", 0, "usable_afterwards", usable ? 1 : 0, "bool");
        }
        System.out.printf("%s connection limit: accepted=%d refusal=[%s] usable afterwards=%b%n",
                db.label(), reached, refusal, usable);
    }

    // ------------------------------------------------------------------ crash under write load

    /** Writes three-row transactions until the server disappears; saves the ids whose COMMIT returned. */
    public static void ledgerRun(Args a) throws Exception {
        Db db = Db.from(a);
        String dbName = "bench_" + a.get("scale");
        int threads = a.getInt("threads", 8);
        long deadline = System.nanoTime() + a.getInt("max-sec", 120) * 1_000_000_000L;
        try (Connection c = db.connect(dbName)) {
            Db.exec(c, "DROP TABLE IF EXISTS ledger_detail", "DROP TABLE IF EXISTS ledger",
                    "CREATE TABLE ledger (txid bigint PRIMARY KEY, client integer, seq integer)",
                    "CREATE TABLE ledger_detail (txid bigint, line integer, amount integer, PRIMARY KEY (txid, line))",
                    "CHECKPOINT");
        }
        List<Long> acked = Collections.synchronizedList(new ArrayList<>());
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int client = t;
            workers[t] = new Thread(() -> {
                List<Long> mine = new ArrayList<>();
                try (Connection c = db.connect(dbName);
                     PreparedStatement head = c.prepareStatement("INSERT INTO ledger VALUES (?, ?, ?)");
                     PreparedStatement line = c.prepareStatement("INSERT INTO ledger_detail VALUES (?, ?, ?)")) {
                    c.setAutoCommit(false);
                    for (int seq = 0; System.nanoTime() < deadline; seq++) {
                        long txid = client * 1_000_000_000L + seq;
                        head.setLong(1, txid);
                        head.setInt(2, client);
                        head.setInt(3, seq);
                        head.executeUpdate();
                        for (int l = 1; l <= 2; l++) {
                            line.setLong(1, txid);
                            line.setInt(2, l);
                            line.setInt(3, l == 1 ? 100 : -100);
                            line.executeUpdate();
                        }
                        c.commit();
                        mine.add(txid); // recorded only after COMMIT returned successfully
                    }
                } catch (SQLException e) {
                    // expected: the server was killed
                }
                acked.addAll(mine);
            });
            workers[t].start();
        }
        System.out.println("RUNNING");
        for (Thread w : workers) w.join();
        StringBuilder sb = new StringBuilder();
        for (long id : acked) sb.append(id).append('\n');
        Files.writeString(Path.of(a.get("acked-file")), sb, StandardCharsets.UTF_8);
        System.out.println("ACKED " + acked.size());
    }

    public static void ledgerVerify(Args a) throws Exception {
        Db db = Db.from(a);
        List<String> acked = Files.readAllLines(Path.of(a.get("acked-file")));
        int rep = a.getInt("rep", 0);
        try (ResultLog log = new ResultLog(a, db.label()); Connection c = db.connect("bench_" + a.get("scale"))) {
            Db.exec(c, "DROP TABLE IF EXISTS ledger_acked", "CREATE TABLE ledger_acked (txid bigint PRIMARY KEY)");
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO ledger_acked VALUES (?)")) {
                int n = 0;
                for (String id : acked) {
                    ps.setLong(1, Long.parseLong(id));
                    ps.addBatch();
                    if (++n % 5000 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            c.setAutoCommit(true);
            long missing = Db.queryLong(c, "SELECT count(*) FROM ledger_acked a WHERE NOT EXISTS"
                    + " (SELECT 1 FROM ledger l WHERE l.txid = a.txid)");
            long partial = Db.queryLong(c, "SELECT count(*) FROM (SELECT l.txid FROM ledger l LEFT JOIN ledger_detail d"
                    + " ON d.txid = l.txid GROUP BY l.txid HAVING count(d.line) <> 2) s")
                    + Db.queryLong(c, "SELECT count(*) FROM ledger_detail d WHERE NOT EXISTS"
                    + " (SELECT 1 FROM ledger l WHERE l.txid = d.txid)");
            long unbalanced = Db.queryLong(c, "SELECT count(*) FROM (SELECT txid FROM ledger_detail GROUP BY txid"
                    + " HAVING sum(amount) <> 0) s");
            long present = Db.queryLong(c, "SELECT count(*) FROM ledger");
            log.add("crash", "write_load", "kill9", rep, "acknowledged_commits", acked.size(), "count");
            log.add("crash", "write_load", "kill9", rep, "acknowledged_but_missing", missing, "count");
            log.add("crash", "write_load", "kill9", rep, "partial_transactions", partial, "count");
            log.add("crash", "write_load", "kill9", rep, "unbalanced_transactions", unbalanced, "count");
            log.add("crash", "write_load", "kill9", rep, "committed_without_ack", present - (acked.size() - missing), "count");
            System.out.printf("%s crash #%d: acked=%d missing=%d partial=%d unbalanced=%d in-doubt-kept=%d%n", db.label(), rep,
                    acked.size(), missing, partial, unbalanced, present - (acked.size() - missing));
            Db.exec(c, "DROP TABLE ledger_acked");
        }
    }

    // ------------------------------------------------------------------ crash during one bulk update

    /** Prepares a fresh work table, prints READY, then runs the bulk UPDATE (to be killed by the script). */
    public static void bulkStart(Args a) throws Exception {
        Db db = Db.from(a);
        try (Connection c = db.connect("bench_" + a.get("scale"))) {
            DbUpdate.createWorkTable(c, "heap");
            System.out.println("READY");
            long t0 = System.nanoTime();
            try (Statement st = c.createStatement()) {
                int rows = st.executeUpdate("UPDATE people_work SET primary_name = replace(primary_name, 'T', 'Ttt')"
                        + " WHERE primary_name LIKE '%T%'");
                System.out.printf("COMPLETED rows=%d ms=%.0f%n", rows, DbSetup.ms(t0));
            } catch (SQLException e) {
                System.out.printf("INTERRUPTED after %.0f ms: %s%n", DbSetup.ms(t0), Db.describe(e));
            }
        } catch (SQLException e) {
            System.out.println("INTERRUPTED " + Db.describe(e));
        }
    }

    public static void bulkVerify(Args a) throws Exception {
        Db db = Db.from(a);
        int rep = a.getInt("rep", 0);
        try (ResultLog log = new ResultLog(a, db.label()); Connection c = db.connect("bench_" + a.get("scale"))) {
            long expected = Db.queryLong(c, "SELECT count(*) FROM people");
            long tttBefore = Db.queryLong(c, "SELECT count(*) FROM people WHERE primary_name LIKE '%Ttt%'");
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*),"
                    + " coalesce(sum(CASE WHEN primary_name LIKE '%Ttt%' THEN 1 ELSE 0 END), 0) FROM people_work")) {
                rs.next();
                long rows = rs.getLong(1), updated = rs.getLong(2);
                log.add("crash", "bulk_update", "kill9", rep, "rows_before", expected, "rows");
                log.add("crash", "bulk_update", "kill9", rep, "rows_after", rows, "rows");
                log.add("crash", "bulk_update", "kill9", rep, "rows_lost", expected - rows, "rows");
                log.add("crash", "bulk_update", "kill9", rep, "rows_with_Ttt_before", tttBefore, "rows");
                log.add("crash", "bulk_update", "kill9", rep, "rows_with_Ttt_after", updated, "rows");
                System.out.printf("%s bulk crash #%d: rows %d -> %d, rows with Ttt %d -> %d%n",
                        db.label(), rep, expected, rows, tttBefore, updated);
            }
            Db.exec(c, "DROP TABLE people_work");
        }
    }
}
