package org.korhan.distile.log4j;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.korhan.distile.core.DrainConfig;
import org.korhan.distile.core.DrainTree;
import org.korhan.distile.core.MatchResult;
import org.korhan.distile.core.Masker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core guarantee behind the appender: a message fed through the appender's hinting path
 * lands in the same template as the equivalent message fed as a raw string. One
 * template space, regardless of ingestion path — verified at the core level, no Log4j runtime
 * needed beyond building the Message objects.
 */
class AppenderParityTest {

    @Test
    void hintedAndRenderedFormsShareOneTemplate() {
        DrainTree tree = new DrainTree(DrainConfig.defaults(), Masker.withDefaults());

        // What the appender produces for log.info("user {} logged in from {}", 4711, "10.0.0.1")
        String hinted = DistileAppender.lineFor(
                new ParameterizedMessage("user {} logged in from {}", new Object[]{4711, "10.0.0.1"}));
        // The same event rendered as a plain string (the stdin path): masking catches the
        // number and the IPv4, so it reaches the identical template.
        String rendered = new SimpleMessage("user 4711 logged in from 10.0.0.1").getFormattedMessage();

        MatchResult viaHint = tree.add(hinted);
        MatchResult viaRendered = tree.add(rendered);

        assertTrue(viaHint.isNew(), "first line creates the template");
        assertFalse(viaRendered.isNew(), "rendered form must merge into the hinted template");
        assertEquals(viaHint.cluster().clusterId(), viaRendered.cluster().clusterId());
        assertEquals(1, tree.clusterCount(), "both paths must produce exactly one template");
        assertEquals(List.of("user", "<*>", "logged", "in", "from", "<*>"),
                viaRendered.cluster().template());
    }
}
