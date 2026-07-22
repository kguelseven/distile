package org.korhan.distile.emission;

import org.korhan.distile.core.LogCluster;

import java.time.Instant;
import java.util.List;

/**
 * Something the emission layer has decided is worth showing the user.
 *
 * <p>The emission layer reads core state and decides when a template
 * surfaces, producing these events; report/ renders them as text or JSON.
 * Core never produces or sees these types.
 */
public sealed interface EmissionEvent
        permits EmissionEvent.NewTemplate,
                EmissionEvent.Milestone,
                EmissionEvent.Snapshot,
                EmissionEvent.Final {

    /**
     * A template was seen for the very first time. Says nothing about frequency. Carries the instant
     * it first appeared (at); like all events, the raw instant lives here and formatting is
     * the reporter's job.
     */
    record NewTemplate(Instant at, LogCluster cluster) implements EmissionEvent {
    }

    /** A cluster's count crossed a milestone boundary (e.g. an order of magnitude), at instant at. */
    record Milestone(Instant at, LogCluster cluster, long milestone) implements EmissionEvent {
    }

    /**
     * Point-in-time Top-N view: "what dominates right now." Carries the instant the snapshot was
     * taken (at, captured by the scheduler at tick time, not when it is later rendered) so
     * a snapshot in a scrolling stream is timestamped like a log line. Formatting is the reporter's
     * job. The event holds the raw instant, not a formatted string.
     */
    record Snapshot(Instant at, List<LogCluster> topN, int totalTemplates) implements EmissionEvent {
    }

    /**
     * End-of-stream (or on-demand) full report: every template sorted by count,
     * plus the outlier view (clusters seen only a handful of times). Carries the instant the
     * report was built (at), timestamped like a snapshot; formatting stays the reporter's job.
     */
    record Final(Instant at, List<LogCluster> all, List<LogCluster> outliers, int totalTemplates)
            implements EmissionEvent {
    }
}
