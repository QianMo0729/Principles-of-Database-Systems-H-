package dbbench.bench;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * The retrieval workload, defined once and executed identically by the file programs and by both
 * databases. All text matching is case-sensitive and byte-exact on both sides.
 */
public final class Queries {
    public enum Kind { POINT, EXACT, PREFIX, CONTAINS }

    /** {@code ids} is used by POINT (several keys spread over the file); {@code text} by the rest. */
    public record Search(String name, Kind kind, String text, int[] ids) {}

    private Queries() {}

    public static List<Search> load(Path metaFile) throws IOException {
        int[] ids = ints(loadMeta(metaFile).getProperty("point_ids"));
        return List.of(
                new Search("point", Kind.POINT, null, ids),
                new Search("exact_common", Kind.EXACT, "Episode #1.1", null),
                new Search("exact_rare", Kind.EXACT, "The Godfather", null),
                new Search("exact_miss", Kind.EXACT, "Zqxjv Nonexistent Title", null),
                new Search("prefix", Kind.PREFIX, "Star Wars", null),
                new Search("contains_common", Kind.CONTAINS, "Love", null),
                new Search("contains_rare", Kind.CONTAINS, "Godfather", null),
                new Search("contains_miss", Kind.CONTAINS, "Zqxjv", null));
    }

    public static Properties loadMeta(Path metaFile) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(metaFile)) {
            p.load(in);
        }
        return p;
    }

    public static int[] ints(String csv) {
        if (csv == null || csv.isBlank()) return new int[0];
        return Arrays.stream(csv.split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray();
    }
}
