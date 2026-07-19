package org.korhan.distile.report;

import org.korhan.distile.core.LogCluster;
import org.korhan.distile.emission.EmissionEvent;

import java.io.PrintStream;
import java.util.List;

/**
 * Human-readable text rendering of emission events.
 *
 * <p>{@link #emit} is synchronized because two threads reach it — the ingest
 * thread (new-template / milestone events) and the snapshot timer — and their
 * output must not interleave mid-line.
 */
public final class TextReporter implements Reporter {

    private final PrintStream out;

    public TextReporter(PrintStream out) {
        this.out = out;
    }

    @Override
    public synchronized void emit(EmissionEvent event) {
        switch (event) {
            case EmissionEvent.NewTemplate e ->
                    out.println("[NEW] #" + e.cluster().clusterId() + "  " + e.cluster().templateString());

            case EmissionEvent.Milestone e ->
                    out.println("[x" + e.milestone() + "] #" + e.cluster().clusterId()
                            + " (count=" + e.cluster().count() + ")  " + e.cluster().templateString());

            case EmissionEvent.Snapshot e -> {
                out.println("== snapshot: top " + e.topN().size() + " of " + e.totalTemplates() + " templates ==");
                printTable(e.topN());
            }

            case EmissionEvent.Final e -> {
                out.println();
                out.println("== final: " + e.totalTemplates() + " templates ==");
                printTable(e.all());
                out.println();
                out.println("-- outliers (count <= threshold): " + e.outliers().size() + " --");
                printTable(e.outliers());
            }
        }
    }

    private void printTable(List<LogCluster> clusters) {
        for (LogCluster c : clusters) {
            // count right-aligned in a small field for scannability
            out.printf("%8d  #%d  %s%n", c.count(), c.clusterId(), c.templateString());
        }
    }

    @Override
    public void flush() {
        out.flush();
    }
}
