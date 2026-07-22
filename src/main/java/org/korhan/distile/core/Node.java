package org.korhan.distile.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A node in the fixed-depth Drain parse tree.
 *
 * <p>Internal nodes route by string key (children); leaf nodes hold the
 * candidate LogClusters (clusters). A node is used as one or the
 * other depending on its depth: the tree only ever consults clusters at
 * the leaf level and children above it, so both fields living on one class
 * is a size trade-off, not a correctness one.
 */
final class Node {

    /** Lazily-created child routing map for internal nodes. */
    private Map<String, Node> children;

    /** Lazily-created cluster list for leaf nodes. */
    private List<LogCluster> clusters;

    Map<String, Node> children() {
        if (children == null) {
            children = new HashMap<>();
        }
        return children;
    }

    boolean hasChildren() {
        return children != null && !children.isEmpty();
    }

    List<LogCluster> clusters() {
        if (clusters == null) {
            clusters = new ArrayList<>();
        }
        return clusters;
    }
}
