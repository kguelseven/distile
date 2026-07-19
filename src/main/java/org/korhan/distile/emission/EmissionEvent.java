package org.korhan.distile.emission;

import org.korhan.distile.core.LogCluster;

import java.util.List;

/**
 * Something the emission layer has decided is worth surfacing to the user.
 *
 * <p>Emission is concern (B): it reads core state and decides <em>when</em> a
 * template surfaces. It produces these events; {@code report/} turns them into
 * text or JSON. Core never produces or sees these types.
 */
public sealed interface EmissionEvent
        permits EmissionEvent.NewTemplate,
                EmissionEvent.Milestone,
                EmissionEvent.Snapshot,
                EmissionEvent.Final {

    /** A template was seen for the very first time. Says nothing about frequency. */
    record NewTemplate(LogCluster cluster) implements EmissionEvent {
    }

    /** A cluster's count crossed a milestone boundary (e.g. an order of magnitude). */
    record Milestone(LogCluster cluster, long milestone) implements EmissionEvent {
    }

    /** Point-in-time Top-N view: "what dominates right now." */
    record Snapshot(List<LogCluster> topN, int totalTemplates) implements EmissionEvent {
    }

    /**
     * End-of-stream (or on-demand) full report: every template sorted by count,
     * plus the outlier view (clusters seen only a handful of times).
     */
    record Final(List<LogCluster> all, List<LogCluster> outliers, int totalTemplates)
            implements EmissionEvent {
    }
}
