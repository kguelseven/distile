package org.korhan.distile.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The Drain engine: a fixed-depth parse tree that clusters log lines into
 * templates in near-O(1) per line. It runs silently on every line (tokenize →
 * mask → descend → match/merge → count++), never decides what to print,
 * and hands back a MatchResult for the emission layer to react to.
 *
 * <p>Fast because matching only ever compares against the clusters in ONE leaf,
 * reached via a path bounded by depth with fan-out bounded by
 * maxChildren (overflow → a single <*> branch): per-line cost is
 * O(depth + clustersInLeaf), independent of lines seen. Memory is bounded by the
 * number of distinct templates, not lines (lines are discarded; add no per-line
 * state).
 *
 * <p>The tree buckets first by token count (level 1), then by leading tokens
 * (levels 2..depth). Level 1's count bucketing means every cluster in a leaf has
 * the same length, so similarity needs no length handling.
 *
 * <p>Thread-safety: add and snapshotTopN synchronize on this
 * instance; ingestion is single-threaded, so the lock is uncontended except for
 * the brief snapshot copy taken by the emission scheduler.
 */
public final class DrainTree {

    private final DrainConfig config;
    private final Tokenizer tokenizer;
    private final Masker masker;

    private final Node root = new Node();
    private long nextClusterId = 0;

    // A flat list of every cluster, kept only so snapshots and the final report
    // can iterate them all. Line matching never reads this (it looks at just one
    // leaf's clusters) so scanning the whole list here doesn't slow ingestion.
    private final List<LogCluster> allClusters = new ArrayList<>();

    public DrainTree(DrainConfig config, Masker masker) {
        this.config = config;
        this.masker = masker;
        this.tokenizer = new Tokenizer();
    }

    public DrainConfig config() {
        return config;
    }

    /** Feed one raw line; returns the matched/created cluster and whether it is new. */
    public synchronized MatchResult add(String line) {
        List<String> masked = masker.mask(tokenizer.tokenize(line));

        // A blank line tokenizes to nothing. Bucket it under an explicit empty
        // key so it clusters with other blanks instead of blowing up descent.
        if (masked.isEmpty()) {
            masked = List.of();
        }

        Node leaf = descendToLeaf(masked);
        List<LogCluster> candidates = leaf.clusters();

        LogCluster best = bestMatch(candidates, masked);
        if (best != null) {
            long newCount = best.merge(masked);
            return new MatchResult(best, false, newCount);
        }

        LogCluster created = new LogCluster(nextClusterId++, masked);
        candidates.add(created);
        allClusters.add(created);
        return new MatchResult(created, true, 1);
    }

    /**
     * Find the leaf holding this masked line's clusters, creating nodes as needed.
     *
     * <p>Level 1 buckets by token count and never overflows to <*>, because
     * similarity assumes every cluster in a leaf has the same length, and mixing
     * lengths would break that. The token levels below it do overflow: once a node
     * hits maxChildren, extra keys share one <*> child, so a flood of distinct
     * tokens can't grow the tree without bound.
     */
    private Node descendToLeaf(List<String> tokens) {
        // Level 1: token-count bucket (no overflow — see above).
        Node node = childForKey(root, Integer.toString(tokens.size()), /*allowOverflow=*/false);

        // Levels 2..depth: leading tokens, at most (depth - 1) of them, and no
        // more than the line actually has.
        int tokenLevels = Math.min(config.depth() - 1, tokens.size());
        for (int i = 0; i < tokenLevels; i++) {
            String key = tokens.get(i);
            // A token that is already a wildcard descends the single "<*>"
            // branch, so variable-position tokens don't fan the tree out.
            node = childForKey(node, key, /*allowOverflow=*/true);
        }
        return node;
    }

    /**
     * Resolve (or create) the child of parent for key. When the
     * key is unknown and the node is already at maxChildren, overflow to
     * a shared <*> child instead of adding an unbounded new branch.
     */
    private Node childForKey(Node parent, String key, boolean allowOverflow) {
        Map<String, Node> children = parent.children();
        Node existing = children.get(key);
        if (existing != null) {
            return existing;
        }
        if (allowOverflow && children.size() >= config.maxChildren()) {
            return children.computeIfAbsent(MaskRule.WILDCARD, k -> new Node());
        }
        Node created = new Node();
        children.put(key, created);
        return created;
    }

    /**
     * Best cluster in the leaf whose similarity meets the threshold, or null.
     * Similarity = matching positions / token count, with a <*> template
     * token matching any line token.
     */
    private LogCluster bestMatch(List<LogCluster> candidates, List<String> tokens) {
        int size = tokens.size();
        LogCluster best = null;
        double bestSim = -1.0;
        for (LogCluster c : candidates) {
            // Level-1 bucketing guarantees this cluster has the same length as the
            // line, so dividing by size is safe. Empty lines (size 0) count as a
            // perfect match with each other.
            double sim = size == 0 ? 1.0 : (double) c.countMatchingPositions(tokens) / size;
            if (sim > bestSim) {
                bestSim = sim;
                best = c;
            }
        }
        return bestSim >= config.simThreshold() ? best : null;
    }

    /**
     * A sorted (count-descending) copy of the top n clusters. Read-only:
     * this never mutates tree state. Allowed to scan all clusters (it is a
     * reporting path, not the per-line hot path).
     */
    public List<LogCluster> snapshotTopN(int n) {
        // Copy each cluster with its count as of right now, then release the lock
        // before sorting. We sort outside the lock so it never stalls the ingest
        // thread, and on the captured counts rather than live count() — otherwise
        // the ingest thread could change a count mid-sort (-> IllegalArgumentException).
        List<Ranked> ranked;
        synchronized (this) {
            ranked = new ArrayList<>(allClusters.size());
            for (LogCluster c : allClusters) {
                ranked.add(new Ranked(c, c.count()));
            }
        }
        ranked.sort(Comparator.comparingLong((Ranked r) -> r.count).reversed()
                .thenComparingLong(r -> r.cluster.clusterId()));

        int limit = (n >= 0 && n < ranked.size()) ? n : ranked.size();
        List<LogCluster> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            out.add(ranked.get(i).cluster);
        }
        return out;
    }

    /** A cluster paired with its count captured at snapshot time (see snapshotTopN). */
    private record Ranked(LogCluster cluster, long count) {
    }

    /** Total number of distinct templates learned so far. */
    public synchronized int clusterCount() {
        return allClusters.size();
    }
}
