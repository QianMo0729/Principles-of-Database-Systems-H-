package dbbench.db;

import dbbench.util.Args;
import dbbench.util.ResultLog;
import dbbench.util.Stats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Closed-loop load generator: N clients, each with its own connection, send one request after the
 * other for a fixed time. This models an application with N concurrent users.
 *
 * <p>Requests are the two things a small business application does most:
 * <ul>
 *   <li>read: fetch one title by primary key;</li>
 *   <li>write: add one vote to one rating (a single-row UPDATE, committed on its own).</li>
 * </ul>
 * Workload {@code read} is 100 % reads, {@code mixed} is 80 % reads and 20 % writes. Keys are drawn
 * uniformly from ids that exist. Reported: throughput, latency percentiles over every request in
 * the measurement window, and every failed request. With {@code --series-sec} the same numbers are
 * also reported per time slice, which shows drift during a long run.
 */
public final class DbLoadTest {
    private DbLoadTest() {}

    public static void run(Args a) throws Exception {
        Db db = Db.from(a);
        String dbName = "bench_" + a.get("scale"), workload = a.get("workload");
        String experiment = a.get("experiment", "load");
        int clients = a.getInt("clients", 16), rep = a.getInt("rep", 0);
        int seriesSec = a.getInt("series-sec", 0);
        long warmupNs = a.getInt("warmup-sec", 5) * 1_000_000_000L;
        long measureNs = a.getInt("duration-sec", 20) * 1_000_000_000L;
        int writePercent = switch (workload) {
            case "read" -> 0;
            case "mixed" -> 20;
            default -> throw new IllegalArgumentException("unknown --workload " + workload);
        };

        int[] titleIds, ratingIds;
        try (Connection c = db.connect(dbName)) {
            titleIds = sampleIds(c, "titles");
            ratingIds = sampleIds(c, "ratings");
        }

        Map<String, Integer> errorKinds = new ConcurrentHashMap<>();
        CountDownLatch connected = new CountDownLatch(clients), go = new CountDownLatch(1);
        long[] window = new long[2]; // measurement start / end, set once all clients are connected
        Worker[] workers = new Worker[clients];
        Thread[] threads = new Thread[clients];
        for (int i = 0; i < clients; i++) {
            workers[i] = new Worker(db, dbName, writePercent, titleIds, ratingIds, 42 + i, window,
                    seriesSec * 1_000_000_000L, errorKinds, connected, go);
            threads[i] = new Thread(workers[i], "client-" + i);
            threads[i].start();
        }
        connected.await();
        window[0] = System.nanoTime() + warmupNs;
        window[1] = window[0] + measureNs;
        go.countDown();
        for (Thread t : threads) t.join();

        String variant = "c" + clients;
        double seconds = measureNs / 1e9;
        long ops = 0, errors = 0, connectFailures = 0;
        for (Worker w : workers) {
            ops += w.count;
            errors += w.errors;
            connectFailures += w.connectFailures;
        }
        int[] all = merge(workers, 0, Integer.MAX_VALUE);
        try (ResultLog log = new ResultLog(a, db.label())) {
            String note = errorKinds.isEmpty() ? "" : errorKinds.toString();
            log.add(experiment, workload, variant, rep, "throughput_ops", ops / seconds, "ops/s");
            log.add(experiment, workload, variant, rep, "p50_ms", Stats.percentileMs(all, all.length, 50), "ms");
            log.add(experiment, workload, variant, rep, "p95_ms", Stats.percentileMs(all, all.length, 95), "ms");
            log.add(experiment, workload, variant, rep, "p99_ms", Stats.percentileMs(all, all.length, 99), "ms");
            log.add(experiment, workload, variant, rep, "max_ms", all.length == 0 ? Double.NaN : all[all.length - 1] / 1000.0, "ms");
            log.add(experiment, workload, variant, rep, "errors", errors, "count", note);
            log.add(experiment, workload, variant, rep, "error_rate_pct",
                    ops + errors == 0 ? 100 : 100.0 * errors / (ops + errors), "%");
            log.add(experiment, workload, variant, rep, "connect_failures", connectFailures, "count");
            if (seriesSec > 0) {
                int slices = (int) (measureNs / (seriesSec * 1_000_000_000L));
                for (int s = 0; s < slices; s++) {
                    int[] slice = merge(workers, s, s + 1);
                    log.add(experiment + "_series", workload, variant, s, "throughput_ops", slice.length / (double) seriesSec, "ops/s");
                    log.add(experiment + "_series", workload, variant, s, "p95_ms", Stats.percentileMs(slice, slice.length, 95), "ms");
                    log.add(experiment + "_series", workload, variant, s, "p99_ms", Stats.percentileMs(slice, slice.length, 99), "ms");
                }
            }
        }
        System.out.printf("%s %-5s clients=%-3d %9.0f ops/s p50=%.3f p95=%.3f p99=%.3f ms errors=%d %s%n",
                db.label(), workload, clients, ops / seconds, Stats.percentileMs(all, all.length, 50),
                Stats.percentileMs(all, all.length, 95), Stats.percentileMs(all, all.length, 99), errors, errorKinds);
    }

