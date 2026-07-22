package org.korhan.distile.emission;

import org.korhan.distile.core.LogCluster;
import org.korhan.distile.core.MatchResult;
import org.korhan.distile.report.Reporter;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-line emission triggers: fire on a brand-new template, and/or when a
 * cluster's count crosses a milestone.
 *
 * <p>It only reads the MatchResult core hands back per line and
 * never touches clustering state. Its own small state — the last milestone
 * reported per cluster — lives here, keyed by cluster id, not on LogCluster.
 *
 * <p>Not thread-safe: driven by the single ingest thread. The interval snapshot
 * is a separate concern (SnapshotScheduler).
 */
public final class EmissionPolicy {

    /** Default milestone boundaries: powers of ten (order-of-magnitude jumps). */
    public static final long[] DEFAULT_MILESTONES = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};

    private final boolean emitNew;
    private final long[] milestones; // ascending; empty = milestones off
    private final Reporter reporter;

    // Emission-side state only: cluster id -> highest milestone already emitted.
    private final Map<Long, Long> lastMilestone = new HashMap<>();

    private EmissionPolicy(boolean emitNew, long[] milestones, Reporter reporter) {
        this.emitNew = emitNew;
        this.milestones = milestones.clone();
        this.reporter = reporter;
    }

    /** New-template events on, milestones off — the recommended local-dev default. */
    public static EmissionPolicy newTemplatesOnly(Reporter reporter) {
        return new EmissionPolicy(true, new long[0], reporter);
    }

    /**
     * Fully configured policy.
     *
     * @param milestones ascending milestone set, or an empty array to disable
     *                   milestone emission
     */
    public static EmissionPolicy of(boolean emitNew, long[] milestones, Reporter reporter) {
        long[] sorted = milestones == null ? new long[0] : milestones.clone();
        java.util.Arrays.sort(sorted);
        return new EmissionPolicy(emitNew, sorted, reporter);
    }

    /** React to one line's match result, emitting any triggered events. */
    public void onMatch(MatchResult result) {
        // Instant.now() is called ONLY when an event actually fires (new template / milestone
        // crossing) — both are rare. The common per-line case (matched an existing template, nothing
        // to emit) touches no clock, keeping the hot path allocation- and syscall-free.
        if (emitNew && result.isNew()) {
            reporter.emit(new EmissionEvent.NewTemplate(Instant.now(), result.cluster()));
        }
        if (milestones.length > 0) {
            checkMilestone(result);
        }
    }

    private void checkMilestone(MatchResult result) {
        long count = result.newCount();
        long id = result.cluster().clusterId();
        long last = lastMilestone.getOrDefault(id, 0L);

        // Highest milestone at or below the current count. Increments are +1 so
        // this crosses one boundary at a time, but taking the max also handles
        // any jump without emitting every intermediate boundary (no spam).
        long crossed = 0;
        for (long m : milestones) {
            if (m <= count && m > crossed) {
                crossed = m;
            }
        }
        if (crossed > last) {
            lastMilestone.put(id, crossed);
            reporter.emit(new EmissionEvent.Milestone(Instant.now(), result.cluster(), crossed));
        }
    }

    /**
     * Build the end-of-stream report event: all clusters sorted by count, plus
     * the outlier subset (count &le; outlierMax).
     */
    public static EmissionEvent.Final buildFinal(List<LogCluster> allSorted, int total, long outlierMax) {
        List<LogCluster> outliers = allSorted.stream()
                .filter(c -> c.count() <= outlierMax)
                .toList();
        // Stamp the moment the report is built (end of stream / on demand), analogous to a snapshot.
        return new EmissionEvent.Final(Instant.now(), allSorted, outliers, total);
    }
}
