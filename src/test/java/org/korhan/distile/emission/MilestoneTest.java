package org.korhan.distile.emission;

import org.junit.jupiter.api.Test;
import org.korhan.distile.core.DrainConfig;
import org.korhan.distile.core.DrainTree;
import org.korhan.distile.core.Masker;
import org.korhan.distile.report.Reporter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone emission fires once per crossed boundary and never per-increment.
 * Drives a real DrainTree} so counts advance the way they do in
 * production, and captures events with an in-memory reporter.
 */
class MilestoneTest {

    /** Reporter that records every event instead of printing it. */
    private static final class CapturingReporter implements Reporter {
        final List<EmissionEvent> events = new ArrayList<>();

        @Override
        public void emit(EmissionEvent event) {
            events.add(event);
        }
    }

    @Test
    void emitsOncePerCrossedMilestoneWithNoSpam() {
        CapturingReporter reporter = new CapturingReporter();
        // milestones on, new-template emission off, so only milestones are captured
        EmissionPolicy policy = EmissionPolicy.of(false, EmissionPolicy.DEFAULT_MILESTONES, reporter);

        DrainTree tree = new DrainTree(new DrainConfig(2, 100, 0.5), new Masker(List.of()));
        for (int i = 0; i < 25; i++) {
            policy.onMatch(tree.add("svc ping ok")); // all one cluster; count 1..25
        }

        List<Long> crossed = reporter.events.stream()
                .filter(e -> e instanceof EmissionEvent.Milestone)
                .map(e -> ((EmissionEvent.Milestone) e).milestone())
                .toList();

        // Counts 1..25 cross only the boundaries 1 and 10 (not 100), each once.
        assertEquals(List.of(1L, 10L), crossed);
    }

    @Test
    void newTemplateDisabledMeansNoNewEvents() {
        CapturingReporter reporter = new CapturingReporter();
        EmissionPolicy policy = EmissionPolicy.of(false, new long[0], reporter);

        DrainTree tree = new DrainTree(DrainConfig.defaults(), Masker.withDefaults());
        policy.onMatch(tree.add("hello world"));

        assertTrue(reporter.events.isEmpty(), "no triggers enabled => no events");
    }
}
