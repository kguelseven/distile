package org.korhan.distile.report;

import org.korhan.distile.core.LogCluster;
import org.korhan.distile.emission.EmissionEvent;

import java.io.PrintStream;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Line-delimited JSON (JSONL) rendering: one self-contained JSON object per
 * emission event. No JSON dependency — distile stays JDK-only — so a tiny
 * hand-rolled escaper handles string safety.
 *
 * <p>{@link #emit} is synchronized for the same reason as {@link TextReporter}:
 * the ingest thread and snapshot timer both write here.
 */
public final class JsonReporter implements Reporter {

    private final PrintStream out;

    public JsonReporter(PrintStream out) {
        this.out = out;
    }

    @Override
    public synchronized void emit(EmissionEvent event) {
        StringBuilder sb = new StringBuilder(128);
        switch (event) {
            case EmissionEvent.NewTemplate e -> {
                sb.append("{\"event\":\"new\",\"at\":\"")
                        .append(DateTimeFormatter.ISO_INSTANT.format(e.at())).append("\",");
                cluster(sb, e.cluster());
                sb.append('}');
            }
            case EmissionEvent.Milestone e -> {
                sb.append("{\"event\":\"milestone\",\"at\":\"")
                        .append(DateTimeFormatter.ISO_INSTANT.format(e.at()))
                        .append("\",\"milestone\":").append(e.milestone()).append(',');
                cluster(sb, e.cluster());
                sb.append('}');
            }
            case EmissionEvent.Snapshot e -> {
                // "at": the instant the snapshot was taken, as an unambiguous ISO-8601 UTC string.
                sb.append("{\"event\":\"snapshot\",\"at\":\"")
                        .append(DateTimeFormatter.ISO_INSTANT.format(e.at()))
                        .append("\",\"total\":").append(e.totalTemplates())
                        .append(",\"templates\":");
                array(sb, e.topN());
                sb.append('}');
            }
            case EmissionEvent.Final e -> {
                sb.append("{\"event\":\"final\",\"at\":\"")
                        .append(DateTimeFormatter.ISO_INSTANT.format(e.at()))
                        .append("\",\"total\":").append(e.totalTemplates())
                        .append(",\"templates\":");
                array(sb, e.all());
                sb.append(",\"outliers\":");
                array(sb, e.outliers());
                sb.append('}');
            }
        }
        out.println(sb);
    }

    /** Append the shared cluster fields (no surrounding braces). */
    private static void cluster(StringBuilder sb, LogCluster c) {
        sb.append("\"clusterId\":").append(c.clusterId())
                .append(",\"count\":").append(c.count())
                .append(",\"template\":\"").append(escape(c.templateString())).append('"');
    }

    private static void array(StringBuilder sb, List<LogCluster> clusters) {
        sb.append('[');
        for (int i = 0; i < clusters.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            cluster(sb, clusters.get(i));
            sb.append('}');
        }
        sb.append(']');
    }

    /** Minimal JSON string escaping (quotes, backslash, control chars). */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    @Override
    public void flush() {
        out.flush();
    }
}
