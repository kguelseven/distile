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

    // How wide the aligned template prefix may grow before alignment is abandoned. Text output has
    // no viewport to fit, so this only rejects a grid so wide the line would be absurd; the live
    // view passes a much smaller budget derived from the actual terminal.
    private static final int GRID_BUDGET = 120;

    private final PrintStream out;

    // Remembers column widths across tables, so the snapshot, final and outlier tables of one run
    // share a layout. Safe to keep unsynchronized: only ever touched from the synchronized emit.
    private final TemplateColumns columns = new TemplateColumns();

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
        List<String> templates = columns.align(clusters, GRID_BUDGET);
        // Left-pad the id column to the widest id in this table, otherwise the template column goes
        // ragged as soon as ids reach two digits. Width 1 reproduces the previous "#%d" exactly.
        String rowFormat = "%8d  #%-" + idWidth(clusters) + "d  %s%n";
        for (int i = 0; i < clusters.size(); i++) {
            LogCluster c = clusters.get(i);
            // count right-aligned in a small field for scannability
            out.printf(rowFormat, c.count(), c.clusterId(), templates.get(i));
        }
    }

    private static int idWidth(List<LogCluster> clusters) {
        long widest = 0;
        for (LogCluster c : clusters) {
            widest = Math.max(widest, c.clusterId());
        }
        return Long.toString(widest).length();
    }

    @Override
    public void flush() {
        out.flush();
    }
}
