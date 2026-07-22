package org.korhan.distile.report;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.korhan.distile.core.LogCluster;
import org.korhan.distile.emission.EmissionEvent;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TopReporter has two testable surfaces: the pure header-bar / highlight math (no terminal),
 * and the graceful degradation path when there is no interactive terminal (which is also the
 * situation in a headless test run). The live JLine rendering is exercised by the manual demo,
 * not here.
 */
class TopReporterTest {

    // ---- pure helpers: the header-bar math and row highlighting ----

    @Test
    void ratePerSecondIsLinesOverSeconds() {
        assertEquals(30L, TopReporter.ratePerSecond(300, 10.0));
        assertEquals(0L, TopReporter.ratePerSecond(300, 0.0), "no interval -> no divide-by-zero");
        assertEquals(0L, TopReporter.ratePerSecond(300, -1.0), "non-positive interval -> 0");
    }

    @Test
    void newTemplatesIsTheMonotonicDelta() {
        assertEquals(3, TopReporter.newTemplates(142, 139));
        assertEquals(0, TopReporter.newTemplates(5, 5));
        assertEquals(0, TopReporter.newTemplates(5, 10), "never negative");
    }

    @Test
    void isNewInViewFlagsOnlyEntriesAbsentLastFrame() {
        Map<Long, Long> prev = new HashMap<>();
        prev.put(7L, 100L);
        assertTrue(TopReporter.isNewInView(9L, prev), "unseen cluster just appeared -> flash");
        assertFalse(TopReporter.isNewInView(7L, prev), "already in view -> no flash (even if it grew)");
    }

    @Test
    void truncateUsesEllipsisWhenTooWide() {
        assertEquals("hello", TopReporter.truncate("hello", 10));
        assertEquals("hel…", TopReporter.truncate("hello world", 4));
        assertEquals("", TopReporter.truncate("hello", 0));
    }

    @Test
    void elapsedFormatsHoursMinutesSeconds() {
        assertEquals("01:01:01", TopReporter.elapsed(Duration.ofSeconds(3661)));
        assertEquals("00:00:00", TopReporter.elapsed(Duration.ZERO));
    }

    @Test
    void rowNeverExceedsWidth() {
        // A realistically long template (Hibernate SQL / DispatcherServlet lines) must be
        // truncated so it can never wrap and corrupt the frame — the bug that motivated this.
        String longTemplate = "<*> DEBUG <*> --- [http-nio-<*>-exec-<*>] o.h.SQL : select "
                + "o1_0.id,o1_0.total,o1_0.status,o1_0.created,o1_0.customer_id from orders o1_0 "
                + "where o1_0.id=? and o1_0.status=? order by o1_0.created desc limit ?";
        LogCluster c = new LogCluster(7, List.of(longTemplate.split(" ")));
        for (int width : new int[] {40, 80, 100, 120}) {
            AttributedString r = TopReporter.row(c, AttributedStyle.DEFAULT, width);
            assertTrue(r.columnLength() <= width,
                    "row width " + r.columnLength() + " exceeds " + width);
        }
    }

    @Test
    void fadeStyleFlashesThenReturnsToDefault() {
        assertNotEquals(AttributedStyle.DEFAULT, TopReporter.fadeStyle(0), "fresh row is coloured");
        assertNotEquals(AttributedStyle.DEFAULT, TopReporter.fadeStyle(1), "still fading");
        assertEquals(AttributedStyle.DEFAULT, TopReporter.fadeStyle(99), "fully faded -> default");
    }

    @Test
    void clipTruncatesToWidth() {
        AttributedString wide = new AttributedString("x".repeat(200));
        assertEquals(50, TopReporter.clip(wide, 50).columnLength());
        AttributedString narrow = new AttributedString("short");
        assertEquals(5, TopReporter.clip(narrow, 50).columnLength(), "under-width lines untouched");
    }

    // ---- graceful degradation: no TTY -> delegate to plain text ----

    @Test
    void withoutATerminalItFallsBackToPlainText() {
        String prevDumb = System.getProperty("org.jline.terminal.dumb");
        PrintStream realOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        // Force JLine to a dumb terminal so this is deterministic regardless of where tests run.
        System.setProperty("org.jline.terminal.dumb", "true");
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            TopReporter top = new TopReporter(2);

            LogCluster c = new LogCluster(3, List.of("User", "<*>", "logged", "in"));
            top.emit(new EmissionEvent.Snapshot(Instant.now(), List.of(c), 1, 42));
            // New-template and milestone events must NOT scroll in top mode.
            top.emit(new EmissionEvent.NewTemplate(Instant.now(), c));
            top.emit(new EmissionEvent.Milestone(Instant.now(), c, 10));
            top.emit(new EmissionEvent.Final(Instant.now(), List.of(c), List.of(), 1));
        } finally {
            System.setOut(realOut);
            if (prevDumb == null) {
                System.clearProperty("org.jline.terminal.dumb");
            } else {
                System.setProperty("org.jline.terminal.dumb", prevDumb);
            }
        }

        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("SNAPSHOT"), "snapshot delegated to text fallback");
        assertTrue(out.contains("User <*> logged in"), "template rendered");
        assertTrue(out.contains("FINAL"), "final delegated to text fallback");
        assertFalse(out.contains("NEW"), "new-template events are silent in top mode");
        assertFalse(out.contains("MILESTONE"), "milestone events are silent in top mode");
    }
}
