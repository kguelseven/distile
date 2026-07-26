package org.korhan.distile.report;

import org.junit.jupiter.api.Test;
import org.korhan.distile.core.LogCluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Alignment has to earn its keep on the formats it recognises and, more importantly, keep its
 * hands off everything else. Every template below is real masker output, captured by running
 * distile over a sample of the format in question, not written by hand to suit the code.
 *
 * The negative tests carry the most weight: they pin the promise that a format alignment cannot
 * handle reliably prints exactly as it did before this class existed.
 */
class TemplateColumnsTest {

    // Formats where alignment fires.

    @Test
    void logbackGetsAFullFieldGrid() {
        // The lone colon sits at token 6 in every row, so the message column can be squared up.
        List<String> aligned = align(120,
                "<*> INFO <*> --- [t-<*>] a.b.C : hello <*>",
                "<*> DEBUG <*> --- [t-<*>] a.b.Dee : bye");

        assertEquals("<*>  INFO <*> --- [t-<*>] a.b.C   : hello <*>", aligned.get(0));
        assertEquals("<*> DEBUG <*> --- [t-<*>] a.b.Dee : bye", aligned.get(1));
    }

    @Test
    void realSpringBootTableStartsEveryMessageAtTheSameColumn() {
        List<String> aligned = align(120,
                "<*> INFO <*> --- [http-nio-<*>-exec-<*>] c.e.demo.OrderController : Created order <*> for customer <*>",
                "<*> DEBUG <*> --- [http-nio-<*>-exec-<*>] o.s.web.servlet.DispatcherServlet : POST /api/orders parameters={masked}",
                "<*> ERROR <*> --- [http-nio-<*>-exec-<*>] c.e.demo.ExceptionHandler : Unhandled exception handling request <*>",
                "<*> DEBUG <*> --- [HikariPool-<*>-housekeeper] com.zaxxer.hikari.pool.HikariPool : HikariPool-<*> - Pool stats (total=<*>)");

        assertSameMessageColumn(aligned);
        assertTrue(aligned.get(0).startsWith("<*>  INFO "),
                "level aligned to the right like a five wide level field: " + aligned.get(0));
    }

    @Test
    void journaldGetsAFieldGridFromTheUnitColon() {
        // Both unit tokens end in a colon at token 4, at different widths but the same index.
        List<String> aligned = align(120,
                "Jul <*> <*> lima-devbox systemd[<*>]: Starting apt-daily.service",
                "Jul <*> <*> lima-devbox sshd[<*>]: Accepted publickey for korhan from <*>");

        assertEquals("Jul <*> <*> lima-devbox systemd[<*>]: Starting apt-daily.service", aligned.get(0));
        assertEquals("Jul <*> <*> lima-devbox sshd[<*>]:    Accepted publickey for korhan from <*>", aligned.get(1));
    }

    @Test
    void rustEnvLoggerGetsTheLevelColumn() {
        // No token ends in a colon, so tier 1 declines. Token 0 is identical and token 1 is all
        // levels, which is enough for the level column.
        List<String> aligned = align(120,
                "[<*> INFO myapp::orders] created order <*>",
                "[<*> DEBUG myapp::db] select from orders where id=<*>",
                "[<*> ERROR myapp::orders] payment declined for order <*>");

        assertEquals("[<*>  INFO myapp::orders] created order <*>", aligned.get(0));
        assertEquals("[<*> DEBUG myapp::db] select from orders where id=<*>", aligned.get(1));
        assertEquals("[<*> ERROR myapp::orders] payment declined for order <*>", aligned.get(2));
    }

    // Formats where alignment has to do nothing at all.

    @Test
    void goLogIsUnchanged() {
        // Two wildcard columns, then prose. Identical columns are neutral for width, so nothing
        // is inserted.
        assertUnchanged(
                "<*> <*> order <*> created for customer <*>",
                "<*> <*> payment <*> declined",
                "<*> <*> cache miss for key <*>");
    }

    @Test
    void pythonDashFormatIsUnchanged() {
        // The level sits at token 5, out of reach: token 3, the logger, stops tier 2. The bare
        // separator token is deliberately not a boundary, because treating it as one mis-splits
        // exactly this layout.
        assertUnchanged(
                "<*> <*> - myapp.orders - INFO - created order <*> for customer <*>",
                "<*> <*> - myapp.db - DEBUG - select from orders where id=<*>",
                "<*> <*> - myapp.orders - ERROR - payment declined for order <*>");
    }

    @Test
    void pythonDefaultFormatIsUnchanged() {
        // No whitespace in the prefix: level, logger and the first message word arrive fused into
        // token 0, so there is no column structure to find at all.
        assertUnchanged(
                "WARNING:root:cache miss for key <*>",
                "INFO:myapp.orders:created order <*> for customer <*>",
                "ERROR:myapp.db:connection refused");
    }

    @Test
    void log4j2DefaultPatternIsUnchanged() {
        // This pattern puts the thread before the level, and the thread differs in every row, so
        // tier 2 stops at token 1 and never reaches the level. Field order decides the outcome,
        // not which family the format belongs to.
        assertUnchanged(
                "<*> [main] INFO com.example.Orders - created order <*>",
                "<*> [http-nio-<*>-exec-<*>] DEBUG com.example.Db - select from orders where id=<*>",
                "<*> [http-nio-<*>-exec-<*>] ERROR com.example.Orders - payment declined for order <*>");
    }

    @Test
    void nginxAccessLogIsUnchanged() {
        assertUnchanged(
                "<*> - - [<*>:<*> <*>] \"GET /api/orders HTTP/<*>\" <*> <*> \"-\" \"curl/<*>.<*>\"",
                "<*> - - [<*>:<*> <*>] \"POST /api/payments HTTP/<*>\" <*> <*> \"-\" \"curl/<*>.<*>\"");
    }

