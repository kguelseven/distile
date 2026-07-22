package org.korhan.distile.log4j;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.ReusableMessageFactory;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real appender: build it through its @PluginFactory, push LogEvents
 * at DistileAppender#append, and confirm the core collapses them as expected — including
 * that a concatenated (SimpleMessage) event joins the same template as the parameterized
 * ones. Output is routed to a temp file so nothing prints during the build.
 */
class DistileAppenderIntegrationTest {

    private static LogEvent event(Message m) {
        return Log4jLogEvent.newBuilder().setLoggerName("test").setLevel(Level.INFO).setMessage(m).build();
    }

    @Test
    void appenderClustersParameterizedAndConcatenatedEvents() throws IOException {
        Path out = Files.createTempFile("distile-appender-", ".txt");
        // snapshotInterval=0 disables the timer so the test is deterministic and quiet.
        DistileAppender appender = DistileAppender.createAppender(
                "test", null, 0.5, 4, 100, 10, 0L, true, false, 2L, out.toString());
        assertTrue(appender != null, "factory should build the appender");
        appender.start();
        try {
            // Same shape, varying values -> one template.
            for (int i = 0; i < 5; i++) {
                appender.append(event(new ParameterizedMessage(
                        "user {} logged in from {}", new Object[]{1000 + i, "10.0.0." + i})));
            }
            // A concatenated (unstructured) event of the same shape must join that template.
            appender.append(event(new SimpleMessage("user 7777 logged in from 10.0.0.9")));
            // A different shape -> a second template.
            for (int i = 0; i < 3; i++) {
                appender.append(event(new ParameterizedMessage(
                        "db slow query {} took {}ms", new Object[]{"users", 100 + i})));
            }

            assertEquals(2, appender.tree().clusterCount(),
                    "6 login events (5 parameterized + 1 concatenated) and 3 slow-query events -> 2 templates");
        } finally {
            appender.stop(0, TimeUnit.MILLISECONDS);
        }

        String report = Files.readString(out);
        assertTrue(report.contains("[FINAL"), "stop() should emit the final report");
        Files.deleteIfExists(out);
    }

    @Test
    void collapsesRealReusableParameterizedEventsToOneTemplate() throws IOException {
        // End-to-end guard for the frontend a real app actually uses: messages built by the default
        // ReusableMessageFactory (as logger.info(fmt, args) does), not hand-built ParameterizedMessage.
        // Varied usernames must collapse to ONE template — one per user would mean lineFor fell back
        // to the rendered text.
        Path out = Files.createTempFile("distile-appender-reusable-", ".txt");
        DistileAppender appender = DistileAppender.createAppender(
                "test", null, 0.5, 4, 100, 10, 0L, true, false, 2L, out.toString());
        appender.start();
        try {
            String[] users = {"alice", "bob", "carol", "dave", "erin"};
            for (int i = 0; i < users.length; i++) {
                // Read synchronously in append() before the next newMessage() reuses the instance.
                Message m = ReusableMessageFactory.INSTANCE.newMessage(
                        "user {} logged in from {}", users[i], "10.0.0." + i);
                appender.append(event(m));
            }
            assertEquals(1, appender.tree().clusterCount(),
                    "5 varied logins via the default (reusable) factory -> 1 template, not 1 per user");
        } finally {
            appender.stop(0, TimeUnit.MILLISECONDS);
        }
        Files.deleteIfExists(out);
    }
}
