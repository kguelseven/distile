package org.korhan.distile.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The similarity metric: matching positions / token count, where a template
 * <*> matches any line token (wildcard matches everything).
 */
class SimilarityTest {

    @Test
    void concreteTokensMatchOnlyWhenEqual() {
        LogCluster c = new LogCluster(0, List.of("a", "b", "c"));
        assertEquals(3, c.countMatchingPositions(List.of("a", "b", "c")));
        assertEquals(2, c.countMatchingPositions(List.of("a", "x", "c")));
        assertEquals(0, c.countMatchingPositions(List.of("x", "y", "z")));
    }

    @Test
    void wildcardMatchesAnything() {
        LogCluster c = new LogCluster(0, List.of("a", "<*>", "c"));
        assertEquals(3, c.countMatchingPositions(List.of("a", "anything", "c")));
        assertEquals(3, c.countMatchingPositions(List.of("a", "42", "c")));
        // Still only counts the concrete positions that actually match.
        assertEquals(2, c.countMatchingPositions(List.of("a", "anything", "WRONG")));
    }
}
