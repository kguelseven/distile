package org.korhan.distile.report;

import org.junit.jupiter.api.Test;
import org.korhan.distile.core.LogCluster;
import org.korhan.distile.emission.EmissionEvent;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where column alignment applies, and where it deliberately does not. */
class TextReporterTest {

    private static final Instant AT = Instant.parse("2026-07-19T10:02:31.512Z");

    @Test
    void snapshotTableAlignsColumnsAndPadsTheIdColumn() {
        List<LogCluster> table = List.of(
                cluster(9, "<*> INFO <*> --- [t-<*>] a.b.C : hello <*>"),
                cluster(10, "<*> DEBUG <*> --- [t-<*>] a.b.Dee : bye"));

        String out = emit(new EmissionEvent.Snapshot(AT, table, 2, 100));

        assertTrue(out.contains("#9   <*>  INFO <*> --- [t-<*>] a.b.C   : hello <*>"),
                "id padded to the widest id, level to the right, logger column squared:\n" + out);
        assertTrue(out.contains("#10  <*> DEBUG <*> --- [t-<*>] a.b.Dee : bye"), out);
    }

    @Test
    void aSingleDigitIdTableIsFormattedExactlyAsBefore() {
        // An id width of one has to reproduce the original format, so short runs stay untouched.
        List<LogCluster> table = List.of(
                cluster(0, "<*> <*> order <*> created"),
                cluster(1, "<*> <*> payment <*> declined"));

        String out = emit(new EmissionEvent.Snapshot(AT, table, 2, 10));

        assertTrue(out.contains("       1  #1  <*> <*> payment <*> declined"), out);
    }

    @Test
    void newTemplateLeadLinesAreNotAligned() {
        // One event has no other row to align against, so its template is printed as it is.
        String out = emit(new EmissionEvent.NewTemplate(AT,
                cluster(7, "<*> INFO <*> --- [t-<*>] a.b.C : hello <*>")));

        assertTrue(out.contains("#7  <*> INFO <*> --- [t-<*>] a.b.C : hello <*>"),
                "lead line carries the raw template:\n" + out);
    }

    @Test
    void finalAndOutlierTablesShareOneLayout() {
        // The outlier table holds only the two narrow rows. On its own it would pick a much
        // narrower logger column; remembered widths keep it lined up with the table above it. A
        // single row outlier table is a different case and declines outright, because one row
        // demonstrates no structure. See TemplateColumnsTest.aSingleRowIsUnchanged.
        List<LogCluster> all = List.of(
                cluster(0, "<*> INFO <*> --- [t-<*>] com.zaxxer.hikari.pool.HikariPool : pool stats"),
                cluster(1, "<*> DEBUG <*> --- [t-<*>] a.b.C : hello <*>"),
                cluster(2, "<*> DEBUG <*> --- [t-<*>] a.b.D : bye"));
        List<LogCluster> outliers = List.of(all.get(1), all.get(2));

        String out = emit(new EmissionEvent.Final(AT, all, outliers, 3));

        List<Integer> boundaries = new ArrayList<>();
        for (String line : out.split("\n")) {
            int i = line.indexOf(" : ");
            if (i > 0) {
                boundaries.add(i);
            }
        }
        assertEquals(5, boundaries.size(), "three table rows plus two of them again as outliers");
        assertEquals(1, boundaries.stream().distinct().count(),
                "the outlier table must not reflow to its own narrower widths: " + boundaries);
    }

    private static LogCluster cluster(long id, String template) {
        return new LogCluster(id, List.of(template.split(" ")));
    }

    private static String emit(EmissionEvent event) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        TextReporter reporter = new TextReporter(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        reporter.emit(event);
        reporter.flush();
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
