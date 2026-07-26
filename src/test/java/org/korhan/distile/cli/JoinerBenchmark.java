package org.korhan.distile.cli;

import org.junit.jupiter.api.Test;
import org.korhan.distile.core.DrainConfig;
import org.korhan.distile.core.DrainTree;
import org.korhan.distile.core.Masker;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What joining costs, and what it buys. Companion to {@code core.Benchmark}, kept in the
 * cli package because the joiner is a cli concern.
 *
 * <p>Two things worth knowing and easy to regress:
 * <ol>
 *   <li>On trace-free input the joiner must be near-free. Every line pays five detection
 *       gates, so a rule that reaches for a regex before its cheap prefix check would
 *       show up here as a throughput drop.</li>
 *   <li>On trace-heavy input joining should be <em>faster</em> than not joining, not
 *       merely cheaper than it looks: one {@code add()} per trace instead of one per
 *       frame, and none of the junk frame clusters that every later frame line would
 *       otherwise be compared against in the overflow leaf.</li>
 * </ol>
 *
 * <p>Numbers are printed, not asserted (a slow CI box must not fail the build). The only
 * assertion is the template count, which is a correctness bound, not a timing one.
 */
class JoinerBenchmark {

    private static final int RECORDS = 100_000;

    /** Distinct ordinary templates in the corpora below. */
    private static final int PLAIN_VARIANTS = 4;

    @Test
    void joinerIsNearFreeOnTraceFreeInput() {
        List<String> corpus = plainCorpus(RECORDS);
        warmUp(corpus);

        Result without = feed(corpus, false);
        Result with = feed(corpus, true);

        System.out.printf("[joiner] trace-free, unjoined: %,.0f lines/sec, %d templates%n",
                without.linesPerSec(), without.clusters);
        System.out.printf("[joiner] trace-free,   joined: %,.0f lines/sec, %d templates%n",
                with.linesPerSec(), with.clusters);

        assertTrue(with.clusters == without.clusters,
                "joining must not change clustering of trace-free input: "
                        + without.clusters + " -> " + with.clusters);
    }

    @Test
    void joiningCollapsesTraceHeavyInput() {
        List<String> corpus = traceCorpus(RECORDS / 10);   // ~10 lines per trace
        warmUp(corpus);

        Result without = feed(corpus, false);
        Result with = feed(corpus, true);

        System.out.printf("[joiner] trace-heavy, unjoined: %,.0f lines/sec, %d templates%n",
                without.linesPerSec(), without.clusters);
        System.out.printf("[joiner] trace-heavy,   joined: %,.0f lines/sec, %d templates%n",
                with.linesPerSec(), with.clusters);

        // The corpus is PLAIN_VARIANTS ordinary templates plus one repeated trace. Joined,
        // that trace must contribute exactly one template however many frames it has.
        assertEquals(PLAIN_VARIANTS + 1, with.clusters,
                "the whole trace should collapse to a single template");
        assertTrue(without.clusters > 2 * with.clusters,
                "unjoined, every distinct frame is its own template: "
                        + without.clusters + " vs " + with.clusters);
    }

    /** JIT warmup, so the first measured run is not penalised for going first. */
    private static void warmUp(List<String> corpus) {
        feed(corpus, false);
        feed(corpus, true);
    }

    private static Result feed(List<String> corpus, boolean join) {
        DrainTree tree = new DrainTree(DrainConfig.defaults(), Masker.withDefaults());
        LineSink ingest = tree::add;
        LineSink sink = join ? new StackTraceJoiner(ingest, 1) : ingest;

        long start = System.nanoTime();
        for (String line : corpus) {
            sink.accept(line);
        }
        sink.onEnd();
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new Result(corpus.size(), Math.max(millis, 1), tree.clusterCount());
    }

    private static List<String> plainCorpus(int lines) {
        Random rnd = new Random(7);
        List<String> out = new ArrayList<>(lines);
        for (int i = 0; i < lines; i++) {
            out.add(plain(rnd, i % PLAIN_VARIANTS));
        }
        return out;
    }

    /** Interleaves ordinary lines with realistic Spring Boot traces. */
    private static List<String> traceCorpus(int traces) {
        Random rnd = new Random(7);
        List<String> out = new ArrayList<>(traces * 10);
        for (int i = 0; i < traces; i++) {
            out.add(plain(rnd, i % PLAIN_VARIANTS));
            out.add("2026-07-19T10:00:00Z ERROR c.e.OrderService : Failed to process order " + rnd.nextInt(100000));
            out.add("java.lang.IllegalStateException: no such order");
            for (int f = 0; f < 5; f++) {
                out.add("\tat com.example.Layer" + f + ".call(Layer" + f + ".java:" + rnd.nextInt(400) + ") ~[classes/:na]");
            }
            out.add("Caused by: java.sql.SQLException: connection reset");
            out.add("\tat com.zaxxer.hikari.Pool.get(Pool.java:" + rnd.nextInt(400) + ") ~[HikariCP-5.0.1.jar:na]");
            out.add("\t... " + rnd.nextInt(90) + " common frames omitted");
        }
        return out;
    }

    private static String plain(Random rnd, int t) {
        String ts = String.format("2026-07-19T10:%02d:%02dZ", rnd.nextInt(60), rnd.nextInt(60));
        return switch (t) {
            case 0 -> ts + " INFO auth login user u" + rnd.nextInt(10000);
            case 1 -> ts + " WARN db slow query took " + rnd.nextInt(5000) + " ms";
            case 2 -> ts + " DEBUG cache hit key k" + rnd.nextInt(100000);
            default -> ts + " INFO metrics cpu " + rnd.nextInt(100) + " load " + rnd.nextInt(16);
        };
    }

    record Result(int lines, long millis, int clusters) {
        double linesPerSec() {
            return lines / (millis / 1000.0);
        }
    }
}
