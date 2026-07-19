package org.korhan.distile.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The Drain engine: a fixed-depth parse tree that clusters log lines into
 * templates in near-O(1) per line.
 *
 * <p><b>This is concern (A): clustering.</b> It runs on every line — tokenize →
 * mask → descend tree → match/merge → {@code count++} — and is silent. It never
 * decides what to print; it only maintains state (templates + counts) and hands
 * back a {@link MatchResult} the emission layer can react to.
 *
 * <h2>Why it is fast</h2>
 * Matching a line only ever compares against the clusters in ONE leaf, never all
 * clusters globally. The path to that leaf is bounded by {@code depth}, and node
 * fan-out is bounded by {@code maxChildren} (overflow routes into a single
 * {@code <*>} branch). So per-line cost is O(depth + clustersInLeaf), independent
 * of how many lines have been seen.
 *
 * <h2>Bounded memory</h2>
 * Lines are consumed and discarded; only clusters (templates + counts) are
 * retained. Memory is bounded by the number of distinct templates, not by the
 * number of lines processed. Do not add any state that grows per line.
 *
 * <h2>Tree shape</h2>
 * <pre>
 *   Root
 *    └─ Level 1: bucket by TOKEN COUNT              key = "6"
 *        └─ Levels 2..depth: bucket by leading token  key = "User"
 *            └─ Leaf: list of LogClusters
 * </pre>
 * Level 1's token-count bucketing guarantees every cluster in a given leaf has
 * the same length, so the similarity metric needs no length handling.
 *
 * <p>Thread-safety: {@link #add} and {@link #snapshotTopN} are synchronized on
 * this instance. Ingestion is single-threaded, so the lock is uncontended except
 * for the brief snapshot copy taken by the emission scheduler.
 */
public final class DrainTree {

    private final DrainConfig config;
    private final Tokenizer tokenizer;
    private final Masker masker;

    private final Node root = new Node();
    private long nextClusterId = 0;

    // Off-hot-path registry of every cluster, used ONLY for snapshots/final
    // reports (which are allowed to scan all clusters). Matching never consults
    // this list — it only touches one leaf's cluster list.
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
     * Descend the tree to the leaf that owns this (already masked) line's
     * clusters, creating internal nodes as needed.
     *
     * <p>Level 1 buckets by token count and NEVER overflows to {@code <*>} —
     * collapsing different lengths into one leaf would break the equal-length
     * invariant the similarity metric relies on. The following token levels DO
     * honour {@code maxChildren}, routing overflow into a shared {@code <*>}
     * child so adversarial high-cardinality tokens can't grow the tree without
     * bound.
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
     * Resolve (or create) the child of {@code parent} for {@code key}. When the
     * key is unknown and the node is already at {@code maxChildren}, overflow to
     * a shared {@code <*>} child instead of adding an unbounded new branch.
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
     * Similarity = matching positions / token count, with a {@code <*>} template
     * token matching any line token.
     */
    private LogCluster bestMatch(List<LogCluster> candidates, List<String> tokens) {
        int size = tokens.size();
        LogCluster best = null;
        double bestSim = -1.0;
        for (LogCluster c : candidates) {
            // Same length is guaranteed by Level-1 bucketing, but stay defensive:
            // the "<*>" overflow branch could, in principle, co-locate lines that
            // share a count bucket (always same length here) — still equal length.
            double sim = size == 0 ? 1.0 : (double) c.countMatchingPositions(tokens) / size;
            if (sim > bestSim) {
                bestSim = sim;
                best = c;
            }
        }
        return bestSim >= config.simThreshold() ? best : null;
    }

    /**
     * A sorted (count-descending) copy of the top {@code n} clusters. Read-only:
     * this never mutates tree state. Allowed to scan all clusters — it is a
     * reporting path, not the per-line hot path.
     */
    public List<LogCluster> snapshotTopN(int n) {
        // Under the lock, capture each cluster together with its count AT THIS
        // INSTANT — an O(T) copy, the "brief snapshot copy" the class contract
        // promises. Two reasons the count is captured here, not read during the
        // sort:
        //   1. The O(T log T) sort must NOT hold the lock. For a long-running
        //      process with many templates, sorting under the lock stalls the
        //      ingest thread on every snapshot interval.
        //   2. Sorting on live count() while the ingest thread mutates it would
        //      feed TimSort an ordering that changes mid-sort, which it detects
        //      and rejects with IllegalArgumentException. Sorting on the captured
        //      snapshot value keeps the comparator stable.
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
