package dbbench.db;

import dbbench.util.Args;
import dbbench.util.ResultLog;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Creates the benchmark database for one data scale, loads the cleaned files and manages the
 * secondary indexes that the retrieval experiment switches between. Load, index-build times and
 * on-disk sizes are recorded because they are part of what each system costs.
 */
public final class DbSetup {
    static final String[] TABLES = {"titles", "people", "ratings"};

    static final String PEOPLE_COLUMNS = "id integer NOT NULL, primary_name text, birth_year smallint,"
            + " death_year smallint, primary_profession text, known_for_titles text";

    private static final String[] DDL = {
            "CREATE TABLE titles (id integer NOT NULL, title_type text, primary_title text,"
                    + " original_title text, is_adult smallint, start_year smallint, end_year smallint,"
                    + " runtime_minutes integer, genres text)",
            "CREATE TABLE people (" + PEOPLE_COLUMNS + ")",
            "CREATE TABLE ratings (id integer NOT NULL, average_rating numeric(3,1), num_votes integer)"
    };

    private DbSetup() {}

    public static void setup(Args a) throws Exception {
        Db db = Db.from(a);
        String scale = a.get("scale"), dbName = "bench_" + scale;
        Path dir = Path.of(a.get("data-dir"));
        try (ResultLog log = new ResultLog(a, db.label())) {
            try (Connection c = db.connect("postgres")) {
                Db.exec(c, "DROP DATABASE IF EXISTS " + dbName, db.createDatabaseSql(dbName));
            }
            try (Connection c = db.connect(dbName)) {
                Db.exec(c, DDL);
                for (String table : TABLES) {
                    long t0 = System.nanoTime();
                    long rows;
                    try (InputStream in = new BufferedInputStream(
                            Files.newInputStream(dir.resolve(table + "_" + scale + ".tsv")), 1 << 20)) {
                        rows = db.copyIn(c, "COPY " + table + " FROM STDIN", in);
                    }
                    double copyMs = ms(t0);
                    t0 = System.nanoTime();
                    Db.exec(c, "ALTER TABLE " + table + " ADD CONSTRAINT " + table + "_pkey PRIMARY KEY (id)");
                    double pkMs = ms(t0);
                    t0 = System.nanoTime();
                    Db.exec(c, "VACUUM ANALYZE " + table);
                    double analyzeMs = ms(t0);
                    log.add("setup", table, "copy", 0, "rows", rows, "rows");
                    log.add("setup", table, "copy", 0, "load_ms", copyMs, "ms");
                    log.add("setup", table, "primary_key", 0, "build_ms", pkMs, "ms");
                    log.add("setup", table, "vacuum_analyze", 0, "latency_ms", analyzeMs, "ms");
                    System.out.printf("%s %s %-8s rows=%d copy=%.0f ms pk=%.0f ms analyze=%.0f ms%n",
                            db.label(), dbName, table, rows, copyMs, pkMs, analyzeMs);
                }
                Db.exec(c, "CHECKPOINT");
                for (String table : TABLES) logSizes(c, log, "setup", table, "loaded");
                log.add("setup", "database", "loaded", 0, "size_mb",
                        Db.queryLong(c, "SELECT pg_database_size(current_database())") / 1048576.0, "MB");
            }
        }
    }

    /**
     * Leaves exactly one secondary-index configuration on titles(primary_title):
     * noidx | btree | trgm (trigram GIN, for LIKE '%x%') | fts (full-text GIN, for whole words).
     * Exits with status 3 when the database cannot build the requested index.
     */
    public static void index(Args a) throws Exception {
        Db db = Db.from(a);
        String variant = a.get("variant");
        try (ResultLog log = new ResultLog(a, db.label()); Connection c = db.connect("bench_" + a.get("scale"))) {
            Db.exec(c, "DROP INDEX IF EXISTS idx_titles_btree", "DROP INDEX IF EXISTS idx_titles_trgm",
                    "DROP INDEX IF EXISTS idx_titles_fts");
            String name = "idx_titles_" + variant;
            String[] create = switch (variant) {
                case "noidx" -> new String[0];
                case "btree" -> new String[] {"CREATE INDEX " + name + " ON titles (primary_title)"};
                case "trgm" -> new String[] {"CREATE EXTENSION IF NOT EXISTS pg_trgm",
                        "CREATE INDEX " + name + " ON titles USING gin (primary_title gin_trgm_ops)"};
                case "fts" -> new String[] {
                        "CREATE INDEX " + name + " ON titles USING gin (to_tsvector('simple', primary_title))"};
                default -> throw new IllegalArgumentException("unknown --variant " + variant);
            };
            if (create.length > 0) {
                long t0 = System.nanoTime();
                try {
                    Db.exec(c, create);
                } catch (SQLException e) {
                    log.add("index", "titles", variant, 0, "supported", 0, "bool", Db.describe(e));
                    System.out.println(db.label() + " index " + variant + " UNSUPPORTED: " + Db.describe(e));
                    System.exit(3);
                }
                log.add("index", "titles", variant, 0, "supported", 1, "bool");
                log.add("index", "titles", variant, 0, "build_ms", ms(t0), "ms");
                log.add("index", "titles", variant, 0, "index_mb",
                        Db.queryLong(c, "SELECT pg_relation_size('" + name + "')") / 1048576.0, "MB");
                System.out.printf("%s index %-5s built in %.0f ms%n", db.label(), variant, ms(t0));
            }
            Db.exec(c, "ANALYZE titles");
        }
    }

    static void logSizes(Connection c, ResultLog log, String experiment, String table, String variant)
            throws SQLException {
        log.add(experiment, table, variant, 0, "heap_mb",
                Db.queryLong(c, "SELECT pg_relation_size('" + table + "')") / 1048576.0, "MB");
        log.add(experiment, table, variant, 0, "total_mb",
                Db.queryLong(c, "SELECT pg_total_relation_size('" + table + "')") / 1048576.0, "MB");
    }

    static double ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e6;
    }
}
