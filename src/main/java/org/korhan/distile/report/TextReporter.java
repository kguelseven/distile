package org.korhan.distile.report;

import org.korhan.distile.core.LogCluster;
import org.korhan.distile.emission.EmissionEvent;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Human-readable text rendering of emission events.
 *
 * <p>emit is synchronized because two threads reach it — the ingest
 * thread (new-template / milestone events) and the snapshot timer — and their
 * output must not interleave mid-line.
 */
public final class TextReporter implements Reporter {

    // Every event's lead line is "[TAG timestamp]  detail", rendered in local time like a log line's
    // timestamp. Brackets keep distile's meta-output visually distinct from the app's own log lines.
    private static final DateTimeFormatter HEADER_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    // Widest tag ("MILESTONE") — pad all tags to this so timestamps and details align in a column.
    private static final int TAG_WIDTH = "MILESTONE".length();

    private final PrintStream out;

    public TextReporter(PrintStream out) {
        this.out = out;
    }

    @Override
    public synchronized void emit(EmissionEvent event) {
        switch (event) {
            case EmissionEvent.NewTemplate e ->
                    out.println(header("NEW", e.at()) + "  #" + e.cluster().clusterId()
                            + "  " + e.cluster().templateString());

            case EmissionEvent.Milestone e ->
                    out.println(header("MILESTONE", e.at()) + "  #" + e.cluster().clusterId()
                            + "  " + e.cluster().templateString() + "  (x" + e.milestone() + ")");

            case EmissionEvent.Snapshot e -> {
                out.println(header("SNAPSHOT", e.at())
                        + "  top " + e.topN().size() + " of " + e.totalTemplates() + " templates");
                printTable(e.topN());
            }

            case EmissionEvent.Final e -> {
                out.println();
                out.println(header("FINAL", e.at()) + "  " + e.totalTemplates() + " templates");
                printTable(e.all());
                out.println();
                out.println("-- outliers (count <= threshold): " + e.outliers().size() + " --");
                printTable(e.outliers());
            }
        }
    }

    /** "[TAG        yyyy-MM-dd'T'HH:mm:ss.SSS]" with the tag padded to a fixed column width. */
    private static String header(String tag, Instant at) {
        String padded = tag.length() >= TAG_WIDTH ? tag : tag + " ".repeat(TAG_WIDTH - tag.length());
        return "[" + padded + " " + HEADER_TS.format(at) + "]";
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
