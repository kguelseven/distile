package org.korhan.distile.log4j;

import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.ReusableMessageFactory;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Pure-function checks on the placeholder hinting that turns a Log4j2 message into a line. */
class HintTest {

    @Test
    void whitespaceDelimitedPlaceholdersBecomeWildcards() {
        assertEquals("user <*> logged in from <*>",
                DistileAppender.hint("user {} logged in from {}"));
    }

    @Test
    void placeholderGluedToLiteralIsReplacedInPlace() {
        // "id={}" -> "id=<*>" so it stays a single token, matching the stdin path's "id=<*>".
        assertEquals("id=<*>", DistileAppender.hint("id={}"));
    }

    @Test
    void formatWithoutPlaceholdersIsUnchanged() {
        assertEquals("no placeholders here", DistileAppender.hint("no placeholders here"));
    }

    @Test
    void multipleAndAdjacentPlaceholders() {
        assertEquals("<*> <*> <*>", DistileAppender.hint("{} {} {}"));
        assertEquals("<*><*>", DistileAppender.hint("{}{}"));
    }

    @Test
    void nullFormatIsEmpty() {
        assertEquals("", DistileAppender.hint(null));
    }

    @Test
    void lineForParameterizedUsesFormatPositionsNotValues() {
        // Only the placeholder positions matter; the argument values (7) are never read.
        assertEquals("user <*> in",
                DistileAppender.lineFor(new ParameterizedMessage("user {} in", new Object[]{7})));
    }

    @Test
    void lineForNonParameterizedFallsBackToFormattedText() {
        assertEquals("plain concatenated text",
                DistileAppender.lineFor(new SimpleMessage("plain concatenated text")));
    }

    @Test
    void lineForReusableParameterizedMessageStillUsesFormatPositions() {
        // Regression guard: a real logger.info(fmt, args) call does NOT create a ParameterizedMessage.
        // The default garbage-free factory creates a ReusableParameterizedMessage, which is not a
        // subtype of it. lineFor must still read placeholder positions (via getParameters()), or every
        // distinct low-cardinality value (a username) would spawn its own template.
        Message m = ReusableMessageFactory.INSTANCE.newMessage("user {} in from {}", "frank", "10.0.0.1");
        assertFalse(m instanceof ParameterizedMessage,
                "precondition: the default reusable message is not a ParameterizedMessage");
        assertEquals("user <*> in from <*>", DistileAppender.lineFor(m));
    }
}
