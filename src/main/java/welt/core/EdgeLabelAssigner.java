package welt.core;

/**
 * Assigns the "edge label used for structural matching" — DISTINCT from the weight.
 *
 * In the original GraMi, an edge label is an integer that participates in subgraph
 * isomorphism matching (two edges match only if they share the same label). In
 * CiteSeer, the 3rd token is a real number (a similarity score) that is nearly unique
 * per edge; using it directly as a structural label would mean no edge ever matches
 * another. Hence the default treats the graph as an "edge-unlabeled graph": every edge
 * carries the same structural label {@link #UNLABELED}.
 *
 * The real value (similarity) is kept separately as the weight via {@link WeightAssigner}.
 */
@FunctionalInterface
public interface EdgeLabelAssigner {

    /** The shared structural label used when the graph has no edge labels. */
    int UNLABELED = 0;

    /**
     * @param src      source vertex id
     * @param dst      destination vertex id
     * @param rawValue raw value at the 3rd token of the edge line
     * @return the structural label (integer) of the edge
     */
    int labelOf(int src, int dst, double rawValue);

    /** Default for CiteSeer: every edge shares the structural label {@link #UNLABELED}. */
    EdgeLabelAssigner CONSTANT_UNLABELED = (src, dst, rawValue) -> UNLABELED;

    /**
     * Use the RAW VALUE as the structural label (integer) — appropriate for datasets
     * with real edge labels such as MiCo (3rd token = number of co-authored papers,
     * 106 distinct labels). Isomorphism matching requires two edges to share the SAME
     * label ⇒ a strong constraint ⇒ mining is feasible on large graphs (unlike treating
     * edges as unlabeled, which causes an explosion).
     */
    EdgeLabelAssigner IDENTITY = (src, dst, rawValue) -> (int) Math.round(rawValue);
}
