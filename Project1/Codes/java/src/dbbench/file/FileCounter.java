package dbbench.file;

import dbbench.util.Args;
import dbbench.util.ResultLog;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Concurrent read-modify-write on a file: several threads each add 1 to a counter stored as a
 * fixed-width record, the file equivalent of "UPDATE ... SET votes = votes + 1".
 *
 * <ul>
 *   <li>{@code nolock}: no coordination. Increments are lost whenever two threads read the same
 *       value.</li>
 *   <li>{@code lock}: an in-process mutex around read + write. Correct, but only one thread works
 *       at a time, nothing is durable, and it does not protect against a second process.</li>
 *   <li>{@code lock_fsync}: additionally forces every increment to disk, which is what a database
 *       commit guarantees.</li>
 * </ul>
 */
public final class FileCounter {
    private static final int WIDTH = 20;

    private FileCounter() {}

    public static void run(Args a) throws Exception {
        Path file = Path.of(a.get("work"));
        String mode = a.get("mode");
        int threads = a.getInt("threads", 8), iterations = a.getInt("iters", 2000), reps = a.getInt("reps", 3);
        try (ResultLog log = new ResultLog(a, "file")) {
            for (int r = 0; r < reps; r++) {
                Files.write(file, format(0));
                ReentrantLock mutex = new ReentrantLock();
                CountDownLatch start = new CountDownLatch(1);
                AtomicLong failures = new AtomicLong();
                Thread[] workers = new Thread[threads];
                for (int t = 0; t < threads; t++) {
                    workers[t] = new Thread(() -> {
                        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                            start.await();
                            byte[] buf = new byte[WIDTH];
                            for (int i = 0; i < iterations; i++) {
                                if (!mode.equals("nolock")) mutex.lock();
                                try {
                                    raf.seek(0);
                                    raf.readFully(buf);
                                    long value = Long.parseLong(new String(buf, StandardCharsets.US_ASCII));
                                    raf.seek(0);
                                    raf.write(format(value + 1));
                                    if (mode.equals("lock_fsync")) raf.getChannel().force(false);
                                } catch (IOException | NumberFormatException e) {
                                    failures.incrementAndGet(); // torn read or write
                                } finally {
                                    if (!mode.equals("nolock")) mutex.unlock();
                                }
                            }
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
                long expected = (long) threads * iterations;
                long actual = Long.parseLong(new String(Files.readAllBytes(file), StandardCharsets.US_ASCII).trim());
                log.add("lost_update", "counter", mode, r, "expected", expected, "count");
                log.add("lost_update", "counter", mode, r, "actual", actual, "count");
                log.add("lost_update", "counter", mode, r, "lost", expected - actual, "count");
                log.add("lost_update", "counter", mode, r, "failures", failures.get(), "count");
                log.add("lost_update", "counter", mode, r, "throughput_ops", expected / seconds, "ops/s");
                System.out.printf("file counter %-10s expected=%d actual=%d lost=%d (%.0f ops/s)%n",
                        mode, expected, actual, expected - actual, expected / seconds);
            }
            Files.deleteIfExists(file);
        }
    }

    private static byte[] format(long value) {
        return String.format("%0" + WIDTH + "d", value).getBytes(StandardCharsets.US_ASCII);
    }
}
