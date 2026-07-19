package org.korhan.distile.core;

import java.util.List;

/**
 * One learned template plus how many lines have matched it.
 *
 * <p>The {@code template} is a list of tokens where variable positions hold the
 * wildcard {@code <*>}. It only ever loses specificity over time: when a matching
 * line disagrees at some position, that position is generalised to {@code <*>} and
 * never goes back. {@code count} is the only mutable state a cluster carries —
 * emission-side concerns (e.g. which milestone was last reported) live in the
 * emission layer, never here.
 */
public final class LogCluster {

    private final long clusterId;
    // Mutable template tokens; positions generalise to "<*>" as lines merge in.
    private final String[] template;
    private long count;

    public LogCluster(long clusterId, List<String> tokens) {
        this.clusterId = clusterId;
        this.template = tokens.toArray(new String[0]);
        this.count = 1;
    }

    public long clusterId() {
        return clusterId;
    }

    public long count() {
        return count;
    }

    /** Number of tokens in this template (fixed for the cluster's lifetime). */
    public int size() {
        return template.length;
    }

    /** Read-only snapshot of the template tokens. */
    public List<String> template() {
        return List.of(template);
    }

    /** Render the template as a single space-joined string. */
    public String templateString() {
        return String.join(" ", template);
    }

    long incrementCount() {
        return ++count;
    }

    /**
     * Count how many positions of this template match the given line tokens.
     * A {@code <*>} template token matches any line token (wildcard matches
     * everything); a concrete token matches only an identical string.
     *
     * <p>Caller guarantees {@code tokens.size() == size()} — Level-1 token-count
     * bucketing makes this invariant hold, so no length handling is needed here.
     */
    int countMatchingPositions(List<String> tokens) {
        int matches = 0;
        for (int i = 0; i < template.length; i++) {
            String t = template[i];
            if (t.equals(MaskRule.WILDCARD) || t.equals(tokens.get(i))) {
                matches++;
            }
        }
        return matches;
    }

    /**
     * Merge a matching line into this template: any position where the template
     * token differs from the line token is generalised to {@code <*>}, then the
     * count is incremented. Returns the new count.
     */
    long merge(List<String> tokens) {
        for (int i = 0; i < template.length; i++) {
            if (!template[i].equals(MaskRule.WILDCARD) && !template[i].equals(tokens.get(i))) {
                template[i] = MaskRule.WILDCARD;
            }
        }
        return incrementCount();
    }
}
