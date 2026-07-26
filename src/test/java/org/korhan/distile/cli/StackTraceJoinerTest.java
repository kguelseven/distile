package org.korhan.distile.cli;

import org.junit.jupiter.api.Test;
import org.korhan.distile.core.DrainConfig;
import org.korhan.distile.core.DrainTree;
import org.korhan.distile.core.LogCluster;
import org.korhan.distile.core.Masker;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fixtures are real Throwable.printStackTrace shapes, not hand-simplified ones: Spring
 * Boot's ~[classes/:na] frame suffixes, module and classloader prefixes, lambda frames,
 * Caused by chains, the "N common frames omitted" elision. Those details are where a
 * plausible-looking rule quietly fails — an end anchor on the frame pattern, for instance,
 * rejects every Spring Boot frame.
 */
class StackTraceJoinerTest {

    /** Collects the records a joiner produces. */
    private static final class Collector implements LineSink {
        final List<String> records = new ArrayList<>();

        @Override
        public void accept(String line) {
            records.add(line);
        }
    }

    private static List<String> join(int framesKept, String... lines) {
        Collector out = new Collector();
        StackTraceJoiner joiner = new StackTraceJoiner(out, framesKept);
        for (String line : lines) {
            joiner.accept(line);
        }
        joiner.onEnd();
        return out.records;
    }

    private static List<String> join(String... lines) {
        return join(1, lines);
    }

    // --- the shapes that must be joined -----------------------------------------------

    @Test
    void springBootTraceCollapsesToOneRecord() {
        List<String> out = join(
                "2026-07-26 10:00:01 ERROR c.e.OrderService : Failed to process order 4711",
                "java.lang.IllegalStateException: no such order",
                "\tat com.example.OrderService.load(OrderService.java:42) ~[classes/:na]",
                "\tat com.example.OrderService.process(OrderService.java:17) ~[classes/:na]",
                "\tat java.base/java.lang.Thread.run(Thread.java:840) ~[na:na]",
                "Caused by: java.sql.SQLException: connection reset",
                "\tat com.zaxxer.hikari.Pool.get(Pool.java:120) ~[HikariCP-5.0.1.jar:na]",
                "\t... 43 common frames omitted");

        assertEquals(1, out.size(), out.toString());
        assertEquals("2026-07-26 10:00:01 ERROR c.e.OrderService : Failed to process order 4711"
                        + " | java.lang.IllegalStateException: no such order"
                        + " | at com.example.OrderService.load(OrderService.java:42) ~[classes/:na]",
                out.get(0));
    }

    @Test
    void framePrefixesAndSuffixesAllMatch() {
        // Every one of these is a frame; if any is missed it becomes its own record.
        List<String> out = join(0,
                "log line",
                "\tat com.example.Svc.load(Svc.java:42)",                          // plain JDK
                "\tat com.example.Svc.load(Svc.java:42) ~[classes/:na]",           // Spring Boot %wEx
                "\tat java.base/java.lang.Thread.run(Thread.java:840)",            // module prefix
                "\tat app//com.foo.Bar.baz(Bar.java:1)",                           // classloader prefix
                "\tat com.example.Svc.lambda$run$0(Svc.java:12)",                  // lambda
                "\tat com.example.Svc.native(Native Method)",                      // no line number
                "\tat com.example.Svc.gen(Unknown Source)",
                "\tSuppressed: java.io.IOException: close failed",
                "\t... 7 more");

        assertEquals(List.of("log line"), out);
    }

    @Test
    void traceWithoutExceptionHeaderStillJoins() {
        List<String> out = join(
                "boom",
                "\tat com.example.Svc.load(Svc.java:42)",
                "\tat com.example.Svc.run(Svc.java:9)");

        assertEquals(List.of("boom | at com.example.Svc.load(Svc.java:42)"), out);
    }

    // --- the shapes that must NOT be joined -------------------------------------------

    @Test
    void exceptionShapedLineNotFollowedByFrameStaysItsOwnRecord() {
        // Someone logging e.toString(). No frame follows, so the lookahead refuses it.
        List<String> out = join(
                "2026-07-26 10:00:01 ERROR c.e.Svc : giving up",
                "java.lang.IllegalStateException: no such order",
                "2026-07-26 10:00:02  INFO c.e.Svc : carrying on");

        assertEquals(3, out.size(), out.toString());
        assertEquals("java.lang.IllegalStateException: no such order", out.get(1));
    }

