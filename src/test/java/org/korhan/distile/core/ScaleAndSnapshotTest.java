package org.korhan.distile.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scale + snapshot-concurrency guard for the high-cardinality, long-running case
 * the throughput Benchmark deliberately does not exercise.
 *
 * <p>Benchmark feeds 8 templates that collapse to ~7 clusters, so it never
 * builds a deep/wide tree, never creates many clusters, and never sorts a large
 * snapshot. This test does the opposite: it generates thousands of structurally
 * distinct templates (distinct tokens in tree-branching positions, so they land in
 * distinct leaves) and takes snapshots concurrently with ingest. It guards two
 * paths Benchmark misses:
 * <ol>
 *   <li>tree descent / cluster creation at scale stays bounded — no explosion, yet
 *       the fan-out cap still lets thousands of legitimate templates form;</li>
 *   <li>a snapshot taken WHILE the ingest thread mutates counts never throws:
 *       neither ConcurrentModificationException from the copy, nor TimSort's
 *       "Comparison method violates its general contract" from sorting live counts
 *       (the reason snapshotTopN captures counts under the lock).</li>
 * </ol>
 *
 * <p>Assertions are machine-independent: template-count bounds and snapshot
 * consistency, never raw lines/sec (printed for information only).
 */
class ScaleAndSnapshotTest {

    private static final int LINES = 300_000;
    // 64 x 64 distinct (a,b) tokens in branching positions => up to 4096 templates.
    private static final String[] A = words("svc", 64);
    private static final String[] B = words("job", 64);

    @Test
    void thousandsOfTemplatesStayBoundedAndCountable() {
        DrainTree tree = new DrainTree(DrainConfig.defaults(), Masker.withDefaults());
        Random rnd = new Random(7);

        long start = System.nanoTime();
        for (int i = 0; i < LINES; i++) {
            tree.add(gen(rnd));
        }
        long ms = Math.max((System.nanoTime() - start) / 1_000_000, 1);

        int clusters = tree.clusterCount();
        System.out.printf("[scale] %,d lines in %,d ms = %,.0f lines/sec, %d templates%n",
                LINES, ms, LINES / (ms / 1000.0), clusters);

        // Many distinct templates DID form (guards over-aggressive masking/merging
        // and proves the create path works at scale)...
        assertTrue(clusters > 2000, "expected thousands of templates, got " + clusters);
        // ...but the count-bucket x branching fan-out keeps it bounded, not runaway.
        assertTrue(clusters <= A.length * B.length,
                "template count exceeded the distinct-combo ceiling: " + clusters);

        // Ingest finished => counts are stable => the snapshot must be a correct
        // count-descending Top-N.
        List<LogCluster> top = tree.snapshotTopN(10);
        assertTrue(top.size() == 10, "expected a full Top-10, got " + top.size());
        for (int i = 1; i < top.size(); i++) {
            assertTrue(top.get(i - 1).count() >= top.get(i).count(),
                    "snapshot not sorted by count descending");
        }
        assertTrue(tree.snapshotTopN(-1).size() == clusters,
                "full snapshot size should equal cluster count");
    }

    @Test
    void snapshotDuringIngestNeverThrows() throws InterruptedException {
        DrainTree tree = new DrainTree(DrainConfig.defaults(), Masker.withDefaults());
        Random rnd = new Random(11);

        AtomicBoolean ingesting = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // Mimics the SnapshotScheduler timer: read a Top-N snapshot as fast as
        // possible while counts change underneath it.
        Thread snapshotter = new Thread(() -> {
            try {
                while (ingesting.get()) {
                    List<LogCluster> top = tree.snapshotTopN(10);
                    // Ordering is NOT asserted here: counts mutate after the copy,
                    // so live count() no longer reflects the sort instant. We only
                    // assert the call itself is safe under concurrent mutation.
                    assertTrue(top.size() <= 10);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "test-snapshotter");
        snapshotter.setDaemon(true);
        snapshotter.start();

        for (int i = 0; i < LINES; i++) {
            tree.add(gen(rnd));
        }
        ingesting.set(false);
        snapshotter.join(5000);

        assertNull(failure.get(), () -> "concurrent snapshot threw: " + failure.get());
    }

    private static String gen(Random rnd) {
        // tokens: [ts, A, B, "done"]; ts masks to "<*>", so A and B are the two
        // branching keys (depth 4 descends tokens 0,1,2) => one leaf per (A,B) pair.
        String ts = String.format("2026-07-19T10:%02d:%02dZ", rnd.nextInt(60), rnd.nextInt(60));
        return ts + " " + A[rnd.nextInt(A.length)] + " " + B[rnd.nextInt(B.length)] + " done";
    }

    /**
     * n distinct, pure-alpha, non-hex tokens for a branching position. Two letters
     * from g..z (never a-f, so the hex-run rule can't mask them; length &lt; 8 so
     * its length gate never fires either) keep every token a stable branch key
     * instead of a masked "<*>".
     */
    private static String[] words(String prefix, int n) {
        String[] w = new String[n];
        for (int i = 0; i < n; i++) {
            char c1 = (char) ('g' + (i / 20) % 20);
            char c2 = (char) ('g' + (i % 20));
            w[i] = prefix + c1 + c2;
        }
        return w;
    }

    public static void main(String[] args) {
        new ScaleAndSnapshotTest().thousandsOfTemplatesStayBoundedAndCountable();
    }
}
