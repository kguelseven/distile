package org.korhan.distile.core;

/**
 * Tuning knobs for the Drain parse tree and matcher. Every field has a
 * documented default; the CLI exposes each as a flag.
 *
 * @param depth        maximum parse-tree depth. Level 1 buckets by token count;
 *                     the next {@code depth - 1} levels bucket by leading tokens.
 *                     Deeper = more selective buckets but more branches. Default 4.
 * @param maxChildren  maximum distinct children per internal node before overflow
 *                     is routed into a single {@code <*>} branch. Bounds tree
 *                     fan-out on adversarial/high-cardinality input. Default 100.
 * @param simThreshold minimum similarity (matched positions / token count) for a
 *                     line to join an existing cluster rather than start a new
 *                     one. Default 0.5.
 */
public record DrainConfig(int depth, int maxChildren, double simThreshold) {

    public static final int DEFAULT_DEPTH = 4;
    public static final int DEFAULT_MAX_CHILDREN = 100;
    public static final double DEFAULT_SIM_THRESHOLD = 0.5;

    public DrainConfig {
        if (depth < 2) {
            throw new IllegalArgumentException("depth must be >= 2 (level 1 is the token-count bucket), got " + depth);
        }
        if (maxChildren < 1) {
            throw new IllegalArgumentException("maxChildren must be >= 1, got " + maxChildren);
        }
        if (simThreshold < 0.0 || simThreshold > 1.0) {
            throw new IllegalArgumentException("simThreshold must be in [0,1], got " + simThreshold);
        }
    }

    public static DrainConfig defaults() {
        return new DrainConfig(DEFAULT_DEPTH, DEFAULT_MAX_CHILDREN, DEFAULT_SIM_THRESHOLD);
    }
}
