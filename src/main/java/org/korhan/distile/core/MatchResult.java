package org.korhan.distile.core;

/**
 * The result of feeding one line to DrainTree#add(String).
 *
 * <p>This is the entire surface the emission layer reacts to per line — it lets
 * emission answer "is this a brand-new template?" and "did this count cross a
 * milestone?" without the core ever calling into emission or printing anything.
 *
 * @param cluster  the cluster this line matched or created
 * @param isNew    true iff this line created the cluster (first sighting)
 * @param newCount the cluster's count after this line was folded in
 */
public record MatchResult(LogCluster cluster, boolean isNew, long newCount) {
}
