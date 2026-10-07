package dbbench.file;

import dbbench.bench.Queries;
import dbbench.bench.Queries.Search;
import dbbench.file.FileSearch.Hit;
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
import java.util.Arrays;
import java.util.List;

/**
 * The file data reorganised for fast retrieval (project hint 4): the id and title columns are
 * loaded into compact arrays and two sorted orders are built over them.
 *
 * <ul>
 *   <li>ids sorted -> binary search answers point lookups;</li>
 *   <li>row numbers sorted by title bytes -> binary search answers exact and prefix matches
 *       (the same idea as the leaf level of a B-tree);</li>
 *   <li>substring matches still have to visit every title, now as an in-memory byte scan.</li>
 * </ul>
 *
 * <p>The point of this class is the price of that speed: it reports how long the structures take to
 * build and how much memory they hold, a cost paid again on every program start and after every
 * change to the file. A DBMS builds the equivalent structures once and keeps them on disk.
 */
public final class MemIndex {
    private int rows;
    private int[] ids;           // id of each row, in file order
    private int[] titleOffset;   // rows + 1 offsets into titleBytes
    private byte[] titleBytes;   // all titles, UTF-8, concatenated (NULL titles are empty and flagged)
    private boolean[] nullTitle;
    private long[] idOrder;      // (id << 32 | row), sorted
    private int[] titleOrder;    // row numbers sorted by title bytes

    public static void run(Args a) throws IOException {
        Path file = Path.of(a.get("file"));
        List<Search> queries = Queries.load(Path.of(a.get("meta")));
        int reps = a.getInt("reps", 5);
        try (ResultLog log = new ResultLog(a, "file")) {
            long heapBefore = usedHeap();
            long t0 = System.nanoTime();
            MemIndex index = new MemIndex();
            index.load(file);
            double loadMs = (System.nanoTime() - t0) / 1e6;
            t0 = System.nanoTime();
            index.buildOrders();
            double sortMs = (System.nanoTime() - t0) / 1e6;
            double heapMb = (usedHeap() - heapBefore) / 1048576.0;
            log.add("search", "build", "memindex", 0, "load_ms", loadMs, "ms");
            log.add("search", "build", "memindex", 0, "sort_ms", sortMs, "ms");
            log.add("search", "build", "memindex", 0, "heap_mb", heapMb, "MB");
            System.out.printf("memindex build: load=%.0f ms sort=%.0f ms heap=%.0f MB rows=%d%n",
                    loadMs, sortMs, heapMb, index.rows);

            for (Search q : queries) {
                // Warm up for half a second, then size each repetition to last about 20 ms:
                // lookups take microseconds, so one repetition averages many executions.
                List<Hit> hits = List.of();
                long warmStart = System.nanoTime();
                int runs = 0;
                while (runs < 3 || System.nanoTime() - warmStart < 500_000_000L) {
                    hits = index.execute(q);
                    runs++;
                }
                double singleMs = (System.nanoTime() - warmStart) / 1e6 / runs;
                int inner = (int) Math.max(1, Math.min(1000, 20.0 / singleMs));
                double[] ms = new double[reps];
                for (int r = 0; r < reps; r++) {
                    long s = System.nanoTime();
                    for (int i = 0; i < inner; i++) hits = index.execute(q);
                    ms[r] = (System.nanoTime() - s) / 1e6 / inner;
                    if (q.kind() == Queries.Kind.POINT) ms[r] /= q.ids().length;
                    log.add("search", q.name(), "memindex", r, "latency_ms", ms[r], "ms");
                }
                log.add("search", q.name(), "memindex", 0, "rows", hits.size(), "rows");
                log.add("search", q.name(), "memindex", 0, "id_sum", FileSearch.idSum(hits), "sum");
                System.out.printf("file memindex %-16s median=%10.5f ms rows=%d%n", q.name(), Stats.median(ms), hits.size());
            }
        }
    }