    /**
     * Up to about two million existing ids spread evenly over the table, so requests touch every
     * part of it and the working set is the whole table rather than a few hot pages.
     */
    private static int[] sampleIds(Connection c, String table) throws SQLException {
        long rows = Db.queryLong(c, "SELECT count(*) FROM " + table);
        long step = Math.max(1, rows / 2_000_000);
        List<Integer> ids = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM " + table + " WHERE mod(id, " + step + ") = 0")) {
            while (rs.next()) ids.add(rs.getInt(1));
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Sorted latencies (microseconds) of all workers for time slices [fromSlice, toSlice). */
    private static int[] merge(Worker[] workers, int fromSlice, int toSlice) {
        int total = 0;
        for (Worker w : workers) total += w.sliceEnd(toSlice - 1) - w.sliceStart(fromSlice);
        int[] out = new int[total];
        int pos = 0;
        for (Worker w : workers) {
            int from = w.sliceStart(fromSlice), len = w.sliceEnd(toSlice - 1) - from;
            System.arraycopy(w.micros, from, out, pos, len);
            pos += len;
        }
        Arrays.sort(out);
        return out;
    }

    private static final class Worker implements Runnable {
        private final Db db;
        private final String dbName;
        private final int writePercent;
        private final int[] titleIds, ratingIds;
        private final SplittableRandom random;
        private final long[] window;
        private final long sliceNs;
        private final Map<String, Integer> errorKinds;
        private final CountDownLatch connected, go;

        int[] micros = new int[1 << 16];
        int count;
        long errors, connectFailures;
        private int[] sliceStarts = new int[16]; // index into micros where each time slice begins
        private int slices = 1;

        Worker(Db db, String dbName, int writePercent, int[] titleIds, int[] ratingIds, long seed, long[] window,
               long sliceNs, Map<String, Integer> errorKinds, CountDownLatch connected, CountDownLatch go) {
            this.db = db;
            this.dbName = dbName;
            this.writePercent = writePercent;
            this.titleIds = titleIds;
            this.ratingIds = ratingIds;
            this.random = new SplittableRandom(seed);
            this.window = window;
            this.sliceNs = sliceNs;
            this.errorKinds = errorKinds;
            this.connected = connected;
            this.go = go;
        }

        int sliceStart(int slice) {
            return slice < slices ? sliceStarts[slice] : count;
        }

        int sliceEnd(int slice) {
            return slice + 1 < slices ? sliceStarts[slice + 1] : count;
        }

        @Override
        public void run() {
            Connection c = open();
            connected.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                return;
            }
            PreparedStatement select = null, update = null;
            while (System.nanoTime() < window[1]) {
                long t0 = System.nanoTime();
                try {
                    if (c == null) {
                        c = open();
                        if (c == null) {
                            if (t0 >= window[0]) errors++;
                            Thread.sleep(200);
                            continue;
                        }
                        select = null;
                    }
                    if (select == null) {
                        select = c.prepareStatement("SELECT primary_title, start_year FROM titles WHERE id = ?");
                        update = c.prepareStatement("UPDATE ratings SET num_votes = num_votes + 1 WHERE id = ?");
                    }
                    if (random.nextInt(100) < writePercent) {
                        update.setInt(1, ratingIds[random.nextInt(ratingIds.length)]);
                        update.executeUpdate();
                    } else {
                        select.setInt(1, titleIds[random.nextInt(titleIds.length)]);
                        try (ResultSet rs = select.executeQuery()) {
                            while (rs.next()) {
                                rs.getString(1);
                                rs.getInt(2);
                            }
                        }
                    }
                    long t1 = System.nanoTime();
                    if (t0 >= window[0] && t1 <= window[1]) record(t1 - t0, t1);
                } catch (SQLException e) {
                    if (t0 >= window[0]) errors++;
                    errorKinds.merge(Db.describe(e), 1, Integer::sum);
                    try {
                        if (c != null && !c.isValid(1)) {
                            c.close();
                            c = null;
                        }
                    } catch (SQLException ignored) {
                        c = null;
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }
            try {
                if (c != null) c.close();
            } catch (SQLException ignored) {
                // closing after the run; nothing to report
            }
        }

        private Connection open() {
            try {
                return db.connect(dbName);
            } catch (SQLException e) {
                connectFailures++;
                errorKinds.merge("connect " + Db.describe(e), 1, Integer::sum);
                return null;
            }
        }

        private void record(long nanos, long finishedAt) {
            if (sliceNs > 0) {
                int slice = (int) ((finishedAt - window[0]) / sliceNs);
                while (slices <= slice) {
                    if (slices == sliceStarts.length) sliceStarts = Arrays.copyOf(sliceStarts, slices * 2);
                    sliceStarts[slices++] = count;
                }
            }
            if (count == micros.length) micros = Arrays.copyOf(micros, count * 2);
            micros[count++] = (int) Math.min(Integer.MAX_VALUE, nanos / 1000);
        }
    }
}
