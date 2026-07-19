package org.korhan.distile.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tree behaviour tested with an <b>empty</b> masker so masking never interferes:
 * these assertions are purely about bucketing, similarity-driven merge, and
 * fan-out bounding.
 */
class DrainTreeTest {

    /** Masker that does nothing, so token differences are exactly as written. */
    private static Masker noMask() {
        return new Masker(List.of());
    }

    @Test
    void matchingLinesMergeDifferingPositionToWildcard() {
        // depth 2 => routes on token count + first token only, so the differing
        // second token lands in the same leaf and merge logic can act on it.
        DrainTree tree = new DrainTree(new DrainConfig(2, 100, 0.5), noMask());

        MatchResult first = tree.add("user alice active");
        assertTrue(first.isNew());

        MatchResult second = tree.add("user bob active");
        assertFalse(second.isNew(), "same-length, high-similarity line should merge");
        assertEquals(first.cluster().clusterId(), second.cluster().clusterId());
        assertEquals(2, second.newCount());

        assertEquals(1, tree.clusterCount());
        assertEquals(List.of("user", "<*>", "active"), second.cluster().template());
    }

    @Test
    void lowSimilarityInSameLeafCreatesNewCluster() {
        DrainTree tree = new DrainTree(new DrainConfig(2, 100, 0.5), noMask());
        tree.add("x a b c");   // template [x,a,b,c]
        tree.add("x p q r");   // shares first token -> same leaf, but sim = 1/4 < 0.5

        assertEquals(2, tree.clusterCount(), "dissimilar lines must not be forced to merge");
    }

    @Test
    void differentTokenCountsNeverMerge() {
        DrainTree tree = new DrainTree(new DrainConfig(4, 100, 0.5), noMask());
        tree.add("a b c");
        tree.add("a b c d"); // identical prefix but different length -> different bucket

        assertEquals(2, tree.clusterCount(),
                "variable-length variants land in different length buckets (documented Drain limitation)");
    }

    @Test
    void fanOutIsBoundedByMaxChildrenOverflow() {
        // maxChildren 2 at the second-token level: 'a' and 'b' get real branches,
        // everything else is funneled into the shared "<*>" overflow leaf and
        // merges there, so the template count stays bounded no matter how many
        // distinct middle tokens arrive.
        DrainTree tree = new DrainTree(new DrainConfig(3, 2, 0.5), noMask());
        tree.add("p a z");
        tree.add("p b z");
        for (char ch = 'c'; ch <= 'z'; ch++) {
            tree.add("p " + ch + " z");
        }

        assertEquals(3, tree.clusterCount(),
                "two real branches + one merged overflow branch = 3 templates regardless of cardinality");
    }
}