    private void load(Path file) throws IOException {
        ids = new int[1 << 16];
        titleOffset = new int[(1 << 16) + 1];
        nullTitle = new boolean[1 << 16];
        titleBytes = new byte[1 << 20];
        int used = 0;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8), 1 << 20)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (rows == ids.length) {
                    ids = Arrays.copyOf(ids, rows * 2);
                    titleOffset = Arrays.copyOf(titleOffset, rows * 2 + 1);
                    nullTitle = Arrays.copyOf(nullTitle, rows * 2);
                }
                int idEnd = line.indexOf('\t');
                int titleStart = line.indexOf('\t', idEnd + 1) + 1;
                int titleEnd = line.indexOf('\t', titleStart);
                String title = line.substring(titleStart, titleEnd);
                ids[rows] = Tsv.parseInt(line, 0, idEnd);
                titleOffset[rows] = used;
                if (title.equals(Tsv.NULL)) {
                    nullTitle[rows] = true;
                } else {
                    byte[] b = Tsv.unescape(title).getBytes(StandardCharsets.UTF_8);
                    if (used + b.length > titleBytes.length) {
                        titleBytes = Arrays.copyOf(titleBytes, Math.max(titleBytes.length * 2, used + b.length));
                    }
                    System.arraycopy(b, 0, titleBytes, used, b.length);
                    used += b.length;
                }
                rows++;
            }
        }
        titleOffset[rows] = used;
        ids = Arrays.copyOf(ids, rows);
        titleOffset = Arrays.copyOf(titleOffset, rows + 1);
        nullTitle = Arrays.copyOf(nullTitle, rows);
        titleBytes = Arrays.copyOf(titleBytes, used);
    }

    private void buildOrders() {
        idOrder = new long[rows];
        for (int r = 0; r < rows; r++) idOrder[r] = ((long) ids[r] << 32) | r;
        Arrays.sort(idOrder);
        titleOrder = new int[rows];
        for (int r = 0; r < rows; r++) titleOrder[r] = r;
        mergeSort(titleOrder, new int[rows], 0, rows);
    }

    /** Merge sort of row numbers by title; written out because Arrays.sort has no int comparator. */
    private void mergeSort(int[] a, int[] tmp, int from, int to) {
        if (to - from < 2) return;
        int mid = (from + to) >>> 1;
        mergeSort(a, tmp, from, mid);
        mergeSort(a, tmp, mid, to);
        if (compareRows(a[mid - 1], a[mid]) <= 0) return;
        System.arraycopy(a, from, tmp, from, to - from);
        int i = from, j = mid, k = from;
        while (i < mid && j < to) a[k++] = compareRows(tmp[i], tmp[j]) <= 0 ? tmp[i++] : tmp[j++];
        while (i < mid) a[k++] = tmp[i++];
        while (j < to) a[k++] = tmp[j++];
    }

    private int compareRows(int r1, int r2) {
        return Arrays.compareUnsigned(titleBytes, titleOffset[r1], titleOffset[r1 + 1],
                titleBytes, titleOffset[r2], titleOffset[r2 + 1]);
    }

    List<Hit> execute(Search q) {
        List<Hit> hits = new ArrayList<>();
        switch (q.kind()) {
            case POINT -> {
                for (int id : q.ids()) {
                    int pos = Arrays.binarySearch(idOrder, (long) id << 32);
                    if (pos < 0) pos = -pos - 1; // the row number sits in the low bits, so we land just before it
                    if (pos < rows && (int) (idOrder[pos] >>> 32) == id) hits.add(hit((int) idOrder[pos]));
                }
            }
            case EXACT, PREFIX -> {
                byte[] key = q.text().getBytes(StandardCharsets.UTF_8);
                boolean exact = q.kind() == Queries.Kind.EXACT;
                for (int pos = lowerBound(key); pos < rows; pos++) {
                    int row = titleOrder[pos];
                    int from = titleOffset[row], to = titleOffset[row + 1];
                    boolean prefixed = to - from >= key.length
                            && Arrays.equals(titleBytes, from, from + key.length, key, 0, key.length);
                    if (!prefixed || (exact && to - from != key.length)) break;
                    if (!nullTitle[row]) hits.add(hit(row));
                }
            }
            default -> {
                byte[] key = q.text().getBytes(StandardCharsets.UTF_8);
                for (int row = 0; row < rows; row++) {
                    if (contains(titleOffset[row], titleOffset[row + 1], key)) hits.add(hit(row));
                }
            }
        }
        return hits;
    }

    /** First position in titleOrder whose title is >= key. */
    private int lowerBound(byte[] key) {
        int lo = 0, hi = rows;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1, row = titleOrder[mid];
            int c = Arrays.compareUnsigned(titleBytes, titleOffset[row], titleOffset[row + 1], key, 0, key.length);
            if (c < 0) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private boolean contains(int from, int to, byte[] key) {
        byte first = key[0];
        for (int i = from, last = to - key.length; i <= last; i++) {
            if (titleBytes[i] != first) continue;
            int k = 1;
            while (k < key.length && titleBytes[i + k] == key[k]) k++;
            if (k == key.length) return true;
        }
        return false;
    }

    private Hit hit(int row) {
        return new Hit(ids[row], new String(titleBytes, titleOffset[row],
                titleOffset[row + 1] - titleOffset[row], StandardCharsets.UTF_8));
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) System.gc();
        return rt.totalMemory() - rt.freeMemory();
    }
}
