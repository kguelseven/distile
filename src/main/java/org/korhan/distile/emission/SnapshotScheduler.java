package org.korhan.distile.emission;

import org.korhan.distile.core.DrainTree;
import org.korhan.distile.core.LogCluster;
import org.korhan.distile.report.Reporter;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Emits a Top-N snapshot on a fixed timer, decoupled from the ingest loop. Runs
 * on its own daemon thread and only ever reads a copied, sorted view via
 * DrainTree#snapshotTopN(int), so it never blocks or slows ingestion —
 * a point-in-time "what dominates right now" view.
 */
public final class SnapshotScheduler implements AutoCloseable {

    private final DrainTree tree;
    private final Reporter reporter;
    private final int topN;
    private final long intervalSeconds;
    // Ingest-side line counter (lines fed to the core so far). Read at tick time and carried on
    // the Snapshot event so reporters can show throughput; kept out of core, which counts
    // templates, not lines.
    private final LongSupplier totalLines;
    private ScheduledExecutorService executor;

    public SnapshotScheduler(DrainTree tree, Reporter reporter, int topN, long intervalSeconds,
                             LongSupplier totalLines) {
        this.tree = tree;
        this.reporter = reporter;
        this.topN = topN;
        this.intervalSeconds = intervalSeconds;
        this.totalLines = totalLines;
    }

    /** Start the timer. A non-positive interval disables snapshots entirely. */
    public void start() {
        if (intervalSeconds <= 0) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "distile-snapshot");
            t.setDaemon(true); // never keep the JVM alive for a snapshot timer
            return t;
        });
        executor.scheduleAtFixedRate(this::tick, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private void tick() {
        // Swallow throwables: a formatting hiccup must never kill the timer or
        // propagate into ingestion.
        try {
            List<LogCluster> top = tree.snapshotTopN(topN);
            if (!top.isEmpty()) {
                reporter.emit(new EmissionEvent.Snapshot(
                        Instant.now(), top, tree.clusterCount(), totalLines.getAsLong()));
            }
        } catch (RuntimeException ignored) {
            // intentionally ignored — best-effort periodic view
        }
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
