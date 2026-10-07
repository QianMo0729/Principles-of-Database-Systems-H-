package dbbench.db;

import dbbench.bench.Queries;
import dbbench.util.Args;
import dbbench.util.ResultLog;
import dbbench.util.Stats;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Bulk update through SQL: replace every "T" in a person's name with "Ttt" (project hint 3).
 *
 * <p>Each repetition works on a fresh copy of the people table, so every run starts from identical
 * data and an identical physical layout. The timed part is the single UPDATE statement including
 * its commit. Afterwards the table is summarised with the same three numbers the file program
 * computes, and its size is measured before the update, after it, and after VACUUM: both systems
 * keep the old row versions for a while, and how much space that takes is part of the comparison.
 *
 * <p>{@code --storage ustore} uses openGauss's in-place update engine instead of its default
 * append-only heap.
 */
public final class DbUpdate {
    private DbUpdate() {}

    public static void run(Args a) throws Exception {
        Db db = Db.from(a);
        String rule = a.get("rule", "broad"), storage = a.get("storage", "heap");
        int reps = a.getInt("reps", 5);
        int[] ids = Queries.ints(Queries.loadMeta(Path.of(a.get("meta"))).getProperty("narrow_ids"));
        String where = rule.equals("narrow")
                ? "id IN (" + Arrays.stream(ids).mapToObj(Integer::toString).collect(Collectors.joining(",")) + ")"
                : "primary_name LIKE '%T%'";
        String update = "UPDATE people_work SET primary_name = replace(primary_name, 'T', 'Ttt') WHERE " + where;

        try (ResultLog log = new ResultLog(a, db.label()); Connection c = db.connect("bench_" + a.get("scale"))) {
            double[] ms = new double[reps];
            long changed = 0;
            for (int r = 0; r < reps; r++) {
                createWorkTable(c, storage);
                double before = heapMb(c);
                long t0 = System.nanoTime();
                try (Statement st = c.createStatement()) {
                    changed = st.executeUpdate(update); // autocommit: the commit is part of the timing
                }
                ms[r] = DbSetup.ms(t0);
                double after = heapMb(c);
                t0 = System.nanoTime();
                Db.exec(c, "VACUUM people_work");
                double vacuumMs = DbSetup.ms(t0);
                log.add("update", rule, storage, r, "latency_ms", ms[r], "ms");
                log.add("update", rule, storage, r, "heap_before_mb", before, "MB");
                log.add("update", rule, storage, r, "heap_after_mb", after, "MB");
                log.add("update", rule, storage, r, "heap_after_vacuum_mb", heapMb(c), "MB");
                log.add("update", rule, storage, r, "vacuum_ms", vacuumMs, "ms");
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT count(*), coalesce(sum(octet_length(primary_name)), 0),"
                            + " coalesce(sum(CASE WHEN primary_name LIKE '%Ttt%' THEN 1 ELSE 0 END), 0) FROM people_work")) {
                rs.next();
                log.add("update", rule, storage, 0, "rows_changed", changed, "rows");
                log.add("update", rule, storage, 0, "check_rows", rs.getLong(1), "rows");
                log.add("update", rule, storage, 0, "check_name_bytes", rs.getLong(2), "bytes");
                log.add("update", rule, storage, 0, "check_rows_with_Ttt", rs.getLong(3), "rows");
            }
            System.out.printf("%s update %-6s %-6s median=%9.1f ms changed=%d%n",
                    db.label(), rule, storage, Stats.median(ms), changed);
            Db.exec(c, "DROP TABLE people_work");
        }
    }

    /** A fresh, vacuumed, checkpointed copy of people with its primary key. */
    static void createWorkTable(Connection c, String storage) throws SQLException {
        Db.exec(c, "DROP TABLE IF EXISTS people_work");
        if (storage.equals("ustore")) {
            Db.exec(c, "CREATE TABLE people_work (" + DbSetup.PEOPLE_COLUMNS + ") WITH (storage_type = USTORE)",
                    "INSERT INTO people_work SELECT * FROM people");
        } else {
            Db.exec(c, "CREATE TABLE people_work AS SELECT * FROM people");
        }
        Db.exec(c, "ALTER TABLE people_work ADD CONSTRAINT people_work_pkey PRIMARY KEY (id)",
                "VACUUM ANALYZE people_work", "CHECKPOINT");
    }

    private static double heapMb(Connection c) throws SQLException {
        return Db.queryLong(c, "SELECT pg_relation_size('people_work')") / 1048576.0;
    }
}
