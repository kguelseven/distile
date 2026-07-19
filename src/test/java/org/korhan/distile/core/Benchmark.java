package org.korhan.distile.core;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Throughput sanity check. Feeds a large synthetic log and reports lines/sec and
 * the final template count. This is documentation of the streaming target, not a
 * hard performance gate — the only assertion is the template-count bound (a slow
 * machine must not fail the build).
 *
 * <p>Also runnable as a {@code main} for ad-hoc measurement.
 */
class Benchmark {

    private static final int LINES = 200_000;

    @Test
    void reportsThroughputAndStaysBounded() {
        Result r = run(LINES);
        System.out.printf("[benchmark] %,d lines in %,d ms = %,.0f lines/sec, %d templates%n",
                r.lines, r.millis, r.linesPerSec(), r.clusters);
        assertTrue(r.clusters < 50, "template count should stay small, got " + r.clusters);
    }

    static Result run(int lines) {
        DrainTree tree = new DrainTree(DrainConfig.defaults(), Masker.withDefaults());
        Random rnd = new Random(7);

        long start = System.nanoTime();
        for (int i = 0; i < lines; i++) {
            tree.add(gen(rnd, i % 8));
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new Result(lines, Math.max(millis, 1), tree.clusterCount());
    }

    private static String gen(Random rnd, int t) {
        String ts = String.format("2026-07-19T10:%02d:%02dZ", rnd.nextInt(60), rnd.nextInt(60));
        return switch (t) {
            case 0 -> ts + " INFO auth login user u" + rnd.nextInt(10000) + " from "
                    + rnd.nextInt(256) + "." + rnd.nextInt(256) + "." + rnd.nextInt(256) + "." + rnd.nextInt(256);
            case 1 -> ts + " WARN db slow query took " + rnd.nextInt(5000) + " ms";
            case 2 -> ts + " ERROR http request GET /api/" + rnd.nextInt(1000) + " status " + rnd.nextInt(600);
            case 3 -> ts + " DEBUG cache hit key k" + rnd.nextInt(100000);
            case 4 -> ts + " DEBUG cache miss key k" + rnd.nextInt(100000);
            case 5 -> ts + " INFO worker job " + Long.toHexString(rnd.nextLong() & 0xFFFFFFFFFFFFL) + "ab completed";
            case 6 -> ts + " WARN mem usage " + rnd.nextInt(100) + " percent";
            default -> ts + " INFO metrics cpu " + rnd.nextInt(100) + " load " + rnd.nextInt(16);
        };
    }

    record Result(int lines, long millis, int clusters) {
        double linesPerSec() {
            return lines / (millis / 1000.0);
        }
    }

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : LINES;
        Result r = run(n);
        System.out.printf("%,d lines in %,d ms = %,.0f lines/sec, %d templates%n",
                r.lines, r.millis, r.linesPerSec(), r.clusters);
    }
}
