package welt.strategy;

import welt.core.LabeledWeightedGraph;
import welt.core.Metrics;
import welt.core.Pattern;

/**
 * WEGM, Max method — a reimplementation of Nguyen, Vo, Chau and Vo, "An efficient method for
 * mining frequent weighted subgraphs based on weighted edges", Journal of Information and
 * Telecommunication 9(4):543-558, 2025 (CC BY 4.0). Page numbers below refer to that article.
 *
 * <p><b>Model, as stated in the article.</b>
 * <ul>
 *   <li>An edge weight is part of the pattern: an isomorphism maps every pattern edge to a graph
 *       edge "and W_S(u, v) = W(f(u), f(v))" (Definition 3(b), p. 547). Support (MNI) therefore
 *       counts only occurrences with the same weights; the worked example confirms it (Figure 3,
 *       p. 548: vertex u6 is not a valid image of P in P-3-A because its edge to A has weight 7).</li>
 *   <li>In the experiments the edge labels "correspond to edge weights" (p. 553), and for Citeseer
 *       the article takes "only the integer value of the edge weight" (p. 553-554).</li>
 *   <li>W_E(S) is the maximum (Max method) or minimum (Min method) of the pattern's own edge
 *       weights (Definition 6, p. 549).</li>
 *   <li>Algorithm WEGM (p. 551) builds the list of frequent edges, removes those whose
 *       Weight(e, w) = 0 (lines 3-5), and extends each remaining edge with Subgraph_Extend, which
 *       adds only edges of that list (lines 3-7) and recurses on candidates that satisfy the
 *       frequency threshold f and the weight threshold w (lines 8-10).</li>
 *   <li>Weight, Max method (p. 552): returns 0 as soon as an edge has W_E &lt; w, otherwise the
 *       maximum edge weight. The condition is therefore "every edge weight ≥ w".</li>
 * </ul>
 *
 * <p><b>Implementation on the shared engine.</b> {@link #weightLabeledGraph} replaces every edge
 * label by the integer part of the edge weight (the article's Citeseer rule, applied to every
 * dataset; the input graphs must be edge-unlabeled). Mining that graph with exact-label
 * isomorphism gives the article's support. Removing the frequent edges below w (lines 3-5) and
 * extending only with the remaining ones (Subgraph_Extend lines 3-7) is equivalent to pruning,
 * before MNI counting, every candidate that contains an edge label below w ({@link #prePrune});
 * frequent-edge membership is enforced by MNI anti-monotonicity. Every surviving candidate
 * satisfies the Max-method weight condition, so the weight test of lines 8-10 never rejects one.
 *
 * <p><b>Inconsistencies of the article, and the reading used.</b>
 * <ol>
 *   <li>Subgraph_Extend line 9 tests "Weight(c, G, w) ≥ 0", which is always true since Weight
 *       returns 0 on failure; the text (p. 552) says the candidate must satisfy "both f and w".
 *       The text is followed (and the weight test never fails here anyway).</li>
 *   <li>The Min-method pseudocode (p. 552) rejects an edge when W_E ≥ w, whereas the example on
 *       p. 553 keeps A-2-D at w = 2. The Min method is not implemented: the comparison with WeLT
 *       uses the Max method, whose condition is consistent in pseudocode and example.</li>
 *   <li>The worked example lists three frequent edges in Figure 1 (p. 550); undirected MNI also
 *       makes the same-label edges P-3-P and A-4-A frequent at threshold 2. The direction of the
 *       edges in Figure 1 is not shown; the reimplementation uses undirected graphs, as for every
 *       algorithm of this repository.</li>
 * </ol>
 *
 * <p>The result set is a set of WEIGHT-LABELED patterns and differs from WeLT's by definition:
 * compare efficiency only.
 */
public final class WEGMStrategy implements MiningStrategy {

    private final double minWeight;
    private final Metrics metrics;

    /** {@code g} must be the output of {@link #weightLabeledGraph}. */
    public WEGMStrategy(double minWeight, Metrics metrics) {
        this.minWeight = minWeight;
        this.metrics = metrics;
    }

    /**
     * Copy of {@code g} in which every edge label is the integer part of the edge weight and the
     * edge weight is that integer. Vertex labels are kept.
     *
     * @throws IllegalArgumentException if {@code g} has edge labels other than 0 (the weight
     *         would have to be combined with an existing label) or a negative weight
     */
    public static LabeledWeightedGraph weightLabeledGraph(LabeledWeightedGraph g) {
        LabeledWeightedGraph w = new LabeledWeightedGraph(g.numVertices());
        for (int v = 0; v < g.numVertices(); v++) w.setVertexLabel(v, g.vertexLabel(v));
        for (int u = 0; u < g.numVertices(); u++) {
            for (LabeledWeightedGraph.Adjacency a : g.neighbors(u)) {
                if (a.to <= u) continue;
                if (a.edgeLabel != 0) throw new IllegalArgumentException("WEGM expects an edge-unlabeled graph");
                if (a.weight < 0) throw new IllegalArgumentException("negative edge weight " + a.weight);
                int label = (int) Math.floor(a.weight);
                w.addUndirectedEdge(u, a.to, label, label);
            }
        }
        return w;
    }

    @Override
    public String name() {
        return "WEGM";
    }

    /**
     * Max method, Weight(S, w) of p. 552: 0 if some edge weight is below w, otherwise the
     * maximum edge weight of S. On the weight-labeled graph the edge weight is the edge label.
     */
    public static double weightMax(Pattern s, double w) {
        double ws = 0;
        for (int[] e : s.edges()) {
            double we = e[2];
            if (we < w) return 0;
            if (ws < we) ws = we;
        }
        return ws;
    }

    private boolean satisfiesWeight(Pattern s) {
        for (int[] e : s.edges()) if (e[2] < minWeight) return false;
        return true;
    }

    /** Early pruning of the frequent-edge list (lines 3-5): no edge of weight below w may be used. */
    @Override
    public boolean prePrune(Pattern s) {
        if (!satisfiesWeight(s)) {
            metrics.prunedByEdgeType++;
            metrics.prunedByStrategy++;
            return true;
        }
        return false;
    }

    /** Subgraph_Extend lines 8-10: a frequent candidate that satisfies w is a result. */
    @Override
    public boolean acceptFrequent(Pattern p, int support) {
        return satisfiesWeight(p);
    }

    /** Subgraph_Extend lines 8-10: and it is extended recursively. */
    @Override
    public boolean allowExtension(Pattern p, int support) {
        return satisfiesWeight(p);
    }

    public double minWeight() {
        return minWeight;
    }
}
