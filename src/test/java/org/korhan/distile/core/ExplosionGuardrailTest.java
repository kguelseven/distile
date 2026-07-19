package org.korhan.distile.core;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard against template explosion. A synthetic stream built from ~10
 * real templates with randomized variable parts (timestamps, ids, IPs, names)
 * must collapse back to a small template count. If masking or the similarity
 * threshold regress, this count blows up — which is exactly what the tool exists
 * to prevent — and the assertion fails.
 */
class ExplosionGuardrailTest {

    private static final String[] NAMES = {"alice", "bob", "carol", "dave", "erin", "frank"};
    private static final String[] HOSTS = {"db01", "db02", "cache-a", "cache-b", "edge-9"};

    @Test
    void syntheticLogFromTenTemplatesStaysUnderFiftyClusters() {
        DrainTree tree = new DrainTree(DrainConfig.defaults(), Masker.withDefaults());
        Random rnd = new Random(42); // deterministic

        for (int i = 0; i < 5000; i++) {
            tree.add(line(rnd, rnd.nextInt(10)));
        }

        int clusters = tree.clusterCount();
        assertTrue(clusters < 50,
                "template count exploded: expected < 50, got " + clusters);
        // Sanity: it should still be clustering, not collapsing everything to one.
        assertTrue(clusters >= 5,
                "expected the ~10 base templates to remain distinguishable, got " + clusters);
    }

    /** Build one line for the given template with randomized variable slots. */
    private static String line(Random rnd, int template) {
        String ts = ts(rnd);
        return switch (template) {
            case 0 -> ts + " INFO auth login user " + name(rnd) + " from " + ip(rnd);
            case 1 -> ts + " INFO auth logout user " + name(rnd);
            case 2 -> ts + " WARN db slow query took " + rnd.nextInt(5000) + " ms";
            case 3 -> ts + " ERROR http request GET /api/" + rnd.nextInt(1000) + " status " + rnd.nextInt(600);
            case 4 -> ts + " DEBUG cache hit key k" + rnd.nextInt(100000);
            case 5 -> ts + " DEBUG cache miss key k" + rnd.nextInt(100000);
            case 6 -> ts + " INFO worker job " + hex(rnd) + " completed in " + rnd.nextInt(9000) + " ms";
            case 7 -> ts + " WARN mem usage " + rnd.nextInt(100) + " percent";
            case 8 -> ts + " ERROR db connection failed host " + host(rnd);
            default -> ts + " INFO metrics cpu " + rnd.nextInt(100) + " load " + rnd.nextInt(16);
        };
    }

    private static String ts(Random rnd) {
        return String.format("2026-07-19T10:%02d:%02dZ", rnd.nextInt(60), rnd.nextInt(60));
    }

    private static String ip(Random rnd) {
        return rnd.nextInt(256) + "." + rnd.nextInt(256) + "." + rnd.nextInt(256) + "." + rnd.nextInt(256);
    }

    private static String name(Random rnd) {
        return NAMES[rnd.nextInt(NAMES.length)];
    }

    private static String host(Random rnd) {
        return HOSTS[rnd.nextInt(HOSTS.length)];
    }

    private static String hex(Random rnd) {
        return Long.toHexString(rnd.nextLong() & 0xFFFFFFFFFFFFL) + "abcd"; // ensure >= 8 hex chars
    }
}