    @Test
    void jsonLinesAreUnchanged() {
        assertUnchanged(
                "{\"ts\":\"<*>\",\"level\":\"info\",\"logger\":\"myapp.orders\",\"msg\":\"created order <*>\"}",
                "{\"ts\":\"<*>\",\"level\":\"debug\",\"logger\":\"myapp.db\",\"msg\":\"select from orders\"}");
    }

    // Reliability gates.

    @Test
    void aSingleRowIsUnchanged() {
        // Nothing to align against, and no vocabulary to measure from one row.
        assertUnchanged("<*> INFO <*> --- [t-<*>] a.b.C : hello <*>");
    }

    @Test
    void aBareMessageWithAnEarlyColonIsUnchanged() {
        // The log4j2 appender feeds bare messages with no framework prefix. A leading "Failed:"
        // must not be mistaken for a field boundary.
        assertUnchanged(
                "Failed: to process order <*>",
                "Created order <*> for customer <*>");
    }

    @Test
    void aFieldGridWiderThanTheBudgetDegradesToTheLevelColumn() {
        // A viewport too narrow for the full grid drops one tier rather than starving the message
        // column. It does not give up on alignment altogether.
        List<String> aligned = align(20,
                "<*> INFO <*> --- [http-nio-<*>-exec-<*>] c.e.demo.OrderController : Created order <*>",
                "<*> DEBUG <*> --- [HikariPool-<*>-housekeeper] com.zaxxer.hikari.pool.HikariPool : Pool stats");

        assertEquals("<*>  INFO <*> --- [http-nio-<*>-exec-<*>] c.e.demo.OrderController : Created order <*>",
                aligned.get(0));
        assertEquals("<*> DEBUG <*> --- [HikariPool-<*>-housekeeper] com.zaxxer.hikari.pool.HikariPool : Pool stats",
                aligned.get(1));
    }

    @Test
    void aBudgetTooNarrowForAnyGridIsUnchanged() {
        List<LogCluster> rows = clusters(new String[] {
                "<*> INFO <*> --- [t-<*>] a.b.C : hello <*>",
                "<*> DEBUG <*> --- [t-<*>] a.b.Dee : bye",
        });
        assertEquals(raw(rows), new TemplateColumns().align(rows, 10),
                "a terminal too narrow for even the level column prints as before");
    }

    @Test
    void columnWidthsGrowButNeverShrink() {
        TemplateColumns columns = new TemplateColumns();
        List<LogCluster> wide = clusters(new String[] {
                "<*> INFO <*> --- [t-<*>] com.zaxxer.hikari.pool.HikariPool : pool stats",
                "<*> DEBUG <*> --- [t-<*>] a.b.C : hello <*>",
        });
        List<LogCluster> narrow = clusters(new String[] {
                "<*> INFO <*> --- [t-<*>] a.b.C : hello <*>",
                "<*> DEBUG <*> --- [t-<*>] a.b.D : bye",
        });

        int wideColumn = messageColumn(columns.align(wide, 120).get(1));
        int narrowColumn = messageColumn(columns.align(narrow, 120).get(0));

        assertEquals(wideColumn, narrowColumn,
                "a later, narrower table must not shift the columns back to the left");
    }

    // The invariant that makes all of the above safe.

    @Test
    void alignmentOnlyEverInsertsSpaces() {
        String[][] tables = {
                {"<*> INFO <*> --- [t-<*>] a.b.C : hello <*>", "<*> DEBUG <*> --- [t-<*>] a.b.Dee : bye"},
                {"[<*> INFO myapp::orders] created <*>", "[<*> DEBUG myapp::db] select <*>"},
                {"Jul <*> <*> host systemd[<*>]: Starting x", "Jul <*> <*> host sshd[<*>]: Accepted y"},
                {"<*> <*> order <*> created", "<*> <*> payment <*> declined"},
                {"WARNING:root:cache miss <*>", "INFO:myapp.orders:created <*>"},
        };
        for (String[] table : tables) {
            List<LogCluster> rows = clusters(table);
            List<String> aligned = new TemplateColumns().align(rows, 120);
            for (int i = 0; i < rows.size(); i++) {
                assertEquals(rows.get(i).template(),
                        Arrays.asList(aligned.get(i).trim().split("\\s+")),
                        "tokens must survive alignment unchanged: " + aligned.get(i));
            }
        }
    }

    // Helpers.

    private static List<String> align(int budget, String... templates) {
        return new TemplateColumns().align(clusters(templates), budget);
    }

    private static void assertUnchanged(String... templates) {
        List<LogCluster> rows = clusters(templates);
        assertEquals(raw(rows), new TemplateColumns().align(rows, 120),
                "this format has no reliable column structure, so output must be byte identical");
    }

    private static List<LogCluster> clusters(String[] templates) {
        List<LogCluster> rows = new ArrayList<>();
        for (int i = 0; i < templates.length; i++) {
            rows.add(new LogCluster(i, List.of(templates[i].split(" "))));
        }
        return rows;
    }

    private static List<String> raw(List<LogCluster> rows) {
        List<String> out = new ArrayList<>();
        for (LogCluster c : rows) {
            out.add(c.templateString());
        }
        return out;
    }

    /** Where the message begins, just past the aligned field boundary. */
    private static int messageColumn(String aligned) {
        int i = aligned.indexOf(" : ");
        assertTrue(i > 0, "expected an aligned field boundary in: " + aligned);
        return i + 3;
    }

    private static void assertSameMessageColumn(List<String> aligned) {
        int expected = messageColumn(aligned.get(0));
        for (String row : aligned) {
            assertEquals(expected, messageColumn(row), "message column differs: " + row);
        }
    }
}