    @Test
    void indentedNonTraceLinesAreNotJoined() {
        // "any indented line is a continuation" is the rule we deliberately rejected:
        // pretty-printed payloads are indented too.
        List<String> out = join(
                "2026-07-26 10:00:01  INFO c.e.Svc : request body",
                "  {",
                "    \"id\": 7",
                "  }");

        assertEquals(4, out.size(), out.toString());
    }

    @Test
    void ordinaryLinesArePassedThroughUnchanged() {
        String[] lines = {"one", "two", "three"};
        assertEquals(List.of(lines), join(lines));
    }

    // --- extent and bounds ------------------------------------------------------------

    @Test
    void zeroFramesKeepsExceptionHeaderOnly() {
        List<String> out = join(0,
                "ERROR failed",
                "java.lang.IllegalStateException: no such order",
                "\tat com.example.Svc.load(Svc.java:42)",
                "\tat com.example.Svc.run(Svc.java:9)");

        assertEquals(List.of("ERROR failed | java.lang.IllegalStateException: no such order"), out);
    }

    @Test
    void joinedLinesAreBoundedRegardlessOfRequestedExtent() {
        List<String> lines = new ArrayList<>();
        lines.add("ERROR failed");
        for (int i = 0; i < 500; i++) {
            lines.add("\tat com.example.Svc.frame" + i + "(Svc.java:" + i + ")");
        }
        List<String> out = join(Integer.MAX_VALUE, lines.toArray(new String[0]));

        assertEquals(1, out.size());
        long joined = out.get(0).split(" \\| ", -1).length - 1;
        assertEquals(StackTraceJoiner.MAX_JOINED_LINES, joined,
                "extent must be capped so one record cannot grow without bound");
    }

    // --- flushing ---------------------------------------------------------------------

    @Test
    void pendingRecordIsReleasedOnIdle() {
        Collector out = new Collector();
        StackTraceJoiner joiner = new StackTraceJoiner(out, 1);

        joiner.accept("2026-07-26 10:00:01  INFO c.e.Svc : waiting");
        assertTrue(joiner.hasPending(), "the line must be held pending a possible continuation");
        assertEquals(List.of(), out.records);

        // Without this, a held line waits for the app's next write — forever on an idle stream.
        joiner.onIdle();
        assertEquals(List.of("2026-07-26 10:00:01  INFO c.e.Svc : waiting"), out.records);
    }

    @Test
    void unconfirmedExceptionHeaderIsReleasedOnEnd() {
        List<String> out = join(
                "ERROR failed",
                "java.lang.IllegalStateException: no such order");

        assertEquals(List.of("ERROR failed", "java.lang.IllegalStateException: no such order"), out);
    }

    // --- end to end through the core --------------------------------------------------

    @Test
    void twoOccurrencesOfOneTraceYieldOneTemplateCountedTwice() {
        DrainTree tree = new DrainTree(DrainConfig.defaults(), Masker.withDefaults());
        StackTraceJoiner joiner = new StackTraceJoiner(tree::add, 1);

        for (int order = 4711; order <= 4712; order++) {
            joiner.accept("2026-07-26 10:00:0" + (order - 4710)
                    + " ERROR c.e.OrderService : Failed to process order " + order);
            joiner.accept("java.lang.IllegalStateException: no such order");
            joiner.accept("\tat com.example.OrderService.load(OrderService.java:4" + order % 10 + ") ~[classes/:na]");
            joiner.accept("\tat com.example.OrderService.process(OrderService.java:17) ~[classes/:na]");
            joiner.accept("\tat java.base/java.lang.Thread.run(Thread.java:840) ~[na:na]");
        }
        joiner.onEnd();

        // Unjoined this is 5 templates (one per distinct frame); joined it is one error.
        assertEquals(1, tree.clusterCount());
        LogCluster only = tree.snapshotAll().get(0);
        assertEquals(2, only.count(), "the error occurred twice, on 10 input lines");
    }
}
